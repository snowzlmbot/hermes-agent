package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.ActiveSession
import com.snowzlmbot.hermes.mobile.core.GatewayProtocol
import com.snowzlmbot.hermes.mobile.core.GatewayRestClient
import com.snowzlmbot.hermes.mobile.core.JsonObjectRpcClient
import com.snowzlmbot.hermes.mobile.core.SessionSummary
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal interface MobileSessionSource {
  suspend fun listSessions(): List<SessionSummary>

  suspend fun updateSession(
    storedId: String,
    title: String?,
    archived: Boolean?,
    pinned: Boolean?,
  ) {
    error("Session updates are unavailable")
  }

  suspend fun deleteSession(storedId: String) {
    error("Session deletion is unavailable")
  }
}

internal class RestMobileSessionSource(
  private val client: GatewayRestClient,
) : MobileSessionSource {
  override suspend fun listSessions(): List<SessionSummary> =
    client.listSessions(includeArchived = false)

  override suspend fun updateSession(
    storedId: String,
    title: String?,
    archived: Boolean?,
    pinned: Boolean?,
  ) {
    client.updateSession(storedId, title, archived, pinned)
  }

  override suspend fun deleteSession(storedId: String) {
    client.deleteSession(storedId)
  }
}

internal class HermesMobileRuntime(
  private val rpc: JsonObjectRpcClient,
  private val sessions: MobileSessionSource,
) : MobileGatewayRuntime {
  override val events: SharedFlow<com.snowzlmbot.hermes.mobile.core.GatewayEvent> = rpc.events

  override suspend fun connect() = rpc.connect()

  override suspend fun listSessions(): List<SessionSummary> = sessions.listSessions()

  override suspend fun createSession(): ActiveSession = GatewayProtocol.parseActiveSession(
    rpc.request(
      "session.create",
      buildJsonObject {
        put("source", "mobile")
        put("profile", "default")
        put("cols", 80)
      },
    ),
  )

  override suspend fun resumeSession(storedId: String): ActiveSession {
    require(storedId.isNotBlank()) { "Stored session id is required" }
    return GatewayProtocol.parseActiveSession(
      rpc.request(
        "session.resume",
        buildJsonObject {
          put("session_id", storedId)
          put("source", "mobile")
          put("cols", 80)
        },
      ),
    )
  }

  override suspend fun submitPrompt(runtimeId: String, text: String) {
    require(runtimeId.isNotBlank()) { "Runtime session id is required" }
    require(text.isNotBlank()) { "Prompt is required" }
    rpc.request(
      "prompt.submit",
      buildJsonObject {
        put("session_id", runtimeId)
        put("text", text.trim())
      },
    )
  }

  override suspend fun interrupt(runtimeId: String) {
    rpc.request("session.interrupt", runtimeParams(runtimeId))
  }

  override suspend fun respondApproval(runtimeId: String, choice: String) {
    rpc.request(
      "approval.respond",
      buildJsonObject {
        put("session_id", requiredRuntime(runtimeId))
        put("choice", choice)
      },
    )
  }

  override suspend fun respondClarify(runtimeId: String, requestId: String, answer: String) {
    rpc.request("clarify.respond", responseParams(runtimeId, requestId, "answer", answer))
  }

  override suspend fun respondSecret(runtimeId: String, requestId: String, value: String) {
    rpc.request("secret.respond", responseParams(runtimeId, requestId, "value", value))
  }

  override suspend fun respondSudo(runtimeId: String, requestId: String, password: String) {
    rpc.request("sudo.respond", responseParams(runtimeId, requestId, "password", password))
  }

  override suspend fun attach(
    runtimeId: String,
    method: String,
    params: Map<String, String>,
  ): String? {
    require(method in ATTACH_METHODS) { "Unsupported attachment method" }
    val result = rpc.request(
      method,
      buildJsonObject {
        put("session_id", requiredRuntime(runtimeId))
        params.forEach { (key, value) -> put(key, value) }
      },
    )
    return (result["ref_text"] as? JsonPrimitive)?.content
  }

  override suspend fun updateSession(
    storedId: String,
    title: String?,
    archived: Boolean?,
    pinned: Boolean?,
  ) {
    sessions.updateSession(storedId, title, archived, pinned)
  }

  override suspend fun deleteSession(storedId: String) {
    sessions.deleteSession(storedId)
  }

  override fun close() = rpc.close()

  private fun runtimeParams(runtimeId: String): JsonObject = buildJsonObject {
    put("session_id", requiredRuntime(runtimeId))
  }

  private fun responseParams(
    runtimeId: String,
    requestId: String,
    field: String,
    value: String,
  ): JsonObject = buildJsonObject {
    put("session_id", requiredRuntime(runtimeId))
    require(requestId.isNotBlank()) { "Request id is required" }
    put("request_id", requestId)
    put(field, value)
  }

  private fun requiredRuntime(runtimeId: String): String =
    runtimeId.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("Runtime session id is required")

  private companion object {
    val ATTACH_METHODS = setOf("image.attach_bytes", "pdf.attach", "file.attach")
  }
}
