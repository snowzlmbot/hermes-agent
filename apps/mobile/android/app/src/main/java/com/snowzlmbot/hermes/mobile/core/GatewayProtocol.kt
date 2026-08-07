package com.snowzlmbot.hermes.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val protocolJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = false
}

class ProtocolException(message: String) : IllegalArgumentException(message)

enum class GatewayEventType {
  GATEWAY_READY,
  SESSION_INFO,
  SESSION_TITLE,
  MESSAGE_START,
  MESSAGE_DELTA,
  MESSAGE_INTERIM,
  MESSAGE_COMPLETE,
  REASONING_DELTA,
  THINKING_DELTA,
  STATUS_UPDATE,
  TOOL_START,
  TOOL_PROGRESS,
  TOOL_COMPLETE,
  APPROVAL_REQUEST,
  CLARIFY_REQUEST,
  CLARIFY_EXPIRE,
  SECRET_REQUEST,
  SECRET_EXPIRE,
  SUDO_REQUEST,
  SUDO_EXPIRE,
  ERROR,
  SESSIONS_CHANGED,
  UNKNOWN,
  ;

  companion object {
    fun fromWire(value: String): GatewayEventType = when (value) {
      "gateway.ready" -> GATEWAY_READY
      "session.info" -> SESSION_INFO
      "session.title" -> SESSION_TITLE
      "message.start" -> MESSAGE_START
      "message.delta" -> MESSAGE_DELTA
      "message.interim" -> MESSAGE_INTERIM
      "message.complete" -> MESSAGE_COMPLETE
      "reasoning.delta" -> REASONING_DELTA
      "thinking.delta" -> THINKING_DELTA
      "status.update" -> STATUS_UPDATE
      "tool.start" -> TOOL_START
      "tool.progress" -> TOOL_PROGRESS
      "tool.complete" -> TOOL_COMPLETE
      "approval.request" -> APPROVAL_REQUEST
      "clarify.request" -> CLARIFY_REQUEST
      "clarify.expire" -> CLARIFY_EXPIRE
      "secret.request" -> SECRET_REQUEST
      "secret.expire" -> SECRET_EXPIRE
      "sudo.request" -> SUDO_REQUEST
      "sudo.expire" -> SUDO_EXPIRE
      "error" -> ERROR
      "sessions.changed" -> SESSIONS_CHANGED
      else -> UNKNOWN
    }
  }
}

data class GatewayEvent(
  val type: GatewayEventType,
  val wireType: String,
  val runtimeSessionId: String?,
  val payload: JsonObject,
)

sealed interface JsonRpcFrame {
  data class Success(val id: String, val result: JsonObject) : JsonRpcFrame
  data class Failure(val id: String?, val code: Int, val message: String) : JsonRpcFrame
  data class Event(val event: GatewayEvent) : JsonRpcFrame
}

object JsonRpcCodec {
  fun encodeRequest(id: String, method: String, params: JsonObject = buildJsonObject {}): String {
    if (id.isBlank() || method.isBlank()) throw ProtocolException("RPC id and method are required")
    val frame = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("method", method)
      put("params", params)
    }
    return protocolJson.encodeToString(JsonElement.serializer(), frame)
  }

  fun decode(raw: String): JsonRpcFrame {
    val root = runCatching { protocolJson.parseToJsonElement(raw) }
      .getOrElse { throw ProtocolException("Invalid JSON-RPC frame") }
      as? JsonObject ?: throw ProtocolException("JSON-RPC frame must be an object")
    if (root.string("jsonrpc") != "2.0") throw ProtocolException("Unsupported JSON-RPC version")

    if (root.string("method") == "event") {
      val params = root["params"] as? JsonObject
        ?: throw ProtocolException("Event frame is missing params")
      val wireType = params.string("type")
        ?: throw ProtocolException("Event frame is missing type")
      return JsonRpcFrame.Event(
        GatewayEvent(
          type = GatewayEventType.fromWire(wireType),
          wireType = wireType,
          runtimeSessionId = params.string("session_id"),
          payload = params["payload"] as? JsonObject ?: buildJsonObject {},
        ),
      )
    }

    val id = root.string("id") ?: throw ProtocolException("Response frame is missing id")
    val error = root["error"] as? JsonObject
    if (error != null) {
      return JsonRpcFrame.Failure(
        id = id,
        code = error.int("code") ?: -1,
        message = error.string("message") ?: "Gateway request failed",
      )
    }
    val result = root["result"] as? JsonObject
      ?: throw ProtocolException("Response frame is missing result")
    return JsonRpcFrame.Success(id, result)
  }
}

