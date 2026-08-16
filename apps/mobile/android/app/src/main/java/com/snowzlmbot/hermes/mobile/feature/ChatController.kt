package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.ActiveSession
import com.snowzlmbot.hermes.mobile.core.GatewayEvent
import com.snowzlmbot.hermes.mobile.core.GatewayEventType
import com.snowzlmbot.hermes.mobile.platform.ChatNotificationSignal
import com.snowzlmbot.hermes.mobile.platform.NotificationKind
import com.snowzlmbot.hermes.mobile.core.GatewayRpcException
import com.snowzlmbot.hermes.mobile.core.ModelCatalog
import com.snowzlmbot.hermes.mobile.core.ModelOption
import com.snowzlmbot.hermes.mobile.core.SessionSummary
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

  suspend fun listSessions(includeArchived: Boolean): List<SessionSummary> = listSessions()
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
  private val eventReplayGuard: EventReplayGuard = EventReplayGuard(),
  private val profileScope: String = "default",
  private val onNotification: (ChatNotificationSignal) -> Unit = {},
) {
  private val mutableState = MutableStateFlow(MobileChatUiState())
  private val closed = AtomicBoolean(false)
  private val lifetimeJob = SupervisorJob(scope.coroutineContext[Job])
  private val lifetimeScope = CoroutineScope(scope.coroutineContext + lifetimeJob)
  private val sessionMutex = Mutex()
  private val sessionsDirty = AtomicBoolean(false)
  private val sessionRefreshRequests = Channel<Unit>(Channel.CONFLATED)
  private val connectionGeneration = AtomicLong(0)
  private val sessionOperationGeneration = AtomicLong(0)
  private val sessionMutationGeneration = AtomicLong(0)
  private val modelControlOperationGeneration = AtomicLong(0)
  private val sessionRefreshJob: Job = lifetimeScope.launch(start = CoroutineStart.UNDISPATCHED) {
    for (request in sessionRefreshRequests) {
      sessionMutex.withLock {
        if (!closed.get() && mutableState.value.phase == ConnectionPhase.CONNECTED) refreshSessionsLocked()
      }
    }
  }
  private val eventJob: Job = lifetimeScope.launch(start = CoroutineStart.UNDISPATCHED) {
    runtime.events.collect { event ->
      val current = mutableState.value
      if (event.type == GatewayEventType.SESSIONS_CHANGED) {
        when (current.phase) {
          ConnectionPhase.CONNECTING -> sessionsDirty.set(true)
          ConnectionPhase.CONNECTED -> sessionRefreshRequests.trySend(Unit)
          ConnectionPhase.DISCONNECTED -> Unit
        }
        return@collect
      }
      val runtimeId = current.chat.runtimeSessionId
      val storedId = current.chat.storedSessionId
      if (runtimeId != null && event.runtimeSessionId != null && event.runtimeSessionId != runtimeId) {
        return@collect
      }
      if (runtimeId != null && storedId != null && event.runtimeSessionId == runtimeId &&
        !eventReplayGuard.accept(profileScope, storedId, event)
      ) {
        return@collect
      }
      val notification = notificationSignal(event, current.chat)
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
      notification?.let(onNotification)
    }
  }

  val state: StateFlow<MobileChatUiState> = mutableState.asStateFlow()

  suspend fun connect(): Boolean = runInLifetime(false) {
    val generation = connectionGeneration.incrementAndGet()
    mutableState.value = mutableState.value.copy(phase = ConnectionPhase.CONNECTING, error = null)
    try {
      runtime.connect()
      sessionMutex.withLock {
        if (!isCurrentConnection(generation)) return@withLock
        val sessions = runtime.listSessions(includeArchived = true)
        if (!isCurrentConnection(generation)) return@withLock
        mutableState.value = mutableState.value.copy(
          phase = ConnectionPhase.CONNECTED,
          sessions = sessions.sortedForDisplay(),
          error = null,
        )
      }
      if (!isCurrentConnection(generation)) return@runInLifetime false
      if (sessionsDirty.getAndSet(false)) sessionRefreshRequests.trySend(Unit)
      true
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      if (isCurrentConnection(generation)) {
        mutableState.value = mutableState.value.copy(
          phase = ConnectionPhase.DISCONNECTED,
          error = error.toUiError(),
        )
      }
      false
    }
  }

  suspend fun connectAndRestore(): Boolean {
    if (closed.get() || !connect()) return false
    val storedId = selectionStore.load()?.takeIf(String::isNotBlank)
      ?: return newSession()
    if (storedId.isBlank()) return newSession()
    return openSession(storedId)
  }

  suspend fun refreshSessions() = runInLifetime(Unit) {
    sessionMutex.withLock { refreshSessionsLocked() }
  }

  suspend fun restoreSession(storedId: String) = runInLifetime(Unit) {
    val id = storedId.trim()
    if (id.isNotEmpty()) runSessionMutation { runtime.updateSession(id, archived = false) }
  }

  suspend fun newSession(): Boolean = runInLifetime(false) {
    createNewSession()
  }

  private suspend fun createNewSession(preservedError: MobileUiError? = null): Boolean {
    val generation = sessionOperationGeneration.incrementAndGet()
    modelControlOperationGeneration.incrementAndGet()
    mutableState.value = mutableState.value.copy(
      isLoadingModelOptions = false,
      pendingModelConfirmation = null,
    )
    return try {
      val active = runtime.createSession()
      if (closed.get() || sessionOperationGeneration.get() != generation) return false
      mutableState.value = mutableState.value.copy(
        chat = active.toChatState(),
        modelCatalog = ModelCatalog(),
        isLoadingModelOptions = false,
        pendingModelConfirmation = null,
        error = preservedError,
      )
      selectionStore.save(active.storedId)
      true
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      if (!closed.get() && sessionOperationGeneration.get() == generation) {
        mutableState.value = mutableState.value.copy(error = error.toUiError())
      }
      false
    }
  }

  suspend fun openSession(storedId: String): Boolean = runInLifetime(false) {
    openSessionInternal(storedId)
  }

  private suspend fun openSessionInternal(storedId: String): Boolean {
    if (storedId.isBlank()) return false
    val generation = sessionOperationGeneration.incrementAndGet()
    modelControlOperationGeneration.incrementAndGet()
    mutableState.value = mutableState.value.copy(
      isLoadingModelOptions = false,
      pendingModelConfirmation = null,
    )
    return try {
      val active = runtime.resumeSession(storedId)
      if (closed.get() || sessionOperationGeneration.get() != generation) return false
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
      if (error is GatewayRpcException && error.code == 4007 &&
        !closed.get() && sessionOperationGeneration.get() == generation
      ) {
        selectionStore.clear()
        return createNewSession()
      }
      if (!closed.get() && sessionOperationGeneration.get() == generation) {
        mutableState.value = mutableState.value.copy(error = error.toUiError())
      }
      false
    }
  }

  suspend fun send(text: String) {
    if (closed.get()) return
    val cleanText = text.trim()
    val runtimeId = mutableState.value.chat.runtimeSessionId
    if (cleanText.isEmpty() || runtimeId.isNullOrBlank()) return
    try {
      runtime.submitPrompt(runtimeId, cleanText)
      if (closed.get()) return
      mutableState.value = mutableState.value.copy(error = null)
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(error = error.toUiError())
    }
  }

  suspend fun stop() {
    if (closed.get()) return
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    try {
      runtime.interrupt(runtimeId)
      if (closed.get()) return
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(error = error.toUiError())
    }
  }

  suspend fun refreshModelOptions(forceRefresh: Boolean = false) {
    if (closed.get()) return
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
    if (closed.get()) return
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
    if (closed.get()) return
    val pending = mutableState.value.pendingModelConfirmation ?: return
    selectModel(pending.option, confirmExpensiveModel = true)
  }

  fun cancelModelSelection() {
    if (closed.get()) return
    mutableState.value = mutableState.value.copy(pendingModelConfirmation = null)
  }

  suspend fun setReasoningEffort(effort: String) {
    if (closed.get()) return
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
    if (closed.get()) return
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    runOperation { runtime.respondApproval(runtimeId, choice) }
  }

  suspend fun respondClarify(requestId: String, answer: String) {
    if (closed.get()) return
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    runOperation { runtime.respondClarify(runtimeId, requestId, answer) }
  }

  suspend fun respondSecret(requestId: String, value: String) {
    if (closed.get()) return
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    runOperation { runtime.respondSecret(runtimeId, requestId, value) }
  }

  suspend fun respondSudo(requestId: String, password: String) {
    if (closed.get()) return
    val runtimeId = mutableState.value.chat.runtimeSessionId ?: return
    runOperation { runtime.respondSudo(runtimeId, requestId, password) }
  }

  suspend fun attach(method: String, params: Map<String, String>): String? {
    if (closed.get()) return null
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
  ) = runInLifetime(Unit) {
    runSessionMutation { runtime.updateSession(storedId, title, archived, pinned) }
  }

  suspend fun setPinned(storedId: String, pinned: Boolean) {
    updateSession(storedId, pinned = pinned)
  }

  suspend fun deleteSession(storedId: String) = runInLifetime(Unit) {
    val activeGeneration = sessionOperationGeneration.get()
    val outcome = runSessionMutation { runtime.deleteSession(storedId) }
    if (outcome !is SessionMutationOutcome.Committed) return@runInLifetime
    if (outcome.refreshError != null) {
      mutableState.value = mutableState.value.copy(
        sessions = mutableState.value.sessions.filterNot { it.storedId == storedId },
      )
    }
    if (sessionOperationGeneration.get() == activeGeneration &&
      mutableState.value.chat.storedSessionId == storedId
    ) {
      createNewSession(preservedError = outcome.refreshError)
    }
  }

  fun clearError() {
    if (closed.get()) return
    mutableState.value = mutableState.value.copy(error = null)
  }

  fun close() {
    if (!closed.compareAndSet(false, true)) return
    connectionGeneration.incrementAndGet()
    sessionOperationGeneration.incrementAndGet()
    sessionMutationGeneration.incrementAndGet()
    modelControlOperationGeneration.incrementAndGet()
    sessionRefreshRequests.close()
    lifetimeJob.cancel(CancellationException("Chat controller closed"))
    runtime.close()
  }

  private fun notificationSignal(event: GatewayEvent, state: ChatState): ChatNotificationSignal? {
    val runtimeId = state.runtimeSessionId ?: return null
    val storedId = state.storedSessionId
      ?.takeIf { it.isNotBlank() && it == it.trim() }
      ?: return null
    if (event.runtimeSessionId != runtimeId) return null
    val kind = notificationKind(event.type) ?: return null
    return ChatNotificationSignal(kind, storedId)
  }

  private suspend fun runOperation(operation: suspend () -> Unit) {
    if (closed.get()) return
    try {
      operation()
      if (closed.get()) return
      mutableState.value = mutableState.value.copy(error = null)
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(error = error.toUiError())
    }
  }

  private suspend fun refreshSessionsLocked(): MobileUiError? = try {
    val sessions = runtime.listSessions(includeArchived = true).sortedForDisplay()
    if (!closed.get()) mutableState.value = mutableState.value.copy(sessions = sessions, error = null)
    null
  } catch (error: Throwable) {
    if (error is CancellationException) throw error
    val uiError = error.toUiError()
    if (!closed.get()) mutableState.value = mutableState.value.copy(error = uiError)
    uiError
  }

  private suspend fun runSessionMutation(operation: suspend () -> Unit): SessionMutationOutcome =
    sessionMutex.withLock {
      if (closed.get()) throw CancellationException("Chat controller closed")
      val generation = sessionMutationGeneration.incrementAndGet()
      try {
        operation()
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        if (!closed.get() && sessionMutationGeneration.get() == generation) {
          mutableState.value = mutableState.value.copy(error = error.toUiError())
        }
        return@withLock SessionMutationOutcome.Failed
      }

      val refreshError = refreshSessionsLocked()
      SessionMutationOutcome.Committed(refreshError)
    }

  private suspend fun <T> runInLifetime(closedResult: T, operation: suspend () -> T): T {
    if (closed.get()) return closedResult
    val task = lifetimeScope.async(start = CoroutineStart.UNDISPATCHED) {
      if (closed.get()) closedResult else operation()
    }
    return try {
      task.await()
    } catch (error: CancellationException) {
      task.cancel(error)
      throw error
    }
  }

  private fun isCurrentConnection(generation: Long): Boolean =
    !closed.get() && connectionGeneration.get() == generation

  private sealed interface SessionMutationOutcome {
    data object Failed : SessionMutationOutcome
    data class Committed(val refreshError: MobileUiError?) : SessionMutationOutcome
  }

  private fun notificationKind(type: GatewayEventType): NotificationKind? = when (type) {
    GatewayEventType.MESSAGE_COMPLETE -> NotificationKind.COMPLETION
    GatewayEventType.APPROVAL_REQUEST -> NotificationKind.APPROVAL
    GatewayEventType.CLARIFY_REQUEST, GatewayEventType.SECRET_REQUEST, GatewayEventType.SUDO_REQUEST -> NotificationKind.INPUT
    else -> null
  }

  private fun isCurrentModelControlOperation(generation: Long, runtimeId: String): Boolean =
    !closed.get() && modelControlOperationGeneration.get() == generation &&
      mutableState.value.chat.runtimeSessionId == runtimeId

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
