package com.snowzlmbot.hermes.mobile.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.snowzlmbot.hermes.mobile.feature.MobileChatUiState
import com.snowzlmbot.hermes.mobile.platform.AttachmentPayload
import java.io.ByteArrayOutputStream
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
  private val mutableState = MutableStateFlow(AppUiState())
  private var controller: ChatController? = null
  private var chatCollection: kotlinx.coroutines.Job? = null
  private var connectJob: Job? = null
  private var connectionGeneration = 0L
  private var oauthDiscovery: NativeOAuthDiscovery? = null
  private var pendingOAuth: PendingNativeOAuth? = null
  private var oauthCallbackInFlight = false
  private var oauthGeneration = 0L
  private val oauthBrowserChannel = Channel<String>(Channel.BUFFERED)

  val state: StateFlow<AppUiState> = mutableState.asStateFlow()
  val oauthBrowserEvents = oauthBrowserChannel.receiveAsFlow()

  init {
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
      try {
        graph.saveConnection(
          GatewayProfile(address.trim(), GatewayAuthMode.TOKEN, allowInsecure),
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

  fun forgetConnection() {
    resetOAuthFlow()
    val generation = nextConnectionGeneration()
    connectJob = viewModelScope.launch {
      controller?.close()
      controller = null
      chatCollection?.cancel()
      chatCollection = null
      graph.clearConnection()
      if (isCurrentConnection(generation)) {
        mutableState.value = AppUiState(screen = AppScreen.ONBOARDING)
      }
    }
  }

  fun newSession() {
    viewModelScope.launch { controller?.newSession() }
  }

  fun openSession(storedId: String) {
    viewModelScope.launch { controller?.openSession(storedId) }
  }

  fun send(text: String) {
    viewModelScope.launch { controller?.send(text) }
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
    nextConnectionGeneration()
    resetOAuthFlow()
    oauthBrowserChannel.close()
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

  private fun startConnectSaved() {
    val generation = nextConnectionGeneration()
    connectJob = viewModelScope.launch { connectSaved(generation) }
  }

  private suspend fun connectSaved(generation: Long) {
    val connection = graph.restoreConnection()
    if (!isCurrentConnection(generation)) return
    if (connection == null) {
      mutableState.value = AppUiState(screen = AppScreen.ONBOARDING)
      return
    }
    val next = ChatController(graph.runtime(connection), viewModelScope)
    if (!isCurrentConnection(generation)) {
      next.close()
      return
    }
    next.connect()
    if (!isCurrentConnection(generation)) {
      next.close()
      return
    }
    if (next.state.value.chat.runtimeSessionId == null) next.newSession()
    if (!isCurrentConnection(generation)) {
      next.close()
      return
    }

    val previous = controller
    controller = next
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
  }

  private fun nextConnectionGeneration(): Long {
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
