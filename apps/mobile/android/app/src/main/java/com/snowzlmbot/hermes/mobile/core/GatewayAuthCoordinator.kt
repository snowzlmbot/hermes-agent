package com.snowzlmbot.hermes.mobile.core

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

interface RestCredentialProvider {
  suspend fun credential(): RestCredential?
  suspend fun refreshAfterUnauthorized(rejected: RestCredential?): RestCredential?
}

class GatewaySignedOutException : IllegalStateException("Gateway credentials have been cleared")

class GatewayAuthCoordinator(
  connection: GatewayConnection,
  private val repository: GatewayProfileRepository,
  private val httpClient: OkHttpClient = OkHttpClient(),
  private val now: () -> Instant = Instant::now,
) : RestCredentialProvider {
  private val stateMutex = Mutex()
  private var activeConnection: GatewayConnection? = connection
  private var refreshInFlight: CompletableDeferred<OAuthTokenSet>? = null
  private var authGeneration: Long = 0

  private val publicRestClient = GatewayRestClient(
    endpoint = connection.endpoint(),
    httpClient = httpClient,
  )
  private val authenticatedRestClient = GatewayRestClient(
    endpoint = connection.endpoint(),
    httpClient = httpClient,
    credentialProvider = this,
  )

  fun restClient(): GatewayRestClient = authenticatedRestClient

  suspend fun nativeOAuthProviders(): List<NativeOAuthProvider> = publicRestClient.nativeOAuthProviders()

  override suspend fun credential(): RestCredential? = when (val auth = currentAuth()) {
    is StoredGatewayAuth.StaticToken -> RestCredential.StaticToken(auth.token)
    is StoredGatewayAuth.Ticket -> null
    is StoredGatewayAuth.OAuth -> RestCredential.OAuthBearer(freshOAuthTokens().accessToken)
  }

  override suspend fun refreshAfterUnauthorized(rejected: RestCredential?): RestCredential? {
    val rejectedBearer = (rejected as? RestCredential.OAuthBearer)?.value ?: return null
    return RestCredential.OAuthBearer(
      freshOAuthTokens(rejectedAccessToken = rejectedBearer).accessToken,
    )
  }

  suspend fun refreshOAuth(): OAuthTokenSet {
    val tokens = currentOAuthTokens()
    return freshOAuthTokens(rejectedAccessToken = tokens.accessToken)
  }

  suspend fun socketCredential(): GatewayCredential = when (val auth = currentAuth()) {
    is StoredGatewayAuth.StaticToken -> GatewayCredential.Token(auth.token)
    is StoredGatewayAuth.Ticket -> GatewayCredential.Ticket(auth.ticket)
    is StoredGatewayAuth.OAuth -> {
      freshOAuthTokens()
      GatewayCredential.Ticket(authenticatedRestClient.mintWebSocketTicket())
    }
  }

  suspend fun logout() {
    invalidate(clearRepository = true)
  }

  suspend fun invalidate() {
    invalidate(clearRepository = false)
  }

  private suspend fun invalidate(clearRepository: Boolean) {
    stateMutex.lock()
    try {
      if (activeConnection == null) return
      authGeneration += 1
      activeConnection = null
      refreshInFlight?.completeExceptionally(GatewaySignedOutException())
      refreshInFlight = null
      if (clearRepository) repository.clear()
    } finally {
      stateMutex.unlock()
    }
  }

  private suspend fun currentAuth(): StoredGatewayAuth = stateMutex.withLock {
    activeConnection?.auth ?: throw GatewaySignedOutException()
  }

  private suspend fun currentOAuthTokens(): OAuthTokenSet = stateMutex.withLock {
    val auth = activeConnection?.auth ?: throw GatewaySignedOutException()
    (auth as? StoredGatewayAuth.OAuth)?.tokens
      ?: throw IllegalStateException("Gateway connection is not using OAuth")
  }

  private suspend fun freshOAuthTokens(rejectedAccessToken: SecretValue? = null): OAuthTokenSet {
    val decision = stateMutex.withLock {
      val connection = activeConnection ?: throw GatewaySignedOutException()
      val tokens = (connection.auth as? StoredGatewayAuth.OAuth)?.tokens
        ?: throw IllegalStateException("Gateway connection is not using OAuth")
      refreshInFlight?.let { return@withLock RefreshDecision.Wait(it) }

      val requiresRefresh = when (rejectedAccessToken) {
        null -> tokens.needsRefresh(now())
        else -> tokens.accessToken == rejectedAccessToken
      }
      if (!requiresRefresh) {
        RefreshDecision.Ready(tokens)
      } else {
        val deferred = CompletableDeferred<OAuthTokenSet>()
        refreshInFlight = deferred
        RefreshDecision.Execute(
          deferred = deferred,
          profile = connection.profile,
          tokens = tokens,
          generation = authGeneration,
        )
      }
    }

    return when (decision) {
      is RefreshDecision.Ready -> decision.tokens
      is RefreshDecision.Wait -> decision.deferred.await()
      is RefreshDecision.Execute -> executeRefresh(decision)
    }
  }

  private suspend fun executeRefresh(work: RefreshDecision.Execute): OAuthTokenSet {
    return try {
      val refreshed = publicRestClient.refreshNativeToken(
        refreshToken = work.tokens.refreshToken,
        provider = work.tokens.provider,
      )
      persistRefresh(work, refreshed)
      work.deferred.complete(refreshed)
      refreshed
    } catch (error: Throwable) {
      work.deferred.completeExceptionally(error)
      throw error
    } finally {
      stateMutex.withLock {
        if (refreshInFlight === work.deferred) refreshInFlight = null
      }
    }
  }

  private suspend fun persistRefresh(work: RefreshDecision.Execute, refreshed: OAuthTokenSet) {
    stateMutex.lock()
    try {
      if (authGeneration != work.generation || activeConnection == null) {
        throw GatewaySignedOutException()
      }
      val updatedAuth = StoredGatewayAuth.OAuth(refreshed)
      repository.save(work.profile, updatedAuth)
      activeConnection = GatewayConnection(work.profile, updatedAuth)
    } finally {
      stateMutex.unlock()
    }
  }

  private sealed interface RefreshDecision {
    data class Ready(val tokens: OAuthTokenSet) : RefreshDecision
    data class Wait(val deferred: CompletableDeferred<OAuthTokenSet>) : RefreshDecision
    data class Execute(
      val deferred: CompletableDeferred<OAuthTokenSet>,
      val profile: GatewayProfile,
      val tokens: OAuthTokenSet,
      val generation: Long,
    ) : RefreshDecision
  }
}
