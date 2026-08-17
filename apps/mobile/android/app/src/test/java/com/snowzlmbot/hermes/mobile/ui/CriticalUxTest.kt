package com.snowzlmbot.hermes.mobile.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.snowzlmbot.hermes.mobile.core.SessionSummary
import com.snowzlmbot.hermes.mobile.feature.SecretPrompt
import com.snowzlmbot.hermes.mobile.feature.SudoPrompt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class CriticalUxTest {
  @get:Rule
  val compose = createComposeRule()

  @Test
  fun deletingSessionRequiresExplicitConfirmation() {
    val deleted = mutableListOf<String>()
    var sessions by mutableStateOf(listOf(session, otherSession))
    compose.setContent {
      MaterialTheme {
        SessionDrawer(
          sessions = sessions,
          onNew = {},
          onOpen = {},
          onRename = {},
          onSetPinned = { _, _ -> },
          onArchive = {},
          onRestore = {},
          onDelete = deleted::add,
        )
      }
    }

    compose.onNodeWithContentDescription("Actions for Other session").assertIsDisplayed()
    compose.onNodeWithContentDescription("Actions for Release checklist").performClick()
    compose.onNodeWithText("Delete").performClick()

    compose.onNodeWithText("Delete session?").assertIsDisplayed()
    compose.onNodeWithText("Delete Release checklist? This cannot be undone.").assertIsDisplayed()
    assertEquals(emptyList<String>(), deleted)
    compose.onNodeWithTag("cancel-delete-session").performClick()
    assertEquals(emptyList<String>(), deleted)

    compose.onNodeWithContentDescription("Actions for Release checklist").performClick()
    compose.onNodeWithText("Delete").performClick()
    compose.runOnIdle { sessions = listOf(otherSession, session) }
    compose.onNodeWithTag("confirm-delete-session").performClick()

    assertEquals(listOf("stored-1"), deleted)
  }

  @Test
  fun protectedInputsExposeLocalizedAccessibleLabels() {
    compose.setContent {
      MaterialTheme {
        Column {
          SecretCard(SecretPrompt("secret-1", ""), onSubmit = { _, _ -> })
          SudoCard(SudoPrompt("sudo-1", ""), onSubmit = { _, _ -> })
        }
      }
    }

    compose.onNodeWithTag("secret-input")
      .assertContentDescriptionEquals("Secret")
    compose.onNodeWithTag("sudo-input")
      .assertContentDescriptionEquals("Password")
  }

  private companion object {
    val session = SessionSummary(
      storedId = "stored-1",
      title = "Release checklist",
      preview = "Ready for review",
      startedAt = 1,
      lastActive = 1,
      messageCount = 1,
      source = "mobile",
    )
    val otherSession = session.copy(storedId = "stored-2", title = "Other session")
  }
}
