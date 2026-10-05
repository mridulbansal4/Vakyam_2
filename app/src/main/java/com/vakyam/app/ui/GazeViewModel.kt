package com.vakyam.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.vakyam.app.aac.AacEffect
import com.vakyam.app.aac.AacIntentDispatcher
import com.vakyam.app.aac.AacUiState
import com.vakyam.app.calibration.CalibrationManager
import com.vakyam.app.calibration.CalibrationQuality
import com.vakyam.app.calibration.CalibrationStep
import com.vakyam.app.calibration.SharedPrefsCalibrationStore
import com.vakyam.app.camera.CameraSource
import com.vakyam.app.dwell.DwellDetector
import com.vakyam.app.dwell.DwellState
import com.vakyam.app.dwell.GazeRect
import com.vakyam.app.gaze.GazeEstimator
import com.vakyam.app.gaze.GazeSample
import com.vakyam.app.gaze.ScreenPoint
import com.vakyam.app.llm.AacPromptTemplate
import com.vakyam.app.llm.GemmaLlmManager
import com.vakyam.app.llm.LlmState
import com.vakyam.app.state.AppState
import com.vakyam.app.state.AppStateMachine
import com.vakyam.app.tts.TtsManager
import com.vakyam.app.tts.TtsState
import com.vakyam.app.vision.FaceLandmarkerHelper
import com.vakyam.app.vision.FaceLandmarks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the camera currently sees of the user. */
data class FaceStatus(
    val detected: Boolean = false,
    val eyesClosed: Boolean = false,
    /** Face position in the camera image, 0..1 (0.5 = centred). */
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    /** Outer-eye-corner distance as a fraction of image width; grows as the face gets closer. */
    val eyeSpan: Float = 0f,
    /** Live filtered iris offsets (see `GazeSample`), for the eye-tracking indicator. */
    val irisDx: Float = 0f,
    val irisDy: Float = 0f,
) {
    /** True when the irises are being tracked: a face is in view and the eyes are open. */
    val eyesTracked: Boolean get() = detected && !eyesClosed
}

/** The sentence bar's result line. */
data class SentenceState(val text: String = "", val isStreaming: Boolean = false)

/**
 * What the CALIBRATION_REQUIRED screen shows while it counts down.
 *
 * @property resumingSaved true when the countdown ends in resuming the persisted calibration
 *   instead of starting a new one.
 */
data class CalibrationPrompt(
    val secondsLeft: Int,
    val resumingSaved: Boolean,
    val savedAvailable: Boolean,
)

/**
 * Orchestrates the pipeline
 * `CameraSource -> FaceLandmarkerHelper -> GazeEstimator -> CalibrationManager -> DwellDetector ->
 * AacIntentDispatcher -> GemmaLlmManager -> TtsManager` and owns the [AppStateMachine].
 *
 * Per-frame work ([onFace]) runs on MediaPipe's callback thread; everything it touches is either
 * confined to that thread or thread-safe. Selections are executed in [viewModelScope].
 */
class GazeViewModel(application: Application) : AndroidViewModel(application) {

    private val machine = AppStateMachine()
    val appState: StateFlow<AppState> = machine.state

    private val estimator = GazeEstimator()
    private val calibration = CalibrationManager(SharedPrefsCalibrationStore(application))
    private val dwellDetector = DwellDetector()
    private val dispatcher = AacIntentDispatcher()
    private val llm = GemmaLlmManager(application)
    private val tts = TtsManager(application)

    private val landmarker = FaceLandmarkerHelper(
        application,
        object : FaceLandmarkerHelper.Listener {
            override fun onFace(face: FaceLandmarks) = this@GazeViewModel.onFace(face, face.timestampMs)
            override fun onNoFace(timestampMs: Long) = this@GazeViewModel.onFace(null, timestampMs)
            override fun onError(message: String) {
                _error.value = message
            }
        },
    )

