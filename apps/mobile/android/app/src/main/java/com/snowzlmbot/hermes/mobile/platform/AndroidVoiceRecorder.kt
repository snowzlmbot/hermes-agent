package com.snowzlmbot.hermes.mobile.platform

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.snowzlmbot.hermes.mobile.feature.RecordedAudio
import com.snowzlmbot.hermes.mobile.feature.VoiceRecorder
import java.io.File

internal class AndroidVoiceRecorder(context: Context) : VoiceRecorder {
  private val appContext = context.applicationContext
  private val cacheDir = appContext.cacheDir.also { directory ->
    directory.listFiles { file -> file.name.startsWith("hermes-voice-") }?.forEach { it.delete() }
  }
  private var recorder: MediaRecorder? = null
  private var outputFile: File? = null
  @Volatile private var limitReached = false

  override fun start() {
    check(recorder == null) { "A recording is already active" }
    val output = File.createTempFile("hermes-voice-", ".m4a", cacheDir)
    val next = newRecorder(appContext)
    try {
      next.setAudioSource(MediaRecorder.AudioSource.MIC)
      next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      next.setAudioEncodingBitRate(96_000)
      next.setAudioSamplingRate(44_100)
      next.setMaxDuration(MAX_DURATION_MILLIS)
      next.setMaxFileSize(MAX_AUDIO_BYTES.toLong())
      limitReached = false
      next.setOnInfoListener { _, _, _ -> limitReached = true }
      next.setOutputFile(output.absolutePath)
      next.prepare()
      next.start()
      recorder = next
      outputFile = output
    } catch (error: Throwable) {
      next.release()
      output.delete()
      throw error
    }
  }

  override fun stop(): RecordedAudio {
    val active = recorder ?: error("No recording is active")
    val output = outputFile ?: error("Recording output is unavailable")
    recorder = null
    outputFile = null
    val reachedLimit = limitReached
    limitReached = false
    try {
      if (!reachedLimit) active.stop()
      val bytes = output.readBytes()
      require(bytes.isNotEmpty()) { "Recording is empty" }
      require(bytes.size <= MAX_AUDIO_BYTES) { "Recording is too large" }
      return RecordedAudio(MIME_TYPE, bytes)
    } finally {
      active.release()
      output.delete()
    }
  }

  override fun release() {
    val active = recorder
    val output = outputFile
    recorder = null
    outputFile = null
    limitReached = false
    if (active != null) {
      runCatching { active.stop() }
      active.release()
    }
    output?.delete()
  }

  @Suppress("DEPRECATION")
  private fun newRecorder(context: Context): MediaRecorder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

  companion object {
    internal const val MAX_AUDIO_BYTES = 25 * 1024 * 1024
    private const val MAX_DURATION_MILLIS = 5 * 60 * 1000
    private const val MIME_TYPE = "audio/mp4"
  }
}
