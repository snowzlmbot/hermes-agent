package com.snowzlmbot.hermes.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.snowzlmbot.hermes.mobile.app.AppScreen
import com.snowzlmbot.hermes.mobile.app.HermesAppViewModel
import com.snowzlmbot.hermes.mobile.platform.NotificationIntentConsumer
import com.snowzlmbot.hermes.mobile.ui.HermesRoot
import com.snowzlmbot.hermes.mobile.ui.HermesTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: HermesAppViewModel by viewModels()
  private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
  private var notificationPermissionRequested = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      HermesTheme { HermesRoot(viewModel) }
    }
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { viewModel.oauthBrowserEvents.collect(::openSystemBrowser) }
        launch {
          viewModel.state.collect { state ->
            if (state.screen == AppScreen.CHAT) requestNotificationPermissionIfNeeded()
          }
        }
      }
    }
    consumeNotificationIntent(intent)
    consumeOAuthCallback(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    consumeNotificationIntent(intent)
    consumeOAuthCallback(intent)
  }

  private fun consumeNotificationIntent(source: Intent) {
    NotificationIntentConsumer.consume(source) { route ->
      viewModel.handleNotificationRoute(route)
    }
  }

  private fun consumeOAuthCallback(source: Intent) {
    val callback = source.data ?: return
    setIntent(Intent(source).setData(null))
    viewModel.handleOAuthCallback(callback)
  }

  private fun requestNotificationPermissionIfNeeded() {
    if (notificationPermissionRequested) return
    if (Build.VERSION.SDK_INT >= 33 &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermissionRequested = true
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  private fun openSystemBrowser(url: String) {
    try {
      startActivity(
        Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
      )
    } catch (_: android.content.ActivityNotFoundException) {
      viewModel.reportOAuthBrowserFailure()
    }
  }
}
