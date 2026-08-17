package com.snowzlmbot.hermes.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin

import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snowzlmbot.hermes.mobile.BuildConfig
import com.snowzlmbot.hermes.mobile.R
import com.snowzlmbot.hermes.mobile.app.AppScreen
import com.snowzlmbot.hermes.mobile.app.AppUiState
import com.snowzlmbot.hermes.mobile.app.HermesAppViewModel
import com.snowzlmbot.hermes.mobile.core.MessageRole
import com.snowzlmbot.hermes.mobile.core.ModelCatalog
import com.snowzlmbot.hermes.mobile.core.SessionSummary
import com.snowzlmbot.hermes.mobile.feature.ApprovalPrompt
import com.snowzlmbot.hermes.mobile.feature.ChatMessage
import com.snowzlmbot.hermes.mobile.feature.ChatState
import com.snowzlmbot.hermes.mobile.feature.ClarifyPrompt
import com.snowzlmbot.hermes.mobile.feature.SecretPrompt
import com.snowzlmbot.hermes.mobile.feature.SessionLibrary
import com.snowzlmbot.hermes.mobile.feature.SessionLibraryView
import com.snowzlmbot.hermes.mobile.feature.SudoPrompt
import com.snowzlmbot.hermes.mobile.feature.ToolState
import kotlinx.coroutines.launch

@Composable
internal fun HermesRoot(viewModel: HermesAppViewModel) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  when (state.screen) {
    AppScreen.LOADING -> LoadingScreen()
    AppScreen.ONBOARDING -> OnboardingScreen(state, viewModel)
    AppScreen.CHAT -> ChatScreen(state, viewModel)
  }
}

@Composable
private fun LoadingScreen() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(stringResource(R.string.loading_connecting), style = MaterialTheme.typography.titleMedium)
  }
}

@Composable
private fun OnboardingScreen(state: AppUiState, viewModel: HermesAppViewModel) {
  var address by remember { mutableStateOf("https://") }
  var token by remember { mutableStateOf("") }
  var allowInsecure by remember { mutableStateOf(false) }
  val scroll = rememberScrollState()

  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(scroll)
        .padding(horizontal = 24.dp, vertical = 48.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Hermes", style = MaterialTheme.typography.displaySmall)
      Text(stringResource(R.string.onboarding_connect_title), style = MaterialTheme.typography.headlineSmall)
      Text(
        stringResource(R.string.connection_guidance),
        style = MaterialTheme.typography.bodyMedium,
      )
      OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text(stringResource(R.string.gateway_address_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("gateway-address"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
      )
      OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(stringResource(R.string.session_token_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("gateway-token"),
      )
      if (BuildConfig.ALLOW_INSECURE_TRANSPORT) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          androidx.compose.material3.Switch(checked = allowInsecure, onCheckedChange = { allowInsecure = it })
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.allow_cleartext_gateway))
        }
      }
      state.configurationError?.let {
        Text(
          stringResource(it.messageRes),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("configuration-error"),
        )
      }
      Button(
        onClick = { viewModel.saveConnection(address, token, allowInsecure) },
        enabled = address.isNotBlank() && token.isNotBlank(),
        modifier = Modifier.fillMaxWidth().testTag("connect-button"),
      ) {
        Icon(Icons.Default.Wifi, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.action_connect))
      }
      NativeOAuthSection(
        state = state,
        address = address,
        onDiscover = viewModel::discoverOAuth,
        onStart = viewModel::startOAuth,
        onCancel = viewModel::cancelOAuth,
      )
    }
  }
}

