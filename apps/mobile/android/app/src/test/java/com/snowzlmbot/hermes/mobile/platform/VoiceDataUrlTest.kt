package com.snowzlmbot.hermes.mobile.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDataUrlTest {
  @Test
  fun roundTripsAudioWithoutExposingBytesInDescription() {
    val encoded = VoiceDataUrl.encode("audio/mpeg", byteArrayOf(1, 2, 3))
    val decoded = VoiceDataUrl.decode(encoded)

    assertEquals("audio/mpeg", decoded.mimeType)
    assertTrue(decoded.bytes.contentEquals(byteArrayOf(1, 2, 3)))
    assertTrue(encoded.startsWith("data:audio/mpeg;base64,"))
  }

  @Test
  fun rejectsMalformedUnsupportedAndOversizedPayloads() {
    val invalid = listOf(
      "not-a-data-url",
      "data:text/plain;base64,QQ==",
      "data:audio/wav,QQ==",
      "data:audio/wav;base64,",
    )
    invalid.forEach { value ->
      assertTrue(runCatching { VoiceDataUrl.decode(value) }.isFailure)
    }
    val oversized = ByteArray(AndroidVoiceRecorder.MAX_AUDIO_BYTES + 1)
    assertTrue(runCatching { VoiceDataUrl.encode("audio/wav", oversized) }.isFailure)
  }
}
