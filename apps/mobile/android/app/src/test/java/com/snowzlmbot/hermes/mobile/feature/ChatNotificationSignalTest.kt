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
    emit(GatewayEventType.MESSAGE_COMPLETE, "message.complete")
    emit(GatewayEventType.APPROVAL_REQUEST, "approval.request")
    emit(GatewayEventType.CLARIFY_REQUEST, "clarify.request")
    emit(GatewayEventType.SECRET_REQUEST, "secret.request")
    emit(GatewayEventType.SUDO_REQUEST, "sudo.request")
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