package com.demo.opencv.metrics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The typing-speed test that is running, if any. Every input mode reports its selections,
 * corrections and spoken text here; with no test armed each call returns immediately.
 * Listeners are called on whichever thread reported the event.
 */
object TypingTest {
    const val MODE_GAZE_LETTER = "gaze-letter"
    const val MODE_GAZE_AMBIGUOUS = "gaze-ambiguous"
    const val MODE_GAZE_LLM = "gaze-llm"
    const val MODE_BLINK = "blink"

    fun interface Listener {
        fun onTypingTestChanged()
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private var log: TrialLog? = null

    /** Replaced in unit tests; elapsed time for durations. */
    @JvmField
    var clock: () -> Long = { SystemClock.elapsedRealtime() }

    @JvmStatic
    @Volatile
    var trial: TypingTrial? = null
        private set

    /** The trial that finished last, until the next one is armed or it is dismissed. */
    @JvmStatic
    @Volatile
    var lastResult: TrialResult? = null
        private set

    /** Sensor state while typing (thermal tier, fps, light), stored with the result. */
    @JvmStatic
    @Volatile
    var conditions: String = ""

    @JvmStatic
    val isActive: Boolean get() = trial != null

    @JvmStatic
    fun attach(context: Context) {
        log = TrialLog(File(context.getExternalFilesDir(null) ?: context.filesDir, TrialLog.FILE_NAME))
    }

    @JvmStatic
    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    @JvmStatic
    fun arm(target: String, language: String) {
        synchronized(this) {
            trial = TypingTrial(target, language)
            lastResult = null
        }
        notifyListeners()
    }

    /** Abandons a running test, or dismisses the result of a finished one. */
    @JvmStatic
    fun clear() {
        synchronized(this) {
            trial = null
            lastResult = null
        }
        notifyListeners()
    }

    @JvmStatic
    fun onSelection() {
        synchronized(this) { (trial ?: return).onSelection(clock()) }
        notifyListeners()
    }

    @JvmStatic
    fun onCorrection() {
        synchronized(this) { (trial ?: return).onCorrection() }
    }

    /** The patient spoke [text]: that ends the test. */
    @JvmStatic
    fun onSpoken(text: String, mode: String) = finish(text, mode, onlyIfComplete = false)

    /** For modes with no speak action: ends the test once [text] matches the target. */
    @JvmStatic
    fun onTextChanged(text: String, mode: String) = finish(text, mode, onlyIfComplete = true)

    /** Elapsed time of the running trial, 0 before the first selection. */
    @JvmStatic
    fun elapsedMs(): Long = synchronized(this) { trial?.elapsedMs(clock()) ?: 0L }

    @JvmStatic
    fun results(): List<TrialResult> = log?.read().orEmpty()

    @JvmStatic
    fun resultsCsv(): String = log?.readText().orEmpty()

    @JvmStatic
    fun logPath(): String = log?.file?.absolutePath.orEmpty()

    private fun finish(text: String, mode: String, onlyIfComplete: Boolean) {
        val result = synchronized(this) {
            val running = trial ?: return
            if (!running.isRunning || (onlyIfComplete && !running.isComplete(text))) return
            trial = null
            running.finish(text, mode, System.currentTimeMillis(), conditions).also { lastResult = it }
        }
        Log.i("TypingTest", "$mode: ${result.summary()}")
        log?.append(result)
        notifyListeners()
    }

    private fun notifyListeners() = listeners.forEach { it.onTypingTestChanged() }
}

/** Append-only CSV of finished trials, in the app's external files folder so it can be copied off the phone. */
class TrialLog(val file: File) {

    @Synchronized
    fun append(result: TrialResult) {
        try {
            if (!file.exists()) file.writeText(TrialResult.CSV_HEADER + "\n")
            file.appendText(result.toCsvRow() + "\n")
        } catch (e: Exception) {
            Log.w("TypingTest", "could not save trial", e)
        }
    }

    @Synchronized
    fun readText(): String = try {
        if (file.exists()) file.readText() else ""
    } catch (e: Exception) {
        Log.w("TypingTest", "could not read trials", e)
        ""
    }

    fun read(): List<TrialResult> = readText().lineSequence().mapNotNull(TrialResult::fromCsvRow).toList()

    companion object {
        const val FILE_NAME = "typing_trials.csv"
    }
}
