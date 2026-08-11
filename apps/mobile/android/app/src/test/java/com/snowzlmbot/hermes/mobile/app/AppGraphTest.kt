package com.snowzlmbot.hermes.mobile.app

import com.snowzlmbot.hermes.mobile.core.CredentialStore
import com.snowzlmbot.hermes.mobile.core.GatewayAuthMode
import com.snowzlmbot.hermes.mobile.core.GatewayProfile
import com.snowzlmbot.hermes.mobile.core.GatewayProfileRepository
import com.snowzlmbot.hermes.mobile.core.ProfileStore
import com.snowzlmbot.hermes.mobile.core.SecretValue
import com.snowzlmbot.hermes.mobile.core.StoredGatewayAuth
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
