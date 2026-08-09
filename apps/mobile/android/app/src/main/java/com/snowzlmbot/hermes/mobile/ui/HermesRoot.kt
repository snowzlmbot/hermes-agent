package com.snowzlmbot.hermes.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin

import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    Text("Connecting to Hermes", style = MaterialTheme.typography.titleMedium)
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
      Text("Connect to a gateway", style = MaterialTheme.typography.headlineSmall)
      Text(
        "Use a secure gateway address and a session token. The token is kept in encrypted storage.",
        style = MaterialTheme.typography.bodyMedium,
      )
      OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text("Gateway address") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("gateway-address"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
      )
      OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("Session token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("gateway-token"),
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Switch(checked = allowInsecure, onCheckedChange = { allowInsecure = it })
        Spacer(Modifier.width(8.dp))
        Text("Allow cleartext for this gateway")
      }
      state.configurationError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("configuration-error"))
      }
      Button(
        onClick = { viewModel.saveConnection(address, token, allowInsecure) },
        enabled = address.isNotBlank() && token.isNotBlank(),
        modifier = Modifier.fillMaxWidth().testTag("connect-button"),
      ) {
        Icon(Icons.Default.Wifi, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Connect")
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(state: AppUiState, viewModel: HermesAppViewModel) {
  val mobile = state.chat
  val chat = mobile?.chat
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  var showAttachmentMenu by remember { mutableStateOf(false) }
  var showSettings by remember { mutableStateOf(false) }
  var renameId by remember { mutableStateOf<String?>(null) }
  val context = LocalContext.current
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    if (uri != null) {
      val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
      val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "attachment" } ?: "attachment"
      viewModel.attach(uri, name, type)
    }
  }

  LaunchedEffect(state.configurationError, mobile?.error?.message, chat?.error) {
    val message = state.configurationError ?: mobile?.error?.message ?: chat?.error
    if (!message.isNullOrBlank()) snackbar.showSnackbar(message)
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
              val title = mobile?.sessions
                ?.firstOrNull { it.storedId == chat?.storedSessionId }
                ?.displayTitle
                ?: "New conversation"
              Text(title)
              val model = chat?.model?.takeIf(String::isNotBlank)
              if (model != null) Text(model, style = MaterialTheme.typography.labelSmall)
            }
          },
          navigationIcon = {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
              Icon(Icons.Default.Menu, contentDescription = "Sessions")
            }
          },
          actions = {
            IconButton(onClick = viewModel::reconnect) {
              Icon(
                if (chat?.streaming == true) Icons.Default.Wifi else Icons.Default.WifiOff,
                contentDescription = "Reconnect",
              )
            }
            IconButton(onClick = { showSettings = true }) {
              Icon(Icons.Default.Settings, contentDescription = "Connection settings")
            }
          },
        )
      },
      snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
      if (chat == null) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
          Text("Preparing a conversation")
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
      title = { Text("Connection settings") },
      text = { Text("Remove the saved gateway profile and encrypted credential from this device.") },
      confirmButton = {
        Button(onClick = { showSettings = false; viewModel.forgetConnection() }) {
          Text("Forget connection")
        }
      },
      dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun SessionDrawer(
  sessions: List<SessionSummary>,
  onNew: () -> Unit,
  onOpen: (String) -> Unit,
  onRename: (String) -> Unit,
  onSetPinned: (String, Boolean) -> Unit,
  onArchive: (String) -> Unit,
  onDelete: (String) -> Unit,
) {
  var menuId by remember { mutableStateOf<String?>(null) }
  Column(Modifier.fillMaxSize().padding(vertical = 20.dp)) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text("Sessions", style = MaterialTheme.typography.titleLarge)
      IconButton(onClick = onNew) { Icon(Icons.Default.Add, contentDescription = "New session") }
    }
    HorizontalDivider()
    if (sessions.isEmpty()) {
      Text("No saved sessions", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
    }
    sessions.forEach { session ->
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = { onOpen(session.storedId) }, modifier = Modifier.weight(1f)) {
          Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(session.displayTitle, maxLines = 1, modifier = Modifier.weight(1f))
              if (session.pinned) {
                Icon(Icons.Default.PushPin, contentDescription = "Pinned")
              }
            }
            Text(session.preview, maxLines = 1, style = MaterialTheme.typography.labelSmall)
          }
        }
        Box {
          IconButton(onClick = { menuId = session.storedId }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Session actions")
          }
          DropdownMenu(expanded = menuId == session.storedId, onDismissRequest = { menuId = null }) {
            DropdownMenuItem(
              text = { Text(if (session.pinned) "Unpin" else "Pin") },
              leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
              onClick = {
                menuId = null
                onSetPinned(session.storedId, !session.pinned)
              },
            )
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuId = null; onRename(session.storedId) })
            DropdownMenuItem(text = { Text("Archive") }, onClick = { menuId = null; onArchive(session.storedId) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menuId = null; onDelete(session.storedId) })
          }
        }
      }
    }
  }
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
  val listState = rememberLazyListState()
  LaunchedEffect(chat.messages.size, chat.messages.lastOrNull()?.text) {
    if (chat.messages.isNotEmpty()) listState.animateScrollToItem(chat.messages.lastIndex)
  }
  LaunchedEffect(chat.runtimeSessionId) {
    if (chat.runtimeSessionId != null && modelCatalog.providers.isEmpty()) viewModel.refreshModelOptions()
  }
  Column(modifier.imePadding()) {
    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      itemsIndexed(chat.messages, key = { _, item -> item.id }) { _, message -> MessageBubble(message) }
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
    )
  }
  if (showAttachmentMenu) {
    AlertDialog(
      onDismissRequest = onDismissAttachmentMenu,
      title = { Text("Attach to this conversation") },
      text = { Text("Select an image, PDF, or file. The content is uploaded to the gateway.") },
      confirmButton = { Button(onClick = onPickAttachment) { Text("Choose file") } },
      dismissButton = { TextButton(onClick = onDismissAttachmentMenu) { Text("Cancel") } },
    )
  }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
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
  chat.secret?.let { SecretCard(it, viewModel) }
  chat.sudo?.let { SudoCard(it, viewModel) }
}

