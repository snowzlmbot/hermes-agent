package com.snowzlmbot.hermes.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.snowzlmbot.hermes.mobile.app.HermesAppViewModel
import com.snowzlmbot.hermes.mobile.ui.HermesRoot
import com.snowzlmbot.hermes.mobile.ui.HermesTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      HermesTheme {
        HermesRoot(viewModel<HermesAppViewModel>())
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
  }
}
