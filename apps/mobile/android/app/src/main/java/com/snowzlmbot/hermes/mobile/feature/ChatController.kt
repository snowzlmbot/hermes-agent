package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.ActiveSession
import com.snowzlmbot.hermes.mobile.core.GatewayEvent
import com.snowzlmbot.hermes.mobile.core.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal enum class ConnectionPhase {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
}

internal data class MobileUiError(
  val message: String,
  val retryable: Boolean,
)

internal data class MobileChatUiState(
  val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
  val sessions: List<SessionSummary> = emptyList(),
  val chat: ChatState = ChatState.empty(),
  val error: MobileUiError? = null,
)

internal interface MobileGatewayRuntime {
  val events: SharedFlow<GatewayEvent>

  suspend fun connect()
  suspend fun listSessions(): List<SessionSummary>
  suspend fun createSession(): ActiveSession
  suspend fun resumeSession(storedId: String): ActiveSession
  suspend fun submitPrompt(runtimeId: String, text: String)
  suspend fun interrupt(runtimeId: String)

  suspend fun respondApproval(runtimeId: String, choice: String) {
    error("Approval responses are unavailable")
  }

  suspend fun respondClarify(runtimeId: String, requestId: String, answer: String) {
    error("Clarify responses are unavailable")
  }

  suspend fun respondSecret(runtimeId: String, requestId: String, value: String) {
    error("Secret responses are unavailable")
  }

  suspend fun respondSudo(runtimeId: String, requestId: String, password: String) {
    error("Sudo responses are unavailable")
  }

  suspend fun attach(runtimeId: String, method: String, params: Map<String, String>): String? {
    error("Attachments are unavailable")
  }

  suspend fun updateSession(
    storedId: String,
    title: String? = null,
    archived: Boolean? = null,
    pinned: Boolean? = null,
  ) {
    error("Session updates are unavailable")
  }

  suspend fun deleteSession(storedId: String) {
    error("Session deletion is unavailable")
  }

  fun close()
}

internal class ChatController(
  private val runtime: MobileGatewayRuntime,
  scope: CoroutineScope,
) {
  private val mutableState = MutableStateFlow(MobileChatUiState())
  private val eventJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
    runtime.events.collect { event ->
      val current = mutableState.value
      mutableState.value = current.copy(chat = ChatReducer.reduce(current.chat, event))
    }
  }

  val state: StateFlow<MobileChatUiState> = mutableState.asStateFlow()

  suspend fun connect() {
    mutableState.value = mutableState.value.copy(phase = ConnectionPhase.CONNECTING, error = null)
    try {
      runtime.connect()
      val sessions = runtime.listSessions()
      mutableState.value = mutableState.value.copy(
        phase = ConnectionPhase.CONNECTED,
        sessions = sessions,
        error = null,
      )
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(
        phase = ConnectionPhase.DISCONNECTED,
        error = error.toUiError(),
      )
    }
  }

  suspend fun refreshSessions() {
    try {
      mutableState.value = mutableState.value.copy(sessions = runtime.listSessions(), error = null)
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(error = error.toUiError())
    }
  }

  suspend fun newSession() {
    try {
      mutableState.value = mutableState.value.copy(chat = runtime.createSession().toChatState(), error = null)
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(error = error.toUiError())
    }
  }

  suspend fun openSession(storedId: String) {
    if (storedId.isBlank()) return
    try {
      mutableState.value = mutableState.value.copy(chat = runtime.resumeSession(storedId).toChatState(), error = null)
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(error = error.toUiError())
    }
  }

  suspend fun send(text: String) {
    val cleanText = text.trim()
    val runtimeId = mutableState.value.chat.runtimeSessionId
    if (cleanText.isEmpty() || runtimeId.isNullOrBlank()) return
    try {
      runtime.submitPrompt(runtimeId, cleanText)
      mutableState.value = mutableState.value.copy(error = null)
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(error = error.toUiError())
    }
  }

  suspend fun stop() {
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    try {
      runtime.interrupt(runtimeId)
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(error = error.toUiError())
    }
  }

  suspend fun respondApproval(choice: String) {
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    runOperation { runtime.respondApproval(runtimeId, choice) }
  }

  suspend fun respondClarify(requestId: String, answer: String) {
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    runOperation { runtime.respondClarify(runtimeId, requestId, answer) }
  }

  suspend fun respondSecret(requestId: String, value: String) {
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    runOperation { runtime.respondSecret(runtimeId, requestId, value) }
  }

  suspend fun respondSudo(requestId: String, password: String) {
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    runOperation { runtime.respondSudo(runtimeId, requestId, password) }
  }

  suspend fun attach(method: String, params: Map<String, String>): String? {
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return null
    var reference: String? = null
    runOperation { reference = runtime.attach(runtimeId, method, params) }
    return reference
  }

  suspend fun updateSession(
    storedId: String,
    title: String? = null,
    archived: Boolean? = null,
    pinned: Boolean? = null,
  ) {
    runOperation { runtime.updateSession(storedId, title, archived, pinned) }
    refreshSessions()
  }

  suspend fun deleteSession(storedId: String) {
    runOperation { runtime.deleteSession(storedId) }
    refreshSessions()
    if (mutableState.value.chat.storedSessionId == storedId) newSession()
  }

  fun clearError() {
    mutableState.value = mutableState.value.copy(error = null)
  }

  fun close() {
    eventJob.cancel()
    runtime.close()
  }

  private fun ActiveSession.toChatState(): ChatState = ChatState(
    runtimeSessionId = runtimeId,
    storedSessionId = storedId,
    messages = messages.mapIndexed { index, record ->
      ChatMessage(
        id = record.rowId?.toString() ?: "history-${index + 1}",
        text = record.content,
        role = record.role,
        status = if (record.pending) MessageStatus.STREAMING else MessageStatus.COMPLETE,
        error = record.error,
        retryable = record.error != null,
      )
    },
    streaming = running,
    model = model,
    provider = provider,
  )

  private fun Throwable.toUiError(): MobileUiError {
    if (this is CancellationException) throw this
    return MobileUiError(
      message = message?.take(240)?.takeIf(String::isNotBlank) ?: "Gateway operation failed",
      retryable = true,
    )
  }
}
