package com.demo.opencv.metrics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sentences for the typing test: short, everyday, and made of words the built-in dictionaries contain. */
object TypingTestPhrases {
    private val ENGLISH = arrayOf(
        "i need water", "please call the nurse", "i feel some pain", "turn off the light", "thank you very much",
    )
    private val HINGLISH = arrayOf(
        "paani chahiye", "doctor ko bulao", "dard ho raha hai", "main theek hoon", "bahut dhanyavaad",
    )

    @JvmStatic
    fun forLanguage(language: String?): Array<String> = when (language) {
        "Hinglish" -> HINGLISH
        else -> ENGLISH
    }
}

/** Plain-text view of the trial log for the Data screen. */
object TrialReport {
    private const val RECENT = 12

    @JvmStatic
    fun format(results: List<TrialResult>, emptyText: String): String {
        if (results.isEmpty()) return emptyText
        val dates = SimpleDateFormat("dd MMM HH:mm", Locale.US)
        return buildString {
            results.groupBy { it.mode }.toSortedMap().forEach { (mode, trials) ->
                val speeds = trials.map { it.wordsPerMinute }
                appendLine(
                    String.format(
                        Locale.US, "%-15s %2d tests  avg %4.1f WPM  best %4.1f  errors %3.0f%%  %.1f selections/char",
                        mode, trials.size, speeds.average(), speeds.max(),
                        trials.map { it.errorPercent }.average(), trials.map { it.selectionsPerCharacter }.average(),
                    )
                )
            }
            appendLine()
            results.takeLast(RECENT).asReversed().forEach {
                appendLine(
                    String.format(
                        Locale.US, "%s  %-15s %4.1f WPM  %3.0f%% err  %3d sel  %3.0f s  %s",
                        dates.format(Date(it.timestampMs)), it.mode, it.wordsPerMinute, it.errorPercent,
                        it.selections, it.durationMs / 1000f, it.target,
                    )
                )
            }
        }.trimEnd()
    }
}
