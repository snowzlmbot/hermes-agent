package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.ActiveSession
import com.snowzlmbot.hermes.mobile.core.GatewayEvent
import com.snowzlmbot.hermes.mobile.core.GatewayRpcException
import com.snowzlmbot.hermes.mobile.core.ModelCatalog
import com.snowzlmbot.hermes.mobile.core.ModelOption
import com.snowzlmbot.hermes.mobile.core.SessionSummary
import java.util.concurrent.atomic.AtomicLong
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

internal sealed interface ModelSwitchResult {
  data object Applied : ModelSwitchResult
  data class ConfirmationRequired(val message: String) : ModelSwitchResult
}

internal data class PendingModelConfirmation(
  val option: ModelOption,
  val message: String,
)

internal data class MobileChatUiState(
  val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
  val sessions: List<SessionSummary> = emptyList(),
  val chat: ChatState = ChatState.empty(),
  val modelCatalog: ModelCatalog = ModelCatalog(),
  val isLoadingModelOptions: Boolean = false,
  val pendingModelConfirmation: PendingModelConfirmation? = null,
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

  suspend fun listModelOptions(runtimeId: String, refresh: Boolean = false): ModelCatalog {
    error("Model options are unavailable")
  }

  suspend fun selectModel(
    runtimeId: String,
    provider: String,
    model: String,
    confirmExpensiveModel: Boolean = false,
  ): ModelSwitchResult {
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
  private val selectionStore: StoredSessionSelectionStore = EmptyStoredSessionSelectionStore,
) {
  private val mutableState = MutableStateFlow(MobileChatUiState())
  private val sessionOperationGeneration = AtomicLong(0)
  private val modelControlOperationGeneration = AtomicLong(0)
  private val eventJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
    runtime.events.collect { event ->
      val current = mutableState.value
      val chat = ChatReducer.reduce(current.chat, event)
      val modelChanged = chat.model != current.chat.model || chat.provider != current.chat.provider
      val modelControlChanged = modelChanged || chat.reasoningEffort != current.chat.reasoningEffort
      if (modelControlChanged) modelControlOperationGeneration.incrementAndGet()
      mutableState.value = current.copy(
        chat = chat,
        modelCatalog = if (modelChanged) {
          current.modelCatalog.copy(currentModel = chat.model, currentProvider = chat.provider)
        } else {
          current.modelCatalog
        },
        isLoadingModelOptions = if (modelControlChanged) false else current.isLoadingModelOptions,
        pendingModelConfirmation = if (modelChanged) null else current.pendingModelConfirmation,
      )
    }
  }

  val state: StateFlow<MobileChatUiState> = mutableState.asStateFlow()

  suspend fun connect(): Boolean {
    mutableState.value = mutableState.value.copy(phase = ConnectionPhase.CONNECTING, error = null)
    try {
      runtime.connect()
      val sessions = runtime.listSessions()
      mutableState.value = mutableState.value.copy(
        phase = ConnectionPhase.CONNECTED,
        sessions = sessions.sortedForDisplay(),
        error = null,
      )
      true
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(
        phase = ConnectionPhase.DISCONNECTED,
        error = error.toUiError(),
      )
      false
    }
  }

  suspend fun connectAndRestore(): Boolean {
    if (!connect()) return false
    val storedId = selectionStore.load()?.takeIf(String::isNotBlank)
      ?: return newSession()
    if (mutableState.value.sessions.none { it.storedId == storedId }) {
      selectionStore.clear()
      return newSession()
    }
    return openSession(storedId)
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

  suspend fun newSession(): Boolean {
    val generation = sessionOperationGeneration.incrementAndGet()
    modelControlOperationGeneration.incrementAndGet()
    mutableState.value = mutableState.value.copy(
      isLoadingModelOptions = false,
      pendingModelConfirmation = null,
    )
    try {
      val active = runtime.createSession()
      if (sessionOperationGeneration.get() != generation) return false
      mutableState.value = mutableState.value.copy(
        chat = active.toChatState(),
        modelCatalog = ModelCatalog(),
        isLoadingModelOptions = false,
        pendingModelConfirmation = null,
        error = null,
      )
      selectionStore.save(active.storedId)
      true
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      if (sessionOperationGeneration.get() == generation) {
        mutableState.value = mutableState.value.copy(error = error.toUiError())
      }
      false
    }
  }

  suspend fun openSession(storedId: String): Boolean {
    if (storedId.isBlank()) return false
    val generation = sessionOperationGeneration.incrementAndGet()
    modelControlOperationGeneration.incrementAndGet()
    mutableState.value = mutableState.value.copy(
      isLoadingModelOptions = false,
      pendingModelConfirmation = null,
    )
    try {
      val active = runtime.resumeSession(storedId)
      if (sessionOperationGeneration.get() != generation) return false
      mutableState.value = mutableState.value.copy(
        chat = active.toChatState(),
        modelCatalog = ModelCatalog(),
        isLoadingModelOptions = false,
        pendingModelConfirmation = null,
        error = null,
      )
      selectionStore.save(active.storedId)
      true
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      if (error is GatewayRpcException && error.code == 4007 && sessionOperationGeneration.get() == generation) {
        selectionStore.clear()
        return newSession()
      }
      if (sessionOperationGeneration.get() == generation) {
        mutableState.value = mutableState.value.copy(error = error.toUiError())
      }
      false
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

  suspend fun refreshModelOptions(forceRefresh: Boolean = false) {
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    val generation = modelControlOperationGeneration.incrementAndGet()
    mutableState.value = mutableState.value.copy(isLoadingModelOptions = true)
    try {
      val catalog = runtime.listModelOptions(runtimeId, refresh = forceRefresh)
      if (!isCurrentModelControlOperation(generation, runtimeId)) return
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
      if (error is CancellationException) throw error
      if (isCurrentModelControlOperation(generation, runtimeId)) {
        mutableState.value = mutableState.value.copy(
          isLoadingModelOptions = false,
          error = error.toUiError(),
        )
      }
    }
  }

  suspend fun selectModel(option: ModelOption, confirmExpensiveModel: Boolean = false) {
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    val generation = modelControlOperationGeneration.incrementAndGet()
    mutableState.value = if (confirmExpensiveModel) {
      mutableState.value.copy(isLoadingModelOptions = false)
    } else {
      mutableState.value.copy(
        isLoadingModelOptions = false,
        pendingModelConfirmation = null,
      )
    }
    try {
      val result = runtime.selectModel(
        runtimeId,
        option.providerId,
        option.id,
        confirmExpensiveModel = confirmExpensiveModel,
      )
      if (!isCurrentModelControlOperation(generation, runtimeId)) return
      val current = mutableState.value
      mutableState.value = when (result) {
        ModelSwitchResult.Applied -> current.copy(
          modelCatalog = current.modelCatalog.copy(
            currentModel = option.id,
            currentProvider = option.providerId,
          ),
          chat = current.chat.copy(model = option.id, provider = option.providerId),
          pendingModelConfirmation = null,
          error = null,
        )
        is ModelSwitchResult.ConfirmationRequired -> current.copy(
          pendingModelConfirmation = PendingModelConfirmation(option, result.message),
          error = null,
        )
      }
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      if (isCurrentModelControlOperation(generation, runtimeId)) {
        mutableState.value = mutableState.value.copy(error = error.toUiError())
      }
    }
  }

  suspend fun confirmModelSelection() {
    val pending = mutableState.value.pendingModelConfirmation ?: return
    selectModel(pending.option, confirmExpensiveModel = true)
  }

  fun cancelModelSelection() {
    mutableState.value = mutableState.value.copy(pendingModelConfirmation = null)
  }

  suspend fun setReasoningEffort(effort: String) {
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    val generation = modelControlOperationGeneration.incrementAndGet()
    mutableState.value = mutableState.value.copy(isLoadingModelOptions = false)
    try {
      runtime.setReasoningEffort(runtimeId, effort)
      if (!isCurrentModelControlOperation(generation, runtimeId)) return
      val current = mutableState.value
      mutableState.value = current.copy(
        chat = current.chat.copy(reasoningEffort = effort),
        error = null,
      )
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      if (isCurrentModelControlOperation(generation, runtimeId)) {
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
    sessionOperationGeneration.incrementAndGet()
    modelControlOperationGeneration.incrementAndGet()
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

  private fun isCurrentModelControlOperation(generation: Long, runtimeId: String): Boolean =
    modelControlOperationGeneration.get() == generation && mutableState.value.chat.runtimeSessionId == runtimeId

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