@Composable
internal fun NativeOAuthSection(
  state: AppUiState,
  address: String,
  onDiscover: (String) -> Unit,
  onStart: (String, String) -> Unit,
  onCancel: () -> Unit,
) {
  HorizontalDivider()
  Text(stringResource(R.string.oauth_sign_in_title), style = MaterialTheme.typography.titleMedium)
  Text(
    stringResource(R.string.oauth_sign_in_guidance),
    style = MaterialTheme.typography.bodyMedium,
  )
  OutlinedButton(
    onClick = { onDiscover(address) },
    enabled = address.isNotBlank() && !state.isOAuthBusy && !state.isOAuthPending,
    modifier = Modifier.fillMaxWidth().testTag("oauth-discover"),
  ) {
    Text(
      if (state.isOAuthBusy) {
        stringResource(R.string.oauth_checking)
      } else {
        stringResource(R.string.oauth_check_options)
      },
    )
  }
  state.oauthProviders.forEach { provider ->
    Button(
      onClick = { onStart(address, provider.name) },
      enabled = !state.isOAuthBusy && !state.isOAuthPending,
      modifier = Modifier.fillMaxWidth().testTag("oauth-provider-${provider.name}"),
    ) {
      Icon(Icons.Default.Person, contentDescription = null)
      Spacer(Modifier.width(8.dp))
      Text(stringResource(R.string.oauth_sign_in_with, provider.displayName))
    }
  }
  if (state.isOAuthPending) {
    Text(
      stringResource(R.string.oauth_pending),
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier
        .testTag("oauth-pending")
        .semantics { liveRegion = LiveRegionMode.Polite },
    )
    TextButton(
      onClick = onCancel,
      modifier = Modifier.testTag("oauth-cancel"),
    ) {
      Text(stringResource(R.string.oauth_cancel))
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(state: AppUiState, viewModel: HermesAppViewModel) {
  val mobile = state.chat
  val chat = mobile?.chat
  val voice by viewModel.voiceState.collectAsState()
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  var showAttachmentMenu by remember { mutableStateOf(false) }
  var showSettings by remember { mutableStateOf(false) }
  var renameId by remember { mutableStateOf<String?>(null) }
  val context = LocalContext.current
  val configurationErrorMessage = state.configurationError?.let { stringResource(it.messageRes) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    if (uri != null) {
      val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
      val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "attachment" } ?: "attachment"
      viewModel.attach(uri, name, type)
    }
  }

  LaunchedEffect(configurationErrorMessage, mobile?.error?.message, chat?.error, voice.error) {
    val primaryError = configurationErrorMessage ?: mobile?.error?.message ?: chat?.error
    val message = primaryError ?: voice.error
    if (!message.isNullOrBlank()) {
      snackbar.showSnackbar(message)
      if (primaryError == null && voice.error != null) viewModel.clearVoiceError()
    }
  }

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ModalDrawerSheet {
        SessionDrawer(
          sessions = mobile?.sessions.orEmpty(),
          onNew = { viewModel.newSession(); scope.launch { drawerState.close() } },
          onOpen = { id -> viewModel.openSession(id); scope.launch { drawerState.close() } },
          onRename = { renameId = it },
          onSetPinned = viewModel::setPinned,
          onArchive = { viewModel.archiveSession(it) },
          onRestore = viewModel::restoreSession,
          onDelete = { viewModel.deleteSession(it) },
        )
      }
    },
  ) {
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Column {
              val selectedSession = mobile?.sessions?.firstOrNull { it.storedId == chat?.storedSessionId }
              val title = if (selectedSession == null) {
                stringResource(R.string.chat_new_conversation)
              } else {
                localizedSessionTitle(selectedSession)
              }
              Text(title)
              val model = chat?.model?.takeIf(String::isNotBlank)
              if (model != null) Text(model, style = MaterialTheme.typography.labelSmall)
            }
          },
          navigationIcon = {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
              Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.sessions_title))
            }
          },
          actions = {
            IconButton(onClick = viewModel::reconnect) {
              Icon(
                if (chat?.streaming == true) Icons.Default.Wifi else Icons.Default.WifiOff,
                contentDescription = stringResource(R.string.reconnect_content_description),
              )
            }
            IconButton(onClick = { showSettings = true }) {
              Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.connection_settings_title))
            }
          },
        )
      },
      snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
      if (chat == null) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
          Text(stringResource(R.string.conversation_preparing))
        }
      } else {
        ChatContent(
          chat = chat,
          modelCatalog = mobile.modelCatalog,
          isLoadingModelOptions = mobile.isLoadingModelOptions,
          viewModel = viewModel,
          modifier = Modifier.fillMaxSize().padding(padding),
          onAttach = { showAttachmentMenu = true },
          showAttachmentMenu = showAttachmentMenu,
          onDismissAttachmentMenu = { showAttachmentMenu = false },
          onPickAttachment = {
            showAttachmentMenu = false
            picker.launch(arrayOf("image/*", "application/pdf", "text/*", "application/json", "application/octet-stream"))
          },
        )
      }
    }
  }

  mobile?.pendingModelConfirmation?.let { pending ->
    AlertDialog(
      onDismissRequest = viewModel::cancelModelSelection,
      title = { Text(stringResource(R.string.model_confirmation_title)) },
      text = { Text(pending.message) },
      confirmButton = {
        Button(onClick = viewModel::confirmModelSelection) {
          Text(stringResource(R.string.action_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = viewModel::cancelModelSelection) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
  renameId?.let { storedId ->
    RenameDialog(
      onDismiss = { renameId = null },
      onSave = { title -> viewModel.updateTitle(storedId, title); renameId = null },
    )
  }
  if (showSettings) {
    AlertDialog(
      onDismissRequest = { showSettings = false },
      title = { Text(stringResource(R.string.connection_settings_title)) },
      text = { Text(stringResource(R.string.settings_forget_description)) },
      confirmButton = {
        Button(onClick = { showSettings = false; viewModel.forgetConnection() }) {
          Text(stringResource(R.string.settings_forget_action))
        }
      },
      dismissButton = { TextButton(onClick = { showSettings = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }
}

@Composable
internal fun SessionDrawer(
  sessions: List<SessionSummary>,
  onNew: () -> Unit,
  onOpen: (String) -> Unit,
  onRename: (String) -> Unit,
  onSetPinned: (String, Boolean) -> Unit,
  onArchive: (String) -> Unit,
  onRestore: (String) -> Unit,
  onDelete: (String) -> Unit,
) {
  var menuId by remember { mutableStateOf<String?>(null) }
  var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
  var query by remember { mutableStateOf("") }
  var view by remember { mutableStateOf(SessionLibraryView.ACTIVE) }
  val visible = SessionLibrary.filter(sessions, view, query)
  Column(Modifier.fillMaxSize().padding(vertical = 20.dp)) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(stringResource(R.string.sessions_title), style = MaterialTheme.typography.titleLarge)
      IconButton(onClick = onNew) {
        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.session_new_content_description))
      }
    }
    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("session-search"),
      singleLine = true,
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.session_search)) },
      trailingIcon = {
        if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text(stringResource(R.string.action_clear)) }
      },
      placeholder = { Text(stringResource(R.string.session_search)) },
    )
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SessionViewButton(stringResource(R.string.session_view_active), view == SessionLibraryView.ACTIVE) { view = SessionLibraryView.ACTIVE }
      SessionViewButton(stringResource(R.string.session_view_archived), view == SessionLibraryView.ARCHIVED) { view = SessionLibraryView.ARCHIVED }
    }
    HorizontalDivider()
    if (visible.isEmpty()) {
      Text(
        stringResource(if (query.trim().isEmpty()) R.string.session_empty_view else R.string.session_empty_search),
        Modifier.padding(20.dp),
        style = MaterialTheme.typography.bodyMedium,
      )
    }
    LazyColumn(Modifier.weight(1f)) {
      items(visible, key = { it.storedId }) { session ->
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(
          onClick = { if (!session.archived) onOpen(session.storedId) },
          enabled = !session.archived,
          modifier = Modifier.weight(1f),
        ) {
          Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                localizedSessionTitle(session),
                maxLines = 1,
                modifier = Modifier.weight(1f),
              )
              if (session.pinned) {
                Icon(Icons.Default.PushPin, contentDescription = stringResource(R.string.session_pinned_content_description))
              }
            }
            Text(session.preview, maxLines = 1, style = MaterialTheme.typography.labelSmall)
          }
        }
        Box {
          IconButton(onClick = { menuId = session.storedId }) {
            Icon(
              Icons.Default.MoreVert,
              contentDescription = stringResource(
                R.string.session_actions_content_description,
                localizedSessionTitle(session),
              ),
            )
          }
          DropdownMenu(expanded = menuId == session.storedId, onDismissRequest = { menuId = null }) {
            if (session.archived) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.session_action_restore)) },
                onClick = { menuId = null; onRestore(session.storedId) },
              )
            } else {
              DropdownMenuItem(
                text = { Text(stringResource(if (session.pinned) R.string.session_action_unpin else R.string.session_action_pin)) },
                leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                onClick = { menuId = null; onSetPinned(session.storedId, !session.pinned) },
              )
              DropdownMenuItem(text = { Text(stringResource(R.string.session_action_rename)) }, onClick = { menuId = null; onRename(session.storedId) })
              DropdownMenuItem(text = { Text(stringResource(R.string.session_action_archive)) }, onClick = { menuId = null; onArchive(session.storedId) })
            }
            DropdownMenuItem(
              text = { Text(stringResource(R.string.session_action_delete)) },
              onClick = { menuId = null; pendingDelete = session },
            )
          }
        }
      }
    }
  }
  pendingDelete?.let { session ->
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text(stringResource(R.string.session_delete_title)) },
      text = {
        Text(
          stringResource(
            R.string.session_delete_message,
            localizedSessionTitle(session),
          ),
        )
      },
      confirmButton = {
        Button(
          onClick = { pendingDelete = null; onDelete(session.storedId) },
          modifier = Modifier.testTag("confirm-delete-session"),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
          Text(stringResource(R.string.session_action_delete))
        }
      },
      dismissButton = {
        TextButton(
          onClick = { pendingDelete = null },
          modifier = Modifier.testTag("cancel-delete-session"),
        ) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}
}

