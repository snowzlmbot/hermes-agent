package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.ActiveSession
import com.snowzlmbot.hermes.mobile.core.ChatMessageRecord
import com.snowzlmbot.hermes.mobile.core.GatewayEvent
import com.snowzlmbot.hermes.mobile.core.GatewayEventType
import com.snowzlmbot.hermes.mobile.core.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
  @Test
  fun connectsLoadsSessionsAndKeepsStoredAndRuntimeIdentitiesSeparate() = runTest {
    val runtime = RecordingRuntime()
    val controller = ChatController(runtime, backgroundScope)

    controller.connect()
    controller.openSession("stored-1")

    assertEquals(ConnectionPhase.CONNECTED, controller.state.value.phase)
    assertEquals("stored-1", controller.state.value.chat.storedSessionId)
    assertEquals("runtime-resumed", controller.state.value.chat.runtimeSessionId)
    assertEquals(listOf("stored-1"), runtime.resumed)
  }

  @Test
  fun sendStopAndEventsUseTheActiveRuntimeIdentity() = runTest {
    val runtime = RecordingRuntime()
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.newSession()

    controller.send("hello")
    controller.stop()
    runtime.events.emit(
      GatewayEvent(
        type = GatewayEventType.MESSAGE_START,
        wireType = "message.start",
        runtimeSessionId = "runtime-new",
        payload = buildJsonObject {},
      ),
    )
    runtime.events.emit(
      GatewayEvent(
        type = GatewayEventType.MESSAGE_COMPLETE,
        wireType = "message.complete",
        runtimeSessionId = "runtime-new",
        payload = buildJsonObject {
          put("text", "reply")
          put("status", "complete")
        },
      ),
    )
    runCurrent()

    assertEquals(listOf("runtime-new" to "hello"), runtime.prompts)
    assertEquals(listOf("runtime-new"), runtime.interrupted)
    assertFalse(controller.state.value.chat.streaming)
    assertEquals("reply", controller.state.value.chat.messages.single().text)
  }

  @Test
  fun failedOperationsSetRecoverableUiErrorWithoutDiscardingSessions() = runTest {
    val runtime = RecordingRuntime().apply { failPrompts = true }
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.newSession()

    controller.send("hello")

    assertTrue(controller.state.value.error?.retryable == true)
    assertEquals(1, controller.state.value.sessions.size)
    controller.clearError()
    assertEquals(null, controller.state.value.error)
  }

  private class RecordingRuntime : MobileGatewayRuntime {
    override val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
    val resumed = mutableListOf<String>()
    val prompts = mutableListOf<Pair<String, String>>()
    val interrupted = mutableListOf<String>()
    var failPrompts = false

    override suspend fun connect() = Unit

    override suspend fun listSessions(): List<SessionSummary> = listOf(summary("stored-1"))

    override suspend fun createSession(): ActiveSession = active("runtime-new", "stored-new")

    override suspend fun resumeSession(storedId: String): ActiveSession {
      resumed += storedId
      return active("runtime-resumed", storedId)
    }

    override suspend fun submitPrompt(runtimeId: String, text: String) {
      if (failPrompts) error("gateway unavailable")
      prompts += runtimeId to text
    }

    override suspend fun interrupt(runtimeId: String) {
      interrupted += runtimeId
    }

    override fun close() = Unit

    private fun summary(id: String) = SessionSummary(
      storedId = id,
      title = "Session",
      preview = "",
      startedAt = 1,
      lastActive = 1,
      messageCount = 1,
      source = "mobile",
    )

    private fun active(runtimeId: String, storedId: String) = ActiveSession(
      runtimeId = runtimeId,
      storedId = storedId,
      messages = emptyList<ChatMessageRecord>(),
      running = false,
      status = "idle",
      model = "fixture-model",
      provider = "fixture",
      reasoningEffort = "max",
    )
  }
}
