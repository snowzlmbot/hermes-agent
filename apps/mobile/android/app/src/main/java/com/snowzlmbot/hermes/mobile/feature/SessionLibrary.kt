package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.SessionSummary
import java.util.Locale

internal enum class SessionLibraryView { ACTIVE, ARCHIVED }

internal object SessionLibrary {
  fun filter(
    sessions: List<SessionSummary>,
    view: SessionLibraryView,
    query: String,
  ): List<SessionSummary> {
    val normalized = query.trim().lowercase(Locale.ROOT)
    return sessions.filter { session ->
      val inView = session.archived == (view == SessionLibraryView.ARCHIVED)
      inView && (
        normalized.isEmpty() ||
          session.displayTitle.matches(normalized) ||
          session.title.matches(normalized) ||
          session.preview.matches(normalized) ||
          session.storedId.matches(normalized)
      )
    }
  }

  private fun String.matches(query: String): Boolean = lowercase(Locale.ROOT).contains(query)
}
