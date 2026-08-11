package com.snowzlmbot.hermes.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.snowzlmbot.hermes.mobile.app.HermesAppViewModel
import com.snowzlmbot.hermes.mobile.ui.HermesRoot
import com.snowzlmbot.hermes.mobile.ui.HermesTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: HermesAppViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      HermesTheme {
        HermesRoot(viewModel)
      }
    }
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.oauthBrowserEvents.collect(::openSystemBrowser)
      }
    }
    consumeOAuthCallback(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    consumeOAuthCallback(intent)
  }

  private fun consumeOAuthCallback(source: Intent) {
    val callback = source.data ?: return
    setIntent(Intent(source).setData(null))
    viewModel.handleOAuthCallback(callback)
  }

  private fun openSystemBrowser(url: String) {
    try {
      startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
      )
    } catch (_: android.content.ActivityNotFoundException) {
      viewModel.reportOAuthBrowserFailure()
    }
  }
}
