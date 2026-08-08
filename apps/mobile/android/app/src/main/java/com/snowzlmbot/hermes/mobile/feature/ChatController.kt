package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.ActiveSession
import com.snowzlmbot.hermes.mobile.core.GatewayEvent
import com.snowzlmbot.hermes.mobile.core.ModelCatalog
import com.snowzlmbot.hermes.mobile.core.ModelOption
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
  val modelCatalog: ModelCatalog = ModelCatalog(),
  val isLoadingModelOptions: Boolean = false,
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

  suspend fun listModelOptions(runtimeId: String): ModelCatalog {
    error("Model options are unavailable")
  }

  suspend fun selectModel(runtimeId: String, provider: String, model: String) {
    error("Model selection is unavailable")
  }

  suspend fun setReasoningEffort(runtimeId: String, effort: String) {
    error("Reasoning controls are unavailable")
  }

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
        sessions = sessions.sortedForDisplay(),
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
      mutableState.value = mutableState.value.copy(
        sessions = runtime.listSessions().sortedForDisplay(),
        error = null,
      )
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(error = error.toUiError())
    }
  }

  suspend fun newSession() {
    try {
      mutableState.value = mutableState.value.copy(
        chat = runtime.createSession().toChatState(),
        modelCatalog = ModelCatalog(),
        isLoadingModelOptions = false,
        error = null,
      )
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(error = error.toUiError())
    }
  }

  suspend fun openSession(storedId: String) {
    if (storedId.isBlank()) return
    try {
      mutableState.value = mutableState.value.copy(
        chat = runtime.resumeSession(storedId).toChatState(),
        modelCatalog = ModelCatalog(),
        isLoadingModelOptions = false,
        error = null,
      )
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

  suspend fun refreshModelOptions() {
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    mutableState.value = mutableState.value.copy(isLoadingModelOptions = true)
    try {
      val catalog = runtime.listModelOptions(runtimeId)
      if (mutableState.value.chat.runtimeSessionId != runtimeId) return
      val current = mutableState.value
      mutableState.value = current.copy(
        modelCatalog = catalog,
        isLoadingModelOptions = false,
        chat = current.chat.copy(
          model = catalog.currentModel.ifBlank { current.chat.model },
          provider = catalog.currentProvider.ifBlank { current.chat.provider },
        ),
        error = null,
      )
    } catch (error: Throwable) {
      if (mutableState.value.chat.runtimeSessionId == runtimeId) {
        mutableState.value = mutableState.value.copy(
          isLoadingModelOptions = false,
          error = error.toUiError(),
        )
      }
    }
  }

  suspend fun selectModel(option: ModelOption) {
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    try {
      runtime.selectModel(runtimeId, option.providerId, option.id)
      if (mutableState.value.chat.runtimeSessionId != runtimeId) return
      val current = mutableState.value
      mutableState.value = current.copy(
        modelCatalog = current.modelCatalog.copy(
          currentModel = option.id,
          currentProvider = option.providerId,
        ),
        chat = current.chat.copy(model = option.id, provider = option.providerId),
        error = null,
      )
    } catch (error: Throwable) {
      if (mutableState.value.chat.runtimeSessionId == runtimeId) {
        mutableState.value = mutableState.value.copy(error = error.toUiError())
      }
    }
  }

  suspend fun setReasoningEffort(effort: String) {
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    try {
      runtime.setReasoningEffort(runtimeId, effort)
      if (mutableState.value.chat.runtimeSessionId != runtimeId) return
      val current = mutableState.value
      mutableState.value = current.copy(
        chat = current.chat.copy(reasoningEffort = effort),
        error = null,
      )
    } catch (error: Throwable) {
      if (mutableState.value.chat.runtimeSessionId == runtimeId) {
        mutableState.value = mutableState.value.copy(error = error.toUiError())
      }
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

  suspend fun setPinned(storedId: String, pinned: Boolean) {
    updateSession(storedId, pinned = pinned)
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

  private suspend fun runOperation(operation: suspend () -> Unit) {
    try {
      operation()
      mutableState.value = mutableState.value.copy(error = null)
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(error = error.toUiError())
    }
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
    reasoningEffort = reasoningEffort,
  )

  private fun Throwable.toUiError(): MobileUiError {
    if (this is CancellationException) throw this
    return MobileUiError(
      message = message?.take(240)?.takeIf(String::isNotBlank) ?: "Gateway operation failed",
      retryable = true,
    )
  }

  private fun List<SessionSummary>.sortedForDisplay(): List<SessionSummary> = sortedWith(
    compareByDescending<SessionSummary> { it.pinned }
      .thenByDescending { maxOf(it.lastActive, it.startedAt) }
      .thenBy { it.storedId },
  )
}
