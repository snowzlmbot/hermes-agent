package com.snowzlmbot.hermes.mobile.core

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.time.Instant
import java.util.UUID

class AndroidProfileStore(context: Context) : ProfileStore {
  private val preferences = context.getSharedPreferences("hermes.mobile.connection", Context.MODE_PRIVATE)

  override suspend fun load(): GatewayProfile? {
    val address = preferences.getString(KEY_ADDRESS, null)?.takeIf(String::isNotBlank) ?: return null
    val authMode = preferences.getString(KEY_AUTH_MODE, null)
      ?.let { runCatching { GatewayAuthMode.valueOf(it) }.getOrNull() }
      ?: return null
    val profileId = preferences.getString(KEY_PROFILE_ID, null)
      ?.takeIf(String::isNotBlank)
      ?: UUID.randomUUID().toString().also { generated ->
        preferences.edit(commit = true) { putString(KEY_PROFILE_ID, generated) }
      }
    return GatewayProfile(
      address = address,
      authMode = authMode,
      allowInsecure = preferences.getBoolean(KEY_ALLOW_INSECURE, false),
      id = profileId,
    )
  }

  override suspend fun save(profile: GatewayProfile) {
    preferences.edit(commit = true) {
      putString(KEY_ADDRESS, profile.address)
      putString(KEY_AUTH_MODE, profile.authMode.name)
      putBoolean(KEY_ALLOW_INSECURE, profile.allowInsecure)
      putString(KEY_PROFILE_ID, profile.id)
    }
  }

  override suspend fun clear() {
    preferences.edit(commit = true) { clear() }
  }

  private companion object {
    const val KEY_ADDRESS = "address"
    const val KEY_AUTH_MODE = "auth_mode"
    const val KEY_ALLOW_INSECURE = "allow_insecure"
    const val KEY_PROFILE_ID = "profile_id"
  }
}

@Suppress("DEPRECATION")
class AndroidCredentialStore(context: Context) : CredentialStore {
  private val applicationContext = context.applicationContext
  private val masterKey = MasterKey.Builder(applicationContext)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()
  private val preferences = EncryptedSharedPreferences.create(
    applicationContext,
    "hermes.mobile.credentials",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
  )

  override suspend fun load(): StoredGatewayAuth? {
    val kind = preferences.getString(KEY_KIND, null)
    if (kind == null) {
      return preferences.getString(LEGACY_KEY_SECRET, null)
        ?.takeIf(String::isNotBlank)
        ?.let(::SecretValue)
        ?.let(StoredGatewayAuth::StaticToken)
    }
    return when (kind) {
      KIND_STATIC_TOKEN -> requiredSecret(KEY_ACCESS_TOKEN)?.let(StoredGatewayAuth::StaticToken)
      KIND_TICKET -> requiredSecret(KEY_ACCESS_TOKEN)?.let(StoredGatewayAuth::Ticket)
      KIND_OAUTH -> loadOAuth()
      else -> null
    }
  }

  override suspend fun save(auth: StoredGatewayAuth) {
    preferences.edit(commit = true) {
      clear()
      when (auth) {
        is StoredGatewayAuth.StaticToken -> {
          putString(KEY_KIND, KIND_STATIC_TOKEN)
          putString(KEY_ACCESS_TOKEN, auth.token.reveal())
        }
        is StoredGatewayAuth.Ticket -> {
          putString(KEY_KIND, KIND_TICKET)
          putString(KEY_ACCESS_TOKEN, auth.ticket.reveal())
        }
        is StoredGatewayAuth.OAuth -> {
          putString(KEY_KIND, KIND_OAUTH)
          putString(KEY_ACCESS_TOKEN, auth.tokens.accessToken.reveal())
          putString(KEY_REFRESH_TOKEN, auth.tokens.refreshToken.reveal())
          putLong(KEY_EXPIRES_AT, auth.tokens.expiresAt.epochSecond)
          putString(KEY_PROVIDER, auth.tokens.provider)
          putString(KEY_USER_ID, auth.tokens.userId)
        }
      }
    }
  }

  override suspend fun clear() {
    preferences.edit(commit = true) { clear() }
  }

  private fun loadOAuth(): StoredGatewayAuth.OAuth? {
    val accessToken = requiredSecret(KEY_ACCESS_TOKEN) ?: return null
    val refreshToken = requiredSecret(KEY_REFRESH_TOKEN) ?: return null
    if (!preferences.contains(KEY_EXPIRES_AT)) return null
    return StoredGatewayAuth.OAuth(
      OAuthTokenSet(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = Instant.ofEpochSecond(preferences.getLong(KEY_EXPIRES_AT, 0L)),
        provider = preferences.getString(KEY_PROVIDER, null).orEmpty(),
        userId = preferences.getString(KEY_USER_ID, null).orEmpty(),
      ),
    )
  }

  private fun requiredSecret(key: String): SecretValue? =
    preferences.getString(key, null)?.takeIf(String::isNotBlank)?.let(::SecretValue)

  private companion object {
    const val KEY_KIND = "credential_kind"
    const val KEY_ACCESS_TOKEN = "access_token"
    const val KEY_REFRESH_TOKEN = "refresh_token"
    const val KEY_EXPIRES_AT = "expires_at"
    const val KEY_PROVIDER = "provider"
    const val KEY_USER_ID = "user_id"
    const val LEGACY_KEY_SECRET = "gateway_credential"

    const val KIND_STATIC_TOKEN = "static_token"
    const val KIND_TICKET = "ticket"
    const val KIND_OAUTH = "oauth"
  }
}
