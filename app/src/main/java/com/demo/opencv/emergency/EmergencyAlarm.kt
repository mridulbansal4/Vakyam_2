package com.demo.opencv.emergency

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Main-thread only. Until [stop], repeats a siren burst followed by a spoken call for help. Both
 * play on the alarm stream at full volume, so a muted or do-not-disturb phone is still heard.
 */
class EmergencyAlarm(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tone: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var message = ""
    private var savedVolume = -1

    var isActive = false
        private set

    fun start(spokenMessage: String, locale: Locale) {
        if (isActive) return
        isActive = true
        message = spokenMessage
        raiseVolume()
        tone = try {
            ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneGenerator unavailable", e)
            null
        }
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = locale
        }
        burst(0)
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
        tts?.shutdown()
        tts = null
        ttsReady = false
        restoreVolume()
    }

    private fun burst(step: Int) {
        if (!isActive) return
        if (step < SIREN_TONES) {
            play(if (step % 2 == 0) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_CDMA_MED_L)
            handler.postDelayed({ burst(step + 1) }, TONE_MS)
        } else {
            // The gap lets the words be understood, and spares the patient a continuous siren.
            speak()
            handler.postDelayed({ burst(0) }, SPEECH_GAP_MS)
        }
    }

    private fun play(toneType: Int) {
        try {
            tone?.startTone(toneType, TONE_MS.toInt())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to play siren tone", e)
        }
    }

    private fun speak() {
        if (!ttsReady) return
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, "emergency")
    }

    private fun raiseVolume() {
        try {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not raise alarm volume", e)
        }
    }

    private fun restoreVolume() {
        if (savedVolume < 0) return
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not restore alarm volume", e)
        }
        savedVolume = -1
    }

    private companion object {
        const val TAG = "EmergencyAlarm"
        const val SIREN_TONES = 6
        const val TONE_MS = 400L
        const val SPEECH_GAP_MS = 4_000L
    }
}

/** Text message to the caregiver's phone: the only part of the alarm that reaches another room or building. */
object CaregiverSms {
    private const val TAG = "CaregiverSms"

    @JvmStatic
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** [onResult] runs on the main thread: true once the network accepted the message, false if it could not be sent. */
    fun send(context: Context, number: String, text: String, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        val action = "${app.packageName}.SMS_SENT.${System.nanoTime()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                app.unregisterReceiver(this) // a long message reports once per part; the first result is enough
                onResult(resultCode == Activity.RESULT_OK)
            }
        }
        try {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            val sent = PendingIntent.getBroadcast(
                app, 0, Intent(action).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE,
            )
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            val parts = sms.divideMessage(text)
            sms.sendMultipartTextMessage(number, null, parts, ArrayList(parts.map { sent }), null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "SMS could not be sent", e)
            try {
                app.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
            onResult(false)
        }
    }
}
