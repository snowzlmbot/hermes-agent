package com.snowzlmbot.hermes.mobile.platform

import com.snowzlmbot.hermes.mobile.core.NativeAuthException
import com.snowzlmbot.hermes.mobile.core.NativePkce
import java.net.URI

class NativeOAuthException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

data class NativeOAuthCallback(val code: String) {
  companion object {
    fun fromUri(uri: URI, expectedState: String): NativeOAuthCallback = try {
      NativeOAuthCallback(NativePkce.parseCallback(uri, expectedState))
    } catch (error: NativeAuthException) {
      throw NativeOAuthException(error.message ?: "OAuth callback rejected", error)
    }
  }
}
