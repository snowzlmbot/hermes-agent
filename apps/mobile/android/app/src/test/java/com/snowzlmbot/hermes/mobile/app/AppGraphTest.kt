package com.snowzlmbot.hermes.mobile.app

import com.snowzlmbot.hermes.mobile.core.CredentialStore
import com.snowzlmbot.hermes.mobile.core.GatewayAuthCoordinator
import com.snowzlmbot.hermes.mobile.core.GatewayAuthMode
import com.snowzlmbot.hermes.mobile.core.GatewayConnection
import com.snowzlmbot.hermes.mobile.core.GatewayProfile
import com.snowzlmbot.hermes.mobile.core.GatewayProfileRepository
import com.snowzlmbot.hermes.mobile.core.GatewaySignedOutException
import com.snowzlmbot.hermes.mobile.core.OAuthTokenSet
import com.snowzlmbot.hermes.mobile.core.ProfileStore
import com.snowzlmbot.hermes.mobile.core.SecretValue
import com.snowzlmbot.hermes.mobile.core.StoredGatewayAuth
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGraphTest {
  @Test
  fun restoresProfileMetadataAndCredentialAsOneConnection() = runTest {
    val profiles = RecordingProfileStore()
    val credentials = RecordingCredentialStore()
    val repository = GatewayProfileRepository(profiles, credentials)
    val graph = AppGraph(repository)
    val profile = GatewayProfile(
      address = "https://agent.example/hermes/",
      authMode = GatewayAuthMode.TOKEN,
      allowInsecure = false,
    )
    repository.save(profile, SecretValue("private-token"))

    val restored = graph.restoreConnection()

    assertEquals(profile, restored?.profile)
    assertEquals("https://agent.example/hermes/", restored?.endpoint()?.httpBaseUrl.toString())
    assertFalse(profiles.serialized.contains("private-token"))
  }

  @Test
  fun missingCredentialKeepsApplicationInOnboarding() = runTest {
    val profiles = RecordingProfileStore().apply {
      profile = GatewayProfile("https://agent.example/", GatewayAuthMode.TOKEN, false)
    }
    val graph = AppGraph(GatewayProfileRepository(profiles, RecordingCredentialStore()))

    assertNull(graph.restoreConnection())
  }

  @Test
  fun staticTokenMintsSingleUseTicketBeforeSocketConnect() = runBlocking {
    val server = MockWebServer()
    server.enqueue(ticketResponse("single-use-ticket"))
    server.enqueue(webSocketResponse())
    server.start()
    try {
      val (graph, connection) = staticTokenGraph(server)
      val runtime = graph.runtime(connection)
      try {
        runtime.connect()

        val ticketRequest = server.takeRequest()
        val socketRequest = server.takeRequest()
        assertStaticTokenTicketRequest(ticketRequest, "durable-token")
        assertEquals("/proxy/api/ws", socketRequest.requestUrl?.encodedPath)
        assertEquals("single-use-ticket", socketRequest.requestUrl?.queryParameter("ticket"))
        assertNull(socketRequest.requestUrl?.queryParameter("token"))
        assertFalse(socketRequest.path.orEmpty().contains("durable-token"))
      } finally {
        runtime.close()
      }
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun staticTokenReconnectMintsAnotherSingleUseTicket() = runBlocking {
    val server = MockWebServer()
    server.enqueue(ticketResponse("first-ticket"))
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(ticketResponse("second-ticket"))
    server.enqueue(webSocketResponse())
    server.start()
    try {
      val (graph, connection) = staticTokenGraph(server)
      val runtime = graph.runtime(connection)
      try {
        assertTrue(runCatching { runtime.connect() }.isFailure)
        runtime.connect()

        val firstTicketRequest = server.takeRequest()
        val firstSocketRequest = server.takeRequest()
        val secondTicketRequest = server.takeRequest()
        val secondSocketRequest = server.takeRequest()
        assertStaticTokenTicketRequest(firstTicketRequest, "durable-token")
        assertEquals("first-ticket", firstSocketRequest.requestUrl?.queryParameter("ticket"))
        assertNull(firstSocketRequest.requestUrl?.queryParameter("token"))
        assertStaticTokenTicketRequest(secondTicketRequest, "durable-token")
        assertEquals("second-ticket", secondSocketRequest.requestUrl?.queryParameter("ticket"))
        assertNull(secondSocketRequest.requestUrl?.queryParameter("token"))
      } finally {
        runtime.close()
      }
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun clearingOAuthConnectionSignsOutTheActiveCoordinator() = runTest {
    val repository = GatewayProfileRepository(RecordingProfileStore(), RecordingCredentialStore())
    val profile = GatewayProfile("https://agent.example/", GatewayAuthMode.OAUTH, false)
    val auth = StoredGatewayAuth.OAuth(
      OAuthTokenSet(
        accessToken = SecretValue("access-private"),
        refreshToken = SecretValue("refresh-private"),
        expiresAt = Instant.ofEpochSecond(20_000),
        provider = "nous",
        userId = "user-1",
      ),
    )
    repository.save(profile, auth)
    val connection = GatewayConnection(profile, auth)
    val coordinator = GatewayAuthCoordinator(connection, repository)
    val graph = AppGraph(
      connections = repository,
      authCoordinatorFactory = { _, _ -> coordinator },
    )
    graph.runtime(connection)

    graph.clearConnection()

    assertNull(repository.load())
    try {
      coordinator.socketCredential()
      throw AssertionError("Expected the active OAuth coordinator to be signed out")
    } catch (_: GatewaySignedOutException) {
      // Expected: clearing the app connection invalidates in-flight auth state.
    }
  }

  @Test
  fun staleConnectionCannotInstallRuntimeAfterClear() = runTest {
    val repository = GatewayProfileRepository(RecordingProfileStore(), RecordingCredentialStore())
    val profile = GatewayProfile("https://agent.example/", GatewayAuthMode.TOKEN, false)
    repository.save(profile, SecretValue("private-token"))
    val stale = requireNotNull(repository.load())
    val graph = AppGraph(repository)

    graph.clearConnection()

    try {
      graph.runtime(stale)
      throw AssertionError("Expected stale connection to be rejected")
    } catch (_: IllegalStateException) {
      // The runtime rechecks repository state while holding the lifecycle lock.
    }
  }

  private suspend fun staticTokenGraph(server: MockWebServer): Pair<AppGraph, GatewayConnection> {
    val repository = GatewayProfileRepository(
      profileStore = RecordingProfileStore(),
      credentialStore = RecordingCredentialStore(),
      allowInsecureTransport = true,
    )
    val profile = GatewayProfile(
      address = server.url("/proxy/").toString(),
      authMode = GatewayAuthMode.TOKEN,
      allowInsecure = true,
    )
    repository.save(profile, SecretValue("durable-token"))
    return AppGraph(repository) to requireNotNull(repository.load())
  }

  private fun ticketResponse(ticket: String): MockResponse =
    MockResponse().setResponseCode(200).setBody("""{"ticket":"$ticket"}""")

  private fun webSocketResponse(): MockResponse =
    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})

  private fun assertStaticTokenTicketRequest(
    request: okhttp3.mockwebserver.RecordedRequest,
    token: String,
  ) {
    assertEquals("POST", request.method)
    assertEquals("/proxy/api/auth/ws-ticket", request.requestUrl?.encodedPath)
    assertEquals("Bearer $token", request.getHeader("Authorization"))
    assertEquals(token, request.getHeader("X-Hermes-Session-Token"))
    assertFalse(request.path.orEmpty().contains(token))
  }

  private class RecordingProfileStore : ProfileStore {
    var profile: GatewayProfile? = null
    val serialized: String get() = profile.toString()

    override suspend fun load(): GatewayProfile? = profile

    override suspend fun save(profile: GatewayProfile) {
      this.profile = profile
    }

    override suspend fun clear() {
      profile = null
    }
  }

  private class RecordingCredentialStore : CredentialStore {
    private var auth: StoredGatewayAuth? = null

    override suspend fun load(): StoredGatewayAuth? = auth

    override suspend fun save(auth: StoredGatewayAuth) {
      this.auth = auth
    }

    override suspend fun clear() {
      auth = null
    }
  }
}
