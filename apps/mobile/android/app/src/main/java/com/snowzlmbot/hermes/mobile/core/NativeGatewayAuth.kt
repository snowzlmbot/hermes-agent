package com.snowzlmbot.hermes.mobile.core

import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Base64
import okhttp3.HttpUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom

enum class GatewayLoginStrategy {
  STATIC_TOKEN,
  NATIVE_OAUTH,
  UNSUPPORTED_INTERACTIVE,
}

data class GatewayStatus(
  val gatewayRunning: Boolean,
  val authRequired: Boolean,
  val authFlows: List<String>,
  val authProviders: List<String>,
) {
  val loginStrategy: GatewayLoginStrategy
    get() = when {
      !authRequired -> GatewayLoginStrategy.STATIC_TOKEN
      "native_pkce_mobile" in authFlows -> GatewayLoginStrategy.NATIVE_OAUTH
      else -> GatewayLoginStrategy.UNSUPPORTED_INTERACTIVE
    }

  companion object {
    fun parse(element: JsonElement): GatewayStatus {
      val value = element as? JsonObject
        ?: throw NativeAuthException("Gateway status must be a JSON object")
      return GatewayStatus(
        gatewayRunning = value["gateway_running"]?.jsonPrimitive?.booleanOrNull ?: false,
        authRequired = value["auth_required"]?.jsonPrimitive?.booleanOrNull ?: false,
        authFlows = value.stringList("auth_flows"),
        authProviders = value.stringList("auth_providers"),
      )
    }

    private fun JsonObject.stringList(key: String): List<String> =
      (get(key) as? JsonArray).orEmpty().mapNotNull { item ->
        runCatching { item.jsonPrimitive.content.trim() }.getOrNull()?.takeIf(String::isNotEmpty)
      }
  }
}

data class NativeOAuthProvider(
  val name: String,
  val displayName: String,
) {
  companion object {
    fun parseList(element: JsonElement): List<NativeOAuthProvider> {
      val root = element as? JsonObject ?: return emptyList()
      return (root["providers"] as? JsonArray).orEmpty().mapNotNull providerLoop@ { item ->
        val provider = item as? JsonObject ?: return@providerLoop null
        if (provider["supports_password"]?.jsonPrimitive?.booleanOrNull == true) {
          return@providerLoop null
        }
        val name = provider.string("name")?.trim()?.takeIf(String::isNotEmpty)
          ?: return@providerLoop null
        NativeOAuthProvider(
          name = name,
          displayName = provider.string("display_name")?.trim()?.takeIf(String::isNotEmpty) ?: name,
        )
      }.distinctBy(NativeOAuthProvider::name)
    }
  }
}

class NativeAuthException(message: String) : IllegalArgumentException(message)

class NativePkce private constructor(
  val verifier: String,
  val challenge: String,
  val state: String,
) {
  val method: String = "S256"

  override fun toString(): String = "NativePkce(REDACTED)"

  companion object {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    val MOBILE_REDIRECT_URI: URI = URI("com.snowzlmbot.hermes.mobile:/oauth/callback")

    fun create(): NativePkce = create(
      verifierBytes = ByteArray(32).also(random::nextBytes),
      stateBytes = ByteArray(24).also(random::nextBytes),
    )

    internal fun create(verifierBytes: ByteArray, stateBytes: ByteArray): NativePkce {
      require(verifierBytes.size >= 32) { "PKCE verifier requires at least 32 random bytes" }
      require(stateBytes.size >= 16) { "OAuth state requires at least 16 random bytes" }
      val verifier = encoder.encodeToString(verifierBytes)
      val challenge = encoder.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
      )
      return NativePkce(verifier, challenge, encoder.encodeToString(stateBytes))
    }

    fun authorizeUrl(
      endpoint: GatewayEndpoint,
      challenge: String,
      redirectUri: URI,
      state: String,
      provider: String? = null,
    ): HttpUrl {
      validateMobileRedirect(redirectUri)
      if (challenge.isBlank()) throw NativeAuthException("PKCE challenge is required")
      if (state.isBlank()) throw NativeAuthException("OAuth state is required")
      return endpoint.httpBaseUrl.newBuilder()
        .addPathSegments("auth/native/authorize")
        .addQueryParameter("code_challenge", challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("redirect_uri", redirectUri.toString())
        .addQueryParameter("state", state)
        .apply {
          provider?.trim()?.takeIf(String::isNotEmpty)?.let { addQueryParameter("provider", it) }
        }
        .build()
    }

    fun parseCallback(uri: URI, expectedState: String): String {
      validateMobileRedirect(uri)
      val queryPairs = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
        val split = part.split('=', limit = 2)
        split.firstOrNull()?.takeIf(String::isNotEmpty)?.let { name ->
          java.net.URLDecoder.decode(name, Charsets.UTF_8.name()) to
            java.net.URLDecoder.decode(split.getOrElse(1) { "" }, Charsets.UTF_8.name())
        }
      }
      val protectedParameters = setOf("code", "state", "error", "error_description")
      val duplicateProtectedParameter = queryPairs
        .groupingBy { it.first }
        .eachCount()
        .any { (name, count) -> name in protectedParameters && count > 1 }
      if (duplicateProtectedParameter) {
        throw NativeAuthException("OAuth callback contains duplicate security parameters")
      }
      val query = queryPairs.toMap()
      query["error"]?.let { error ->
        throw NativeAuthException(
          query["error_description"]?.let { "$error: $it" } ?: error,
        )
      }
      val code = query["code"]?.takeIf(String::isNotBlank)
        ?: throw NativeAuthException("OAuth callback is missing the authorization code")
      if (expectedState.isBlank() || query["state"] != expectedState) {
        throw NativeAuthException("OAuth callback state did not match")
      }
      return code
    }

    private fun validateMobileRedirect(uri: URI) {
      if (
        uri.scheme != MOBILE_REDIRECT_URI.scheme ||
        uri.path != MOBILE_REDIRECT_URI.path ||
        uri.rawAuthority != null ||
        uri.fragment != null
      ) {
        throw NativeAuthException("Native OAuth redirect is not registered for this application")
      }
    }
  }
}

data class OAuthTokenSet(
  val accessToken: SecretValue,
  val refreshToken: SecretValue,
  val expiresAt: Instant,
  val provider: String,
  val userId: String,
) {
  fun needsRefresh(now: Instant, skew: Duration = Duration.ofSeconds(60)): Boolean =
    !now.isBefore(expiresAt.minus(skew))

  override fun toString(): String =
    "OAuthTokenSet(accessToken=REDACTED, refreshToken=REDACTED, expiresAt=$expiresAt, " +
      "provider=$provider, userId=$userId)"
}