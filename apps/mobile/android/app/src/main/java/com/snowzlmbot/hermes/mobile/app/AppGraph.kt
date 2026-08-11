package com.snowzlmbot.hermes.mobile.app

import com.snowzlmbot.hermes.mobile.core.GatewayAuthMode
import com.snowzlmbot.hermes.mobile.core.GatewayAuthCoordinator
import com.snowzlmbot.hermes.mobile.core.GatewayConnection
import com.snowzlmbot.hermes.mobile.core.GatewayProfile
import com.snowzlmbot.hermes.mobile.core.GatewayProfileRepository
import com.snowzlmbot.hermes.mobile.core.GatewayRestClient
import com.snowzlmbot.hermes.mobile.core.GatewaySocketClient
import com.snowzlmbot.hermes.mobile.core.NativeOAuthDiscovery
import com.snowzlmbot.hermes.mobile.core.NativeOAuthLogin
import com.snowzlmbot.hermes.mobile.core.OAuthTokenSet
import com.snowzlmbot.hermes.mobile.core.PendingNativeOAuth
import com.snowzlmbot.hermes.mobile.core.RestCredential
import com.snowzlmbot.hermes.mobile.core.SecretValue
import com.snowzlmbot.hermes.mobile.core.StoredGatewayAuth
import com.snowzlmbot.hermes.mobile.feature.HermesMobileRuntime
import com.snowzlmbot.hermes.mobile.feature.RestMobileSessionSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AppGraph(
  private val connections: GatewayProfileRepository,
  private val nativeOAuthLogin: NativeOAuthLogin = NativeOAuthLogin(),
  private val authCoordinatorFactory: (
    GatewayConnection,
    GatewayProfileRepository,
  ) -> GatewayAuthCoordinator = { connection, repository ->
    GatewayAuthCoordinator(connection, repository)
  },
) {
  private val authCoordinatorMutex = Mutex()
  private var activeAuthCoordinator: GatewayAuthCoordinator? = null

  suspend fun restoreConnection(): GatewayConnection? = connections.load()

  suspend fun discoverNativeOAuth(address: String): NativeOAuthDiscovery =
    nativeOAuthLogin.discover(address)

  fun beginNativeOAuth(
    discovery: NativeOAuthDiscovery,
    provider: String,
  ): PendingNativeOAuth = nativeOAuthLogin.begin(discovery, provider)

  suspend fun completeNativeOAuth(
    pending: PendingNativeOAuth,
    callback: java.net.URI,
  ): OAuthTokenSet = nativeOAuthLogin.complete(pending, callback)

  suspend fun saveConnection(profile: GatewayProfile, secret: SecretValue) {
    authCoordinatorMutex.withLock {
      activeAuthCoordinator?.invalidate()
      activeAuthCoordinator = null
      connections.save(profile, secret)
    }
  }

  suspend fun saveConnection(profile: GatewayProfile, auth: StoredGatewayAuth) {
    authCoordinatorMutex.withLock {
      activeAuthCoordinator?.invalidate()
      activeAuthCoordinator = null
      connections.save(profile, auth)
    }
  }

  suspend fun clearConnection() {
    authCoordinatorMutex.withLock {
      val coordinator = activeAuthCoordinator
      activeAuthCoordinator = null
      if (coordinator != null) {
        coordinator.logout()
      } else {
        connections.clear()
      }
    }
  }

  suspend fun runtime(connection: GatewayConnection): HermesMobileRuntime = authCoordinatorMutex.withLock {
    if (connections.load() != connection) {
      throw IllegalStateException("Gateway connection is no longer current")
    }
    val endpoint = connection.endpoint()
    activeAuthCoordinator?.invalidate()
    if (connection.profile.authMode == GatewayAuthMode.OAUTH) {
      val auth = authCoordinatorFactory(connection, connections)
      activeAuthCoordinator = auth
      return@withLock HermesMobileRuntime(
        rpc = GatewaySocketClient(
          endpoint = endpoint,
          credentialProvider = auth::socketCredential,
        ),
        sessions = RestMobileSessionSource(auth.restClient()),
      )
    }
    activeAuthCoordinator = null
    val restCredential = when (connection.profile.authMode) {
      GatewayAuthMode.TOKEN -> RestCredential.StaticToken(connection.secret)
      GatewayAuthMode.TICKET -> null
      GatewayAuthMode.OAUTH -> error("OAuth handled above")
    }
    val rest = GatewayRestClient(endpoint, restCredential)
    val socket = GatewaySocketClient(
      endpoint = endpoint,
      credentialProvider = { connection.credential() },
    )
    HermesMobileRuntime(socket, RestMobileSessionSource(rest))
  }
}
