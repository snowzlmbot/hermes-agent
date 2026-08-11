package com.snowzlmbot.hermes.mobile.core

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

    override suspend fun load(): StoredGatewayAuth? = auth

    override suspend fun save(auth: StoredGatewayAuth) {
      this.auth = auth
    }

    override suspend fun clear() {
      clearCount += 1
      auth = null
    }
  }
}