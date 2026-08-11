package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.GatewayEvent
import com.snowzlmbot.hermes.mobile.core.GatewayEventType
import com.snowzlmbot.hermes.mobile.core.MessageRole
import kotlinx.serialization.json.JsonObject

internal enum class MessageStatus {
  STREAMING,
  COMPLETE,
  INTERRUPTED,
  ERROR;

  companion object {
    fun fromWire(value: String): MessageStatus = when (value.lowercase()) {
      "complete" -> COMPLETE
      "interrupted" -> INTERRUPTED
      "error" -> ERROR
      else -> STREAMING
    }
  }
}

internal data class ChatMessage(
  val id: String,
  val text: String,
  val role: MessageRole = MessageRole.ASSISTANT,
  val reasoning: String = "",
  val status: MessageStatus = MessageStatus.STREAMING,
  val error: String? = null,
  val retryable: Boolean = false,
)

internal data class ToolState(
  val id: String,
  val name: String,
  val context: String,
  val progress: String = "",
  val summary: String = "",
  val complete: Boolean = false,
)

internal data class ApprovalPrompt(
  val requestId: String,
  val command: String,
  val choices: List<String>,
)

internal data class ClarifyPrompt(
  val requestId: String,
  val question: String,
  val choices: List<String>,
)

internal data class SecretPrompt(
  val requestId: String,
  val prompt: String,
)

internal data class SudoPrompt(
  val requestId: String,
  val prompt: String,
)

internal data class ChatState(
  val runtimeSessionId: String? = null,
  val storedSessionId: String? = null,
  val messages: List<ChatMessage> = emptyList(),
  val tools: List<ToolState> = emptyList(),
  val streaming: Boolean = false,
  val model: String = "",
  val provider: String = "",
  val reasoningEffort: String = "",
  val error: String? = null,
  val approval: ApprovalPrompt? = null,
  val clarify: ClarifyPrompt? = null,
  val secret: SecretPrompt? = null,
  val sudo: SudoPrompt? = null,
) {
  companion object {
    fun empty(): ChatState = ChatState()
  }
}

internal object ChatReducer {
  fun reduce(state: ChatState, event: GatewayEvent): ChatState {
    val runtime = state.runtimeSessionId
    if (runtime != null && event.runtimeSessionId != null && event.runtimeSessionId != runtime) {
      return state
    }
    if (event.type.requiresExactRuntime() && (runtime == null || event.runtimeSessionId != runtime)) {
      return state
    }
    val payload = event.payload
    return when (event.type) {
      GatewayEventType.MESSAGE_START -> state.copy(
        streaming = true,
        error = null,
        messages = state.messages + ChatMessage(id = messageId(payload, state), text = ""),
      )
      GatewayEventType.REASONING_DELTA -> state.updateCurrent { it.copy(reasoning = it.reasoning + payload.string("text")) }
      GatewayEventType.MESSAGE_DELTA, GatewayEventType.MESSAGE_INTERIM ->
        state.updateCurrent { it.copy(text = it.text + payload.string("text")) }
      GatewayEventType.MESSAGE_COMPLETE -> {
        val status = MessageStatus.fromWire(payload.string("status"))
        state.updateCurrent {
          it.copy(
            text = payload.string("text").ifBlank { it.text },
            status = status,
            error = payload.string("error").takeIf { it.isNotBlank() },
            retryable = payload.boolean("recoverable") || status == MessageStatus.ERROR,
          )
        }.copy(streaming = false, error = payload.string("error").takeIf { it.isNotBlank() })
      }
      GatewayEventType.TOOL_START -> state.copy(
        tools = state.tools + ToolState(
          id = payload.string("tool_id").ifBlank { payload.string("tool_call_id") },
          name = payload.string("name"),
          context = payload.string("context").ifBlank { payload.string("preview") },
        ),
      )
      GatewayEventType.TOOL_PROGRESS -> state.updateTool(payload) { tool ->
        tool.copy(progress = payload.string("text").ifBlank { payload.string("progress") })
      }
      GatewayEventType.TOOL_COMPLETE -> state.updateTool(payload) { tool ->
        tool.copy(summary = payload.string("summary").ifBlank { payload.string("result") }, complete = true)
      }
      GatewayEventType.APPROVAL_REQUEST -> state.copy(
        approval = ApprovalPrompt(
          requestId = promptId(payload),
          command = payload.string("command"),
          choices = payload.stringList("choices"),
        ),
      )
      GatewayEventType.CLARIFY_REQUEST -> state.copy(
        clarify = ClarifyPrompt(promptId(payload), payload.string("question"), payload.stringList("choices")),
      )
      GatewayEventType.SECRET_REQUEST -> state.copy(
        secret = SecretPrompt(promptId(payload), payload.string("prompt")),
      )
      GatewayEventType.SUDO_REQUEST -> state.copy(
        sudo = SudoPrompt(promptId(payload), payload.string("prompt").ifBlank { "Authentication required" }),
      )
      GatewayEventType.CLARIFY_EXPIRE -> state.copy(clarify = clearPrompt(state.clarify, payload))
      GatewayEventType.SECRET_EXPIRE -> state.copy(secret = clearPrompt(state.secret, payload))
      GatewayEventType.SUDO_EXPIRE -> state.copy(sudo = clearPrompt(state.sudo, payload))
      GatewayEventType.ERROR -> state.copy(error = payload.string("message").ifBlank { payload.string("error") })
      GatewayEventType.SESSION_INFO -> state.copy(
        streaming = payload.optionalBoolean("running") ?: state.streaming,
        model = payload.string("model").ifBlank { state.model },
        provider = payload.string("provider").ifBlank { state.provider },
        reasoningEffort = payload.string("reasoning_effort").ifBlank { state.reasoningEffort },
      )
      else -> state
    }
  }

