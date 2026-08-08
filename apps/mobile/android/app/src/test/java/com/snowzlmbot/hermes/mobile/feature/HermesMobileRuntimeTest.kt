package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.GatewayEvent
import com.snowzlmbot.hermes.mobile.core.JsonObjectRpcClient
import com.snowzlmbot.hermes.mobile.core.SessionSummary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesMobileRuntimeTest {
  @Test
  fun createsResumesPromptsAndInterruptsWithCorrectIdentityDomains() = runTest {
    val rpc = RecordingRpc()
    val runtime = HermesMobileRuntime(rpc, FakeSessionSource())

    val created = runtime.createSession()
    val resumed = runtime.resumeSession("stored-9")
    runtime.submitPrompt("runtime-created", "hello")
    runtime.interrupt("runtime-created")

    assertEquals("runtime-created", created.runtimeId)
    assertEquals("stored-created", created.storedId)
    assertEquals("runtime-resumed", resumed.runtimeId)
    assertEquals("stored-9", resumed.storedId)
    assertEquals("session.create", rpc.calls[0].first)
    assertEquals("mobile", rpc.calls[0].second.string("source"))
    assertEquals("session.resume", rpc.calls[1].first)
    assertEquals("stored-9", rpc.calls[1].second.string("session_id"))
    assertEquals("prompt.submit", rpc.calls[2].first)
    assertEquals("runtime-created", rpc.calls[2].second.string("session_id"))
    assertEquals("session.interrupt", rpc.calls[3].first)
  }

  @Test
  fun interactionResponsesUseDistinctSensitiveFields() = runTest {
    val rpc = RecordingRpc()
    val runtime = HermesMobileRuntime(rpc, FakeSessionSource())

    runtime.respondApproval("runtime-1", "once")
    runtime.respondClarify("runtime-1", "request-c", "production")
    runtime.respondSecret("runtime-1", "request-s", "secret-value")
    runtime.respondSudo("runtime-1", "request-u", "password-value")

    assertEquals("choice", rpc.calls[0].second.keys.single { it != "session_id" })
    assertEquals("answer", rpc.calls[1].second.keys.single { it !in setOf("session_id", "request_id") })
    assertEquals("value", rpc.calls[2].second.keys.single { it !in setOf("session_id", "request_id") })
    assertEquals("password", rpc.calls[3].second.keys.single { it !in setOf("session_id", "request_id") })
  }

  @Test
  fun attachmentAddsRuntimeIdentityAndPreservesPayloadField() = runTest {
    val rpc = RecordingRpc()
    val runtime = HermesMobileRuntime(rpc, FakeSessionSource())

    val reference = runtime.attach(
      runtimeId = "runtime-1",
      method = "file.attach",
      params = mapOf(
        "name" to "notes.txt",
        "data_url" to "data:text/plain;base64,aGVsbG8=",
      ),
    )

    val params = rpc.calls.single().second
    assertEquals("runtime-1", params.string("session_id"))
    assertTrue(params.containsKey("data_url"))
    assertFalse(params.containsKey("content_base64"))
    assertEquals("@file:.hermes/desktop-attachments/notes.txt", reference)
  }

  @Test
  fun modelAndReasoningControlsStayScopedToRuntimeSession() = runTest {
    val rpc = RecordingRpc()
    val runtime = HermesMobileRuntime(rpc, FakeSessionSource())

    val catalog = runtime.listModelOptions("runtime-1")
    runtime.selectModel("runtime-1", provider = "nous", model = "hermes-4")
    runtime.setReasoningEffort("runtime-1", "max")

    assertEquals("hermes-4", catalog.currentModel)
    assertEquals("model.options", rpc.calls[0].first)
    assertEquals("runtime-1", rpc.calls[0].second.string("session_id"))
    assertEquals("config.set", rpc.calls[1].first)
    assertEquals("runtime-1", rpc.calls[1].second.string("session_id"))
    assertEquals("model", rpc.calls[1].second.string("key"))
    assertEquals("hermes-4 --provider nous --session", rpc.calls[1].second.string("value"))
    assertEquals("config.set", rpc.calls[2].first)
    assertEquals("runtime-1", rpc.calls[2].second.string("session_id"))
    assertEquals("reasoning", rpc.calls[2].second.string("key"))
    assertEquals("max", rpc.calls[2].second.string("value"))
  }

  @Test
  fun modelOptionsRefreshCarriesExplicitRefreshFlag() = runTest {
    val rpc = RecordingRpc()
    val runtime = HermesMobileRuntime(rpc, FakeSessionSource())

    runtime.listModelOptions("runtime-1", refresh = true)

    assertEquals(true, rpc.calls.single().second.boolean("refresh"))
  }

  @Test
  fun modelSelectionRequiresExplicitConfirmationBeforeApplying() = runTest {
    val rpc = RecordingRpc().apply { requireModelConfirmation = true }
    val runtime = HermesMobileRuntime(rpc, FakeSessionSource())

    val pending = runtime.selectModel("runtime-1", provider = "nous", model = "expensive-model")
    assertEquals(ModelSwitchResult.ConfirmationRequired("Confirm expensive model"), pending)
    assertEquals(null, rpc.calls.single().second.boolean("confirm_expensive_model"))

    rpc.requireModelConfirmation = false
    val applied = runtime.selectModel(
      "runtime-1",
      provider = "nous",
      model = "expensive-model",
      confirmExpensiveModel = true,
    )
    assertEquals(ModelSwitchResult.Applied, applied)
    assertEquals(true, rpc.calls[1].second.boolean("confirm_expensive_model"))
  }

  private class RecordingRpc : JsonObjectRpcClient {
    override val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var requireModelConfirmation = false

    override suspend fun connect() = Unit

    override suspend fun request(method: String, params: JsonObject): JsonObject {
      calls += method to params
      return when (method) {
        "session.create" -> sessionResult("runtime-created", "stored-created")
        "session.resume" -> sessionResult("runtime-resumed", params.string("session_id"))
        "file.attach" -> buildJsonObject {
          put("ref_text", "@file:.hermes/desktop-attachments/notes.txt")
        }
        "model.options" -> Json.parseToJsonElement(
          """{"model":"hermes-4","provider":"nous","providers":[{"slug":"nous","name":"Nous","authenticated":true,"models":["hermes-4"],"capabilities":{"hermes-4":{"fast":false,"reasoning":true}}}]}""",
        ) as JsonObject
        "config.set" -> if (params.string("key") == "model" && requireModelConfirmation) {
          buildJsonObject {
            put("confirm_required", true)
            put("confirm_message", "Confirm expensive model")
          }
        } else {
          buildJsonObject { put("ok", true) }
        }
        else -> buildJsonObject { put("ok", true) }
      }
    }

    override fun close() = Unit

    private fun sessionResult(runtimeId: String, storedId: String): JsonObject = buildJsonObject {
      put("session_id", runtimeId)
      put("stored_session_id", storedId)
      put("resumed", storedId)
      put("messages", kotlinx.serialization.json.buildJsonArray {})
      put("running", false)
    }
  }

  private class FakeSessionSource : MobileSessionSource {
    override suspend fun listSessions(): List<SessionSummary> = emptyList()
  }
}

private fun JsonObject.string(key: String): String =
  (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

private fun JsonObject.boolean(key: String): Boolean? =
  (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