    private val camera = CameraSource(
        context = application,
        onFrame = { bitmap: Bitmap, timestampMs: Long -> landmarker.detectAsync(bitmap, timestampMs) },
        onError = { message -> _error.value = message },
    )

    val aac: StateFlow<AacUiState> = dispatcher.state
    val dwell: StateFlow<DwellState> = dwellDetector.state
    val llmState: StateFlow<LlmState> = llm.state
    val ttsState: StateFlow<TtsState> = tts.state

    private val _gaze = MutableStateFlow<ScreenPoint?>(null)
    /** Where the user is looking, in root-window pixels; null while the eyes are not tracked. */
    val gaze: StateFlow<ScreenPoint?> = _gaze.asStateFlow()

    private val _face = MutableStateFlow(FaceStatus())
    val face: StateFlow<FaceStatus> = _face.asStateFlow()

    private val _calibrationStep = MutableStateFlow<CalibrationStep.InProgress?>(null)
    val calibrationStep: StateFlow<CalibrationStep.InProgress?> = _calibrationStep.asStateFlow()

    private val _calibrationPrompt = MutableStateFlow<CalibrationPrompt?>(null)
    val calibrationPrompt: StateFlow<CalibrationPrompt?> = _calibrationPrompt.asStateFlow()

    private val _sentence = MutableStateFlow(SentenceState())
    val sentence: StateFlow<SentenceState> = _sentence.asStateFlow()

    private val _permissionDenied = MutableStateFlow(false)
    val permissionDenied: StateFlow<Boolean> = _permissionDenied.asStateFlow()

    private val _calibrationQuality = MutableStateFlow<CalibrationQuality?>(null)
    /** How trustworthy the active gaze mapping is; null before the first calibration. */
    val calibrationQuality: StateFlow<CalibrationQuality?> = _calibrationQuality.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** A short confirmation shown over the board, e.g. "Calibration complete". */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** A problem the user or caregiver should see (camera, model or speech failure). */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _selections = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emits a tile id every time a tile is selected, for click/haptic feedback. */
    val selections: SharedFlow<String> = _selections.asSharedFlow()

    private val screenSize = MutableStateFlow<Pair<Int, Int>?>(null)
    @Volatile
    private var promptJob: Job? = null

    // Vision-thread state.
    private var lastTrackedMs = 0L
    private var eyesClosedSinceMs = 0L

    @Volatile
    private var lastSample: GazeSample? = null

    @Volatile
    private var noticeJob: Job? = null

