package com.demo.opencv.features.blinkkeyboard

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Procedurally synthesised feedback tones; no audio assets are bundled. */
class AudioFeedbackService {

    private val sampleRate = 22050

    private val tick: AudioTrack? by lazy { buildTrack(tone(440.0, 50, 0.15)) }
    private val select: AudioTrack? by lazy { buildTrack(tone(880.0, 200, 0.35)) }
    private val saved: AudioTrack? by lazy {
        buildTrack(tone(660.0, 120, 0.3) + tone(990.0, 260, 0.3))
    }
    private val cancel: AudioTrack? by lazy {
        buildTrack(tone(520.0, 90, 0.25) + tone(330.0, 160, 0.25))
    }
    private val rest: AudioTrack? by lazy { buildTrack(tone(220.0, 400, 0.3)) }

    fun playTick() = play(tick)
    fun playSelectChime() = play(select)
    fun playWordSaved() = play(saved)
    fun playCancel() = play(cancel)
    fun playRest() = play(rest)

    fun release() {
        listOf(tick, select, saved, cancel, rest).forEach {
            try {
                it?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun play(track: AudioTrack?) {
        track ?: return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
            track.reloadStaticData()
            track.play()
        } catch (e: Exception) {
            Log.w(TAG, "tone playback failed", e)
        }
    }

    private fun tone(freqHz: Double, durationMs: Int, amplitude: Double): ShortArray {
        val count = sampleRate * durationMs / 1000
        val attack = sampleRate * 5 / 1000
        return ShortArray(count) { i ->
            val t = i.toDouble() / sampleRate
            val fadeIn = min(1.0, i.toDouble() / attack)
            val decay = exp(-5.0 * i / count)
            (sin(2 * PI * freqHz * t) * amplitude * fadeIn * decay * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    } catch (e: Exception) {
        Log.w(TAG, "could not create tone track", e)
        null
    }

    private companion object {
        const val TAG = "BlinkAudioFeedback"
    }
}
