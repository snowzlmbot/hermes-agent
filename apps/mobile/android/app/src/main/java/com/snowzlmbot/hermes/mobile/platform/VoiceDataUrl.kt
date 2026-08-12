package com.snowzlmbot.hermes.mobile.platform

import java.util.Base64
import java.util.Locale

data class VoiceData(val mimeType: String, val bytes: ByteArray)

object VoiceDataUrl {
  private const val PREFIX = "data:"
  private const val MAX_ENCODED_CHARACTERS = 35_000_000

  fun encode(mimeType: String, bytes: ByteArray): String {
    val mime = normalizeMime(mimeType)
    validateBytes(bytes)
    return "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
  }

  fun decode(value: String): VoiceData {
    require(value.startsWith(PREFIX)) { "Voice response is not a data URL" }
    val comma = value.indexOf(',')
    require(comma > PREFIX.length) { "Voice response is malformed" }
    val header = value.substring(PREFIX.length, comma)
    val parts = header.split(';')
    val mime = normalizeMime(parts.firstOrNull().orEmpty())
    require(parts.drop(1).any { it.equals("base64", ignoreCase = true) }) {
      "Voice response is not base64 encoded"
    }
    val encoded = value.substring(comma + 1)
    require(encoded.length <= MAX_ENCODED_CHARACTERS) { "Voice audio is too large" }
    val bytes = try {
      Base64.getDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
      throw IllegalArgumentException("Voice response contains invalid base64")
    }
    validateBytes(bytes)
    return VoiceData(mime, bytes)
  }

  private fun normalizeMime(value: String): String {
    val mime = value.trim().lowercase(Locale.ROOT)
    require(mime.startsWith("audio/")) { "Voice MIME type is unsupported" }
    return mime
  }

  private fun validateBytes(bytes: ByteArray) {
    require(bytes.isNotEmpty()) { "Voice audio is empty" }
    require(bytes.size <= AndroidVoiceRecorder.MAX_AUDIO_BYTES) { "Voice audio is too large" }
  }
}
