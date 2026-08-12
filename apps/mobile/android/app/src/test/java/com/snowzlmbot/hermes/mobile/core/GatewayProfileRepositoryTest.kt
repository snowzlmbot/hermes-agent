package com.snowzlmbot.hermes.mobile.core

import java.time.Instant

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProfileRepositoryTest {
  @Test
  fun sessionSelectionScopeIncludesProfileIdentity() {
    val first = GatewayProfile("gateway.example", GatewayAuthMode.TOKEN, false, id = "profile-a")
    val otherProfile = first.copy(id = "profile-b")
    assertFalse(first.sessionSelectionScope == otherProfile.sessionSelectionScope)
  }

  @Test
  fun savesConnectionMetadataSeparatelyFromCredential() = runTest {
    val profiles = FakeProfileStore()
    val credentials = FakeCredentialStore()
    val repository = GatewayProfileRepository(profiles, credentials)
    val profile = GatewayProfile(
      address = "https://agent.example/hermes/",
      authMode = GatewayAuthMode.TOKEN,
      allowInsecure = false,
    )

    repository.save(profile, SecretValue("private-token"))

    assertEquals(profile, profiles.profile)
    assertEquals(
      StoredGatewayAuth.StaticToken(SecretValue("private-token")),
      credentials.auth,
    )
    assertFalse(profiles.serializedValues().contains("private-token"))
    assertEquals(GatewayConnection(profile, SecretValue("private-token")), repository.load())
  }

  @Test
  fun secureOnlyPolicyRejectsSavingCleartextProfiles() = runTest {
    val profiles = FakeProfileStore()
    val credentials = FakeCredentialStore()
    val repository = GatewayProfileRepository(profiles, credentials, false)
    val profile = GatewayProfile("http://127.0.0.1:8765", GatewayAuthMode.TOKEN, true)
    val result = runCatching { repository.save(profile, SecretValue("token")) }
    assertTrue(result.exceptionOrNull() is InsecureEndpointException)
    val legacy = GatewayProfile("https://agent.example", GatewayAuthMode.TOKEN, true)
    val legacyResult = runCatching { repository.save(legacy, SecretValue("token")) }
    assertTrue(legacyResult.exceptionOrNull() is InsecureEndpointException)
    assertNull(profiles.profile)
    assertNull(credentials.auth)
  }

  @Test
  fun secureOnlyPolicyRejectsLoadedCleartextProfiles() = runTest {
    val profiles = FakeProfileStore().apply {
      profile = GatewayProfile("http://gateway.local", GatewayAuthMode.TOKEN, true)
    }
    val credentials = FakeCredentialStore().apply {
      auth = StoredGatewayAuth.StaticToken(SecretValue("token"))
    }
    val repository = GatewayProfileRepository(profiles, credentials, false)
    val result = runCatching { repository.load() }
    assertTrue(result.exceptionOrNull() is InsecureEndpointException)
    assertEquals(0, credentials.loadCount)
    profiles.profile = GatewayProfile("http://127.0.0.1", GatewayAuthMode.OAUTH, true)
    credentials.auth = StoredGatewayAuth.OAuth(testOAuthTokens())
    val debugRepository = GatewayProfileRepository(profiles, credentials, true)
    val oauthResult = runCatching { debugRepository.load() }
    assertTrue(oauthResult.exceptionOrNull() is InsecureEndpointException)
    assertEquals(0, credentials.loadCount)
  }

  @Test
  fun debugPolicyAllowsExplicitCleartextProfiles() = runTest {
    val profiles = FakeProfileStore()
    val repository = GatewayProfileRepository(profiles, FakeCredentialStore(), true)
    val denied = GatewayProfile("http://127.0.0.1", GatewayAuthMode.TOKEN, false)
    val deniedResult = runCatching { repository.save(denied, SecretValue("token")) }
    assertTrue(deniedResult.exceptionOrNull() is InsecureEndpointException)
    val oauth = GatewayProfile("http://127.0.0.1", GatewayAuthMode.OAUTH, true)
    val oauthResult = runCatching { repository.save(oauth, StoredGatewayAuth.OAuth(testOAuthTokens())) }
    assertTrue(oauthResult.exceptionOrNull() is InsecureEndpointException)
    val profile = GatewayProfile("http://gateway.local", GatewayAuthMode.TOKEN, true)
    repository.save(profile, SecretValue("token"))
    assertEquals(profile, profiles.profile)
  }

  @Test
  fun clearingRepositoryRemovesMetadataAndCredential() = runTest {
    val profiles = FakeProfileStore()
    val credentials = FakeCredentialStore()
    val repository = GatewayProfileRepository(profiles, credentials)
    repository.save(
      GatewayProfile("https://agent.example/", GatewayAuthMode.TICKET, false),
      SecretValue("single-use-ticket"),
    )

    repository.clear()

    assertNull(repository.load())
    assertNull(profiles.profile)
    assertNull(credentials.auth)
    assertTrue(credentials.clearCount > 0)
  }

  private fun testOAuthTokens() = OAuthTokenSet(
    accessToken = SecretValue("access"),
    refreshToken = SecretValue("refresh"),
    expiresAt = Instant.MAX,
    provider = "provider",
    userId = "user",
  )

  private class FakeProfileStore : ProfileStore {
    var profile: GatewayProfile? = null

    override suspend fun load(): GatewayProfile? = profile

    override suspend fun save(profile: GatewayProfile) {
      this.profile = profile
    }

    override suspend fun clear() {
      profile = null
    }

    fun serializedValues(): String = profile.toString()
  }

  private class FakeCredentialStore : CredentialStore {
    var auth: StoredGatewayAuth? = null
    var clearCount: Int = 0
    var loadCount: Int = 0

    override suspend fun load(): StoredGatewayAuth? {
      loadCount += 1
      return auth
    }

    override suspend fun save(auth: StoredGatewayAuth) {
      this.auth = auth
    }

    override suspend fun clear() {
      clearCount += 1
      auth = null
    }
  }
}