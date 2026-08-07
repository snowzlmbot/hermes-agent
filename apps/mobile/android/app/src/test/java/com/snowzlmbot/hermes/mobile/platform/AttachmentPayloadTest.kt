package com.snowzlmbot.hermes.mobile.platform

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPayloadTest {
  @Test
  fun encodesImagesAndPdfsAsContentBase64() {
    val image = AttachmentPayload.image("photo.png", "image/png", byteArrayOf(1, 2, 3))
    val pdf = AttachmentPayload.pdf("notes.pdf", byteArrayOf(37, 80, 68, 70, 45))

    assertEquals("image.attach_bytes", image.method)
    assertEquals("pdf.attach", pdf.method)
    assertTrue(image.params["content_base64"]?.isNotBlank() == true)
    assertTrue(pdf.params["content_base64"]?.isNotBlank() == true)
    assertFalse(image.params.containsKey("data_url"))
    assertFalse(pdf.params.containsKey("data_url"))
  }

  @Test
  fun encodesOrdinaryFilesAsDataUrlAndUsesGatewayReference() {
    val file = AttachmentPayload.file("notes.txt", "text/plain", "hello".toByteArray())

    assertEquals("file.attach", file.method)
    assertTrue(file.params["data_url"]?.contains("data:text/plain;base64,") == true)
    assertFalse(file.params.containsKey("content_base64"))
    assertEquals("@file:.hermes/desktop-attachments/notes.txt", AttachmentResult.referenceFrom(
      Json.parseToJsonElement("""{"ref_text":"@file:.hermes/desktop-attachments/notes.txt"}"""),
    ))
  }

  @Test
  fun rejectsUnsafeNamesAndOversizedPayloadsBeforeEncoding() {
    assertThrows(AttachmentException::class.java) {
      AttachmentPayload.file("../secret.txt", "text/plain", byteArrayOf(1))
    }
    assertThrows(AttachmentException::class.java) {
      AttachmentPayload.image("image.png", "image/png", ByteArray(25 * 1024 * 1024 + 1))
    }
  }
}