    init {
        viewModelScope.launch { landmarker.initialize() }
        viewModelScope.launch { llm.initialize() }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle and permission
    // ---------------------------------------------------------------------------------------

    /** IDLE -> PERMISSION_CHECK. Called once the UI is up. */
    fun onAppStarted() {
        machine.transition(AppState.PERMISSION_CHECK, from = AppState.IDLE)
    }

    /** Reports the camera permission result. Granting moves on to calibration and opens the camera. */
    fun onCameraPermissionResult(granted: Boolean, lifecycleOwner: LifecycleOwner) {
        _permissionDenied.value = !granted
        if (!granted) return
        camera.start(lifecycleOwner)
        if (machine.transition(AppState.CALIBRATION_REQUIRED, from = AppState.PERMISSION_CHECK)) {
            startCalibrationPrompt(allowResume = true)
        }
    }

    /** Size of the Compose root in pixels: the coordinate space of gaze points and tile bounds. */
    fun onScreenSize(width: Int, height: Int) {
        if (width > 0 && height > 0) screenSize.value = width to height
    }

    fun dismissError() {
        _error.value = null
    }

    // ---------------------------------------------------------------------------------------
    // Calibration
    // ---------------------------------------------------------------------------------------

    /**
     * Runs the CALIBRATION_REQUIRED countdown. A user who can only move their eyes cannot press
     * "start", so the next step always begins by itself: resuming the saved calibration if there
     * is one and [allowResume] is set, otherwise a new 5-point calibration.
     */
    private fun startCalibrationPrompt(allowResume: Boolean) {
        promptJob?.cancel()
        promptJob = viewModelScope.launch {
            val (width, height) = screenSize.first { it != null }!!
            val savedAvailable = calibration.restoreSaved(width, height)
            val resume = savedAvailable && allowResume
            var remainingMs = if (resume) RESUME_COUNTDOWN_MS else CALIBRATION_COUNTDOWN_MS
            while (remainingMs > 0) {
                _calibrationPrompt.value = CalibrationPrompt(
                    secondsLeft = ((remainingMs + 999) / 1000).toInt(),
                    resumingSaved = resume,
                    savedAvailable = savedAvailable,
                )
                delay(PROMPT_TICK_MS)
                // A new calibration only counts down while a face is in view, so it never starts
                // collecting samples from an empty chair.
                if (resume || _face.value.detected) remainingMs -= PROMPT_TICK_MS
            }
            if (resume) enterGazeActiveFromPrompt() else beginCalibration()
        }
    }

    /** Touch shortcut on the prompt screen: start a new calibration now. */
    fun calibrateNow() {
        if (machine.current != AppState.CALIBRATION_REQUIRED) return
        promptJob?.cancel()
        viewModelScope.launch { beginCalibration() }
    }

    /** Touch shortcut on the prompt screen: keep using the saved calibration. */
    fun useSavedCalibration() {
        if (machine.current != AppState.CALIBRATION_REQUIRED || calibration.calibration == null) return
        promptJob?.cancel()
        enterGazeActiveFromPrompt()
    }

    /** Touch shortcut on the main screen. The gaze equivalent is the eyes-closed gesture. */
    fun requestRecalibration() {
        if (machine.transition(AppState.CALIBRATION_REQUIRED, from = AppState.GAZE_ACTIVE)) {
            _gaze.value = null
            startCalibrationPrompt(allowResume = false)
        }
    }

    /**
     * Touch shortcut on the prompt screen: open the board straight away with the built-in default
     * calibration. Whatever the user is looking at right now is taken as the middle of the screen,
     * so it works best if they are looking at the centre when it is pressed.
     */
    fun skipCalibration() {
        if (machine.current != AppState.CALIBRATION_REQUIRED) return
        val (width, height) = screenSize.value ?: return
        promptJob?.cancel()
        val default = calibration.useDefault(width, height, lastSample)
        estimator.reset()
        if (machine.transition(AppState.GAZE_ACTIVE, from = AppState.CALIBRATION_REQUIRED)) {
            _calibrationPrompt.value = null
            dwellDetector.reset(SystemClock.uptimeMillis())
            _calibrationQuality.value = default.quality
            showNotice("Using default calibration")
        }
    }

    private suspend fun beginCalibration() {
        val (width, height) = screenSize.first { it != null }!!
        estimator.reset()
        // Publish the first target before the state flips so the calibration screen never
        // renders without one.
        _calibrationStep.value = calibration.begin(width, height, SystemClock.uptimeMillis())
        _calibrationPrompt.value = null
        if (!machine.transition(AppState.CALIBRATING_5_POINT, from = AppState.CALIBRATION_REQUIRED)) {
            calibration.cancel()
            _calibrationStep.value = null
        }
    }

    private fun enterGazeActiveFromPrompt() {
        if (machine.transition(AppState.GAZE_ACTIVE, from = AppState.CALIBRATION_REQUIRED)) {
            _calibrationPrompt.value = null
            dwellDetector.reset(SystemClock.uptimeMillis())
            _calibrationQuality.value = calibration.calibration?.quality
        }
    }

    private fun onCalibrationSample(sample: GazeSample?, nowMs: Long) {
        when (val step = calibration.onSample(sample, nowMs)) {
            is CalibrationStep.InProgress -> _calibrationStep.value = step

            // A finished run always yields a usable mapping, so the board always opens. How good
            // the mapping is gets reported instead of being enforced.
            is CalibrationStep.Completed -> {
                val result = step.calibration
                Log.i(
                    TAG,
                    "Calibration ${result.quality}: rms=${result.rmsErrorPx}px " +
                        "X=${result.x0}+${result.xDx}*dx+${result.xDy}*dy " +
                        "Y=${result.y0}+${result.yDx}*dx+${result.yDy}*dy",
                )
                dwellDetector.reset(nowMs)
                if (machine.transition(AppState.GAZE_ACTIVE, from = AppState.CALIBRATING_5_POINT)) {
                    _calibrationQuality.value = result.quality
                    showNotice("Calibration complete", announce = true)
                }
                _calibrationStep.value = null
            }

            null -> Unit
        }
    }

    /** Shows [text] over the board for a few seconds and, if [announce], says it aloud. */
    private fun showNotice(text: String, announce: Boolean = false) {
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            _notice.value = text
            if (announce) launch { tts.speak(text) }
            delay(NOTICE_DURATION_MS)
            _notice.value = null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Per-frame gaze processing (vision thread)
    // ---------------------------------------------------------------------------------------

    private fun onFace(face: FaceLandmarks?, timestampMs: Long) {
        val sample = face?.let(estimator::estimate)
        lastSample = sample
        _face.value = if (sample == null) {
            FaceStatus(detected = false)
        } else {
            FaceStatus(
                detected = true,
                eyesClosed = sample.eyesClosed,
                centerX = sample.faceCenterX,
                centerY = sample.faceCenterY,
                eyeSpan = sample.eyeSpan,
                irisDx = sample.dx,
                irisDy = sample.dy,
            )
        }

        when (machine.current) {
            AppState.CALIBRATING_5_POINT -> onCalibrationSample(sample, timestampMs)

            AppState.GAZE_ACTIVE -> {
                val point = updatePointer(sample, timestampMs)
                if (detectRecalibrationGesture(sample, timestampMs)) {
                    requestRecalibration()
                    return
                }
                val selectedId = dwellDetector.onGaze(point, timestampMs)
                if (selectedId != null) activateTile(selectedId)
            }

            // The pointer keeps following the eyes while the app is busy, but nothing can be
            // selected until it is back in GAZE_ACTIVE.
            AppState.DWELL_TRIGGERED, AppState.LLM_EXPANDING, AppState.SPEAKING ->
                updatePointer(sample, timestampMs)

            else -> Unit
        }
    }

    /** @return the current gaze point, or null if the eyes are not usable in this frame. */
    private fun updatePointer(sample: GazeSample?, nowMs: Long): ScreenPoint? {
        if (sample == null || sample.eyesClosed) {
            // Keep the pointer where it was through a blink; hide it once tracking is really lost.
            if (nowMs - lastTrackedMs > POINTER_HIDE_AFTER_MS) _gaze.value = null
            return null
        }
        lastTrackedMs = nowMs
        val point = calibration.mapToScreen(sample.dx, sample.dy)
        _gaze.value = point
        return point
    }

    /**
     * Recalibration must be reachable even when the calibration is too far off to hit any tile,
     * so it is a gesture that does not depend on gaze position: close both eyes for 2-5 seconds,
     * then open them. Anything longer is treated as resting or sleeping and ignored.
     */
    private fun detectRecalibrationGesture(sample: GazeSample?, nowMs: Long): Boolean {
        if (sample == null) {
            eyesClosedSinceMs = 0L
            return false
        }
        if (sample.eyesClosed) {
            if (eyesClosedSinceMs == 0L) eyesClosedSinceMs = nowMs
            return false
        }
        val closedForMs = if (eyesClosedSinceMs == 0L) 0L else nowMs - eyesClosedSinceMs
        eyesClosedSinceMs = 0L
        return closedForMs in RECALIBRATE_GESTURE_MIN_MS..RECALIBRATE_GESTURE_MAX_MS
    }

    // ---------------------------------------------------------------------------------------
    // Selection -> AAC intent -> LLM -> TTS
    // ---------------------------------------------------------------------------------------

    /** Registers (or moves) a tile's bounds as a dwell target. */
    fun setTileBounds(tileId: String, bounds: GazeRect) {
        val tile = dispatcher.findTile(tileId) ?: return
        if (tile.enabled) dwellDetector.setTarget(tileId, bounds, tile.dwellMs) else dwellDetector.removeTarget(tileId)
    }

    fun removeTile(tileId: String) {
        dwellDetector.removeTarget(tileId)
    }

    /**
     * Selects a tile, whether by dwell (vision thread) or by touch (main thread). Selections are
     * only accepted in GAZE_ACTIVE; the state machine makes the two sources mutually exclusive.
     */
    fun activateTile(tileId: String) {
        val tile = dispatcher.findTile(tileId)?.takeIf { it.enabled } ?: return
        if (!machine.transition(AppState.DWELL_TRIGGERED, from = AppState.GAZE_ACTIVE)) return
        _selections.tryEmit(tileId)
        viewModelScope.launch {
            try {
                when (val effect = dispatcher.dispatch(tile.intent)) {
                    AacEffect.None -> Unit
                    is AacEffect.Speak -> speak(effect.sentence)
                    is AacEffect.Expand -> expandAndSpeak(effect.keywords)
                }
            } finally {
                withContext(NonCancellable) {
                    // The cooldown starts when the app becomes selectable again, not when the
                    // tile fired, so a long utterance cannot end straight into a new dwell.
                    dwellDetector.reset(SystemClock.uptimeMillis())
                    machine.transition(AppState.GAZE_ACTIVE)
                }
            }
        }
    }

    private suspend fun speak(sentence: String) {
        _sentence.value = SentenceState(sentence)
        if (!machine.transition(AppState.SPEAKING)) return
        val spoken = withTimeoutOrNull(SPEECH_TIMEOUT_MS) { tts.speak(sentence) }
        if (spoken != true && tts.state.value == TtsState.UNAVAILABLE) {
            _error.value = "Speech is unavailable. Install or enable a text-to-speech engine in Android settings."
        }
    }

    private suspend fun expandAndSpeak(keywords: List<String>) {
        if (!machine.transition(AppState.LLM_EXPANDING, from = AppState.DWELL_TRIGGERED)) return
        _sentence.value = SentenceState(isStreaming = true)
        var expanded = ""
        try {
            llm.expand(keywords).collect { partial ->
                expanded = partial
                _sentence.value = SentenceState(partial, isStreaming = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LLM expansion failed", e)
        }
        // Being heard matters more than being eloquent: without a model, speak the keywords.
        val sentence = expanded.ifBlank { AacPromptTemplate.fallbackSentence(keywords) }
        speak(sentence)
        dispatcher.onSentenceSpoken()
    }

    override fun onCleared() {
        promptJob?.cancel()
        camera.stop()
        landmarker.close()
        tts.shutdown()
        // viewModelScope is already cancelled here; release the native engine on its own thread.
        Thread { kotlinx.coroutines.runBlocking { llm.close() } }.start()
        super.onCleared()
    }

    private companion object {
        const val TAG = "GazeViewModel"
        const val CALIBRATION_COUNTDOWN_MS = 5_000L
        const val RESUME_COUNTDOWN_MS = 3_000L
        const val PROMPT_TICK_MS = 100L
        const val POINTER_HIDE_AFTER_MS = 600L
        const val RECALIBRATE_GESTURE_MIN_MS = 2_000L
        const val RECALIBRATE_GESTURE_MAX_MS = 5_000L
        const val SPEECH_TIMEOUT_MS = 30_000L
        const val NOTICE_DURATION_MS = 3_000L
    }
}
