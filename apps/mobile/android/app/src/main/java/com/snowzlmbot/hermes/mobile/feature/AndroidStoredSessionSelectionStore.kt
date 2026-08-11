package com.snowzlmbot.hermes.mobile.feature

import android.content.Context
import androidx.core.content.edit

internal class AndroidStoredSessionSelectionStore(
  context: Context,
  profileKey: String,
) : StoredSessionSelectionStore {
  private val preferences = context.applicationContext.getSharedPreferences(
    "hermes.mobile.session-selection",
    Context.MODE_PRIVATE,
  )
  private val key = "stored-session:$profileKey"

  override fun load(): String? = preferences.getString(key, null)?.takeIf(String::isNotBlank)

  override fun save(storedSessionId: String) {
    preferences.edit(commit = true) { putString(key, storedSessionId) }
  }

  override fun clear() {
    preferences.edit(commit = true) { remove(key) }
  }
}
