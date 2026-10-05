package com.vakyam.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class TtsState { INITIALISING, READY, UNAVAILABLE }

/**
 * Wrapper around Android [TextToSpeech] that turns its callback API into suspend functions.
 *
 * The engine binds asynchronously; [speak] waits for that, so callers never have to care whether
 * initialisation has finished.
 */
class TtsManager(context: Context, private val preferredLocale: Locale = Locale.getDefault()) {

    private val _state = MutableStateFlow(TtsState.INITIALISING)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val utteranceCounter = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, ::onInit)

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech init failed with status $status")
            _state.value = TtsState.UNAVAILABLE
            return
        }
        if (!selectLanguage()) {
            Log.e(TAG, "No usable TTS voice for $preferredLocale or ${Locale.US}")
            _state.value = TtsState.UNAVAILABLE
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                // Routes speech like an accessibility announcement so it stays audible when the
                // media volume is low.
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setSpeechRate(SPEECH_RATE)
        tts.setOnUtteranceProgressListener(progressListener)
        _state.value = TtsState.READY
    }

    /** Uses the device locale when it has a voice, otherwise US English. */
    private fun selectLanguage(): Boolean {
        for (locale in listOf(preferredLocale, Locale.US)) {
            val result = tts.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                return true
            }
        }
        return false
    }

    /**
     * Speaks [text] and suspends until playback has finished.
     *
     * @param flush true to interrupt whatever is being spoken; false to queue behind it.
     * @return true if the utterance was spoken to the end; false if TTS is unavailable or the
     *   utterance failed or was interrupted.
     */
    suspend fun speak(text: String, flush: Boolean = true): Boolean {
        if (text.isBlank()) return true
        val ready = withTimeoutOrNull(INIT_TIMEOUT_MS) { state.first { it != TtsState.INITIALISING } }
        if (ready != TtsState.READY) return false

        val utteranceId = "vakyam-${utteranceCounter.incrementAndGet()}"
        val done = CompletableDeferred<Boolean>()
        pending[utteranceId] = done
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (tts.speak(text, queueMode, null, utteranceId) != TextToSpeech.SUCCESS) {
            pending.remove(utteranceId)
            return false
        }
        return try {
            done.await()
        } finally {
            // Reached with the entry still present only when the caller was cancelled mid-speech.
            if (pending.remove(utteranceId) != null) tts.stop()
        }
    }

    /** Stops speech immediately and drops everything queued. */
    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        pending.values.forEach { it.complete(false) }
        pending.clear()
        _state.value = TtsState.UNAVAILABLE
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) = finish(utteranceId, true)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = finish(utteranceId, false)

        override fun onError(utteranceId: String, errorCode: Int) {
            Log.w(TAG, "Utterance $utteranceId failed with code $errorCode")
            finish(utteranceId, false)
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId, false)

        private fun finish(utteranceId: String, success: Boolean) {
            pending.remove(utteranceId)?.complete(success)
        }
    }

    companion object {
        private const val TAG = "TtsManager"
        private const val INIT_TIMEOUT_MS = 5_000L

        /** Slightly slower than default: listeners are often across a room. */
        private const val SPEECH_RATE = 0.95f
    }
}