data class SessionSummary(
  val storedId: String,
  val title: String,
  val preview: String,
  val startedAt: Long,
  val lastActive: Long,
  val messageCount: Int,
  val source: String,
  val archived: Boolean = false,
) {
  val displayTitle: String get() = title.ifBlank { preview.ifBlank { "New conversation" } }
}

enum class MessageRole {
  USER,
  ASSISTANT,
  TOOL,
  SYSTEM,
  UNKNOWN;

  companion object {
    fun fromWire(value: String): MessageRole = when (value.lowercase()) {
      "user" -> USER
      "assistant" -> ASSISTANT
      "tool" -> TOOL
      "system" -> SYSTEM
      else -> UNKNOWN
    }
  }
}

data class ChatMessageRecord(
  val rowId: Long?,
  val role: MessageRole,
  val content: String,
  val timestamp: Long?,
  val pending: Boolean = false,
  val error: String? = null,
)

data class ActiveSession(
  val runtimeId: String,
  val storedId: String,
  val messages: List<ChatMessageRecord>,
  val running: Boolean,
  val status: String,
  val model: String,
  val provider: String,
  val reasoningEffort: String,
)

object GatewayProtocol {
  fun parseSessionList(result: JsonObject): List<SessionSummary> =
    (result["sessions"] as? JsonArray).orEmpty().mapNotNull { element ->
      val value = element as? JsonObject ?: return@mapNotNull null
      val id = value.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
      SessionSummary(
        storedId = id,
        title = value.string("title").orEmpty(),
        preview = value.string("preview").orEmpty(),
        startedAt = value.long("started_at") ?: 0L,
        lastActive = value.long("last_active") ?: value.long("started_at") ?: 0L,
        messageCount = value.int("message_count") ?: 0,
        source = value.string("source").orEmpty(),
        archived = value.boolean("archived") ?: false,
      )
    }

  fun parseActiveSession(result: JsonObject): ActiveSession {
    val runtimeId = result.string("session_id")
      ?: throw ProtocolException("Session response is missing runtime session id")
    val info = result["info"] as? JsonObject ?: buildJsonObject {}
    val storedId = result.string("resumed")
      ?: result.string("stored_session_id")
      ?: info.string("stored_session_id")
      ?: throw ProtocolException("Session response is missing durable session id")
    return ActiveSession(
      runtimeId = runtimeId,
      storedId = storedId,
      messages = parseMessages(result["messages"] as? JsonArray),
      running = result.boolean("running") ?: info.boolean("running") ?: false,
      status = result.string("status") ?: if (result.boolean("running") == true) "streaming" else "idle",
      model = info.string("model").orEmpty(),
      provider = info.string("provider").orEmpty(),
      reasoningEffort = info.string("reasoning_effort").orEmpty(),
    )
  }

  fun parseMessages(elements: JsonArray?): List<ChatMessageRecord> = elements.orEmpty().mapNotNull { element ->
    val value = element as? JsonObject ?: return@mapNotNull null
    val content = value.string("content") ?: value.string("text").orEmpty()
    ChatMessageRecord(
      rowId = value.long("id"),
      role = MessageRole.fromWire(value.string("role").orEmpty()),
      content = content,
      timestamp = value.long("timestamp"),
      pending = value.boolean("pending") ?: false,
      error = value.string("error"),
    )
  }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull()
internal fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()
internal fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
internal fun JsonObject.boolean(key: String): Boolean? = string(key)?.toBooleanStrictOrNull()
internal fun JsonObject.objectValue(key: String): JsonObject = this[key] as? JsonObject
  ?: throw ProtocolException("JSON object field '$key' is missing")
internal fun JsonPrimitive.contentOrNull(): String? = content