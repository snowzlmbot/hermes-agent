package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.GatewayEvent
import com.snowzlmbot.hermes.mobile.core.GatewayEventType
import com.snowzlmbot.hermes.mobile.platform.ChatNotificationSignal
import com.snowzlmbot.hermes.mobile.platform.NotificationKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatNotificationSignalTest {
  @Test
  fun emitsCompletionApprovalAndInputSignalsWithStoredIdentityOnly() = runTest {
    val runtime = SignalRuntime()
    val signals = mutableListOf<ChatNotificationSignal>()
    val controller = ChatController(runtime, backgroundScope) { signals += it }

    controller.connect()
    controller.newSession()
    runtime.emit(GatewayEventType.MESSAGE_COMPLETE, "message.complete")
    runtime.emit(GatewayEventType.APPROVAL_REQUEST, "approval.request")
    runtime.emit(GatewayEventType.CLARIFY_REQUEST, "clarify.request")
    runtime.emit(GatewayEventType.SECRET_REQUEST, "secret.request")
    runtime.emit(GatewayEventType.SUDO_REQUEST, "sudo.request")
    runCurrent()

    assertEquals(
      listOf(
        ChatNotificationSignal(NotificationKind.COMPLETION, "stored-new"),
        ChatNotificationSignal(NotificationKind.APPROVAL, "stored-new"),
        ChatNotificationSignal(NotificationKind.INPUT, "stored-new"),
        ChatNotificationSignal(NotificationKind.INPUT, "stored-new"),
        ChatNotificationSignal(NotificationKind.INPUT, "stored-new"),
      ),
      signals,
    )
    assertTrue(signals.all { it.storedSessionId == "stored-new" })
  }

  @Test
  fun ignoresSignalsFromAnotherRuntimeSession() = runTest {
    val runtime = SignalRuntime()
    val signals = mutableListOf<ChatNotificationSignal>()
    val controller = ChatController(runtime, backgroundScope) { signals += it }

    controller.connect()
    controller.newSession()
    runtime.events.emit(
      GatewayEvent(
        type = GatewayEventType.APPROVAL_REQUEST,
        wireType = "approval.request",
        runtimeSessionId = "runtime-stale",
        payload = buildJsonObject {},
      ),
    )
    runCurrent()

    assertTrue(signals.isEmpty())
  }

  @Test
  fun dropsDuplicateMessageCompletionBeforeReducerAndNotification() = runTest {
    val runtime = SignalRuntime()
    val signals = mutableListOf<ChatNotificationSignal>()
    val controller = ChatController(runtime, backgroundScope) { signals += it }

    controller.connect()
    controller.newSession()
    runtime.events.emit(messageStartEvent())
    runtime.events.emit(completionEvent("first"))
    runtime.events.emit(completionEvent("replayed"))
    runCurrent()

    assertEquals(1, signals.count { it.kind == NotificationKind.COMPLETION })
    assertEquals("first", controller.state.value.chat.messages.single().text)
  }

  @Test
  fun dropsDuplicateApprovalBeforeReducerAndNotification() = runTest {
    val runtime = SignalRuntime()
    val signals = mutableListOf<ChatNotificationSignal>()
    val controller = ChatController(runtime, backgroundScope) { signals += it }

    controller.connect()
    controller.newSession()
    runtime.events.emit(approvalEvent("first command"))
    runtime.events.emit(approvalEvent("replayed command"))
    runCurrent()

    assertEquals(1, signals.count { it.kind == NotificationKind.APPROVAL })
    assertEquals("first command", controller.state.value.chat.approval?.command)
  }

  @Test
  fun keepsDistinctDeltasWithTheSameMessageIdWhenTheyHaveNoFrameIdentity() = runTest {
    val runtime = SignalRuntime()
    val controller = ChatController(runtime, backgroundScope)

    controller.connect()
    controller.newSession()
    runtime.events.emit(messageStartEvent())
    runtime.events.emit(deltaEvent(text = "A", messageId = "message-1"))
    runtime.events.emit(deltaEvent(text = "B", messageId = "message-1"))
    runCurrent()

    assertEquals("AB", controller.state.value.chat.messages.single().text)
  }

  @Test
  fun dropsDuplicateDeltaWithTheSameFrameIdentity() = runTest {
    val runtime = SignalRuntime()
    val controller = ChatController(runtime, backgroundScope)

    controller.connect()
    controller.newSession()
    runtime.events.emit(messageStartEvent())
    runtime.events.emit(deltaEvent(text = "A", messageId = "message-1", eventId = "frame-1"))
    runtime.events.emit(deltaEvent(text = "replayed", messageId = "message-1", eventId = "frame-1"))
    runCurrent()

    assertEquals("A", controller.state.value.chat.messages.single().text)
  }

  @Test
  fun staleRuntimeDoesNotPoisonReplayGuard() = runTest {
    val runtime = SignalRuntime()
    val signals = mutableListOf<ChatNotificationSignal>()
    val controller = ChatController(runtime, backgroundScope) { signals += it }

    controller.connect()
    controller.newSession()
    runtime.events.emit(messageStartEvent())
    runtime.events.emit(completionEvent("stale", runtimeId = "runtime-stale", eventId = "frame-1"))
    runtime.events.emit(completionEvent("current", eventId = "frame-1"))
    runCurrent()

    assertEquals("current", controller.state.value.chat.messages.single().text)
    assertEquals(1, signals.count { it.kind == NotificationKind.COMPLETION })
  }

  private suspend fun SignalRuntime.emit(type: GatewayEventType, wireType: String) {
    events.emit(
      GatewayEvent(
        type = type,
        wireType = wireType,
        runtimeSessionId = "runtime-new",
        payload = buildJsonObject {},
      ),
    )
  }

  private fun messageStartEvent() = GatewayEvent(
    type = GatewayEventType.MESSAGE_START,
    wireType = "message.start",
    runtimeSessionId = "runtime-new",
    payload = buildJsonObject { put("message_id", "message-1") },
  )

  private fun completionEvent(
    text: String,
    runtimeId: String = "runtime-new",
    eventId: String? = null,
  ) = GatewayEvent(
    type = GatewayEventType.MESSAGE_COMPLETE,
    wireType = "message.complete",
    runtimeSessionId = runtimeId,
    payload = buildJsonObject {
      put("message_id", "message-1")
      put("text", text)
      put("status", "complete")
      eventId?.let { put("event_id", it) }
    },
  )

  private fun approvalEvent(command: String) = GatewayEvent(
    type = GatewayEventType.APPROVAL_REQUEST,
    wireType = "approval.request",
    runtimeSessionId = "runtime-new",
    payload = buildJsonObject {
      put("request_id", "approval-1")
      put("command", command)
    },
  )

  private fun deltaEvent(text: String, messageId: String, eventId: String? = null) = GatewayEvent(
    type = GatewayEventType.MESSAGE_DELTA,
    wireType = "message.delta",
    runtimeSessionId = "runtime-new",
    payload = buildJsonObject {
      put("message_id", messageId)
      put("text", text)
      eventId?.let { put("event_id", it) }
    },
  )

  private class SignalRuntime : MobileGatewayRuntime {
    override val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)

    override suspend fun connect() = Unit

    override suspend fun listSessions() = listOf(
      com.snowzlmbot.hermes.mobile.core.SessionSummary(
        storedId = "stored-new",
        title = "Session",
        preview = "",
        startedAt = 1,
        lastActive = 1,
        messageCount = 0,
        source = "mobile",
      ),
    )

    override suspend fun createSession() = active()

    override suspend fun resumeSession(storedId: String) = active()

    override suspend fun submitPrompt(runtimeId: String, text: String) = Unit

    override suspend fun interrupt(runtimeId: String) = Unit

    override fun close() = Unit

    private fun active() = com.snowzlmbot.hermes.mobile.core.ActiveSession(
      runtimeId = "runtime-new",
      storedId = "stored-new",
      messages = emptyList(),
      running = false,
      status = "idle",
      model = "model",
      provider = "provider",
      reasoningEffort = "medium",
    )
  }
}
