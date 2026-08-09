package com.snowzlmbot.hermes.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
  fun selectsModelAndShowsReasoningOnlyAfterConfirmedSelection() {
    val selections = mutableListOf<String>()
    compose.setContent {
      MaterialTheme {
        var currentModel by remember { mutableStateOf("fixture-model") }
        ModelControls(
          catalog = catalog,
          currentModel = currentModel,
          currentProvider = "fixture",
          reasoningEffort = "medium",
          isLoading = false,
          onRefresh = {},
          onSelectModel = {
            selections += it.id
            currentModel = it.id
          },
          onSetReasoningEffort = {},
        )
      }
    }

    compose.onAllNodes(hasTestTag("reasoning-picker")).assertCountEquals(0)
    compose.onNodeWithTag("model-picker").assertIsDisplayed().performClick()
    compose.onNodeWithText("fixture-fast", substring = true).assertIsDisplayed().performClick()
    compose.onNodeWithTag("reasoning-picker").assertIsDisplayed()
    assertEquals(listOf("fixture-fast"), selections)
  }

  @Test
  fun keepsCapabilitiesBoundToCurrentModelUntilParentConfirmsSelection() {
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

    compose.onNodeWithTag("model-picker").performClick()
    compose.onNodeWithText("fixture-fast", substring = true).performClick()
    compose.onAllNodes(hasTestTag("reasoning-picker")).assertCountEquals(0)
    assertEquals(listOf("fixture-fast"), selections)
  }

  @Test
  fun doesNotClaimCatalogCapabilityWhenCurrentModelIsUnknown() {
    compose.setContent {
      MaterialTheme {
        ModelControls(
          catalog = catalog,
          currentModel = "remote-only-model",
          currentProvider = "remote-provider",
          reasoningEffort = "high",
          isLoading = false,
          onRefresh = {},
          onSelectModel = {},
          onSetReasoningEffort = {},
        )
      }
    }

    compose.onNodeWithTag("model-picker").assertIsDisplayed()
    compose.onNodeWithText("Select model").assertIsDisplayed()
    compose.onAllNodes(hasTestTag("reasoning-picker")).assertCountEquals(0)
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