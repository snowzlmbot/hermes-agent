package com.snowzlmbot.hermes.mobile.feature

internal interface StoredSessionSelectionStore {
  fun load(): String?
  fun save(storedSessionId: String)
  fun clear()
}

internal object EmptyStoredSessionSelectionStore : StoredSessionSelectionStore {
  override fun load(): String? = null

  override fun save(storedSessionId: String) = Unit

  override fun clear() = Unit
}
