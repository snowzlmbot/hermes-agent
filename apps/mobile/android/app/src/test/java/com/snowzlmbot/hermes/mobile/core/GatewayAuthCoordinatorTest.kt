package com.snowzlmbot.hermes.mobile.core

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayAuthCoordinatorTest {
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
  fun concurrentSocketConnectionsShareOneRefreshAndMintIndependentTickets() = runBlocking {
    val refreshCount = AtomicInteger()
    val ticketCount = AtomicInteger()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
        "/auth/native/refresh" -> {
          refreshCount.incrementAndGet()
          Thread.sleep(150)
          jsonResponse(tokensBody("new-access", "new-refresh", 20_000))
        }
        "/api/auth/ws-ticket" -> {
          val number = ticketCount.incrementAndGet()
          MockResponse().setResponseCode(200).setBody("""{"ticket":"ticket-$number"}""")
        }
        else -> MockResponse().setResponseCode(404)
      }
    }
    val fixture = coordinator(tokens("old-access", "old-refresh", 9_000))

    val credentials = listOf(
      async(Dispatchers.IO) { fixture.coordinator.socketCredential() },
      async(Dispatchers.IO) { fixture.coordinator.socketCredential() },
    ).awaitAll()

    assertEquals(1, refreshCount.get())
    assertEquals(2, ticketCount.get())
    assertEquals(setOf("ticket-1", "ticket-2"), credentials.map { it.secret.reveal() }.toSet())
    assertEquals(2, fixture.credentialStore.saveCount.get())
    val stored = fixture.repository.load()?.auth as StoredGatewayAuth.OAuth
    assertEquals(SecretValue("new-access"), stored.tokens.accessToken)
  }

  @Test
  fun unauthorizedResponseRefreshesAndRetriesExactlyOnce() = runBlocking {
    val sessionHeaders = CopyOnWriteArrayList<String?>()
    val refreshCount = AtomicInteger()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
        "/api/sessions" -> {
          sessionHeaders += request.getHeader("Authorization")
          if (sessionHeaders.size == 1) {
            MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")
          } else {
            jsonResponse(
              """{"sessions":[{"id":"stored-1","title":"Recovered","preview":"","started_at":1,"message_count":1,"source":"mobile","archived":false}],"total":1,"limit":20,"offset":0}""",
            )
          }
        }
        "/auth/native/refresh" -> {
          refreshCount.incrementAndGet()
          jsonResponse(tokensBody("new-access", "new-refresh", 20_000))
        }
        else -> MockResponse().setResponseCode(404)
      }
    }
    val fixture = coordinator(tokens("old-access", "old-refresh", 20_000))

    val sessions = fixture.coordinator.restClient().listSessions()

    assertEquals("stored-1", sessions.single().storedId)
    assertEquals(listOf("Bearer old-access", "Bearer new-access"), sessionHeaders.toList())
    assertEquals(1, refreshCount.get())
    val stored = fixture.repository.load()?.auth as StoredGatewayAuth.OAuth
    assertEquals(SecretValue("new-access"), stored.tokens.accessToken)
  }

  @Test
  fun secondUnauthorizedResponseDoesNotCreateAnInfiniteRetryLoop() = runBlocking {
    val sessionCount = AtomicInteger()
    val refreshCount = AtomicInteger()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
        "/api/sessions" -> {
          sessionCount.incrementAndGet()
          MockResponse().setResponseCode(401).setBody("""{"detail":"still expired"}""")
        }
        "/auth/native/refresh" -> {
          refreshCount.incrementAndGet()
          jsonResponse(tokensBody("new-access", "new-refresh", 20_000))
        }
        else -> MockResponse().setResponseCode(404)
      }
    }
    val fixture = coordinator(tokens("old-access", "old-refresh", 20_000))

    val failure = runCatching { fixture.coordinator.restClient().listSessions() }.exceptionOrNull()

    assertTrue(failure is GatewayHttpException)
    assertEquals(2, sessionCount.get())
    assertEquals(1, refreshCount.get())
  }

  private suspend fun coordinator(tokens: OAuthTokenSet): Fixture {
    val profileStore = FakeProfileStore()
    val credentialStore = FakeCredentialStore()
    val repository = GatewayProfileRepository(profileStore, credentialStore)
    val profile = GatewayProfile(server.url("/").toString(), GatewayAuthMode.OAUTH, true)
    repository.save(profile, StoredGatewayAuth.OAuth(tokens))
    return Fixture(
      coordinator = GatewayAuthCoordinator(
        connection = requireNotNull(repository.load()),
        repository = repository,
        httpClient = OkHttpClient(),
        now = { Instant.ofEpochSecond(10_000) },
      ),
      repository = repository,
      credentialStore = credentialStore,
    )
  }

  private fun tokens(access: String, refresh: String, expiresAt: Long) = OAuthTokenSet(
    accessToken = SecretValue(access),
    refreshToken = SecretValue(refresh),
    expiresAt = Instant.ofEpochSecond(expiresAt),
    provider = "nous",
    userId = "user-1",
  )

  private fun tokensBody(access: String, refresh: String, expiresAt: Long): String =
    """{"access_token":"$access","refresh_token":"$refresh","expires_at":$expiresAt,"provider":"nous","user_id":"user-1"}"""

  private fun jsonResponse(body: String): MockResponse = MockResponse().setResponseCode(200).setBody(body)

  private data class Fixture(
    val coordinator: GatewayAuthCoordinator,
    val repository: GatewayProfileRepository,
    val credentialStore: FakeCredentialStore,
  )

  private class FakeProfileStore : ProfileStore {
    private val value = AtomicReference<GatewayProfile?>(null)
    override suspend fun load(): GatewayProfile? = value.get()
    override suspend fun save(profile: GatewayProfile) { value.set(profile) }
    override suspend fun clear() { value.set(null) }
  }

  private class FakeCredentialStore : CredentialStore {
    private val value = AtomicReference<StoredGatewayAuth?>(null)
    val saveCount = AtomicInteger()

    override suspend fun load(): StoredGatewayAuth? = value.get()
    override suspend fun save(auth: StoredGatewayAuth) {
      value.set(auth)
      saveCount.incrementAndGet()
    }
    override suspend fun clear() { value.set(null) }
  }
}
