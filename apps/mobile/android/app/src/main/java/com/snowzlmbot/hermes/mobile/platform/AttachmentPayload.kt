package com.snowzlmbot.hermes.mobile.platform

import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class AttachmentException(message: String) : IllegalArgumentException(message)

sealed class AttachmentPayload private constructor(
  val method: String,
  val params: Map<String, String>,
) {
  class Image internal constructor(
    filename: String,
    mimeType: String,
    data: ByteArray,
  ) : AttachmentPayload(
    method = "image.attach_bytes",
    params = mapOf(
      "filename" to filename,
      "mime_type" to mimeType,
      "content_base64" to encode(data),
    ),
  )

  class Pdf internal constructor(
    filename: String,
    data: ByteArray,
  ) : AttachmentPayload(
    method = "pdf.attach",
    params = mapOf(
      "filename" to filename,
      "mime_type" to "application/pdf",
      "content_base64" to encode(data),
    ),
  )

  class File internal constructor(
    filename: String,
    mimeType: String,
    data: ByteArray,
  ) : AttachmentPayload(
    method = "file.attach",
    params = mapOf(
      "name" to filename,
      "mime_type" to mimeType,
      "data_url" to "data:$mimeType;base64,${encode(data)}",
    ),
  )

  companion object {
    private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
    private const val MAX_PDF_BYTES = 50 * 1024 * 1024

    fun image(filename: String, mimeType: String, data: ByteArray): AttachmentPayload {
      validateFilename(filename)
      requireMime(mimeType, "image/")
      validateBytes(data, MAX_IMAGE_BYTES)
      return Image(filename, mimeType, data)
    }

    fun pdf(filename: String, data: ByteArray): AttachmentPayload {
      validateFilename(filename)
      validateBytes(data, MAX_PDF_BYTES)
      return Pdf(filename, data)
    }

    fun file(filename: String, mimeType: String, data: ByteArray): AttachmentPayload {
      validateFilename(filename)
      if (mimeType.isBlank()) throw AttachmentException("File MIME type is required")
      validateBytes(data, MAX_PDF_BYTES)
      return File(filename, mimeType.lowercase(Locale.ROOT), data)
    }

    private fun validateFilename(filename: String) {
      val clean = filename.trim()
      if (
        clean.isEmpty() || clean == "." || clean == ".." ||
        clean.contains('/') || clean.contains('\\') || clean.contains('\u0000')
      ) {
        throw AttachmentException("Attachment filename is unsafe")
      }
    }

    private fun requireMime(mimeType: String, prefix: String) {
      if (!mimeType.lowercase(Locale.ROOT).startsWith(prefix)) {
        throw AttachmentException("Unsupported attachment MIME type")
      }
    }

    private fun validateBytes(data: ByteArray, limit: Int) {
      if (data.isEmpty()) throw AttachmentException("Attachment is empty")
      if (data.size > limit) throw AttachmentException("Attachment is too large")
    }

    private fun encode(data: ByteArray): String =
      Base64.getEncoder().encodeToString(data)
  }
}

object AttachmentResult {
  fun referenceFrom(result: JsonElement): String {
    val value = result as? JsonObject ?: throw AttachmentException("Attachment response is invalid")
    val reference = (value["ref_text"] as? JsonPrimitive)?.content
      ?.takeIf { it.startsWith("@file:") }
      ?: throw AttachmentException("Attachment response is missing a gateway reference")
    return reference
  }
}