  private fun GatewayEventType.requiresExactRuntime(): Boolean = when (this) {
    GatewayEventType.MESSAGE_COMPLETE,
    GatewayEventType.APPROVAL_REQUEST,
    GatewayEventType.CLARIFY_REQUEST,
    GatewayEventType.SECRET_REQUEST,
    GatewayEventType.SUDO_REQUEST,
    GatewayEventType.CLARIFY_EXPIRE,
    GatewayEventType.SECRET_EXPIRE,
    GatewayEventType.SUDO_EXPIRE,
    -> true
    else -> false
  }

  private fun messageId(payload: JsonObject, state: ChatState): String =
    payload.string("message_id").ifBlank { "message-${state.messages.size + 1}" }

  private fun promptId(payload: JsonObject): String =
    payload.string("request_id").ifBlank { payload.string("id") }

  private fun <T> clearPrompt(current: T?, payload: JsonObject): T? where T : Any = when {
    current == null -> null
    promptId(payload).isBlank() -> null
    current is ApprovalPrompt && current.requestId == promptId(payload) -> null
    current is ClarifyPrompt && current.requestId == promptId(payload) -> null
    current is SecretPrompt && current.requestId == promptId(payload) -> null
    current is SudoPrompt && current.requestId == promptId(payload) -> null
    else -> current
  }

  private fun ChatState.updateCurrent(transform: (ChatMessage) -> ChatMessage): ChatState {
    if (messages.isEmpty()) return this
    val index = messages.lastIndex
    return copy(messages = messages.toMutableList().also { it[index] = transform(it[index]) })
  }

  private fun ChatState.updateTool(
    payload: JsonObject,
    transform: (ToolState) -> ToolState,
  ): ChatState {
    val id = payload.string("tool_id").ifBlank { payload.string("tool_call_id") }
    val index = tools.indexOfLast { it.id == id }
    if (index < 0) return this
    return copy(tools = tools.toMutableList().also { it[index] = transform(it[index]) })
  }
}

private fun JsonObject.string(key: String): String =
  (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull().orEmpty()

private fun JsonObject.boolean(key: String): Boolean = optionalBoolean(key) == true

private fun JsonObject.optionalBoolean(key: String): Boolean? =
  (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull()?.toBooleanStrictOrNull()

private fun JsonObject.stringList(key: String): List<String> =
  (this[key] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull {
    (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull()
  }

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = content
