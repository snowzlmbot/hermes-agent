package com.snowzlmbot.hermes.mobile.feature

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecordedAudio(val mimeType: String, val bytes: ByteArray)

data class SynthesizedVoice(val mimeType: String, val bytes: ByteArray)

interface VoiceRecorder {
  fun start()
  fun stop(): RecordedAudio
  fun release()
}

interface VoiceGateway {
  suspend fun transcribe(audio: RecordedAudio): String
  suspend fun synthesize(text: String): SynthesizedVoice
}

interface VoicePlayer {
  fun play(audio: SynthesizedVoice, onComplete: () -> Unit, onError: (Throwable) -> Unit)
  fun stop()
}

data class VoiceInteractionState(
  val isRecording: Boolean = false,
  val isTranscribing: Boolean = false,
  val isSpeaking: Boolean = false,
  val error: String? = null,
)

class VoiceInteractionController(
  private val recorder: VoiceRecorder,
  private val gateway: VoiceGateway,
  private val player: VoicePlayer,
  private val scope: CoroutineScope,
) : Closeable {
  private val transcriptionGeneration = AtomicLong(0)
  private val speechGeneration = AtomicLong(0)
  private val mutableState = MutableStateFlow(VoiceInteractionState())
  private val mutableTranscripts = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
  private var transcriptionJob: Job? = null
  private var speechJob: Job? = null
  private var closed = false

  val state: StateFlow<VoiceInteractionState> = mutableState.asStateFlow()
  val transcripts: SharedFlow<String> = mutableTranscripts.asSharedFlow()

  fun startRecording() {
    if (closed || mutableState.value.isRecording) return
    transcriptionGeneration.incrementAndGet()
    transcriptionJob?.cancel()
    transcriptionJob = null
    stopSpeaking()
    try {
      recorder.start()
      mutableState.value = VoiceInteractionState(isRecording = true)
    } catch (error: Throwable) {
      recorder.release()
      mutableState.value = VoiceInteractionState(error = error.voiceMessage())
    }
  }

  fun stopAndTranscribe() {
    if (closed || !mutableState.value.isRecording) return
    val audio = try {
      recorder.stop()
    } catch (error: Throwable) {
      recorder.release()
      mutableState.value = VoiceInteractionState(error = error.voiceMessage())
      return
    }
    recorder.release()
    val operation = transcriptionGeneration.incrementAndGet()
    transcriptionJob?.cancel()
    mutableState.value = VoiceInteractionState(isTranscribing = true)
    transcriptionJob = scope.launch {
      try {
        val transcript = gateway.transcribe(audio).trim()
        if (!closed && transcriptionGeneration.get() == operation) {
          if (transcript.isNotEmpty()) mutableTranscripts.emit(transcript)
          mutableState.value = VoiceInteractionState()
        }
      } catch (_: CancellationException) {
        // A newer operation or lifecycle closure owns the state.
      } catch (error: Throwable) {
        if (!closed && transcriptionGeneration.get() == operation) {
          mutableState.value = VoiceInteractionState(error = error.voiceMessage())
        }
      }
    }
  }

  fun speak(text: String) {
    val cleanText = text.trim()
    if (closed || cleanText.isEmpty()) return
    if (mutableState.value.isRecording || mutableState.value.isTranscribing) return
    val operation = speechGeneration.incrementAndGet()
    speechJob?.cancel()
    if (mutableState.value.isSpeaking) player.stop()
    mutableState.value = mutableState.value.copy(isSpeaking = true, error = null)
    speechJob = scope.launch {
      try {
        val audio = gateway.synthesize(cleanText)
        if (closed || speechGeneration.get() != operation) return@launch
        player.play(
          audio = audio,
          onComplete = {
            if (!closed && speechGeneration.get() == operation) {
              mutableState.value = mutableState.value.copy(isSpeaking = false)
            }
          },
          onError = { error ->
            if (!closed && speechGeneration.get() == operation) {
              mutableState.value = mutableState.value.copy(isSpeaking = false, error = error.voiceMessage())
            }
          },
        )
      } catch (_: CancellationException) {
        // A newer operation or lifecycle closure owns the state.
      } catch (error: Throwable) {
        if (!closed && speechGeneration.get() == operation) {
          mutableState.value = mutableState.value.copy(isSpeaking = false, error = error.voiceMessage())
        }
      }
    }
  }

  fun stopSpeaking() {
    speechGeneration.incrementAndGet()
    speechJob?.cancel()
    speechJob = null
    if (mutableState.value.isSpeaking) player.stop()
    mutableState.value = mutableState.value.copy(isSpeaking = false)
  }

  fun clearError() {
    mutableState.value = mutableState.value.copy(error = null)
  }

  fun reportError(message: String) {
    if (!closed) {
      mutableState.value = mutableState.value.copy(error = message.trim().take(160))
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    transcriptionGeneration.incrementAndGet()
    speechGeneration.incrementAndGet()
    transcriptionJob?.cancel()
    speechJob?.cancel()
    recorder.release()
    player.stop()
    mutableState.value = VoiceInteractionState()
  }

  private fun Throwable.voiceMessage(): String =
    message?.lineSequence()?.firstOrNull()?.trim()?.take(160)?.takeIf(String::isNotEmpty)
      ?: "Voice operation failed"
}
