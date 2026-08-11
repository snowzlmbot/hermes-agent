package com.snowzlmbot.hermes.mobile.app

import com.snowzlmbot.hermes.mobile.core.GatewayAuthMode
import com.snowzlmbot.hermes.mobile.core.GatewayAuthCoordinator
import com.snowzlmbot.hermes.mobile.core.GatewayConnection
import com.snowzlmbot.hermes.mobile.core.GatewayProfile
import com.snowzlmbot.hermes.mobile.core.GatewayProfileRepository
import com.snowzlmbot.hermes.mobile.core.GatewayRestClient
import com.snowzlmbot.hermes.mobile.core.GatewaySocketClient
import com.snowzlmbot.hermes.mobile.core.RestCredential
import com.snowzlmbot.hermes.mobile.core.SecretValue
import com.snowzlmbot.hermes.mobile.core.StoredGatewayAuth
import com.snowzlmbot.hermes.mobile.feature.HermesMobileRuntime
import com.snowzlmbot.hermes.mobile.feature.RestMobileSessionSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AppGraph(
  private val connections: GatewayProfileRepository,
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

  suspend fun saveConnection(profile: GatewayProfile, secret: SecretValue) {
    invalidateActiveCoordinator()
    connections.save(profile, secret)
  }

  suspend fun saveConnection(profile: GatewayProfile, auth: StoredGatewayAuth) {
    invalidateActiveCoordinator()
    connections.save(profile, auth)
  }

  suspend fun clearConnection() {
    val coordinator = authCoordinatorMutex.withLock {
      activeAuthCoordinator.also { activeAuthCoordinator = null }
    }
    if (coordinator != null) {
      coordinator.logout()
    } else {
      connections.clear()
    }
  }

  suspend fun runtime(connection: GatewayConnection): HermesMobileRuntime {
    val endpoint = connection.endpoint()
    if (connection.profile.authMode == GatewayAuthMode.OAUTH) {
      val auth = authCoordinatorFactory(connection, connections)
      replaceActiveCoordinator(auth)
      return HermesMobileRuntime(
        socket = GatewaySocketClient(
          endpoint = endpoint,
          credentialProvider = auth::socketCredential,
        ),
        sessions = RestMobileSessionSource(auth.restClient()),
      )
    }
    replaceActiveCoordinator(null)
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
    return HermesMobileRuntime(socket, RestMobileSessionSource(rest))
  }

  private suspend fun replaceActiveCoordinator(next: GatewayAuthCoordinator?) {
    val previous = authCoordinatorMutex.withLock {
      activeAuthCoordinator.also { activeAuthCoordinator = next }
    }
    if (previous !== next) previous?.invalidate()
  }

  private suspend fun invalidateActiveCoordinator() {
    replaceActiveCoordinator(null)
  }
}
