package com.movieroulettetuesday.app.roulette

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/** The spin sound (iOS `SpinFeedback` clip part). */
interface SpinFeedback {
    fun startClip()
    /** Stop the clip; [fadeSeconds] > 0 fades it out first. */
    fun stopClip(fadeSeconds: Double)

    object None : SpinFeedback {
        override fun startClip() = Unit
        override fun stopClip(fadeSeconds: Double) = Unit
    }
}

/**
 * Plays `wheel-spin.m4a` (Matthew's recording, committed in assets, byte-identical to iOS and verified by
 * sha256; see app/build.gradle.kts). Missing asset = silent. The whole clip plays once per spin; the
 * wheel's motion is locked to it.
 */
class SpinSoundPlayer(private val context: Context) : SpinFeedback {
    private var player: MediaPlayer? = null
    private var unavailable = false
    private val handler = Handler(Looper.getMainLooper())
    private var fade: Runnable? = null

    private fun ensure(): MediaPlayer? {
        player?.let { return it }
        if (unavailable) return null
        return try {
            context.assets.openFd(SpinSoundTrack.ASSET).use { fd ->
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    prepare()
                }
            }.also { player = it }
        } catch (_: Exception) {
            unavailable = true
            null
        }
    }

    override fun startClip() {
        val p = ensure() ?: return
        cancelFade()
        p.setVolume(1f, 1f)
        p.seekTo(0)
        p.start()
    }

    override fun stopClip(fadeSeconds: Double) {
        val p = player ?: return
        cancelFade()
        if (!p.isPlaying) return
        if (fadeSeconds <= 0) { p.pause(); return }
        val steps = 16
        val stepMs = (fadeSeconds * 1000 / steps).toLong().coerceAtLeast(1)
        var i = 0
        val r = object : Runnable {
            override fun run() {
                i++
                val v = (1f - i.toFloat() / steps).coerceAtLeast(0f)
                p.setVolume(v, v)
                if (i < steps) handler.postDelayed(this, stepMs) else { p.pause(); p.setVolume(1f, 1f) }
            }
        }
        fade = r
        handler.postDelayed(r, stepMs)
    }

    private fun cancelFade() {
        fade?.let(handler::removeCallbacks)
        fade = null
    }

    fun release() {
        cancelFade()
        player?.release()
        player = null
    }
}
