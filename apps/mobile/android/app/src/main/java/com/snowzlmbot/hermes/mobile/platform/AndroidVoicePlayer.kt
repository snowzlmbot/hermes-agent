package com.snowzlmbot.hermes.mobile.platform

import android.content.Context
import android.media.MediaPlayer
import com.snowzlmbot.hermes.mobile.feature.SynthesizedVoice
import com.snowzlmbot.hermes.mobile.feature.VoicePlayer
import java.io.File

internal class AndroidVoicePlayer(context: Context) : VoicePlayer {
  private val cacheDir = context.applicationContext.cacheDir.also { directory ->
    directory.listFiles { file -> file.name.startsWith("hermes-speech-") }?.forEach { it.delete() }
  }
  private val lock = Any()
  private var active: MediaPlayer? = null
  private var activeFile: File? = null

  override fun play(
    audio: SynthesizedVoice,
    onComplete: () -> Unit,
    onError: (Throwable) -> Unit,
  ) {
    val extension = when (audio.mimeType.lowercase()) {
      "audio/mpeg", "audio/mp3" -> ".mp3"
      "audio/mp4", "audio/m4a" -> ".m4a"
      "audio/ogg" -> ".ogg"
      else -> ".audio"
    }
    val file = File.createTempFile("hermes-speech-", extension, cacheDir)
    file.writeBytes(audio.bytes)
    val player = MediaPlayer()
    synchronized(lock) {
      releaseLocked()
      active = player
      activeFile = file
    }
    try {
      player.setDataSource(file.absolutePath)
      player.setOnCompletionListener {
        synchronized(lock) { if (active === player) releaseLocked() }
        onComplete()
      }
      player.setOnErrorListener { _, what, extra ->
        synchronized(lock) { if (active === player) releaseLocked() }
        onError(IllegalStateException("Audio playback failed ($what/$extra)"))
        true
      }
      player.prepare()
      player.start()
    } catch (error: Throwable) {
      synchronized(lock) { if (active === player) releaseLocked() }
      throw error
    }
  }

  override fun stop() {
    synchronized(lock) { releaseLocked() }
  }

  private fun releaseLocked() {
    val player = active
    val file = activeFile
    active = null
    activeFile = null
    if (player != null) {
      runCatching { player.stop() }
      runCatching { player.reset() }
      runCatching { player.release() }
    }
    file?.delete()
  }
}
