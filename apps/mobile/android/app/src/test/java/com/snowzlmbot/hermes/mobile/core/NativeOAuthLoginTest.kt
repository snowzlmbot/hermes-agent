package com.snowzlmbot.hermes.mobile.core

import java.net.URI
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NativeOAuthLoginTest {
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun discoversProviderAndCompletesRegisteredCallbackWithoutPersistingPkceSecrets() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"gateway_running":true,"auth_required":true,"auth_flows":["native_pkce","native_pkce_mobile"],"auth_providers":["password","nous"]}""",
      ),
    )
    server.enqueue(
      MockResponse().setBody(
        """{"providers":[{"name":"password","display_name":"Password","supports_password":true},{"name":"nous","display_name":"Nous Research","supports_password":false}]}""",
      ),
    )
    server.enqueue(
      MockResponse().setBody(
        """{"access_token":"access-private","refresh_token":"refresh-private","expires_at":4102444800,"provider":"nous","user_id":"user-1"}""",
      ),
    )
    val login = NativeOAuthLogin()

    val discovery = login.discover(server.url("/gateway/").toString())
    assertEquals(listOf(NativeOAuthProvider("nous", "Nous Research")), discovery.providers)
    val pending = login.begin(discovery, provider = "nous")
    assertEquals("/gateway/auth/native/authorize", pending.authorizationUrl.encodedPath)
    assertEquals("S256", pending.authorizationUrl.queryParameter("code_challenge_method"))
    assertEquals("nous", pending.authorizationUrl.queryParameter("provider"))
    assertEquals(NativePkce.MOBILE_REDIRECT_URI.toString(), pending.authorizationUrl.queryParameter("redirect_uri"))
    val state = requireNotNull(pending.authorizationUrl.queryParameter("state"))
    assertFalse(pending.toString().contains(state))
    assertTrue(pending.toString().contains("REDACTED"))

    try {
      login.complete(
        pending,
        URI("com.snowzlmbot.hermes.mobile:/oauth/callback?code=forged&state=wrong"),
      )
      throw AssertionError("Expected a forged callback to be rejected")
    } catch (_: NativeAuthException) {
      // The legitimate callback remains usable because validation happens before code exchange.
    }
    assertEquals(2, server.requestCount)

    val tokens = login.complete(
      pending,
      URI("com.snowzlmbot.hermes.mobile:/oauth/callback?code=gateway-code&state=$state"),
    )

    assertEquals(SecretValue("access-private"), tokens.accessToken)
    assertEquals(SecretValue("refresh-private"), tokens.refreshToken)
    assertEquals("nous", tokens.provider)
    assertEquals("user-1", tokens.userId)
    val exchange = server.takeRequest()
    assertEquals("/gateway/api/status", exchange.path)
    val providers = server.takeRequest()
    assertEquals("/gateway/api/auth/providers", providers.path)
    val token = server.takeRequest()
    assertEquals("/gateway/auth/native/token", token.path)
    val body = token.body.readUtf8()
    assertTrue(body.contains("gateway-code"))
    assertFalse(body.contains("access-private"))
    assertFalse(body.contains("refresh-private"))
  }

  @Test
  fun rejectsUnadvertisedProviderAndRemoteCleartextBeforeOpeningBrowser() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"gateway_running":true,"auth_required":true,"auth_flows":["native_pkce_mobile"],"auth_providers":["nous"]}""",
      ),
    )
    server.enqueue(
      MockResponse().setBody(
        """{"providers":[{"name":"nous","display_name":"Nous Research","supports_password":false}]}""",
      ),
    )
    val login = NativeOAuthLogin()
    val discovery = login.discover(server.url("/").toString())

    assertThrows(NativeAuthException::class.java) {
      login.begin(discovery, provider = "attacker")
    }
    assertThrows(InsecureEndpointException::class.java) {
      runBlocking { login.discover("http://192.0.2.10:8080") }
    }
    assertEquals(2, server.requestCount)
  }
}
