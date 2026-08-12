package com.snowzlmbot.hermes.mobile.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.snowzlmbot.hermes.mobile.app.AppScreen
import com.snowzlmbot.hermes.mobile.app.AppUiState
import com.snowzlmbot.hermes.mobile.core.NativeOAuthProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeOAuthSectionTest {
  @get:Rule
  val compose = createComposeRule()

  @Test
  fun discoversProvidersWithCurrentAddress() {
    val discoveries = mutableListOf<String>()
    compose.setContent {
      MaterialTheme {
        NativeOAuthSection(
          state = AppUiState(screen = AppScreen.ONBOARDING),
          address = "https://gateway.example/",
          onDiscover = discoveries::add,
          onStart = { _, _ -> },
          onCancel = {},
        )
      }
    }

    compose.onNodeWithTag("oauth-discover").assertIsDisplayed().performClick()

    assertEquals(listOf("https://gateway.example/"), discoveries)
  }

  @Test
  fun startsSelectedProviderWithCurrentAddress() {
    val starts = mutableListOf<Pair<String, String>>()
    compose.setContent {
      MaterialTheme {
        NativeOAuthSection(
          state = AppUiState(
            screen = AppScreen.ONBOARDING,
            oauthProviders = listOf(NativeOAuthProvider("nous", "Nous Research")),
          ),
          address = "https://gateway.example/",
          onDiscover = {},
          onStart = { address, provider -> starts += address to provider },
          onCancel = {},
        )
      }
    }

    compose.onNodeWithTag("oauth-provider-nous").assertIsDisplayed().performClick()

    assertEquals(listOf("https://gateway.example/" to "nous"), starts)
  }

  @Test
  fun pendingFlowDisablesDiscoveryAndProviderAndOffersCancel() {
    var cancelled = false
    compose.setContent {
      MaterialTheme {
        NativeOAuthSection(
          state = AppUiState(
            screen = AppScreen.ONBOARDING,
            oauthProviders = listOf(NativeOAuthProvider("nous", "Nous Research")),
            isOAuthPending = true,
          ),
          address = "https://gateway.example/",
          onDiscover = {},
          onStart = { _, _ -> },
          onCancel = { cancelled = true },
        )
      }
    }

    compose.onNodeWithTag("oauth-discover").assertIsNotEnabled()
    compose.onNodeWithTag("oauth-provider-nous").assertIsNotEnabled()
    compose.onNodeWithTag("oauth-pending").assert(
      SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
    )
    compose.onNodeWithTag("oauth-cancel").assertIsDisplayed().performClick()

    assertEquals(true, cancelled)
  }
}
