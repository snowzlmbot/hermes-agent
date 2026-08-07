package com.snowzlmbot.hermes.mobile.core

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AndroidProfileStore(context: Context) : ProfileStore {
  private val preferences = context.getSharedPreferences("hermes.mobile.connection", Context.MODE_PRIVATE)

  override suspend fun load(): GatewayProfile? {
    val address = preferences.getString(KEY_ADDRESS, null)?.takeIf(String::isNotBlank) ?: return null
    val authMode = preferences.getString(KEY_AUTH_MODE, null)
      ?.let { runCatching { GatewayAuthMode.valueOf(it) }.getOrNull() }
      ?: return null
    return GatewayProfile(
      address = address,
      authMode = authMode,
      allowInsecure = preferences.getBoolean(KEY_ALLOW_INSECURE, false),
    )
  }

  override suspend fun save(profile: GatewayProfile) {
    preferences.edit(commit = true) {
      putString(KEY_ADDRESS, profile.address)
      putString(KEY_AUTH_MODE, profile.authMode.name)
      putBoolean(KEY_ALLOW_INSECURE, profile.allowInsecure)
    }
  }

  override suspend fun clear() {
    preferences.edit(commit = true) { clear() }
  }

  private companion object {
    const val KEY_ADDRESS = "address"
    const val KEY_AUTH_MODE = "auth_mode"
    const val KEY_ALLOW_INSECURE = "allow_insecure"
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

  override suspend fun load(): SecretValue? =
    preferences.getString(KEY_SECRET, null)?.takeIf(String::isNotBlank)?.let(::SecretValue)

  override suspend fun save(secret: SecretValue) {
    preferences.edit(commit = true) { putString(KEY_SECRET, secret.reveal()) }
  }

  override suspend fun clear() {
    preferences.edit(commit = true) { remove(KEY_SECRET) }
  }

  private companion object {
    const val KEY_SECRET = "gateway_credential"
  }
}