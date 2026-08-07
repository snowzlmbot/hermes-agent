package com.snowzlmbot.hermes.mobile.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class EndpointValidationException(message: String) : IllegalArgumentException(message)

class InsecureEndpointException(message: String) : IllegalArgumentException(message)

class SecretValue(private val value: String) {
  init {
    require(value.isNotBlank()) { "Secret value must not be blank" }
  }

  internal fun reveal(): String = value

  override fun equals(other: Any?): Boolean = other is SecretValue && value == other.value

  override fun hashCode(): Int = value.hashCode()

  override fun toString(): String = "SecretValue(REDACTED)"
}

sealed interface GatewayCredential {
  val secret: SecretValue
  val queryName: String

  data class Token(override val secret: SecretValue) : GatewayCredential {
    override val queryName: String = "token"

    override fun toString(): String = "GatewayCredential.Token(REDACTED)"
  }

  data class Ticket(override val secret: SecretValue) : GatewayCredential {
    override val queryName: String = "ticket"

    override fun toString(): String = "GatewayCredential.Ticket(REDACTED)"
  }
}

class GatewayWebSocketUrl internal constructor(
  private val transportUrl: HttpUrl,
  private val webSocketScheme: String,
) {
  val encodedQuery: String?
    get() = transportUrl.encodedQuery

  fun queryParameter(name: String): String? = transportUrl.queryParameter(name)

  override fun toString(): String = buildString {
    append(webSocketScheme)
    append(':')
    append(transportUrl.toString().substringAfter(':'))
  }
}

class GatewayEndpoint private constructor(
  val httpBaseUrl: HttpUrl,
) {
  fun webSocketUrl(credential: GatewayCredential? = null): GatewayWebSocketUrl {
    val builder = httpBaseUrl.newBuilder().addPathSegments("api/ws")
    if (credential != null) {
      builder.setQueryParameter(credential.queryName, credential.secret.reveal())
    }
    return GatewayWebSocketUrl(
      transportUrl = builder.build(),
      webSocketScheme = if (httpBaseUrl.isHttps) "wss" else "ws",
    )
  }

  companion object {
    fun parse(raw: String, allowInsecure: Boolean = false): GatewayEndpoint {
      val value = raw.trim()
      if (value.isEmpty()) throw EndpointValidationException("Gateway address is required")

      val withScheme = if (SCHEME_PREFIX.matches(value)) value else "https://$value"
      val normalizedScheme =
        when {
          withScheme.startsWith("ws://", ignoreCase = true) -> "http://${withScheme.substringAfter("://")}"
          withScheme.startsWith("wss://", ignoreCase = true) -> "https://${withScheme.substringAfter("://")}"
          else -> withScheme
        }
      val parsed = normalizedScheme.toHttpUrlOrNull()
        ?: throw EndpointValidationException("Enter a valid HTTP or HTTPS gateway address")
      if (parsed.scheme != "http" && parsed.scheme != "https") {
        throw EndpointValidationException("Gateway address must use HTTP or HTTPS")
      }
      if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
        throw EndpointValidationException("Credentials must not be embedded in the gateway address")
      }
      if (parsed.query != null || parsed.fragment != null) {
        throw EndpointValidationException("Gateway address must not contain a query or fragment")
      }
      if (!parsed.isHttps && !allowInsecure && !isLoopback(parsed.host)) {
        throw InsecureEndpointException("Cleartext gateways require explicit consent")
      }

      val base = parsed.newBuilder().apply {
        if (!parsed.encodedPath.endsWith('/')) addPathSegment("")
      }.build()
      return GatewayEndpoint(base)
    }

    private fun isLoopback(host: String): Boolean =
      host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"

    private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://.*$")
  }
}