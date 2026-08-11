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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
