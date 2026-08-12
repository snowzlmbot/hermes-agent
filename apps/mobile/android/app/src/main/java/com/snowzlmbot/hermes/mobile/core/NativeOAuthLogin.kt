package com.snowzlmbot.hermes.mobile.core

import java.net.URI
import okhttp3.HttpUrl

data class NativeOAuthDiscovery(
  val endpoint: GatewayEndpoint,
  val providers: List<NativeOAuthProvider>,
)

class PendingNativeOAuth internal constructor(
  internal val endpoint: GatewayEndpoint,
  internal val pkce: NativePkce,
  val provider: String,
  val authorizationUrl: HttpUrl,
) {
  override fun toString(): String =
    "PendingNativeOAuth(provider=$provider, authorizationUrl=REDACTED, pkce=REDACTED)"
}

class NativeOAuthLogin internal constructor(
  private val allowInsecureLoopbackForTesting: Boolean = false,
) {
  suspend fun discover(address: String): NativeOAuthDiscovery {
    val endpoint = GatewayEndpoint.parse(address)
    if (!endpoint.httpBaseUrl.isHttps && !allowInsecureLoopbackForTesting) {
      throw NativeAuthException("OAuth requires HTTPS in this build")
    }
    val client = GatewayRestClient(endpoint)
    val status = client.status()
    if (status.loginStrategy != GatewayLoginStrategy.NATIVE_OAUTH) {
      throw NativeAuthException("Gateway does not advertise native mobile OAuth")
    }
    val advertised = status.authProviders.toSet()
    val providers = client.nativeOAuthProviders().filter { it.name in advertised }
    if (providers.isEmpty()) {
      throw NativeAuthException("Gateway has no native OAuth provider available")
    }
    return NativeOAuthDiscovery(endpoint, providers)
  }

  fun begin(discovery: NativeOAuthDiscovery, provider: String): PendingNativeOAuth {
    val selected = provider.trim()
    if (discovery.providers.none { it.name == selected }) {
      throw NativeAuthException("OAuth provider is not advertised by the gateway")
    }
    val pkce = NativePkce.create()
    return PendingNativeOAuth(
      endpoint = discovery.endpoint,
      pkce = pkce,
      provider = selected,
      authorizationUrl = NativePkce.authorizeUrl(
        endpoint = discovery.endpoint,
        challenge = pkce.challenge,
        redirectUri = NativePkce.MOBILE_REDIRECT_URI,
        state = pkce.state,
        provider = selected,
      ),
    )
  }

  suspend fun complete(pending: PendingNativeOAuth, callback: URI): OAuthTokenSet {
    val code = NativePkce.parseCallback(callback, expectedState = pending.pkce.state)
    return GatewayRestClient(pending.endpoint).exchangeNativeCode(
      code = code,
      verifier = pending.pkce.verifier,
    )
  }
}
