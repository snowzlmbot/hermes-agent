package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLibraryTest {
  private val sessions = listOf(
    summary("stored-pinned", "Pinned Roadmap", "Release Preview", archived = false, pinned = true),
    summary("stored-active", "Active Notes", "Kotlin details", archived = false),
    summary("stored-archive", "Old Research", "Swift Preview", archived = true),
  )

  @Test
  fun filtersLoadedSessionsAcrossAllContractFieldsWithoutReordering() {
    assertEquals(listOf("stored-pinned"), SessionLibrary.filter(sessions, SessionLibraryView.ACTIVE, " roadmap ").map { it.storedId })
    assertEquals(listOf("stored-active"), SessionLibrary.filter(sessions, SessionLibraryView.ACTIVE, "KOTLIN").map { it.storedId })
    assertEquals(listOf("stored-archive"), SessionLibrary.filter(sessions, SessionLibraryView.ARCHIVED, "stored-ARCHIVE").map { it.storedId })
    assertEquals(listOf("stored-archive"), SessionLibrary.filter(sessions, SessionLibraryView.ARCHIVED, "swift preview").map { it.storedId })
  }

  @Test
  fun emptyQueryShowsCurrentViewAndPreservesPinnedServerOrder() {
    assertEquals(listOf("stored-pinned", "stored-active"), SessionLibrary.filter(sessions, SessionLibraryView.ACTIVE, "  ").map { it.storedId })
    assertEquals(listOf("stored-archive"), SessionLibrary.filter(sessions, SessionLibraryView.ARCHIVED, "").map { it.storedId })
  }

  private fun summary(id: String, title: String, preview: String, archived: Boolean, pinned: Boolean = false) = SessionSummary(
    storedId = id,
    title = title,
    preview = preview,
    startedAt = 1,
    lastActive = 1,
    messageCount = 1,
    source = "mobile",
    archived = archived,
    pinned = pinned,
  )
}