@Composable
private fun localizedSessionTitle(session: SessionSummary): String {
  val fallback = stringResource(R.string.chat_new_conversation)
  return session.title.ifBlank { session.preview.ifBlank { fallback } }
}

@Composable
private fun RowScope.SessionViewButton(label: String, selected: Boolean, onClick: () -> Unit) {
  if (selected) Button(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label) }
  else OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label) }
}

@Composable
private fun ChatContent(
  chat: ChatState,
  modelCatalog: ModelCatalog,
  isLoadingModelOptions: Boolean,
  viewModel: HermesAppViewModel,
  modifier: Modifier,
  onAttach: () -> Unit,
  showAttachmentMenu: Boolean,
  onDismissAttachmentMenu: () -> Unit,
  onPickAttachment: () -> Unit,
) {
  var text by remember { mutableStateOf("") }
  val voice by viewModel.voiceState.collectAsState()
  val context = LocalContext.current
  val microphonePermissionRequired = stringResource(R.string.error_microphone_permission_required)
  val microphonePermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) viewModel.startVoiceRecording()
    else viewModel.reportVoiceError(microphonePermissionRequired)
  }
  val listState = rememberLazyListState()
  LaunchedEffect(chat.messages.size, chat.messages.lastOrNull()?.text) {
    if (chat.messages.isNotEmpty()) listState.animateScrollToItem(chat.messages.lastIndex)
  }
  LaunchedEffect(chat.runtimeSessionId) {
    if (chat.runtimeSessionId != null && modelCatalog.providers.isEmpty()) viewModel.refreshModelOptions()
  }
  LaunchedEffect(viewModel) {
    viewModel.voiceTranscripts.collect { transcript ->
      text = if (text.isBlank()) transcript else "$text $transcript"
    }
  }
  Column(modifier.imePadding()) {
    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      itemsIndexed(chat.messages, key = { _, item -> item.id }) { _, message ->
        MessageBubble(
          message = message,
          isSpeaking = voice.isSpeaking,
          onSpeak = viewModel::speak,
          onStopSpeaking = viewModel::stopSpeaking,
        )
      }
      items(chat.tools, key = { it.id }) { tool -> ToolCard(tool) }
      item { PromptCards(chat, viewModel) }
    }
    ModelControls(
      catalog = modelCatalog,
      currentModel = chat.model,
      currentProvider = chat.provider,
      reasoningEffort = chat.reasoningEffort,
      isLoading = isLoadingModelOptions,
      onRefresh = { viewModel.refreshModelOptions(forceRefresh = true) },
      onSelectModel = viewModel::selectModel,
      onSetReasoningEffort = viewModel::setReasoningEffort,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
    Composer(
      value = text,
      onValueChange = { text = it },
      streaming = chat.streaming,
      onSend = { val outgoing = text; text = ""; viewModel.send(outgoing) },
      onStop = viewModel::stop,
      onAttach = onAttach,
      isRecording = voice.isRecording,
      isTranscribing = voice.isTranscribing,
      onRecord = {
        if (voice.isRecording) {
          viewModel.stopVoiceRecordingAndTranscribe()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
          viewModel.startVoiceRecording()
        } else {
          microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
      },
    )
  }
  if (showAttachmentMenu) {
    AlertDialog(
      onDismissRequest = onDismissAttachmentMenu,
      title = { Text(stringResource(R.string.attachment_dialog_title)) },
      text = { Text(stringResource(R.string.attachment_dialog_message)) },
      confirmButton = { Button(onClick = onPickAttachment) { Text(stringResource(R.string.attachment_choose_file)) } },
      dismissButton = { TextButton(onClick = onDismissAttachmentMenu) { Text(stringResource(R.string.action_cancel)) } },
    )
  }
}

