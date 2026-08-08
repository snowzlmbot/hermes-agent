package com.snowzlmbot.hermes.mobile.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.snowzlmbot.hermes.mobile.core.GatewayAuthMode
import com.snowzlmbot.hermes.mobile.core.GatewayProfile
import com.snowzlmbot.hermes.mobile.core.ModelOption
import com.snowzlmbot.hermes.mobile.core.SecretValue
import com.snowzlmbot.hermes.mobile.feature.ChatController
import com.snowzlmbot.hermes.mobile.feature.MobileChatUiState
import com.snowzlmbot.hermes.mobile.platform.AttachmentPayload
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
)

internal class HermesAppViewModel(application: Application) : AndroidViewModel(application) {
  private val graph = (application as HermesApplication).graph
  private val mutableState = MutableStateFlow(AppUiState())
  private var controller: ChatController? = null
  private var chatCollection: kotlinx.coroutines.Job? = null

  val state: StateFlow<AppUiState> = mutableState.asStateFlow()

  init {
    viewModelScope.launch { restore() }
  }

  fun saveConnection(address: String, token: String, allowInsecure: Boolean) {
    viewModelScope.launch {
      val cleanToken = token.trim()
      if (cleanToken.isEmpty()) {
        mutableState.value = mutableState.value.copy(configurationError = "A gateway token is required")
        return@launch
      }
      try {
        graph.saveConnection(
          GatewayProfile(address.trim(), GatewayAuthMode.TOKEN, allowInsecure),
          SecretValue(cleanToken),
        )
        connectSaved()
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        mutableState.value = mutableState.value.copy(
          screen = AppScreen.ONBOARDING,
          configurationError = error.message ?: "Could not save the connection",
        )
      }
    }
  }

  fun reconnect() {
    viewModelScope.launch { connectSaved() }
  }

  fun forgetConnection() {
    viewModelScope.launch {
      controller?.close()
      controller = null
      chatCollection?.cancel()
      graph.clearConnection()
      mutableState.value = AppUiState(screen = AppScreen.ONBOARDING)
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

  fun refreshModelOptions() {
    viewModelScope.launch { controller?.refreshModelOptions() }
  }

  fun selectModel(option: ModelOption) {
    viewModelScope.launch { controller?.selectModel(option) }
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
    controller?.close()
  }

  private suspend fun restore() {
    if (graph.restoreConnection() == null) {
      mutableState.value = AppUiState(screen = AppScreen.ONBOARDING)
    } else {
      connectSaved()
    }
  }

  private suspend fun connectSaved() {
    val connection = graph.restoreConnection()
    if (connection == null) {
      mutableState.value = AppUiState(screen = AppScreen.ONBOARDING)
      return
    }
    controller?.close()
    val next = ChatController(graph.runtime(connection), viewModelScope)
    controller = next
    chatCollection?.cancel()
    chatCollection = viewModelScope.launch {
      next.state.collect { chat ->
        mutableState.value = AppUiState(screen = AppScreen.CHAT, chat = chat)
      }
    }
    next.connect()
    if (next.state.value.chat.runtimeSessionId == null) next.newSession()
  }

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
