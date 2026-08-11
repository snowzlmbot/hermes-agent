package com.snowzlmbot.hermes.mobile.core

enum class GatewayAuthMode {
  TOKEN,
  TICKET,
  OAUTH,
}

data class GatewayProfile(
  val address: String,
  val authMode: GatewayAuthMode,
  val allowInsecure: Boolean,
)

sealed interface StoredGatewayAuth {
  data class StaticToken(val token: SecretValue) : StoredGatewayAuth {
    override fun toString(): String = "StoredGatewayAuth.StaticToken(REDACTED)"
  }

  data class Ticket(val ticket: SecretValue) : StoredGatewayAuth {
    override fun toString(): String = "StoredGatewayAuth.Ticket(REDACTED)"
  }

  data class OAuth(val tokens: OAuthTokenSet) : StoredGatewayAuth {
    override fun toString(): String = "StoredGatewayAuth.OAuth(tokens=$tokens)"
  }
}

data class GatewayConnection(
  val profile: GatewayProfile,
  val auth: StoredGatewayAuth,
) {
  constructor(profile: GatewayProfile, secret: SecretValue) : this(
    profile = profile,
    auth = storedAuth(profile.authMode, secret),
  )

  val secret: SecretValue
    get() = when (val current = auth) {
      is StoredGatewayAuth.StaticToken -> current.token
      is StoredGatewayAuth.Ticket -> current.ticket
      is StoredGatewayAuth.OAuth -> current.tokens.accessToken
    }

  fun endpoint(): GatewayEndpoint = GatewayEndpoint.parse(profile.address, profile.allowInsecure)

  fun credential(): GatewayCredential = when (val current = auth) {
    is StoredGatewayAuth.StaticToken -> GatewayCredential.Token(current.token)
    is StoredGatewayAuth.Ticket -> GatewayCredential.Ticket(current.ticket)
    is StoredGatewayAuth.OAuth -> throw IllegalStateException(
      "OAuth WebSocket connections require a freshly minted single-use ticket",
    )
  }

  override fun toString(): String = "GatewayConnection(profile=$profile, auth=REDACTED)"
}

interface ProfileStore {
  suspend fun load(): GatewayProfile?
  suspend fun save(profile: GatewayProfile)
  suspend fun clear()
}

interface CredentialStore {
  suspend fun load(): StoredGatewayAuth?
  suspend fun save(auth: StoredGatewayAuth)
  suspend fun clear()
}

class GatewayProfileRepository(
  private val profileStore: ProfileStore,
  private val credentialStore: CredentialStore,
) {
  suspend fun load(): GatewayConnection? {
    val profile = profileStore.load() ?: return null
    val auth = credentialStore.load() ?: return null
    return GatewayConnection(profile, normalizeLoadedAuth(profile.authMode, auth))
  }

  suspend fun save(profile: GatewayProfile, secret: SecretValue) {
    save(profile, storedAuth(profile.authMode, secret))
  }

  suspend fun save(profile: GatewayProfile, auth: StoredGatewayAuth) {
    GatewayEndpoint.parse(profile.address, profile.allowInsecure)
    require(auth.matches(profile.authMode)) { "Stored credential does not match the gateway auth mode" }
    credentialStore.save(auth)
    profileStore.save(profile)
  }

  suspend fun clear() {
    credentialStore.clear()
    profileStore.clear()
  }

  private fun StoredGatewayAuth.matches(mode: GatewayAuthMode): Boolean = when (mode) {
    GatewayAuthMode.TOKEN -> this is StoredGatewayAuth.StaticToken
    GatewayAuthMode.TICKET -> this is StoredGatewayAuth.Ticket
    GatewayAuthMode.OAUTH -> this is StoredGatewayAuth.OAuth
  }

  private fun normalizeLoadedAuth(mode: GatewayAuthMode, auth: StoredGatewayAuth): StoredGatewayAuth = when {
    mode == GatewayAuthMode.TICKET && auth is StoredGatewayAuth.StaticToken -> StoredGatewayAuth.Ticket(auth.token)
    auth.matches(mode) -> auth
    else -> throw IllegalStateException("Stored credential does not match the gateway auth mode")
  }
}

private fun storedAuth(mode: GatewayAuthMode, secret: SecretValue): StoredGatewayAuth = when (mode) {
  GatewayAuthMode.TOKEN -> StoredGatewayAuth.StaticToken(secret)
  GatewayAuthMode.TICKET -> StoredGatewayAuth.Ticket(secret)
  GatewayAuthMode.OAUTH -> throw IllegalArgumentException("OAuth requires an access and refresh token set")
}