@Composable
private fun MessageBubble(
  message: ChatMessage,
  isSpeaking: Boolean,
  onSpeak: (String) -> Unit,
  onStopSpeaking: () -> Unit,
) {
  val user = message.role == MessageRole.USER
  Column(
    Modifier.fillMaxWidth(),
    horizontalAlignment = if (user) Alignment.End else Alignment.Start,
  ) {
    Card(
      colors = CardDefaults.cardColors(
        containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
      ),
      modifier = Modifier.fillMaxWidth(if (user) 0.88f else 0.96f),
    ) {
      Column(Modifier.padding(14.dp)) {
        if (message.reasoning.isNotBlank()) {
          Text("Reasoning", style = MaterialTheme.typography.labelMedium)
          Text(message.reasoning, style = MaterialTheme.typography.bodySmall)
          Spacer(Modifier.height(8.dp))
        }
        Text(message.text.ifBlank { if (message.status.name == "STREAMING") "Working..." else "" })
        if (!user && message.text.isNotBlank()) {
          TextButton(onClick = { if (isSpeaking) onStopSpeaking() else onSpeak(message.text) }) {
            Icon(
              if (isSpeaking) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
              contentDescription = null,
            )
            Spacer(Modifier.width(6.dp))
            Text(if (isSpeaking) "Stop speaking" else "Speak")
          }
        }
        message.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    }
  }
}

@Composable
private fun ToolCard(tool: ToolState) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp)) {
      Text(tool.name.ifBlank { "Tool" }, style = MaterialTheme.typography.titleSmall)
      if (tool.context.isNotBlank()) Text(tool.context, style = MaterialTheme.typography.bodySmall)
      if (tool.progress.isNotBlank()) Text(tool.progress, style = MaterialTheme.typography.bodySmall)
      if (tool.summary.isNotBlank()) Text(tool.summary, style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun PromptCards(chat: ChatState, viewModel: HermesAppViewModel) {
  chat.approval?.let { ApprovalCard(it, viewModel) }
  chat.clarify?.let { ClarifyCard(it, viewModel) }
  chat.secret?.let { SecretCard(it, viewModel::respondSecret) }
  chat.sudo?.let { SudoCard(it, viewModel::respondSudo) }
}

@Composable
private fun ApprovalCard(prompt: ApprovalPrompt, viewModel: HermesAppViewModel) {
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.tertiaryContainer)) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.approval_required), style = MaterialTheme.typography.titleSmall)
      Text(prompt.command)
      prompt.choices.forEach { choice ->
        OutlinedButton(onClick = { viewModel.respondApproval(choice) }, modifier = Modifier.fillMaxWidth()) {
          Icon(Icons.Default.Check, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(choice)
        }
      }
    }
  }
}

