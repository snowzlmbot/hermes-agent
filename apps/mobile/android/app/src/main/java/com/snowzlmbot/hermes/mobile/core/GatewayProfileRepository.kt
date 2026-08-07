package com.snowzlmbot.hermes.mobile.core

enum class GatewayAuthMode {
  TOKEN,
  TICKET,
}

data class GatewayProfile(
  val address: String,
  val authMode: GatewayAuthMode,
  val allowInsecure: Boolean,
)

data class GatewayConnection(
  val profile: GatewayProfile,
  val secret: SecretValue,
) {
  fun endpoint(): GatewayEndpoint = GatewayEndpoint.parse(profile.address, profile.allowInsecure)

  fun credential(): GatewayCredential = when (profile.authMode) {
    GatewayAuthMode.TOKEN -> GatewayCredential.Token(secret)
    GatewayAuthMode.TICKET -> GatewayCredential.Ticket(secret)
  }

  override fun toString(): String = "GatewayConnection(profile=$profile, secret=REDACTED)"
}

interface ProfileStore {
  suspend fun load(): GatewayProfile?
  suspend fun save(profile: GatewayProfile)
  suspend fun clear()
}

interface CredentialStore {
  suspend fun load(): SecretValue?
  suspend fun save(secret: SecretValue)
  suspend fun clear()
}

class GatewayProfileRepository(
  private val profileStore: ProfileStore,
  private val credentialStore: CredentialStore,
) {
  suspend fun load(): GatewayConnection? {
    val profile = profileStore.load() ?: return null
    val secret = credentialStore.load() ?: return null
    return GatewayConnection(profile, secret)
  }

  suspend fun save(profile: GatewayProfile, secret: SecretValue) {
    GatewayEndpoint.parse(profile.address, profile.allowInsecure)
    credentialStore.save(secret)
    profileStore.save(profile)
  }

  suspend fun clear() {
    credentialStore.clear()
    profileStore.clear()
  }
}