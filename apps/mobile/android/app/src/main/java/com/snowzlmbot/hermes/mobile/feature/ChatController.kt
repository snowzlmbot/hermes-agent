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