@Composable
private fun ClarifyCard(prompt: ClarifyPrompt, viewModel: HermesAppViewModel) {
  var answer by remember(prompt.requestId) { mutableStateOf("") }
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer)) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Answer needed", style = MaterialTheme.typography.titleSmall)
      Text(prompt.question)
      prompt.choices.forEach { choice -> TextButton(onClick = { viewModel.respondClarify(prompt.requestId, choice) }) { Text(choice) } }
      OutlinedTextField(answer, { answer = it }, label = { Text("Answer") }, modifier = Modifier.fillMaxWidth())
      Button(onClick = { viewModel.respondClarify(prompt.requestId, answer) }, enabled = answer.isNotBlank()) { Text("Submit") }
    }
  }
}

@Composable
internal fun SecretCard(prompt: SecretPrompt, onSubmit: (String, String) -> Unit) {
  var value by remember(prompt.requestId) { mutableStateOf("") }
  val inputLabel = stringResource(R.string.secret_input_label)
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        if (prompt.prompt.isBlank()) stringResource(R.string.secret_required) else prompt.prompt,
        style = MaterialTheme.typography.titleSmall,
      )
      OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text(inputLabel) },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("secret-input")
          .semantics { contentDescription = inputLabel },
      )
      Button(onClick = { onSubmit(prompt.requestId, value); value = "" }, enabled = value.isNotBlank()) {
        Text(stringResource(R.string.secret_send_securely))
      }
    }
  }
}

