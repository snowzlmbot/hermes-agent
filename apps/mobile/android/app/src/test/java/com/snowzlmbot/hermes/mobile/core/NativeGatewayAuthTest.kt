package com.snowzlmbot.hermes.mobile.core

import java.net.URI
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGatewayAuthTest {
  @Test
  fun detectsStaticTokenAndNativeOauthFromPublicStatus() {
    val open = GatewayStatus.parse(
      Json.parseToJsonElement(
        """{"gateway_running":true,"auth_required":false,"auth_flows":[]}""",
      ),
    )
    val gated = GatewayStatus.parse(
      Json.parseToJsonElement(
        """{"gateway_running":true,"auth_required":true,"auth_flows":["cookie","native_pkce","native_pkce_mobile"],"auth_providers":["nous"]}""",
      ),
    )
    val desktopOnly = GatewayStatus.parse(
      Json.parseToJsonElement(
        """{"gateway_running":true,"auth_required":true,"auth_flows":["cookie","native_pkce"]}""",
      ),
    )
    val legacy = GatewayStatus.parse(
      Json.parseToJsonElement(
        """{"gateway_running":true,"auth_required":true,"auth_flows":["cookie"]}""",
      ),
    )

    assertEquals(GatewayLoginStrategy.STATIC_TOKEN, open.loginStrategy)
    assertEquals(GatewayLoginStrategy.NATIVE_OAUTH, gated.loginStrategy)
    assertEquals(listOf("nous"), gated.authProviders)
    assertEquals(GatewayLoginStrategy.UNSUPPORTED_INTERACTIVE, desktopOnly.loginStrategy)
    assertEquals(GatewayLoginStrategy.UNSUPPORTED_INTERACTIVE, legacy.loginStrategy)
  }

  @Test
  fun createsRfc7636S256PairAndIndependentState() {
    val pair = NativePkce.create(
      verifierBytes = ByteArray(32) { it.toByte() },
      stateBytes = ByteArray(24) { (it + 64).toByte() },
    )

    assertEquals(43, pair.verifier.length)
    assertEquals("S256", pair.method)
    assertEquals("6oZqdX5MOLq_qBJ8vppAnT4fk6AP8UiP9zX8-Rev_9A", pair.challenge)
    assertFalse(pair.state == pair.verifier)
    assertFalse(pair.toString().contains(pair.verifier))
  }

  @Test
  fun buildsMobileAuthorizeUrlAndRejectsForgedCallbackState() {
    val endpoint = GatewayEndpoint.parse("https://agent.example/hermes/")
    val url = NativePkce.authorizeUrl(
      endpoint = endpoint,
      challenge = "challenge value",
      redirectUri = NativePkce.MOBILE_REDIRECT_URI,
      state = "state value",
      provider = "nous",
    )

    assertEquals("/hermes/auth/native/authorize", url.encodedPath)
    assertEquals("challenge value", url.queryParameter("code_challenge"))
    assertEquals("S256", url.queryParameter("code_challenge_method"))
    assertEquals("com.snowzlmbot.hermes.mobile:/oauth/callback", url.queryParameter("redirect_uri"))
    assertEquals("nous", url.queryParameter("provider"))

    assertEquals(
      "gateway-code",
      NativePkce.parseCallback(
        URI("com.snowzlmbot.hermes.mobile:/oauth/callback?code=gateway-code&state=expected"),
        expectedState = "expected",
      ),
    )
    assertThrows(NativeAuthException::class.java) {
      NativePkce.parseCallback(
        URI("com.snowzlmbot.hermes.mobile:/oauth/callback?code=attacker-code&state=wrong"),
        expectedState = "expected",
      )
    }
    assertThrows(NativeAuthException::class.java) {
      NativePkce.authorizeUrl(
        endpoint = endpoint,
        challenge = "challenge",
        redirectUri = URI("com.attacker.app:/oauth/callback"),
        state = "state",
      )
    }
  }

  @Test
  fun refreshesOauthTokensBeforeExpiryWithoutExposingTheirValues() {
    val tokens = OAuthTokenSet(
      accessToken = SecretValue("access-private"),
      refreshToken = SecretValue("refresh-private"),
      expiresAt = Instant.ofEpochSecond(10_000),
      provider = "nous",
      userId = "user-1",
    )

    assertFalse(tokens.needsRefresh(Instant.ofEpochSecond(9_000)))
    assertTrue(tokens.needsRefresh(Instant.ofEpochSecond(9_950)))
    assertFalse(tokens.toString().contains("access-private"))
    assertFalse(tokens.toString().contains("refresh-private"))
    assertTrue(tokens.toString().contains("REDACTED"))
  }
}