package com.snowzlmbot.hermes.mobile.platform

import java.security.MessageDigest

internal data class StoredSessionRoute(
  val storedSessionId: String,
  val profileScope: String,
)

internal object NotificationProfileScope {
  fun fromSelectionScope(selectionScope: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(selectionScope.toByteArray(Charsets.UTF_8))
      .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
