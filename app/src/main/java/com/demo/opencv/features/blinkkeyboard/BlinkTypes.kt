package com.demo.opencv.features.blinkkeyboard

data class BlinkConfig(
    val earThreshold: Float = 0.20f,
    val involuntaryBlinkMaxMs: Long = 250L,
    val minBlinkDurationMs: Long = 350L,
    val maxBlinkDurationMs: Long = 1200L,
    val restClosureMs: Long = 1800L,
    /** Eye openings shorter than this inside a closure are treated as tracking flicker, not a reopen. */
    val reopenGraceMs: Long = 90L,
    /** Minimum gap between two selections (blink or switch), to stop double triggers. */
    val selectionCooldownMs: Long = 450L,
    val scanIntervalMs: Long = 1600L,
    val autoSleepLoops: Int = 4,
    val columnSweepsBeforeCancel: Int = 2,
    /** ML Kit eye-open probability below which an eye counts as closed, regardless of EAR. */
    val eyeOpenProbabilityThreshold: Float = 0.25f,
)

enum class ScanTier { ROW, COLUMN, PAUSED }

enum class KeyType { CHAR, ACTION, SUGGESTION }

enum class RowStyle { SUGGESTIONS, LETTERS, ACTIONS }

data class KeyItem(
    val id: String,
    val label: String,
    val type: KeyType,
    val value: String,
)

data class KeyboardRow(
    val id: String,
    val label: String,
    val items: List<KeyItem>,
    val style: RowStyle,
)

object KeyActions {
    const val BACKSPACE = "BACKSPACE"
    const val SPACE = "SPACE"
    const val SPEAK_AND_SAVE = "SPEAK_AND_SAVE"
    const val CANCEL = "CANCEL"
}
