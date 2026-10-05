package com.vakyam.app.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Top-level application states. See AGENT_CONTEXT.md for the full transition table. */
enum class AppState {
    IDLE,
    PERMISSION_CHECK,
    CALIBRATION_REQUIRED,
    CALIBRATING_5_POINT,
    GAZE_ACTIVE,
    DWELL_TRIGGERED,
    LLM_EXPANDING,
    SPEAKING,
}

/**
 * Single source of truth for [AppState]. Every transition must be listed in [ALLOWED]; anything
 * else is rejected, so a late callback from the vision thread can never drag the app into a state
 * the UI thread has already left.
 *
 * Thread-safe: transitions are requested from both the main thread and the vision callback thread.
 */
class AppStateMachine(initial: AppState = AppState.IDLE) {

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<AppState> = _state.asStateFlow()

    val current: AppState get() = _state.value

    /**
     * Moves to [to] if the edge `current -> to` is legal. When [from] is given the transition
     * additionally requires the current state to be [from] (compare-and-set), which resolves races
     * between e.g. a dwell selection and a touch selection.
     *
     * @return true if the state changed.
     */
    @Synchronized
    fun transition(to: AppState, from: AppState? = null): Boolean {
        val now = _state.value
        if (from != null && now != from) return false
        if (to !in ALLOWED.getValue(now)) return false
        _state.value = to
        return true
    }

    companion object {
        val ALLOWED: Map<AppState, Set<AppState>> = mapOf(
            AppState.IDLE to setOf(AppState.PERMISSION_CHECK),
            AppState.PERMISSION_CHECK to setOf(AppState.CALIBRATION_REQUIRED),
            // -> GAZE_ACTIVE directly when a persisted calibration is resumed or calibration is
            // skipped in favour of the default mapping.
            AppState.CALIBRATION_REQUIRED to setOf(AppState.CALIBRATING_5_POINT, AppState.GAZE_ACTIVE),
            // A calibration run cannot fail: it always ends in GAZE_ACTIVE.
            AppState.CALIBRATING_5_POINT to setOf(AppState.GAZE_ACTIVE),
            // -> CALIBRATION_REQUIRED on a recalibration request (eyes-closed gesture or touch).
            AppState.GAZE_ACTIVE to setOf(AppState.DWELL_TRIGGERED, AppState.CALIBRATION_REQUIRED),
            AppState.DWELL_TRIGGERED to setOf(AppState.LLM_EXPANDING, AppState.SPEAKING, AppState.GAZE_ACTIVE),
            AppState.LLM_EXPANDING to setOf(AppState.SPEAKING, AppState.GAZE_ACTIVE),
            AppState.SPEAKING to setOf(AppState.GAZE_ACTIVE),
        )
    }
}
