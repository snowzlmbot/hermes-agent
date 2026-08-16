package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.GatewayEvent
import com.snowzlmbot.hermes.mobile.core.GatewayEventType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {
  @Test
  fun reducesStreamingReasoningToolsAndTerminalStatus() {
    var state = ChatState.empty().copy(runtimeSessionId = "runtime-1", storedSessionId = "stored-1")

    state = ChatReducer.reduce(state, event(GatewayEventType.MESSAGE_START, "message.start"))
    state = ChatReducer.reduce(state, event(GatewayEventType.REASONING_DELTA, "reasoning.delta", """{"text":"Checking"}"""))
    state = ChatReducer.reduce(state, event(GatewayEventType.TOOL_START, "tool.start", """{"tool_id":"tool-1","name":"terminal","context":"date"}"""))
    state = ChatReducer.reduce(state, event(GatewayEventType.MESSAGE_DELTA, "message.delta", """{"text":"Hello"}"""))
    state = ChatReducer.reduce(state, event(GatewayEventType.TOOL_COMPLETE, "tool.complete", """{"tool_id":"tool-1","name":"terminal","summary":"Ran command"}"""))
    state = ChatReducer.reduce(state, event(GatewayEventType.MESSAGE_COMPLETE, "message.complete", """{"text":"Hello","status":"complete"}"""))

    assertFalse(state.streaming)
    assertEquals("Hello", state.messages.single().text)
    assertEquals(MessageStatus.COMPLETE, state.messages.single().status)
    assertEquals("Checking", state.messages.single().reasoning)
    assertTrue(state.tools.single().complete)
  }

  @Test
  fun keepsErrorAndInterruptedTerminalStatesRetryable() {
    var state = ChatState.empty().copy(runtimeSessionId = "runtime-1")
    state = ChatReducer.reduce(state, event(GatewayEventType.MESSAGE_START, "message.start"))
    state = ChatReducer.reduce(state, event(GatewayEventType.MESSAGE_DELTA, "message.delta", """{"text":"Partial"}"""))
    state = ChatReducer.reduce(state, event(GatewayEventType.MESSAGE_COMPLETE, "message.complete", """{"text":"Partial","status":"error","error":"provider unavailable","recoverable":true}"""))

    assertEquals(MessageStatus.ERROR, state.messages.single().status)
    assertEquals("provider unavailable", state.messages.single().error)
    assertTrue(state.messages.single().retryable)

    state = ChatReducer.reduce(state, event(GatewayEventType.MESSAGE_START, "message.start"))
    state = ChatReducer.reduce(state, event(GatewayEventType.MESSAGE_COMPLETE, "message.complete", """{"text":"","status":"interrupted"}"""))
    assertEquals(MessageStatus.INTERRUPTED, state.messages.last().status)
  }

  @Test
  fun scopesActionCardsAndExpiresBlockingPrompts() {
    var state = ChatState.empty().copy(runtimeSessionId = "runtime-1")
    state = ChatReducer.reduce(state, event(GatewayEventType.APPROVAL_REQUEST, "approval.request", """{"command":"git status","choices":["once","deny"]}"""))
    state = ChatReducer.reduce(state, event(GatewayEventType.CLARIFY_REQUEST, "clarify.request", """{"request_id":"clarify-1","question":"Where?","choices":["staging","prod"]}"""))
    state = ChatReducer.reduce(state, event(GatewayEventType.SECRET_REQUEST, "secret.request", """{"request_id":"secret-1","prompt":"API key"}"""))
    state = ChatReducer.reduce(state, event(GatewayEventType.SUDO_REQUEST, "sudo.request", """{"request_id":"sudo-1"}"""))

    assertEquals("git status", state.approval?.command)
    assertEquals("clarify-1", state.clarify?.requestId)
    assertEquals("secret-1", state.secret?.requestId)
    assertEquals("sudo-1", state.sudo?.requestId)
    assertEquals("", state.sudo?.prompt)

    state = ChatReducer.reduce(state, event(GatewayEventType.CLARIFY_EXPIRE, "clarify.expire", """{"request_id":"clarify-1"}"""))
    state = ChatReducer.reduce(state, event(GatewayEventType.SECRET_EXPIRE, "secret.expire", """{"request_id":"secret-1"}"""))
    state = ChatReducer.reduce(state, event(GatewayEventType.SUDO_EXPIRE, "sudo.expire", """{"request_id":"sudo-1"}"""))
    assertNull(state.clarify)
    assertNull(state.secret)
    assertNull(state.sudo)
  }

  @Test
  fun ignoresActionEventsWithoutCurrentRuntimeIdentity() {
    val state = ChatState.empty().copy(runtimeSessionId = "runtime-1")
    val event = GatewayEvent(
      type = GatewayEventType.APPROVAL_REQUEST,
      wireType = "approval.request",
      runtimeSessionId = null,
      payload = jsonObject("""{"command":"secret"}"""),
    )
    assertEquals(state, ChatReducer.reduce(state, event))
  }

  @Test
  fun ignoresEventsFromAnotherRuntimeSession() {
    val state = ChatState.empty().copy(runtimeSessionId = "runtime-1")
    val other = GatewayEvent(
      type = GatewayEventType.MESSAGE_DELTA,
      wireType = "message.delta",
      runtimeSessionId = "runtime-2",
      payload = jsonObject("""{"text":"wrong chat"}"""),
    )

    assertEquals(state, ChatReducer.reduce(state, other))
  }

  private fun event(
    type: GatewayEventType,
    wireType: String,
    payload: String = "{}",
  ): GatewayEvent = GatewayEvent(type, wireType, "runtime-1", jsonObject(payload))

  private fun jsonObject(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject
}
