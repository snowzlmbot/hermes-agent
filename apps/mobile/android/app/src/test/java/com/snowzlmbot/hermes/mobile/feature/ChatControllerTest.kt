package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.ActiveSession
import com.snowzlmbot.hermes.mobile.core.ChatMessageRecord
import com.snowzlmbot.hermes.mobile.core.GatewayEvent
import com.snowzlmbot.hermes.mobile.core.GatewayEventType
import com.snowzlmbot.hermes.mobile.core.ModelCatalog
import com.snowzlmbot.hermes.mobile.core.ModelOption
import com.snowzlmbot.hermes.mobile.core.ModelProviderOption
import com.snowzlmbot.hermes.mobile.core.SessionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
  @Test
  fun connectsLoadsSessionsAndKeepsStoredAndRuntimeIdentitiesSeparate() = runTest {
    val runtime = RecordingRuntime()
    val controller = ChatController(runtime, backgroundScope)

    controller.connect()
    controller.openSession("stored-1")

    assertEquals(ConnectionPhase.CONNECTED, controller.state.value.phase)
    assertEquals("stored-1", controller.state.value.chat.storedSessionId)
    assertEquals("runtime-resumed", controller.state.value.chat.runtimeSessionId)
    assertEquals(listOf("stored-1"), runtime.resumed)
  }

  @Test
  fun restoreListsThenResumesOnlyPersistedStoredIdentity() = runTest {
    val runtime = RecordingRuntime().apply {
      listedSessions = listOf(summary("stored-kept"), summary("stored-other"))
    }
    val selection = RecordingSessionSelectionStore("stored-kept")
    val controller = ChatController(runtime, backgroundScope, selection)

    assertTrue(controller.connectAndRestore())

    assertEquals(listOf("list", "resume:stored-kept"), runtime.lifecycleCalls)
    assertEquals("stored-kept", controller.state.value.chat.storedSessionId)
    assertEquals("runtime-resumed", controller.state.value.chat.runtimeSessionId)
    assertEquals("stored-kept", selection.storedSessionId)
  }

  @Test
  fun restoreWithoutPersistedIdentityKeepsNewSessionBehavior() = runTest {
    val runtime = RecordingRuntime()
    val selection = RecordingSessionSelectionStore()
    val controller = ChatController(runtime, backgroundScope, selection)

    assertTrue(controller.connectAndRestore())

    assertEquals(listOf("list", "create"), runtime.lifecycleCalls)
    assertEquals("stored-new", selection.storedSessionId)
    assertEquals("runtime-new", controller.state.value.chat.runtimeSessionId)
  }

  @Test
  fun failedDirectedRestoreIsNonDestructiveAndRetryable() = runTest {
    val runtime = RecordingRuntime().apply {
      listedSessions = listOf(summary("stored-kept"))
      failResumes = true
    }
    val selection = RecordingSessionSelectionStore("stored-kept")
    val controller = ChatController(runtime, backgroundScope, selection)

    assertFalse(controller.connectAndRestore())

    assertEquals("stored-kept", selection.storedSessionId)
    assertNull(controller.state.value.chat.runtimeSessionId)
    assertTrue(controller.state.value.error?.retryable == true)

    runtime.failResumes = false
    assertTrue(controller.connectAndRestore())
    assertEquals(listOf("stored-kept", "stored-kept"), runtime.resumed)
  }

  @Test
  fun staleResumeCannotPersistOrReplaceNewerSession() = runTest {
    val runtime = RecordingRuntime().apply { delayResumes = true }
    val selection = RecordingSessionSelectionStore("stored-old")
    val controller = ChatController(runtime, backgroundScope, selection)
    controller.connect()

    val stale = async { controller.openSession("stored-old") }
    runCurrent()
    controller.newSession()
    runtime.completeResume("stored-old", "runtime-old")
    stale.await()

    assertEquals("stored-new", controller.state.value.chat.storedSessionId)
    assertEquals("runtime-new", controller.state.value.chat.runtimeSessionId)
    assertEquals("stored-new", selection.storedSessionId)
  }

  @Test
  fun sendStopAndEventsUseTheActiveRuntimeIdentity() = runTest {
    val runtime = RecordingRuntime()
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.newSession()

    controller.send("hello")
    controller.stop()
    runtime.events.emit(
      GatewayEvent(
        type = GatewayEventType.MESSAGE_START,
        wireType = "message.start",
        runtimeSessionId = "runtime-new",
        payload = buildJsonObject {},
      ),
    )
    runtime.events.emit(
      GatewayEvent(
        type = GatewayEventType.MESSAGE_COMPLETE,
        wireType = "message.complete",
        runtimeSessionId = "runtime-new",
        payload = buildJsonObject {
          put("text", "reply")
          put("status", "complete")
        },
      ),
    )
    runCurrent()

    assertEquals(listOf("runtime-new" to "hello"), runtime.prompts)
    assertEquals(listOf("runtime-new"), runtime.interrupted)
    assertFalse(controller.state.value.chat.streaming)
    assertEquals("reply", controller.state.value.chat.messages.single().text)
  }

  @Test
  fun failedOperationsSetRecoverableUiErrorWithoutDiscardingSessions() = runTest {
    val runtime = RecordingRuntime().apply { failPrompts = true }
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.newSession()

    controller.send("hello")

    assertTrue(controller.state.value.error?.retryable == true)
    assertEquals(1, controller.state.value.sessions.size)
    controller.clearError()
    assertEquals(null, controller.state.value.error)
  }

  @Test
  fun pinningUsesStoredIdentityAndRefreshesSessions() = runTest {
    val runtime = RecordingRuntime().apply {
      listedSessions = listOf(
        summary("stored-recent", lastActive = 20),
        summary("stored-1", lastActive = 1),
      )
    }
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()

    controller.setPinned("stored-1", true)

    assertEquals(listOf("stored-1" to true), runtime.pinnedUpdates)
    assertEquals(2, runtime.sessionListRequests)
    assertEquals(listOf("stored-1", "stored-recent"), controller.state.value.sessions.map { it.storedId })
    assertTrue(controller.state.value.sessions.first().pinned)
  }

  @Test
  fun modelControlsLoadForActiveRuntimeAndUpdateVisibleChatState() = runTest {
    val runtime = RecordingRuntime()
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.newSession()

    controller.refreshModelOptions()
    val option = controller.state.value.modelCatalog.providers.single().models.single()
    controller.selectModel(option)
    controller.setReasoningEffort("high")

    assertEquals(listOf("runtime-new"), runtime.modelOptionsRequests)
    assertEquals(listOf("runtime-new" to ("fixture" to "fixture-model")), runtime.modelSelections)
    assertEquals(listOf("runtime-new" to "high"), runtime.reasoningSelections)
    assertEquals("fixture-model", controller.state.value.chat.model)
    assertEquals("fixture", controller.state.value.chat.provider)
    assertEquals("high", controller.state.value.chat.reasoningEffort)
  }

  @Test
  fun switchingSessionsClearsModelCatalogAndLoadingState() = runTest {
    val runtime = RecordingRuntime()
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.newSession()
    controller.refreshModelOptions()

    assertTrue(controller.state.value.modelCatalog.providers.isNotEmpty())
    controller.openSession("stored-1")

    assertEquals("runtime-resumed", controller.state.value.chat.runtimeSessionId)
    assertTrue(controller.state.value.modelCatalog.providers.isEmpty())
    assertFalse(controller.state.value.isLoadingModelOptions)
  }

  @Test
  fun confirmationRequiredDoesNotChangeModelUntilExplicitlyConfirmed() = runTest {
    val runtime = RecordingRuntime().apply {
      modelSwitchResult = ModelSwitchResult.ConfirmationRequired("Confirm expensive model")
    }
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.newSession()
    controller.refreshModelOptions()
    val option = ModelOption(
      providerId = "fixture",
      providerName = "Fixture",
      id = "expensive-model",
      supportsReasoning = true,
    )

    controller.selectModel(option)

    assertEquals("fixture-model", controller.state.value.chat.model)
    assertEquals(option, controller.state.value.pendingModelConfirmation?.option)
    assertEquals("Confirm expensive model", controller.state.value.pendingModelConfirmation?.message)

    runtime.modelSwitchResult = ModelSwitchResult.Applied
    controller.confirmModelSelection()

    assertEquals("expensive-model", controller.state.value.chat.model)
    assertEquals(null, controller.state.value.pendingModelConfirmation)
    assertEquals(listOf(false, true), runtime.modelSelectionConfirmations)
  }

  @Test
  fun slowerEarlierResumeCannotOverwriteNewerSessionSelection() = runTest {
    val runtime = RecordingRuntime().apply { delayResumes = true }
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()

    val first = async { controller.openSession("stored-1") }
    runCurrent()
    val second = async { controller.openSession("stored-2") }
    runCurrent()

    runtime.completeResume("stored-2", "runtime-2")
    runCurrent()
    runtime.completeResume("stored-1", "runtime-1")
    first.await()
    second.await()

    assertEquals("stored-2", controller.state.value.chat.storedSessionId)
    assertEquals("runtime-2", controller.state.value.chat.runtimeSessionId)
  }

  @Test
  fun manualRefreshForcesProviderProbe() = runTest {
    val runtime = RecordingRuntime()
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.newSession()

    controller.refreshModelOptions(forceRefresh = true)

    assertEquals(listOf(true), runtime.modelOptionRefreshes)
  }

  @Test
  fun confirmedModelSelectionWinsOverSlowerEarlierCatalogRefresh() = runTest {
    val runtime = RecordingRuntime().apply { delayModelOptions = true }
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.newSession()
    val option = ModelOption(
      providerId = "fixture",
      providerName = "Fixture",
      id = "new-model",
      supportsReasoning = true,
    )

    val refresh = async { controller.refreshModelOptions() }
    runCurrent()
    controller.selectModel(option)
    runtime.completeModelOptions()
    refresh.await()

    assertEquals("new-model", controller.state.value.chat.model)
    assertEquals("fixture", controller.state.value.chat.provider)
    assertFalse(controller.state.value.isLoadingModelOptions)
  }

  @Test
  fun restoreSessionRefreshesArchiveWithoutChangingCurrentIdentity() = runTest {
    val runtime = RecordingRuntime()
    runtime.listedSessions = listOf(archivedSummary("stored-archived"))
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.newSession()
    val identity = controller.state.value.chat.runtimeSessionId to controller.state.value.chat.storedSessionId

    controller.restoreSession("stored-archived")

    assertEquals(listOf("stored-archived" to false), runtime.archivedUpdates)
    assertFalse(controller.state.value.sessions.single().archived)
    assertEquals(identity, controller.state.value.chat.runtimeSessionId to controller.state.value.chat.storedSessionId)
  }

  @Test
  fun restoreFailureKeepsArchivedEntryAndCurrentIdentity() = runTest {
    val runtime = RecordingRuntime().apply {
      listedSessions = listOf(archivedSummary("stored-archived"))
      failSessionUpdates = true
    }
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.newSession()
    val identity = controller.state.value.chat.runtimeSessionId to controller.state.value.chat.storedSessionId

    controller.restoreSession("stored-archived")

    assertTrue(controller.state.value.sessions.single().archived)
    assertEquals(identity, controller.state.value.chat.runtimeSessionId to controller.state.value.chat.storedSessionId)
    assertNotNull(controller.state.value.error)
  }

  @Test
  fun durableMutationsEnterRuntimeSeriallyAndOlderCommitSurvivesNewerFailure() = runTest {
    val runtime = RecordingRuntime().apply { delaySessionMutations = true }
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()

    val first = async { controller.setPinned("stored-1", true) }
    runCurrent()
    val second = async { controller.setPinned("stored-1", false) }
    runCurrent()

    assertEquals(listOf("update:stored-1:pinned=true"), runtime.sessionMutationCalls)

    runtime.completeSessionMutation(0)
    runCurrent()

    assertTrue(controller.state.value.sessions.single().pinned)
    assertEquals(
      listOf("update:stored-1:pinned=true", "update:stored-1:pinned=false"),
      runtime.sessionMutationCalls,
    )

    runtime.failSessionMutation(0)
    first.await()
    second.await()

    assertTrue(controller.state.value.sessions.single().pinned)
    assertNotNull(controller.state.value.error)
    assertEquals(2, runtime.sessionListRequests)
  }

  @Test
  fun failedActiveDeletePreservesIdentityAndErrorWithoutReplacementOrRefresh() = runTest {
    val runtime = RecordingRuntime().apply { failSessionDeletes = true }
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.openSession("stored-1")
    val active = controller.state.value.chat

    controller.deleteSession("stored-1")

    assertEquals(active, controller.state.value.chat)
    assertNotNull(controller.state.value.error)
    assertEquals(1, runtime.sessionListRequests)
    assertEquals(0, runtime.createdSessions)
  }

  @Test
  fun cancellingCallerCancelsPendingMutationAndReleasesSerialization() = runTest {
    val runtime = RecordingRuntime().apply { delaySessionMutations = true }
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    val first = async { controller.setPinned("stored-1", true) }
    runCurrent()

    first.cancel()
    assertTrue(runCatching { first.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    runCurrent()
    assertEquals(1, runtime.cancelledSessionMutations)

    runtime.delaySessionMutations = false
    controller.setPinned("stored-1", false)
    assertEquals(
      listOf("update:stored-1:pinned=true", "update:stored-1:pinned=false"),
      runtime.sessionMutationCalls,
    )
  }

  @Test
  fun closeCancelsPendingMutationAndAllSessionApisAreTerminalNoOps() = runTest {
    val runtime = RecordingRuntime().apply { delaySessionMutations = true }
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    val mutation = async { controller.setPinned("stored-1", true) }
    runCurrent()

    controller.close()
    runCurrent()

    assertTrue(runCatching { mutation.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    assertEquals(1, runtime.cancelledSessionMutations)
    val callsAtClose = runtime.lifecycleCalls.toList()
    val mutationsAtClose = runtime.sessionMutationCalls.toList()
    controller.connect()
    controller.refreshSessions()
    controller.restoreSession("stored-1")
    controller.newSession()
    controller.openSession("stored-1")
    controller.setPinned("stored-1", false)
    controller.deleteSession("stored-1")

    assertEquals(callsAtClose, runtime.lifecycleCalls)
    assertEquals(mutationsAtClose, runtime.sessionMutationCalls)
    assertEquals(1, runtime.closeCalls)
  }

  @Test
  fun sessionsChangedDuringConnectRefreshesAfterInitialSnapshot() = runTest {
    val runtime = RecordingRuntime().apply { delaySessionLists = true }
    val controller = ChatController(runtime, backgroundScope)
    val connect = async { controller.connect() }
    runCurrent()

    runtime.events.emit(sessionsChangedEvent())
    runtime.listedSessions = listOf(runtime.summary("stored-latest"))
    runCurrent()
    assertEquals(1, runtime.sessionListRequests)

    runtime.completeSessionList(0)
    runCurrent()
    assertTrue(connect.await())
    assertEquals(2, runtime.sessionListRequests)

    runtime.completeSessionList(0)
    runCurrent()

    assertEquals(listOf("stored-latest"), controller.state.value.sessions.map { it.storedId })
  }

  @Test
  fun sessionsChangedBurstConflatesToOneTrailingLatestRefresh() = runTest {
    val runtime = RecordingRuntime()
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    runtime.delaySessionLists = true

    runtime.events.emit(sessionsChangedEvent())
    runCurrent()
    runtime.listedSessions = listOf(runtime.summary("stored-latest"))
    repeat(4) { runtime.events.emit(sessionsChangedEvent()) }
    runCurrent()

    assertEquals(2, runtime.sessionListRequests)
    runtime.completeSessionList(0)
    runCurrent()
    assertEquals(3, runtime.sessionListRequests)
    runtime.completeSessionList(0)
    runCurrent()

    assertEquals(3, runtime.sessionListRequests)
    assertEquals(listOf("stored-latest"), controller.state.value.sessions.map { it.storedId })
  }

  @Test
  fun failedMutationWaitsForEventRefreshAndDoesNotDiscardItsSnapshot() = runTest {
    val runtime = RecordingRuntime()
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    runtime.delaySessionLists = true
    runtime.listedSessions = listOf(runtime.summary("stored-external"))
    runtime.events.emit(sessionsChangedEvent())
    runCurrent()

    runtime.failSessionUpdates = true
    val mutation = async { controller.setPinned("stored-1", true) }
    runCurrent()
    assertTrue(runtime.sessionMutationCalls.isEmpty())

    runtime.completeSessionList(0)
    runCurrent()
    mutation.await()

    assertEquals(listOf("stored-external"), controller.state.value.sessions.map { it.storedId })
    assertNotNull(controller.state.value.error)
  }

  @Test
  fun successfulActiveDeleteKeepsRefreshErrorAfterCreatingReplacement() = runTest {
    val runtime = RecordingRuntime()
    val controller = ChatController(runtime, backgroundScope)
    controller.connect()
    controller.openSession("stored-1")
    runtime.failSessionLists = true

    controller.deleteSession("stored-1")

    assertTrue(controller.state.value.sessions.none { it.storedId == "stored-1" })
    assertEquals("stored-new", controller.state.value.chat.storedSessionId)
    assertNotNull(controller.state.value.error)
    assertEquals(1, runtime.createdSessions)
  }

  private fun archivedSummary(id: String) = SessionSummary(
    storedId = id,
    title = "",
    preview = "",
    startedAt = 0,
    lastActive = 0,
    messageCount = 0,
    source = "mobile",
    archived = true,
  )

  private fun sessionsChangedEvent() = GatewayEvent(
    type = GatewayEventType.SESSIONS_CHANGED,
    wireType = "sessions.changed",
    runtimeSessionId = null,
    payload = buildJsonObject {},
  )

  private class RecordingRuntime : MobileGatewayRuntime {
    override val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
    val lifecycleCalls = mutableListOf<String>()
    val resumed = mutableListOf<String>()
    val prompts = mutableListOf<Pair<String, String>>()
    val interrupted = mutableListOf<String>()
    val pinnedUpdates = mutableListOf<Pair<String, Boolean>>()
    val archivedUpdates = mutableListOf<Pair<String, Boolean>>()
    val sessionMutationCalls = mutableListOf<String>()
    val modelOptionsRequests = mutableListOf<String>()
    val modelOptionRefreshes = mutableListOf<Boolean>()
    val modelSelections = mutableListOf<Pair<String, Pair<String, String>>>()
    val modelSelectionConfirmations = mutableListOf<Boolean>()
    val reasoningSelections = mutableListOf<Pair<String, String>>()
    var listedSessions = listOf(summary("stored-1"))
    var sessionListRequests = 0
    var failPrompts = false
    var failSessionUpdates = false
    var failSessionDeletes = false
    var failSessionLists = false
    var failResumes = false
    var modelSwitchResult: ModelSwitchResult = ModelSwitchResult.Applied
    var delayResumes = false
    var delayModelOptions = false
    var delaySessionLists = false
    var delaySessionMutations = false
    var createdSessions = 0
    var cancelledSessionMutations = 0
    var closeCalls = 0
    private val pendingResumes = mutableMapOf<String, CompletableDeferred<ActiveSession>>()
    private var pendingModelOptions: CompletableDeferred<ModelCatalog>? = null
    private data class PendingSessionList(
      val snapshot: List<SessionSummary>,
      val result: CompletableDeferred<List<SessionSummary>> = CompletableDeferred(),
    )
    private data class PendingSessionMutation(
      val result: CompletableDeferred<Unit> = CompletableDeferred(),
    )
    private val pendingSessionLists = mutableListOf<PendingSessionList>()
    private val pendingSessionMutations = mutableListOf<PendingSessionMutation>()

    override suspend fun connect() = Unit

    override suspend fun listSessions(): List<SessionSummary> {
      lifecycleCalls += "list"
      sessionListRequests += 1
      if (failSessionLists) error("session refresh failed")
      if (delaySessionLists) {
        val pending = PendingSessionList(listedSessions)
        pendingSessionLists += pending
        return pending.result.await()
      }
      return listedSessions
    }

    fun completeSessionList(index: Int) {
      val pending = pendingSessionLists.removeAt(index)
      pending.result.complete(pending.snapshot)
    }

    override suspend fun createSession(): ActiveSession {
      lifecycleCalls += "create"
      createdSessions += 1
      return active("runtime-new", "stored-new")
    }

    override suspend fun resumeSession(storedId: String): ActiveSession {
      lifecycleCalls += "resume:$storedId"
      resumed += storedId
      if (failResumes) error("gateway unavailable")
      if (delayResumes) {
        return pendingResumes.getOrPut(storedId) { CompletableDeferred() }.await()
      }
      return active("runtime-resumed", storedId)
    }

    fun completeResume(storedId: String, runtimeId: String) {
      pendingResumes.getOrPut(storedId) { CompletableDeferred() }
        .complete(active(runtimeId, storedId))
    }

    override suspend fun submitPrompt(runtimeId: String, text: String) {
      if (failPrompts) error("gateway unavailable")
      prompts += runtimeId to text
    }

    override suspend fun interrupt(runtimeId: String) {
      interrupted += runtimeId
    }

    override suspend fun listModelOptions(runtimeId: String, refresh: Boolean): ModelCatalog {
      modelOptionsRequests += runtimeId
      modelOptionRefreshes += refresh
      val catalog = ModelCatalog(
        currentModel = "fixture-model",
        currentProvider = "fixture",
        providers = listOf(
          ModelProviderOption(
            id = "fixture",
            name = "Fixture",
            models = listOf(
              ModelOption(
                providerId = "fixture",
                providerName = "Fixture",
                id = "fixture-model",
                supportsReasoning = true,
              ),
            ),
          ),
        ),
      )
      if (!delayModelOptions) return catalog
      val deferred = CompletableDeferred<ModelCatalog>()
      pendingModelOptions = deferred
      return deferred.await()
    }

    fun completeModelOptions() {
      pendingModelOptions?.complete(
        ModelCatalog(
          currentModel = "fixture-model",
          currentProvider = "fixture",
          providers = emptyList(),
        ),
      )
    }

    override suspend fun selectModel(
      runtimeId: String,
      provider: String,
      model: String,
      confirmExpensiveModel: Boolean,
    ): ModelSwitchResult {
      modelSelections += runtimeId to (provider to model)
      modelSelectionConfirmations += confirmExpensiveModel
      return modelSwitchResult
    }

    override suspend fun setReasoningEffort(runtimeId: String, effort: String) {
      reasoningSelections += runtimeId to effort
    }

    override suspend fun updateSession(
      storedId: String,
      title: String?,
      archived: Boolean?,
      pinned: Boolean?,
    ) {
      sessionMutationCalls += "update:$storedId:pinned=$pinned"
      if (failSessionUpdates) error("gateway unavailable")
      awaitSessionMutationIfDelayed()
      if (archived != null) {
        archivedUpdates += storedId to archived
        listedSessions = listedSessions.map { session ->
          if (session.storedId == storedId) session.copy(archived = archived) else session
        }
      }
      if (pinned != null) {
        pinnedUpdates += storedId to pinned
        listedSessions = listedSessions.map { session ->
          if (session.storedId == storedId) session.copy(pinned = pinned) else session
        }
      }
    }

    override suspend fun deleteSession(storedId: String) {
      sessionMutationCalls += "delete:$storedId"
      if (failSessionDeletes) error("gateway unavailable")
      awaitSessionMutationIfDelayed()
      listedSessions = listedSessions.filterNot { it.storedId == storedId }
    }

    fun completeSessionMutation(index: Int) {
      pendingSessionMutations.removeAt(index).result.complete(Unit)
    }

    fun failSessionMutation(index: Int) {
      pendingSessionMutations.removeAt(index).result.completeExceptionally(IllegalStateException("gateway unavailable"))
    }

    private suspend fun awaitSessionMutationIfDelayed() {
      if (!delaySessionMutations) return
      val pending = PendingSessionMutation()
      pendingSessionMutations += pending
      try {
        pending.result.await()
      } catch (error: kotlinx.coroutines.CancellationException) {
        cancelledSessionMutations += 1
        throw error
      }
    }

    override fun close() {
      closeCalls += 1
    }

    fun summary(id: String, lastActive: Long = 1) = SessionSummary(
      storedId = id,
      title = "Session",
      preview = "",
      startedAt = 1,
      lastActive = lastActive,
      messageCount = 1,
      source = "mobile",
    )

    private fun active(runtimeId: String, storedId: String) = ActiveSession(
      runtimeId = runtimeId,
      storedId = storedId,
      messages = emptyList<ChatMessageRecord>(),
      running = false,
      status = "idle",
      model = "fixture-model",
      provider = "fixture",
      reasoningEffort = "max",
    )
  }

  private class RecordingSessionSelectionStore(
    initialStoredSessionId: String? = null,
  ) : StoredSessionSelectionStore {
    var storedSessionId: String? = initialStoredSessionId
      private set

    override fun load(): String? = storedSessionId

    override fun save(storedSessionId: String) {
      this.storedSessionId = storedSessionId
    }

    override fun clear() {
      storedSessionId = null
    }
  }
}
