package com.snowzlmbot.hermes.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProtocolTest {
  @Test
  fun encodesJsonRpcRequestAndDecodesSuccessfulResponse() {
    val encoded = JsonRpcCodec.encodeRequest(
      id = "mobile-7",
      method = "session.create",
      params = buildJsonObject {
        put("source", "mobile")
        put("profile", "default")
        put("cols", 80)
      },
    )
    val request = Json.parseToJsonElement(encoded) as JsonObject

    assertEquals("2.0", request.string("jsonrpc"))
    assertEquals("mobile-7", request.string("id"))
    assertEquals("session.create", request.string("method"))
    assertEquals(80, request.objectValue("params").int("cols"))

    val frame = JsonRpcCodec.decode(
      """{"jsonrpc":"2.0","id":"mobile-7","result":{"session_id":"runtime-1","stored_session_id":"stored-1"}}""",
    ) as JsonRpcFrame.Success
    assertEquals("mobile-7", frame.id)
    assertEquals("runtime-1", frame.result.string("session_id"))
  }

  @Test
  fun decodesErrorsAndSessionScopedEvents() {
    val error = JsonRpcCodec.decode(
      """{"jsonrpc":"2.0","id":"mobile-9","error":{"code":4007,"message":"session not found"}}""",
    ) as JsonRpcFrame.Failure
    assertEquals(4007, error.code)
    assertEquals("session not found", error.message)

    val event = JsonRpcCodec.decode(
      """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime-1","payload":{"text":"Hello"}}}""",
    ) as JsonRpcFrame.Event
    assertEquals(GatewayEventType.MESSAGE_DELTA, event.event.type)
    assertEquals("runtime-1", event.event.runtimeSessionId)
    assertEquals("Hello", event.event.payload.string("text"))
  }

  @Test
  fun parsesDurableSessionListAndRuntimeResumeIdentitySeparately() {
    val sessions = GatewayProtocol.parseSessionList(
      jsonObject(
        """{"sessions":[{"id":"stored-1","title":"Welcome","preview":"Hi","started_at":1700000000,"message_count":2,"source":"mobile"}]}""",
      ),
    )
    assertEquals("stored-1", sessions.single().storedId)
    assertEquals("Welcome", sessions.single().displayTitle)

    val active = GatewayProtocol.parseActiveSession(
      jsonObject(
        """{"session_id":"runtime-1","resumed":"stored-1","messages":[{"id":4,"role":"assistant","content":"Connected","timestamp":1700000001}],"running":false,"info":{"model":"fixture-model","provider":"fixture","stored_session_id":"stored-1"}}""",
      ),
    )
    assertEquals("runtime-1", active.runtimeId)
    assertEquals("stored-1", active.storedId)
    assertEquals(MessageRole.ASSISTANT, active.messages.single().role)
    assertEquals(4L, active.messages.single().rowId)
    assertFalse(active.running)
    assertEquals("fixture-model", active.model)
  }

  @Test
  fun rejectsMalformedFramesAndNeverIncludesRpcPayloadInExceptionText() {
    assertThrows(ProtocolException::class.java) {
      JsonRpcCodec.decode("[]")
    }
    val exception = assertThrows(ProtocolException::class.java) {
      JsonRpcCodec.decode("""{"jsonrpc":"2.0","unknown":"secret-token"}""")
    }
    assertFalse(exception.message.orEmpty().contains("secret-token"))
    assertTrue(exception.message.orEmpty().contains("frame"))
  }

  private fun jsonObject(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject
}