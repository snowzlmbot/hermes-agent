package com.snowzlmbot.hermes.mobile.core

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class AndroidOAuthFlowTest {
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
  fun excludesPasswordProvidersFromNativeOAuthChoices() {
    val root = Json.parseToJsonElement(
      """{"providers":[{"name":"password","display_name":"Password","supports_password":true},{"name":"nous","display_name":"Nous Research","supports_password":false}]}""",
    )

    assertEquals(
      listOf(NativeOAuthProvider("nous", "Nous Research")),
      NativeOAuthProvider.parseList(root),
    )
  }

  @Test
  fun roundTripsOAuthTokensSeparatelyFromProfileMetadata() = runTest {
    val profiles = FakeProfileStore()
    val credentials = FakeCredentialStore()
    val repository = GatewayProfileRepository(profiles, credentials)
    val profile = GatewayProfile("https://agent.example/", GatewayAuthMode.OAUTH, false)
    val tokens = tokens("access-private", "refresh-private", 10_000)

    repository.save(profile, StoredGatewayAuth.OAuth(tokens))

    assertEquals(profile, repository.load()?.profile)
    assertEquals(StoredGatewayAuth.OAuth(tokens), repository.load()?.auth)
    assertFalse(profiles.profile.toString().contains("access-private"))
  }

  @Test
  fun refreshesBeforeMintingSingleUseWebSocketTicket() = runTest {
    server.enqueue(MockResponse().setBody("""{"access_token":"new-access","refresh_token":"new-refresh","expires_at":20000,"provider":"nous","user_id":"user-1"}"""))
    server.enqueue(MockResponse().setBody("""{"ticket":"fresh-ticket"}"""))
    val repository = GatewayProfileRepository(FakeProfileStore(), FakeCredentialStore())
    val profile = GatewayProfile(server.url("/").toString(), GatewayAuthMode.OAUTH, true)
    repository.save(profile, StoredGatewayAuth.OAuth(tokens("old-access", "old-refresh", 9_000)))
    val coordinator = GatewayAuthCoordinator(
      connection = requireNotNull(repository.load()),
      repository = repository,
      httpClient = OkHttpClient(),
      now = { Instant.ofEpochSecond(10_000) },
    )

    assertEquals(GatewayCredential.Ticket(SecretValue("fresh-ticket")), coordinator.socketCredential())
    val stored = (requireNotNull(repository.load()).auth as StoredGatewayAuth.OAuth).tokens
    assertEquals(SecretValue("new-access"), stored.accessToken)
    assertEquals(SecretValue("new-refresh"), stored.refreshToken)
    val refresh = server.takeRequest()
    val ticket = server.takeRequest()
    assertEquals("/auth/native/refresh", refresh.path)
    assertEquals("Bearer new-access", ticket.getHeader("Authorization"))
    assertEquals("/api/auth/ws-ticket", ticket.path)
  }

  private fun tokens(access: String, refresh: String, expiresAt: Long) = OAuthTokenSet(
    accessToken = SecretValue(access),
    refreshToken = SecretValue(refresh),
    expiresAt = Instant.ofEpochSecond(expiresAt),
    provider = "nous",
    userId = "user-1",
  )

  private class FakeProfileStore : ProfileStore {
    var profile: GatewayProfile? = null
    override suspend fun load(): GatewayProfile? = profile
    override suspend fun save(profile: GatewayProfile) { this.profile = profile }
    override suspend fun clear() { profile = null }
  }

  private class FakeCredentialStore : CredentialStore {
    var auth: StoredGatewayAuth? = null
    override suspend fun load(): StoredGatewayAuth? = auth
    override suspend fun save(auth: StoredGatewayAuth) { this.auth = auth }
    override suspend fun clear() { auth = null }
  }
}