@Composable
private fun ApprovalCard(prompt: ApprovalPrompt, viewModel: HermesAppViewModel) {
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.tertiaryContainer)) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Approval required", style = MaterialTheme.typography.titleSmall)
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
private fun SecretCard(prompt: SecretPrompt, viewModel: HermesAppViewModel) {
  var value by remember(prompt.requestId) { mutableStateOf("") }
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(prompt.prompt.ifBlank { "Secret required" }, style = MaterialTheme.typography.titleSmall)
      OutlinedTextField(value, { value = it }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
      Button(onClick = { viewModel.respondSecret(prompt.requestId, value); value = "" }, enabled = value.isNotBlank()) { Text("Send securely") }
    }
  }
}

@Composable
private fun SudoCard(prompt: SudoPrompt, viewModel: HermesAppViewModel) {
  var password by remember(prompt.requestId) { mutableStateOf("") }
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(prompt.prompt, style = MaterialTheme.typography.titleSmall)
      OutlinedTextField(password, { password = it }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
      Button(onClick = { viewModel.respondSudo(prompt.requestId, password); password = "" }, enabled = password.isNotBlank()) { Text("Authenticate") }
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
) {
  Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
      Box {
        IconButton(onClick = onAttach) { Icon(Icons.Default.AttachFile, contentDescription = "Attach") }
      }
      OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.weight(1f).testTag("composer"),
        placeholder = { Text("Message Hermes") },
        maxLines = 5,
      )
      Spacer(Modifier.width(4.dp))
      IconButton(onClick = if (streaming) onStop else onSend, enabled = streaming || value.isNotBlank(), modifier = Modifier.testTag(if (streaming) "stop" else "send")) {
        Icon(
          if (streaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
          contentDescription = if (streaming) "Stop" else "Send",
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
    title = { Text("Rename session") },
    text = { OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Title") }) },
    confirmButton = { Button(onClick = { onSave(title.trim()) }, enabled = title.isNotBlank()) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
