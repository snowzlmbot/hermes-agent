package com.snowzlmbot.hermes.mobile.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProfileRepositoryTest {
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
    assertEquals(SecretValue("private-token"), credentials.secret)
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
    assertNull(credentials.secret)
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
    var secret: SecretValue? = null
    var clearCount: Int = 0

    override suspend fun load(): SecretValue? = secret

    override suspend fun save(secret: SecretValue) {
      this.secret = secret
    }

    override suspend fun clear() {
      clearCount += 1
      secret = null
    }
  }
}