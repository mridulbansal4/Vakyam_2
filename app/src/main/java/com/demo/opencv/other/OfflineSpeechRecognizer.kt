package com.demo.opencv.other

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * On-device speech-to-text, used when the phone is offline or has no working system recognizer.
 *
 * Records 16 kHz mono from the microphone until the speaker pauses (or [stopListening] is called),
 * then transcribes the whole utterance with a Whisper model through sherpa-onnx. The model is not
 * bundled in the APK; like the Gemma model it is pushed to the device with adb (see README).
 * Results and errors are delivered on the main thread.
 */
class OfflineSpeechRecognizer {

    interface Listener {
        fun onResult(text: String)
        fun onError(error: String)
    }

    internal data class ModelFiles(val encoder: String, val decoder: String, val tokens: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizerLock = Mutex()
    private var appContext: Context? = null
    @Volatile private var listener: Listener? = null
    private var recognizer: OfflineRecognizer? = null
    private var recognizerLanguage = ""
    private var session: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var language = "en"

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Takes a BCP-47 tag such as "en-US" or "hi-IN"; Whisper only needs the language part. */
    fun setLanguage(languageTag: String?) {
        language = languageTag?.substringBefore('-')?.lowercase(Locale.ROOT)?.ifBlank { null } ?: "en"
    }

    /** True when a model has been pushed to the device. */
    fun isAvailable(): Boolean = appContext?.let { findModel(it) } != null

    fun isListening(): Boolean = session?.isActive == true

    fun startListening() {
        val ctx = appContext
        if (ctx == null) {
            fail("not initialised")
            return
        }
        if (session?.isActive == true) {
            fail("still busy with the previous recording")
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("microphone permission not granted")
            return
        }
        stopRequested = false
        session = scope.launch {
            try {
                val model = findModel(ctx)
                if (model == null) {
                    fail("speech model not found")
                    return@launch
                }
                val samples = record()
                if (samples == null) {
                    fail("microphone unavailable")
                    return@launch
                }
                val text = if (samples.size < MIN_SAMPLES) "" else transcribe(model, samples)
                if (text.isEmpty()) {
                    fail("no speech recognised")
                } else {
                    Log.d(TAG, "Recognised offline: $text")
                    mainHandler.post { listener?.onResult(text) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Offline recognition failed", e)
                fail(e.message ?: "recognition failed")
            }
        }
    }

    /** Ends the recording early; whatever was captured is still transcribed. */
    fun stopListening() {
        stopRequested = true
    }

    fun destroy() {
        listener = null
        stopRequested = true
        val running = session
        // The native recognizer must not be released while a decode is still using it
        CoroutineScope(Dispatchers.IO).launch {
            running?.join()
            recognizerLock.withLock {
                recognizer?.release()
                recognizer = null
            }
        }
    }

    private fun fail(error: String) {
        Log.w(TAG, "Offline speech error: $error")
        mainHandler.post { listener?.onError(error) }
    }

    /** Returns the recorded utterance, an empty array if nobody spoke, or null if the microphone could not be opened. */
    @SuppressLint("MissingPermission") // checked in startListening
    private fun record(): FloatArray? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SAMPLE_RATE))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
        val samples = FloatArray(MAX_SECONDS * SAMPLE_RATE)
        var count = 0
        val endpointer = SpeechEndpointer()
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null // another app holds the microphone
            while (!stopRequested && count < samples.size) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) break
                var energy = 0.0
                for (i in 0 until read) {
                    if (count >= samples.size) break
                    val s = chunk[i] / 32768f
                    samples[count++] = s
                    energy += s * s
                }
                if (endpointer.accept(sqrt(energy / read).toFloat(), read * 1000L / SAMPLE_RATE)) break
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        // A manual stop is trusted even if the endpointer never heard speech (very quiet speaker)
        return if (endpointer.heardSpeech || stopRequested) samples.copyOf(count) else FloatArray(0)
    }

    private suspend fun transcribe(model: ModelFiles, samples: FloatArray): String = recognizerLock.withLock {
        val lang = language
        var active = recognizer
        if (active == null || recognizerLanguage != lang) { // Whisper's language is fixed when the recognizer is built
            active?.release()
            recognizer = null
            Log.i(TAG, "Loading offline speech model ${model.encoder} for language $lang")
            active = OfflineRecognizer(config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(encoder = model.encoder, decoder = model.decoder,
                                    language = lang, task = "transcribe"),
                            tokens = model.tokens,
                            numThreads = 4,
                            modelType = "whisper")))
            recognizer = active
            recognizerLanguage = lang
        }
        val stream = active.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            active.decode(stream)
            cleanTranscript(active.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    companion object {
        private const val TAG = "OfflineSpeech"
        private const val SAMPLE_RATE = 16000
        private const val MAX_SECONDS = 25 // Whisper handles at most 30 s per utterance
        private const val MIN_SAMPLES = SAMPLE_RATE * 3 / 10
        const val MODEL_DIR = "models/asr"

        internal fun findModel(ctx: Context): ModelFiles? {
            val dirs = listOfNotNull(
                    File(ctx.filesDir, MODEL_DIR),
                    ctx.getExternalFilesDir(MODEL_DIR), // /sdcard/Android/data/<package>/files/models/asr
                    File("/sdcard/Models/asr"),
                    File("/data/local/tmp/asr"))
            return dirs.firstNotNullOfOrNull { modelIn(it) }
        }

        /** Finds a sherpa-onnx Whisper export in [dir], preferring the smaller int8 files. */
        internal fun modelIn(dir: File): ModelFiles? {
            val names = try { dir.list() } catch (_: SecurityException) { null } ?: return null
            fun pick(part: String) = names.filter { it.endsWith("$part.int8.onnx") }
                    .ifEmpty { names.filter { it.endsWith("$part.onnx") } }.minOrNull()
            val encoder = pick("encoder") ?: return null
            val decoder = pick("decoder") ?: return null
            val tokens = names.filter { it.endsWith("tokens.txt") }.minOrNull() ?: return null
            return ModelFiles(File(dir, encoder).absolutePath, File(dir, decoder).absolutePath, File(dir, tokens).absolutePath)
        }

        /** Lower-cases like the online recognizer and drops Whisper's non-speech tags such as "[BLANK_AUDIO]" or "(music)". */
        @JvmStatic
        fun cleanTranscript(raw: String?): String {
            if (raw == null) return ""
            return raw.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .lowercase(Locale.ROOT)
        }
    }
}

