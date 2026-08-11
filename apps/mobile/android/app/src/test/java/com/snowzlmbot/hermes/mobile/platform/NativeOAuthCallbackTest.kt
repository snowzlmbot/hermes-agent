package com.snowzlmbot.hermes.mobile.platform

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeOAuthCallbackTest {
  @Test
  fun acceptsOnlyTheRegisteredApplicationCallback() {
    val callback = NativeOAuthCallback.fromUri(
      URI("com.snowzlmbot.hermes.mobile:/oauth/callback?code=code-1&state=state-1"),
      expectedState = "state-1",
    )
    assertEquals("code-1", callback.code)
  }

  @Test
  fun rejectsForgedSchemeAndState() {
    assertThrows(NativeOAuthException::class.java) {
      NativeOAuthCallback.fromUri(
        URI("com.attacker.app:/oauth/callback?code=code-1&state=state-1"),
        expectedState = "state-1",
      )
    }
    assertThrows(NativeOAuthException::class.java) {
      NativeOAuthCallback.fromUri(
        URI("com.snowzlmbot.hermes.mobile:/oauth/callback?code=code-1&state=wrong"),
        expectedState = "state-1",
      )
    }
  }

  @Test
  fun rejectsDuplicateStateAndCodeParameters() {
    assertThrows(NativeOAuthException::class.java) {
      NativeOAuthCallback.fromUri(
        URI("com.snowzlmbot.hermes.mobile:/oauth/callback?code=one&state=expected&state=expected"),
        expectedState = "expected",
      )
    }
    assertThrows(NativeOAuthException::class.java) {
      NativeOAuthCallback.fromUri(
        URI("com.snowzlmbot.hermes.mobile:/oauth/callback?code=one&code=two&state=expected"),
        expectedState = "expected",
      )
    }
  }
}
