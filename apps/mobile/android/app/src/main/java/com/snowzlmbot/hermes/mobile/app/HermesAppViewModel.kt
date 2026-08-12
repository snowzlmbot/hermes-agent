package com.snowzlmbot.hermes.mobile.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.snowzlmbot.hermes.mobile.BuildConfig
import com.snowzlmbot.hermes.mobile.core.GatewayAuthMode
import com.snowzlmbot.hermes.mobile.core.GatewayEndpoint
import com.snowzlmbot.hermes.mobile.core.GatewayProfile
import com.snowzlmbot.hermes.mobile.core.ModelOption
import com.snowzlmbot.hermes.mobile.core.NativeAuthException
import com.snowzlmbot.hermes.mobile.core.NativeOAuthDiscovery
import com.snowzlmbot.hermes.mobile.core.NativeOAuthProvider
import com.snowzlmbot.hermes.mobile.core.PendingNativeOAuth
import com.snowzlmbot.hermes.mobile.core.SecretValue
import com.snowzlmbot.hermes.mobile.core.StoredGatewayAuth
import com.snowzlmbot.hermes.mobile.feature.ChatController
import com.snowzlmbot.hermes.mobile.feature.EventReplayGuard
import com.snowzlmbot.hermes.mobile.feature.HermesMobileRuntime
import com.snowzlmbot.hermes.mobile.feature.VoiceInteractionController
import com.snowzlmbot.hermes.mobile.feature.VoiceInteractionState
import com.snowzlmbot.hermes.mobile.feature.AndroidStoredSessionSelectionStore
import com.snowzlmbot.hermes.mobile.feature.MobileChatUiState
import com.snowzlmbot.hermes.mobile.platform.AttachmentPayload
import com.snowzlmbot.hermes.mobile.platform.AndroidVoicePlayer
import com.snowzlmbot.hermes.mobile.platform.AndroidVoiceRecorder
import com.snowzlmbot.hermes.mobile.platform.LocalNotificationService
import com.snowzlmbot.hermes.mobile.platform.NotificationProfileScope
import com.snowzlmbot.hermes.mobile.platform.NotificationRouteMetadata
import com.snowzlmbot.hermes.mobile.platform.StoredSessionRoute
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal enum class AppScreen {
  LOADING,
  ONBOARDING,
  CHAT,
}

internal data class AppUiState(
  val screen: AppScreen = AppScreen.LOADING,
  val chat: MobileChatUiState? = null,
  val configurationError: String? = null,
  val oauthProviders: List<NativeOAuthProvider> = emptyList(),
  val isOAuthBusy: Boolean = false,
  val isOAuthPending: Boolean = false,
)