/**
 * Decides when to stop recording from the loudness of successive audio chunks.
 *
 * Speech is a chunk louder than three times the quietest level heard so far (and never below
 * [minSpeechRms]); two speech chunks in a row are needed so a tap on the record button is not
 * mistaken for speech. Recording stops after [trailingSilenceMs] of quiet following speech, or
 * after [noSpeechTimeoutMs] if nobody speaks at all.
 */
internal class SpeechEndpointer(
    private val minSpeechRms: Float = 0.008f,
    private val trailingSilenceMs: Long = 1200L,
    private val noSpeechTimeoutMs: Long = 7000L
) {
    var heardSpeech = false
        private set
    private var noiseFloor = Float.NaN
    private var loudChunks = 0
    private var silenceMs = 0L
    private var elapsedMs = 0L

    /** Feeds one chunk; returns true when recording should stop. */
    fun accept(rms: Float, durationMs: Long): Boolean {
        elapsedMs += durationMs
        // Follows the quietest level down immediately and creeps up slowly, so it settles on the room noise
        noiseFloor = if (noiseFloor.isNaN()) rms else minOf(rms, noiseFloor * 1.01f + 1e-5f)
        if (rms >= maxOf(minSpeechRms, noiseFloor * 3f)) {
            if (++loudChunks >= 2) heardSpeech = true
            silenceMs = 0L
        } else {
            loudChunks = 0
            silenceMs += durationMs
        }
        return if (heardSpeech) silenceMs >= trailingSilenceMs else elapsedMs >= noSpeechTimeoutMs
    }
}
