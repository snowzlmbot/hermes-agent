package com.snowzlmbot.hermes.mobile.feature

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInteractionControllerTest {
  @Test
  fun stopRecordingTranscribesAndEmitsDraftText() = runTest {
    val recorder = FakeRecorder()
    val gateway = FakeVoiceGateway(transcript = "hello from voice")
    val player = FakePlayer()
    val controller = VoiceInteractionController(recorder, gateway, player, this)

    controller.startRecording()
    assertTrue(controller.state.value.isRecording)

    controller.stopAndTranscribe()
    runCurrent()

    assertFalse(controller.state.value.isRecording)
    assertFalse(controller.state.value.isTranscribing)
    assertEquals(listOf("hello from voice"), controller.transcripts.replayCache)
    assertEquals(1, gateway.transcribed.size)
    assertEquals("audio/mp4", gateway.transcribed.single().mimeType)
    assertTrue(gateway.transcribed.single().bytes.contentEquals(byteArrayOf(1, 2, 3)))
    assertTrue(recorder.releaseCount == 1)
  }

  @Test
  fun transcriptionFailureClearsBusyStateAndSurfacesSanitizedError() = runTest {
    val recorder = FakeRecorder()
    val gateway = FakeVoiceGateway(error = IllegalStateException("gateway unavailable"))
    val controller = VoiceInteractionController(recorder, gateway, FakePlayer(), this)

    controller.startRecording()
    controller.stopAndTranscribe()
    runCurrent()

    assertFalse(controller.state.value.isRecording)
    assertFalse(controller.state.value.isTranscribing)
    assertEquals("gateway unavailable", controller.state.value.error)
    assertTrue(controller.transcripts.replayCache.isEmpty())
    assertTrue(recorder.releaseCount == 1)
  }

  @Test
  fun speakingReplacesExistingPlaybackAndCompletionClearsState() = runTest {
    val gateway = FakeVoiceGateway(speech = SynthesizedVoice("audio/wav", byteArrayOf(4, 5)))
    val player = FakePlayer()
    val controller = VoiceInteractionController(FakeRecorder(), gateway, player, this)

    controller.speak("first")
    runCurrent()
    assertTrue(controller.state.value.isSpeaking)
    assertEquals(listOf("first"), gateway.spoken)

    controller.speak("second")
    runCurrent()
    assertEquals(1, player.stopCount)
    assertEquals(listOf("first", "second"), gateway.spoken)

    player.complete()
    assertFalse(controller.state.value.isSpeaking)
  }

  @Test
  fun playbackFailureStopsSpeakingAndSurfacesError() = runTest {
    val player = FakePlayer()
    val controller = VoiceInteractionController(FakeRecorder(), FakeVoiceGateway(), player, this)

    controller.speak("answer")
    runCurrent()
    player.fail(IllegalStateException("speaker unavailable"))

    assertFalse(controller.state.value.isSpeaking)
    assertEquals("speaker unavailable", controller.state.value.error)
  }

  @Test
  fun emptyTranscriptAndEmptySpeechNeverMutateOrCallGateway() = runTest {
    val gateway = FakeVoiceGateway(transcript = "   ")
    val controller = VoiceInteractionController(FakeRecorder(), gateway, FakePlayer(), this)

    controller.startRecording()
    controller.stopAndTranscribe()
    controller.speak("   ")
    runCurrent()

    assertTrue(controller.transcripts.replayCache.isEmpty())
    assertTrue(gateway.spoken.isEmpty())
    assertNull(controller.state.value.error)
  }

  @Test
  fun stoppingSpeechDoesNotCancelAnActiveTranscription() = runTest {
    val gateway = FakeVoiceGateway(transcript = "kept transcript")
    val controller = VoiceInteractionController(FakeRecorder(), gateway, FakePlayer(), this)

    controller.startRecording()
    controller.stopAndTranscribe()
    controller.stopSpeaking()
    runCurrent()

    assertEquals(listOf("kept transcript"), controller.transcripts.replayCache)
    assertFalse(controller.state.value.isTranscribing)
  }

  @Test
  fun closeCancelsRecordingPlaybackAndLateTranscription() = runTest {
    val recorder = FakeRecorder()
    val player = FakePlayer()
    val gateway = FakeVoiceGateway(transcript = "late")
    val controller = VoiceInteractionController(recorder, gateway, player, this)

    controller.startRecording()
    controller.stopAndTranscribe()
    controller.speak("answer")
    controller.close()
    runCurrent()

    assertTrue(recorder.releaseCount >= 1)
    assertTrue(player.stopCount >= 1)
    assertFalse(controller.state.value.isRecording)
    assertFalse(controller.state.value.isSpeaking)
    assertTrue(controller.transcripts.replayCache.isEmpty())
  }

  private class FakeRecorder : VoiceRecorder {
    var releaseCount = 0

    override fun start() = Unit

    override fun stop(): RecordedAudio = RecordedAudio("audio/mp4", byteArrayOf(1, 2, 3))

    override fun release() {
      releaseCount += 1
    }
  }

  private class FakeVoiceGateway(
    private val transcript: String = "",
    private val speech: SynthesizedVoice = SynthesizedVoice("audio/wav", byteArrayOf(7)),
    private val error: Throwable? = null,
  ) : VoiceGateway {
    val transcribed = mutableListOf<RecordedAudio>()
    val spoken = mutableListOf<String>()

    override suspend fun transcribe(audio: RecordedAudio): String {
      error?.let { throw it }
      transcribed += audio
      return transcript
    }

    override suspend fun synthesize(text: String): SynthesizedVoice {
      error?.let { throw it }
      spoken += text
      return speech
    }
  }

  private class FakePlayer : VoicePlayer {
    var stopCount = 0
    private var completion: (() -> Unit)? = null
    private var failure: ((Throwable) -> Unit)? = null

    override fun play(
      audio: SynthesizedVoice,
      onComplete: () -> Unit,
      onError: (Throwable) -> Unit,
    ) {
      completion = onComplete
      failure = onError
    }

    override fun stop() {
      stopCount += 1
      completion = null
      failure = null
    }
    fun complete() {
      val current = completion
      completion = null
      failure = null
      current?.invoke()
    }

    fun fail(error: Throwable) {
      val current = failure
      completion = null
      failure = null
      current?.invoke(error)
    }
  }
}
