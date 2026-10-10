package com.demo.opencv.metrics

import java.util.Locale

/** Standard text-entry measures, so results are comparable with published AAC numbers. */
object TypingMetrics {

    private val NOT_TEXT = Regex("[^\\p{L}\\p{M}\\p{N}\\s]")
    private val WHITESPACE = Regex("\\s+")

    /** Case, punctuation and spacing differences are not typing errors. */
    @JvmStatic
    fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(NOT_TEXT, "").replace(WHITESPACE, " ").trim()

    /** Words per minute with the usual 5-characters-per-word convention, first to last selection. */
    @JvmStatic
    fun wordsPerMinute(typed: String, durationMs: Long): Float {
        val characters = normalize(typed).length
        if (characters < 2 || durationMs <= 0L) return 0f
        return (characters - 1) / 5f * 60_000f / durationMs
    }

    /** Minimum string distance between target and result, as a percentage of the longer one. */
    @JvmStatic
    fun errorPercent(target: String, typed: String): Float {
        val a = normalize(target)
        val b = normalize(typed)
        val longest = maxOf(a.length, b.length)
        return if (longest == 0) 0f else 100f * editDistance(a, b) / longest
    }

    @JvmStatic
    fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current.also { current = previous }
        }
        return previous[b.length]
    }
}

data class TrialResult(
    val timestampMs: Long,
    val mode: String,
    val language: String,
    val target: String,
    val typed: String,
    val durationMs: Long,
    val selections: Int,
    val corrections: Int,
    val conditions: String,
) {
    val wordsPerMinute: Float get() = TypingMetrics.wordsPerMinute(typed, durationMs)
    val errorPercent: Float get() = TypingMetrics.errorPercent(target, typed)

    /** Eye gestures or blinks spent per character produced; lower is less effort. */
    val selectionsPerCharacter: Float
        get() = TypingMetrics.normalize(typed).length.let { if (it == 0) 0f else selections.toFloat() / it }

    fun summary(): String = String.format(
        Locale.US, "%.1f WPM · %.0f%% errors · %d selections · %d corrections · %.0f s",
        wordsPerMinute, errorPercent, selections, corrections, durationMs / 1000f,
    )

    fun toCsvRow(): String = listOf(
        timestampMs.toString(), mode, language, target, typed, durationMs.toString(),
        selections.toString(), corrections.toString(),
        String.format(Locale.US, "%.2f", wordsPerMinute),
        String.format(Locale.US, "%.1f", errorPercent),
        String.format(Locale.US, "%.2f", selectionsPerCharacter),
        conditions,
    ).joinToString(",", transform = ::quote)

    companion object {
        const val CSV_HEADER =
            "timestamp_ms,mode,language,target,typed,duration_ms,selections,corrections,wpm,error_percent,selections_per_char,conditions"

        /** Null for the header, blank lines or rows that do not parse. */
        @JvmStatic
        fun fromCsvRow(row: String): TrialResult? {
            val cells = splitCsv(row)
            if (cells.size < 8) return null
            return TrialResult(
                timestampMs = cells[0].toLongOrNull() ?: return null,
                mode = cells[1],
                language = cells[2],
                target = cells[3],
                typed = cells[4],
                durationMs = cells[5].toLongOrNull() ?: return null,
                selections = cells[6].toIntOrNull() ?: return null,
                corrections = cells[7].toIntOrNull() ?: return null,
                conditions = cells.getOrElse(11) { "" },
            )
        }

        private fun quote(cell: String) = "\"" + cell.replace("\"", "\"\"").replace('\n', ' ') + "\""

        private fun splitCsv(row: String): List<String> {
            val cells = ArrayList<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            while (i < row.length) {
                val c = row[i]
                when {
                    quoted && c == '"' && row.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { cells.add(cell.toString()); cell.setLength(0) }
                    else -> cell.append(c)
                }
                i++
            }
            cells.add(cell.toString())
            return cells
        }
    }
}

/**
 * One timed transcription of [target]. The clock starts at the patient's first selection, so the
 * caregiver setting up the screen is not counted, and stops at the selection that ends the trial.
 */
class TypingTrial(val target: String, val language: String) {
    var startedAtMs = -1L
        private set
    var selections = 0
        private set
    var corrections = 0
        private set
    private var lastSelectionMs = -1L

    val isRunning: Boolean get() = startedAtMs >= 0L

    fun onSelection(nowMs: Long) {
        if (startedAtMs < 0L) startedAtMs = nowMs
        lastSelectionMs = nowMs
        selections++
    }

    fun onCorrection() {
        corrections++
    }

    fun elapsedMs(nowMs: Long): Long = if (isRunning) nowMs - startedAtMs else 0L

    /** True once [typed] reads the same as the target. */
    fun isComplete(typed: String): Boolean = TypingMetrics.normalize(typed) == TypingMetrics.normalize(target)

    fun finish(typed: String, mode: String, wallClockMs: Long, conditions: String): TrialResult = TrialResult(
        timestampMs = wallClockMs,
        mode = mode,
        language = language,
        target = target,
        typed = typed.trim(),
        durationMs = if (isRunning) lastSelectionMs - startedAtMs else 0L,
        selections = selections,
        corrections = corrections,
        conditions = conditions,
    )
}
