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

  private class RecordingRuntime : MobileGatewayRuntime {
    override val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
    val resumed = mutableListOf<String>()
    val prompts = mutableListOf<Pair<String, String>>()
    val interrupted = mutableListOf<String>()
    val pinnedUpdates = mutableListOf<Pair<String, Boolean>>()
    val modelOptionsRequests = mutableListOf<String>()
    val modelOptionRefreshes = mutableListOf<Boolean>()
    val modelSelections = mutableListOf<Pair<String, Pair<String, String>>>()
    val modelSelectionConfirmations = mutableListOf<Boolean>()
    val reasoningSelections = mutableListOf<Pair<String, String>>()
    var listedSessions = listOf(summary("stored-1"))
    var sessionListRequests = 0
    var failPrompts = false
    var modelSwitchResult: ModelSwitchResult = ModelSwitchResult.Applied
    var delayResumes = false
    var delayModelOptions = false
    private val pendingResumes = mutableMapOf<String, CompletableDeferred<ActiveSession>>()
    private var pendingModelOptions: CompletableDeferred<ModelCatalog>? = null

    override suspend fun connect() = Unit

    override suspend fun listSessions(): List<SessionSummary> {
      sessionListRequests += 1
      return listedSessions
    }

    override suspend fun createSession(): ActiveSession = active("runtime-new", "stored-new")

    override suspend fun resumeSession(storedId: String): ActiveSession {
      resumed += storedId
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
      if (pinned != null) {
        pinnedUpdates += storedId to pinned
        listedSessions = listedSessions.map { session ->
          if (session.storedId == storedId) session.copy(pinned = pinned) else session
        }
      }
    }

    override fun close() = Unit

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
}