internal class HermesAppViewModel(application: Application) : AndroidViewModel(application) {
  private val graph = (application as HermesApplication).graph
  private val notificationService = LocalNotificationService(application)
  private val notificationRoutes = StoredSessionRouteQueue()
  private val eventReplayGuard = EventReplayGuard()
  private var replayGuardScope: String? = null
  private var activeNotificationProfileScope: String? = null
  private val mutableState = MutableStateFlow(AppUiState())
  private val mutableVoiceState = MutableStateFlow(VoiceInteractionState())
  private val voiceTranscriptChannel = Channel<String>(Channel.BUFFERED)
  private var controller: ChatController? = null
  private var voiceController: VoiceInteractionController? = null
  private var voiceStateCollection: Job? = null
  private var voiceTranscriptCollection: Job? = null
  private var chatCollection: kotlinx.coroutines.Job? = null
  private var connectJob: Job? = null
  private var forgetJob: Job? = null
  private var forgetInFlight = false
  private var forgetGeneration = 0L
  private var connectionGeneration = 0L
  private var oauthDiscovery: NativeOAuthDiscovery? = null
  private var pendingOAuth: PendingNativeOAuth? = null
  private var oauthCallbackInFlight = false
  private var oauthGeneration = 0L
  private val oauthBrowserChannel = Channel<String>(Channel.BUFFERED)
  private var foregroundRecoveryArmed = false
  private val processLifecycleObserver = object : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) {
      foregroundRecoveryArmed = true
      connectJob?.cancel()
    }

    override fun onStart(owner: LifecycleOwner) {
      if (!foregroundRecoveryArmed || forgetInFlight) return
      foregroundRecoveryArmed = false
      startConnectSaved()
    }
  }

  val state: StateFlow<AppUiState> = mutableState.asStateFlow()
  val voiceState: StateFlow<VoiceInteractionState> = mutableVoiceState.asStateFlow()
  val voiceTranscripts = voiceTranscriptChannel.receiveAsFlow()
  val oauthBrowserEvents = oauthBrowserChannel.receiveAsFlow()

  init {
    ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
    startConnectSaved()
  }

  fun saveConnection(address: String, token: String, allowInsecure: Boolean) {
    resetOAuthFlow()
    val generation = nextConnectionGeneration()
    connectJob = viewModelScope.launch {
      val cleanToken = token.trim()
      if (cleanToken.isEmpty()) {
        if (isCurrentConnection(generation)) {
          mutableState.value = mutableState.value.copy(configurationError = "A gateway token is required")
        }
        return@launch
      }
      if (allowInsecure && !BuildConfig.ALLOW_INSECURE_TRANSPORT) {
        if (isCurrentConnection(generation)) {
          mutableState.value = mutableState.value.copy(
            configurationError = "Cleartext gateways are disabled in this build",
          )
        }
        return@launch
      }
      try {
        graph.saveConnection(
          GatewayProfile(
            address = address.trim(),
            authMode = GatewayAuthMode.TOKEN,
            allowInsecure = BuildConfig.ALLOW_INSECURE_TRANSPORT && allowInsecure,
            id = UUID.randomUUID().toString(),
          ),
          SecretValue(cleanToken),
        )
        connectSaved(generation)
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        if (!isCurrentConnection(generation)) return@launch
        mutableState.value = mutableState.value.copy(
          screen = AppScreen.ONBOARDING,
          configurationError = error.message ?: "Could not save the connection",
        )
      }
    }
  }

  fun discoverOAuth(address: String) {
    val generation = ++oauthGeneration
    oauthDiscovery = null
    pendingOAuth = null
    oauthCallbackInFlight = false
    mutableState.value = mutableState.value.copy(
      configurationError = null,
      oauthProviders = emptyList(),
      isOAuthBusy = true,
      isOAuthPending = false,
    )
    viewModelScope.launch {
      try {
        val discovery = graph.discoverNativeOAuth(address.trim())
        if (generation != oauthGeneration) return@launch
        oauthDiscovery = discovery
        mutableState.value = mutableState.value.copy(
          oauthProviders = discovery.providers,
          isOAuthBusy = false,
        )
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        if (generation != oauthGeneration) return@launch
        mutableState.value = mutableState.value.copy(
          configurationError = error.message ?: "Could not load OAuth sign-in options",
          oauthProviders = emptyList(),
          isOAuthBusy = false,
        )
      }
    }
  }

  fun startOAuth(address: String, provider: String) {
    if (pendingOAuth != null) {
      mutableState.value = mutableState.value.copy(
        configurationError = "Finish or cancel the current OAuth sign-in",
      )
      return
    }
    val discovery = oauthDiscovery ?: run {
      mutableState.value = mutableState.value.copy(
        configurationError = "Load OAuth sign-in options first",
      )
      return
    }
    try {
      val currentEndpoint = GatewayEndpoint.parse(address.trim())
      if (currentEndpoint.httpBaseUrl != discovery.endpoint.httpBaseUrl) {
        mutableState.value = mutableState.value.copy(
          configurationError = "Reload OAuth sign-in options for this gateway address",
          oauthProviders = emptyList(),
        )
        oauthDiscovery = null
        return
      }
      val pending = graph.beginNativeOAuth(discovery, provider)
      pendingOAuth = pending
      mutableState.value = mutableState.value.copy(
        configurationError = null,
        isOAuthPending = true,
      )
      if (!oauthBrowserChannel.trySend(pending.authorizationUrl.toString()).isSuccess) {
        pendingOAuth = null
        mutableState.value = mutableState.value.copy(
          configurationError = "Could not open OAuth sign-in",
          isOAuthPending = false,
        )
      }
    } catch (error: Throwable) {
      mutableState.value = mutableState.value.copy(
        configurationError = error.message ?: "Could not start OAuth sign-in",
      )
    }
  }

  fun handleOAuthCallback(uri: Uri) {
    if (oauthCallbackInFlight) return
    val pending = pendingOAuth ?: run {
      mutableState.value = mutableState.value.copy(
        configurationError = "No OAuth sign-in is in progress",
      )
      return
    }
    oauthCallbackInFlight = true
    val generation = oauthGeneration
    mutableState.value = mutableState.value.copy(configurationError = null, isOAuthBusy = true)
    viewModelScope.launch {
      try {
        val tokens = graph.completeNativeOAuth(pending, java.net.URI(uri.toString()))
        if (generation != oauthGeneration || pendingOAuth !== pending) return@launch
        pendingOAuth = null
        oauthCallbackInFlight = false
        mutableState.value = mutableState.value.copy(
          isOAuthBusy = false,
          isOAuthPending = false,
        )
        graph.saveConnection(
          GatewayProfile(
            address = pending.endpoint.httpBaseUrl.toString(),
            authMode = GatewayAuthMode.OAUTH,
            allowInsecure = false,
            id = UUID.randomUUID().toString(),
          ),
          StoredGatewayAuth.OAuth(tokens),
        )
        startConnectSaved()
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        if (generation != oauthGeneration) return@launch
        oauthCallbackInFlight = false
        if (error !is NativeAuthException) pendingOAuth = null
        mutableState.value = mutableState.value.copy(
          screen = AppScreen.ONBOARDING,
          configurationError = error.message ?: "Could not complete OAuth sign-in",
          isOAuthBusy = false,
          isOAuthPending = pendingOAuth != null,
        )
      }
    }
  }

  fun reportOAuthBrowserFailure() {
    oauthGeneration += 1
    pendingOAuth = null
    oauthCallbackInFlight = false
    mutableState.value = mutableState.value.copy(
      configurationError = "No system browser is available for OAuth sign-in",
      isOAuthBusy = false,
      isOAuthPending = false,
    )
  }

  fun cancelOAuth() {
    oauthGeneration += 1
    pendingOAuth = null
    oauthCallbackInFlight = false
    mutableState.value = mutableState.value.copy(
      configurationError = null,
      isOAuthBusy = false,
      isOAuthPending = false,
    )
  }

  fun reconnect() {
    startConnectSaved()
  }

  fun handleNotificationRoute(route: StoredSessionRoute) {
    if (!NotificationRouteMetadata.isValid(route)) return
    notificationRoutes.enqueue(route)
    consumeNotificationRouteIfReady()
  }

  fun forgetConnection() {
    notificationRoutes.clear()
    eventReplayGuard.clear()
    replayGuardScope = null
    activeNotificationProfileScope = null
    resetOAuthFlow()
    foregroundRecoveryArmed = false
    val generation = nextConnectionGeneration()
    forgetGeneration += 1
    val forgetOperation = forgetGeneration
    forgetInFlight = true
    forgetJob?.cancel()
    forgetJob = viewModelScope.launch {
      try {
        controller?.close()
        controller = null
        chatCollection?.cancel()
        chatCollection = null
        AndroidStoredSessionSelectionStore.clearAll(getApplication<Application>())
        graph.clearConnection()
        if (isCurrentConnection(generation)) {
          mutableState.value = AppUiState(screen = AppScreen.ONBOARDING)
        }
      } finally {
        if (forgetGeneration == forgetOperation) {
          forgetInFlight = false
          forgetJob = null
        }
      }
    }
  }

  fun newSession() {
    notificationRoutes.clear()
    viewModelScope.launch { controller?.newSession() }
  }

  fun openSession(storedId: String) {
    notificationRoutes.clear()
    viewModelScope.launch { controller?.openSession(storedId) }
  }

  fun send(text: String) {
    viewModelScope.launch { controller?.send(text) }
  }

  fun startVoiceRecording() {
    voiceController?.startRecording()
  }

  fun stopVoiceRecordingAndTranscribe() {
    voiceController?.stopAndTranscribe()
  }

  fun speak(text: String) {
    voiceController?.speak(text)
  }

  fun stopSpeaking() {
    voiceController?.stopSpeaking()
  }

  fun clearVoiceError() {
    voiceController?.clearError()
  }

  fun reportVoiceError(message: String) {
    voiceController?.reportError(message)
  }

  fun stop() {
    viewModelScope.launch { controller?.stop() }
  }

  fun refreshModelOptions(forceRefresh: Boolean = false) {
    viewModelScope.launch { controller?.refreshModelOptions(forceRefresh = forceRefresh) }
  }

  fun selectModel(option: ModelOption) {
    viewModelScope.launch { controller?.selectModel(option) }
  }

  fun confirmModelSelection() {
    viewModelScope.launch { controller?.confirmModelSelection() }
  }

  fun cancelModelSelection() {
    controller?.cancelModelSelection()
  }

  fun setReasoningEffort(effort: String) {
    viewModelScope.launch { controller?.setReasoningEffort(effort) }
  }

  fun clearError() {
    controller?.clearError()
  }

  fun updateTitle(storedId: String, title: String) {
    viewModelScope.launch { controller?.updateSession(storedId, title = title) }
  }

  fun archiveSession(storedId: String) {
    viewModelScope.launch { controller?.updateSession(storedId, archived = true) }
  }

  fun restoreSession(storedId: String) {
    viewModelScope.launch { controller?.restoreSession(storedId) }
  }

  fun setPinned(storedId: String, pinned: Boolean) {
    viewModelScope.launch { controller?.setPinned(storedId, pinned) }
  }

  fun deleteSession(storedId: String) {
    viewModelScope.launch { controller?.deleteSession(storedId) }
  }

  fun respondApproval(choice: String) {
    viewModelScope.launch { controller?.respondApproval(choice) }
  }

  fun respondClarify(requestId: String, answer: String) {
    viewModelScope.launch { controller?.respondClarify(requestId, answer) }
  }

  fun respondSecret(requestId: String, value: String) {
    viewModelScope.launch { controller?.respondSecret(requestId, value) }
  }

  fun respondSudo(requestId: String, password: String) {
    viewModelScope.launch { controller?.respondSudo(requestId, password) }
  }

  fun attach(uri: Uri, displayName: String, mimeType: String) {
    viewModelScope.launch {
      try {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
          input.copyBounded(MAX_ATTACHMENT_BYTES)
        } ?: error("Could not read the selected attachment")
        val payload = when {
          mimeType.startsWith("image/") -> AttachmentPayload.image(displayName, mimeType, bytes)
          mimeType == "application/pdf" -> AttachmentPayload.pdf(displayName, bytes)
          else -> AttachmentPayload.file(displayName, mimeType, bytes)
        }
        val reference = controller?.attach(payload.method, payload.params)
        if (!reference.isNullOrBlank()) send(reference)
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        mutableState.value = mutableState.value.copy(
          configurationError = error.message ?: "Could not attach the selected file",
        )
      }
    }
  }

  override fun onCleared() {
    ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
    nextConnectionGeneration()
    forgetJob?.cancel()
    resetOAuthFlow()
    oauthBrowserChannel.close()
    voiceTranscriptChannel.close()
    controller?.close()
  }

  private fun resetOAuthFlow() {
    oauthGeneration += 1
    oauthDiscovery = null
    pendingOAuth = null
    oauthCallbackInFlight = false
    mutableState.value = mutableState.value.copy(
      oauthProviders = emptyList(),
      isOAuthBusy = false,
      isOAuthPending = false,
    )
  }

  private fun consumeNotificationRouteIfReady() {
    val profileScope = activeNotificationProfileScope ?: return
    val target = controller ?: return
    val route = notificationRoutes.consume(profileScope) ?: return
    viewModelScope.launch { target.openSession(route.storedSessionId) }
  }

  private fun startConnectSaved() {
    activeNotificationProfileScope = null
    controller?.close()
    controller = null
    chatCollection?.cancel()
    chatCollection = null
    val generation = nextConnectionGeneration()
    connectJob = viewModelScope.launch {
      try {
        connectSaved(generation)
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        if (isCurrentConnection(generation)) {
          mutableState.value = AppUiState(
            screen = AppScreen.ONBOARDING,
            configurationError = error.message ?: "Could not connect to the gateway",
          )
        }
      }
    }
  }

  private suspend fun connectSaved(generation: Long) {
    val connection = graph.restoreConnection()
    if (!isCurrentConnection(generation)) return
    if (connection == null) {
      mutableState.value = AppUiState(screen = AppScreen.ONBOARDING)
      return
    }
    val selectionScope = connection.profile.sessionSelectionScope
    if (replayGuardScope != selectionScope) {
      eventReplayGuard.clear()
      eventReplayGuard.activate(selectionScope)
      replayGuardScope = selectionScope
    }
    val notificationProfileScope = NotificationProfileScope.fromSelectionScope(selectionScope)
    val runtime = graph.runtime(connection)
    val next = ChatController(
      runtime = runtime,
      scope = viewModelScope,
      selectionStore = AndroidStoredSessionSelectionStore(getApplication<Application>(), selectionScope),
      eventReplayGuard = eventReplayGuard,
      profileScope = selectionScope,
      onNotification = { signal ->
        if (isCurrentConnection(generation) && activeNotificationProfileScope == notificationProfileScope) {
          notificationService.post(
            signal.kind,
            StoredSessionRoute(signal.storedSessionId, notificationProfileScope),
          )
        }
      },
    )
    var installed = false
    try {
      if (!isCurrentConnection(generation)) return
      val restored = next.connectAndRestore()
      if (!isCurrentConnection(generation)) return
      check(restored) { next.state.value.error?.message ?: "Could not restore the saved session" }

      val previous = controller
      installed = true
      controller = next
      installVoice(runtime)
      activeNotificationProfileScope = notificationProfileScope
      previous?.close()
      chatCollection?.cancel()
      chatCollection = viewModelScope.launch {
        next.state.collect { chat ->
          if (isCurrentConnection(generation) && controller === next) {
            mutableState.value = AppUiState(screen = AppScreen.CHAT, chat = chat)
          }
        }
      }
      mutableState.value = AppUiState(screen = AppScreen.CHAT, chat = next.state.value)
      consumeNotificationRouteIfReady()
    } finally {
      if (!installed) next.close()
    }
  }

  private fun installVoice(runtime: HermesMobileRuntime) {
    closeVoice()
    val next = VoiceInteractionController(
      recorder = AndroidVoiceRecorder(getApplication<Application>()),
      gateway = runtime,
      player = AndroidVoicePlayer(getApplication<Application>()),
      scope = viewModelScope,
    )
    voiceController = next
    voiceStateCollection = viewModelScope.launch {
      next.state.collect { mutableVoiceState.value = it }
    }
    voiceTranscriptCollection = viewModelScope.launch {
      next.transcripts.collect { voiceTranscriptChannel.send(it) }
    }
  }

  private fun closeVoice() {
    voiceStateCollection?.cancel()
    voiceTranscriptCollection?.cancel()
    voiceStateCollection = null
    voiceTranscriptCollection = null
    voiceController?.close()
    voiceController = null
    mutableVoiceState.value = VoiceInteractionState()
  }

  private fun nextConnectionGeneration(): Long {
    closeVoice()
    connectionGeneration += 1
    connectJob?.cancel()
    connectJob = null
    return connectionGeneration
  }

  private fun isCurrentConnection(generation: Long): Boolean = generation == connectionGeneration

  private companion object {
    const val MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024
  }
}

private fun java.io.InputStream.copyBounded(limit: Int): ByteArray {
  val output = ByteArrayOutputStream()
  val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
  while (true) {
    val count = read(buffer)
    if (count < 0) break
    if (output.size() + count > limit) error("Attachment is too large")
    output.write(buffer, 0, count)
  }
  return output.toByteArray()
}
