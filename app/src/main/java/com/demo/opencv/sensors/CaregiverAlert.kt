package com.demo.opencv.sensors

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Main-thread only. One repeating chime per key; keys are independent. */
class CaregiverAlert(private val repeatMs: Long = 30_000L) {

    private val handler = Handler(Looper.getMainLooper())
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_ALARM, VOLUME) // still audible on silent/DND
    } catch (e: RuntimeException) {
        Log.w(TAG, "ToneGenerator unavailable", e)
        null
    }
    private val active = HashMap<String, Runnable>()

    fun start(key: String, toneType: Int) {
        if (active.containsKey(key)) return
        val repeat = object : Runnable {
            override fun run() {
                play(toneType)
                handler.postDelayed(this, repeatMs)
            }
        }
        active[key] = repeat
        handler.post(repeat)
    }

    fun chimeOnce(toneType: Int) = play(toneType)

    fun stop(key: String) {
        active.remove(key)?.let { handler.removeCallbacks(it) }
    }

    fun release() {
        active.values.forEach { handler.removeCallbacks(it) }
        active.clear()
        tone?.release()
    }

    private fun play(toneType: Int) {
        try {
            tone?.startTone(toneType, TONE_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to play caregiver tone", e)
        }
    }

    companion object {
        private const val TAG = "CaregiverAlert"
        private const val VOLUME = 70
        private const val TONE_MS = 600
    }
}
