package com.snowzlmbot.hermes.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.snowzlmbot.hermes.mobile.core.ModelCatalog
import com.snowzlmbot.hermes.mobile.core.ModelOption
import com.snowzlmbot.hermes.mobile.core.ModelProviderOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelControlsTest {
  @get:Rule
  val compose = createComposeRule()

  @Test
  fun selectsModelAndShowsReasoningOnlyForCapableSelection() {
    val selections = mutableListOf<String>()
    compose.setContent {
      MaterialTheme {
        ModelControls(
          catalog = catalog,
          currentModel = "fixture-model",
          currentProvider = "fixture",
          reasoningEffort = "medium",
          isLoading = false,
          onRefresh = {},
          onSelectModel = { selections += it.id },
          onSetReasoningEffort = {},
        )
      }
    }

    compose.onNodeWithTag("model-picker").assertExists().performClick()
    compose.onNodeWithText("fixture-fast").assertExists().performClick()
    compose.onNodeWithTag("reasoning-picker").assertExists()
    assertEquals(listOf("fixture-fast"), selections)
  }

  private companion object {
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
              supportsReasoning = false,
            ),
            ModelOption(
              providerId = "fixture",
              providerName = "Fixture",
              id = "fixture-fast",
              supportsFast = true,
              supportsReasoning = true,
            ),
          ),
        ),
      ),
    )
  }
}