@Composable
internal fun SudoCard(prompt: SudoPrompt, onSubmit: (String, String) -> Unit) {
  var password by remember(prompt.requestId) { mutableStateOf("") }
  val inputLabel = stringResource(R.string.sudo_input_label)
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        if (prompt.prompt.isBlank()) stringResource(R.string.sudo_required) else prompt.prompt,
        style = MaterialTheme.typography.titleSmall,
      )
      OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(inputLabel) },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("sudo-input")
          .semantics { contentDescription = inputLabel },
      )
      Button(onClick = { onSubmit(prompt.requestId, password); password = "" }, enabled = password.isNotBlank()) {
        Text(stringResource(R.string.sudo_authenticate))
      }
    }
  }
}

@Composable
private fun Composer(
  value: String,
  onValueChange: (String) -> Unit,
  streaming: Boolean,
  onSend: () -> Unit,
  onStop: () -> Unit,
  onAttach: () -> Unit,
  isRecording: Boolean,
  isTranscribing: Boolean,
  onRecord: () -> Unit,
) {
  Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
      Box {
        IconButton(onClick = onAttach) {
          Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.composer_attach))
        }
      }
      IconButton(onClick = onRecord, enabled = !isTranscribing) {
        Icon(
          Icons.Default.Mic,
          contentDescription = stringResource(
            if (isRecording) R.string.composer_stop_recording else R.string.composer_record_voice,
          ),
          tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
      }
      OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.weight(1f).testTag("composer"),
        placeholder = {
          Text(
            stringResource(
              if (isTranscribing) R.string.composer_transcribing_voice else R.string.composer_message_placeholder,
            ),
          )
        },
        maxLines = 5,
      )
      Spacer(Modifier.width(4.dp))
      IconButton(onClick = if (streaming) onStop else onSend, enabled = streaming || value.isNotBlank(), modifier = Modifier.testTag(if (streaming) "stop" else "send")) {
        Icon(
          if (streaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
          contentDescription = stringResource(if (streaming) R.string.action_stop else R.string.action_send),
        )
      }
    }
  }
}

@Composable
private fun RenameDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
  var title by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.session_rename_title)) },
    text = {
      OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(stringResource(R.string.session_title_label)) })
    },
    confirmButton = {
      Button(onClick = { onSave(title.trim()) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.action_save)) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}
