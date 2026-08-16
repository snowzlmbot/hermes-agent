package com.snowzlmbot.hermes.mobile.core

import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface RestCredential {
  data class StaticToken(val value: SecretValue) : RestCredential {
    override fun toString(): String = "RestCredential.StaticToken(REDACTED)"
  }

  data class OAuthBearer(val value: SecretValue) : RestCredential {
    override fun toString(): String = "RestCredential.OAuthBearer(REDACTED)"
  }
}

class GatewayHttpException(
  val statusCode: Int,
  message: String,
) : IllegalStateException(message)

data class AudioTranscription(
  val transcript: String,
  val provider: String,
)

data class SynthesizedAudio(
  val dataUrl: String,
  val mimeType: String,
  val provider: String,
)

data class SessionUpdateResult(
  val title: String,
  val archived: Boolean,
  val pinned: Boolean,
)

class GatewayRestClient(
  private val endpoint: GatewayEndpoint,
  private val credential: RestCredential? = null,
  private val httpClient: OkHttpClient = OkHttpClient(),
  private val credentialProvider: RestCredentialProvider? = null,
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun status(): GatewayStatus {
    val body = execute(
      Request.Builder()
        .url(endpoint.httpBaseUrl.newBuilder().addPathSegments("api/status").build())
        .get()
        .build(),
      authenticate = false,
    )
    return GatewayStatus.parse(parseObject(body))
  }

  suspend fun nativeOAuthProviders(): List<NativeOAuthProvider> {
    val body = execute(
      Request.Builder()
        .url(endpoint.httpBaseUrl.newBuilder().addPathSegments("api/auth/providers").build())
        .get()
        .build(),
      authenticate = false,
    )
    return NativeOAuthProvider.parseList(json.parseToJsonElement(body))
  }

  suspend fun mintWebSocketTicket(): SecretValue {
    val result = postJson("api/auth/ws-ticket", buildJsonObject {}, authenticate = true)
    return result.string("ticket")?.takeIf(String::isNotBlank)?.let(::SecretValue)
      ?: throw ProtocolException("Gateway did not return a WebSocket ticket")
  }

  suspend fun listSessions(includeArchived: Boolean = false): List<SessionSummary> {
    val url = endpoint.httpBaseUrl.newBuilder()
      .addPathSegments("api/sessions")
      .addQueryParameter("order", "recent")
      .addQueryParameter("archived", if (includeArchived) "include" else "exclude")
      .build()
    val body = execute(
      Request.Builder()
        .url(url)
        .get()
        .build(),
      authenticate = true,
    )
    return GatewayProtocol.parseSessionList(parseObject(body))
  }

  suspend fun updateSession(
    storedId: String,
    title: String? = null,
    archived: Boolean? = null,
    pinned: Boolean? = null,
  ): SessionUpdateResult {
    val cleanId = storedId.trim()
    require(cleanId.isNotEmpty()) { "Stored session id is required" }
    require(title != null || archived != null || pinned != null) { "At least one update is required" }
    val body = buildJsonObject {
      title?.trim()?.let { put("title", it) }
      archived?.let { put("archived", it) }
      pinned?.let { put("pinned", it) }
    }
    val result = parseObject(
      execute(
        Request.Builder()
          .url(sessionUrl(cleanId))
          .patch(body.toString().toRequestBody(JSON_MEDIA_TYPE))
          .build(),
        authenticate = true,
      ),
    )
    return SessionUpdateResult(
      title = result.string("title").orEmpty(),
      archived = result.boolean("archived") ?: archived ?: false,
      pinned = result.boolean("pinned") ?: pinned ?: false,
    )
  }

  suspend fun deleteSession(storedId: String) {
    val cleanId = storedId.trim()
    require(cleanId.isNotEmpty()) { "Stored session id is required" }
    execute(
      Request.Builder()
        .url(sessionUrl(cleanId))
        .delete()
        .build(),
      authenticate = true,
    )
  }

  suspend fun exchangeNativeCode(code: String, verifier: String): OAuthTokenSet {
    if (code.isBlank() || verifier.isBlank()) throw NativeAuthException("OAuth code and verifier are required")
    val result = postJson(
      "auth/native/token",
      buildJsonObject {
        put("code", code)
        put("code_verifier", verifier)
      },
      authenticate = false,
    )
    return parseTokenSet(result)
  }

  suspend fun refreshNativeToken(refreshToken: SecretValue, provider: String): OAuthTokenSet {
    val result = postJson(
      "auth/native/refresh",
      buildJsonObject {
        put("refresh_token", refreshToken.reveal())
        provider.takeIf(String::isNotBlank)?.let { put("provider", it) }
      },
      authenticate = false,
    )
    return parseTokenSet(result)
  }

  suspend fun transcribeAudio(dataUrl: String, mimeType: String): AudioTranscription {
    if (!dataUrl.startsWith("data:audio/") && !dataUrl.startsWith("data:video/webm")) {
      throw IllegalArgumentException("Audio must be a base64 data URL")
    }
    val result = postJson(
      "api/audio/transcribe",
      buildJsonObject {
        put("data_url", dataUrl)
        put("mime_type", mimeType)
      },
      authenticate = true,
    )
    return AudioTranscription(
      transcript = result.string("transcript").orEmpty(),
      provider = result.string("provider").orEmpty(),
    )
  }

  suspend fun speakText(text: String): SynthesizedAudio {
    val cleanText = text.trim()
    require(cleanText.isNotEmpty()) { "Speech text is required" }
    val result = postJson(
      "api/audio/speak",
      buildJsonObject { put("text", cleanText) },
      authenticate = true,
    )
    val dataUrl = result.string("data_url")?.takeIf(String::isNotBlank)
      ?: throw ProtocolException("Speech response is missing audio")
    return SynthesizedAudio(
      dataUrl = dataUrl,
      mimeType = result.string("mime_type").orEmpty(),
      provider = result.string("provider").orEmpty(),
    )
  }

  private suspend fun postJson(
    path: String,
    body: JsonObject,
    authenticate: Boolean,
  ): JsonObject {
    val request = Request.Builder()
      .url(endpoint.httpBaseUrl.newBuilder().addPathSegments(path).build())
      .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
      .build()
    return parseObject(execute(request, authenticate))
  }

  private fun Request.Builder.applyCredential(current: RestCredential?) {
    removeHeader("Authorization")
    removeHeader("X-Hermes-Session-Token")
    when (current) {
      is RestCredential.StaticToken -> {
        header("X-Hermes-Session-Token", current.value.reveal())
        header("Authorization", "Bearer ${current.value.reveal()}")
      }
      is RestCredential.OAuthBearer -> header("Authorization", "Bearer ${current.value.reveal()}")
      null -> Unit
    }
  }

  private fun sessionUrl(storedId: String) = endpoint.httpBaseUrl.newBuilder()
    .addPathSegments("api/sessions")
    .addPathSegment(storedId)
    .build()

  private suspend fun execute(request: Request, authenticate: Boolean): String {
    val initialCredential = if (authenticate) {
      credentialProvider?.credential() ?: credential
    } else {
      null
    }
    return try {
      executeOnce(request.withCredential(initialCredential))
    } catch (error: GatewayHttpException) {
      if (error.statusCode != 401 || !authenticate || credentialProvider == null) throw error
      val refreshedCredential = credentialProvider.refreshAfterUnauthorized(initialCredential) ?: throw error
      executeOnce(request.withCredential(refreshedCredential))
    }
  }

  private fun Request.withCredential(current: RestCredential?): Request = newBuilder()
    .apply { applyCredential(current) }
    .build()

  private suspend fun executeOnce(request: Request): String = suspendCancellableCoroutine { continuation ->
    val call = httpClient.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : okhttp3.Callback {
      override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
        if (continuation.isActive) continuation.resumeWithException(e)
      }

      override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
        response.use {
          try {
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
              val detail = runCatching { parseObject(responseBody).string("detail") }.getOrNull()
              throw GatewayHttpException(
                statusCode = response.code,
                message = detail?.take(300) ?: "Gateway request failed with HTTP ${response.code}",
              )
            }
            if (continuation.isActive) continuation.resume(responseBody)
          } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
          }
        }
      }
    })
  }

  private fun parseObject(raw: String): JsonObject = runCatching {
    json.parseToJsonElement(raw) as JsonObject
  }.getOrElse { throw ProtocolException("Gateway returned an invalid JSON object") }

  private fun parseTokenSet(result: JsonObject): OAuthTokenSet {
    val access = result.string("access_token")?.takeIf(String::isNotBlank)
      ?: throw ProtocolException("Gateway token response is missing access token")
    val refresh = result.string("refresh_token")?.takeIf(String::isNotBlank)
      ?: throw ProtocolException("Gateway token response is missing refresh token")
    return OAuthTokenSet(
      accessToken = SecretValue(access),
      refreshToken = SecretValue(refresh),
      expiresAt = Instant.ofEpochSecond(result.long("expires_at") ?: 0L),
      provider = result.string("provider").orEmpty(),
      userId = result.string("user_id").orEmpty(),
    )
  }

  private companion object {
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}