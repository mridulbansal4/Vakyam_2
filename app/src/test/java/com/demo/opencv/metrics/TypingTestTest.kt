package com.demo.opencv.metrics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TypingMetricsTest {

    @Test
    fun wordsPerMinuteUsesFiveCharactersPerWord() {
        // 11 characters, so 10 after the first selection = 2 words, in one minute.
        assertEquals(2f, TypingMetrics.wordsPerMinute("hello world", 60_000L), 0.001f)
        assertEquals(4f, TypingMetrics.wordsPerMinute("hello world", 30_000L), 0.001f)
        assertEquals(0f, TypingMetrics.wordsPerMinute("hello world", 0L), 0f)
        assertEquals(0f, TypingMetrics.wordsPerMinute("", 60_000L), 0f)
    }

    @Test
    fun caseAndPunctuationAreNotErrors() {
        assertEquals(0f, TypingMetrics.errorPercent("i need water", "I need  water."), 0f)
        assertEquals("i need water", TypingMetrics.normalize("  I need\twater!! "))
        assertEquals("पानी चाहिए", TypingMetrics.normalize("पानी  चाहिए।"))
    }

    @Test
    fun errorRateIsEditDistanceOverTheLongerText() {
        assertEquals(3, TypingMetrics.editDistance("kitten", "sitting"))
        assertEquals(0, TypingMetrics.editDistance("", ""))
        assertEquals(4, TypingMetrics.editDistance("", "abcd"))
        assertEquals(100f * 1 / 12, TypingMetrics.errorPercent("i need water", "i need watr"), 0.001f)
        assertEquals(100f, TypingMetrics.errorPercent("abc", ""), 0f)
    }

    @Test
    fun aTrialRunsFromTheFirstSelectionToTheLast() {
        val trial = TypingTrial("i need water", "English")
        assertFalse(trial.isRunning)
        assertEquals(0L, trial.elapsedMs(5_000L))
        trial.onSelection(10_000L)
        trial.onSelection(25_000L)
        trial.onCorrection()
        trial.onSelection(70_000L)
        assertEquals(50_000L, trial.elapsedMs(60_000L))

        val result = trial.finish(" i need water ", "gaze-letter", 123L, "tier=FULL")
        assertEquals(60_000L, result.durationMs)
        assertEquals(3, result.selections)
        assertEquals(1, result.corrections)
        assertEquals("i need water", result.typed)
        assertEquals(2.2f, result.wordsPerMinute, 0.001f)
        assertEquals(0f, result.errorPercent, 0f)
        assertEquals(0.25f, result.selectionsPerCharacter, 0.001f)
    }

    @Test
    fun resultsSurviveTheCsvFile() {
        val original = TrialResult(
            timestampMs = 1_760_000_000_000L, mode = "blink", language = "English",
            target = "say \"hi\", please", typed = "say hi, please", durationMs = 42_000L,
            selections = 31, corrections = 2, conditions = "tier=FULL fps=28, lux=120",
        )
        assertEquals(original, TrialResult.fromCsvRow(original.toCsvRow()))
        assertNull(TrialResult.fromCsvRow(TrialResult.CSV_HEADER))
        assertNull(TrialResult.fromCsvRow(""))
        assertNull(TrialResult.fromCsvRow("garbage,row"))
    }

    @Test
    fun theLogAppendsAndReadsBack() {
        val file = File.createTempFile("trials", ".csv").apply { delete() }
        try {
            val log = TrialLog(file)
            assertTrue(log.read().isEmpty())
            val first = TrialResult(1L, "blink", "English", "a b", "a b", 9_000L, 7, 0, "")
            val second = first.copy(timestampMs = 2L, mode = "gaze-letter")
            log.append(first)
            log.append(second)
            assertEquals(listOf(first, second), log.read())
            assertTrue(log.readText().startsWith(TrialResult.CSV_HEADER))
        } finally {
            file.delete()
        }
    }

    @Test
    fun theReportSummarisesEachModeAndListsNewestFirst() {
        val slow = TrialResult(1_000L, "blink", "English", "i need water", "i need water", 120_000L, 40, 1, "")
        val fast = TrialResult(2_000L, "gaze-llm", "English", "thank you", "thank you", 30_000L, 6, 0, "")
        val report = TrialReport.format(listOf(slow, fast), "none")
        val lines = report.lines()
        assertTrue(lines[0].startsWith("blink") && "1.1 WPM" in lines[0])
        assertTrue(lines[1].startsWith("gaze-llm") && "3.2 WPM" in lines[1])
        assertTrue(lines.indexOfFirst { "thank you" in it } < lines.indexOfFirst { "i need water" in it })
        assertEquals("none", TrialReport.format(emptyList(), "none"))
    }
}

class TypingTestTest {

    private var now = 0L
    private var changes = 0
    private val listener = TypingTest.Listener { changes++ }

    @Before
    fun setUp() {
        TypingTest.clock = { now }
        TypingTest.clear()
        TypingTest.addListener(listener)
        changes = 0
    }

    @After
    fun tearDown() {
        TypingTest.removeListener(listener)
        TypingTest.clear()
    }

    @Test
    fun inputWithNoTestArmedIsIgnored() {
        TypingTest.onSelection()
        TypingTest.onCorrection()
        TypingTest.onSpoken("hello", TypingTest.MODE_BLINK)
        assertFalse(TypingTest.isActive)
        assertNull(TypingTest.lastResult)
        assertEquals(0, changes)
    }

    @Test
    fun speakingEndsTheTest() {
        TypingTest.arm("i need water", "English")
        TypingTest.conditions = "tier=FULL"
        assertTrue(TypingTest.isActive)
        now = 5_000L
        TypingTest.onSelection()
        TypingTest.onCorrection()
        now = 35_000L
        TypingTest.onSelection()
        assertEquals(30_000L, TypingTest.elapsedMs())
        TypingTest.onSpoken("I need water.", TypingTest.MODE_GAZE_LLM)

        assertFalse(TypingTest.isActive)
        val result = TypingTest.lastResult
        assertNotNull(result)
        assertEquals(TypingTest.MODE_GAZE_LLM, result!!.mode)
        assertEquals(30_000L, result.durationMs)
        assertEquals(2, result.selections)
        assertEquals(1, result.corrections)
        assertEquals("tier=FULL", result.conditions)
        assertEquals(0f, result.errorPercent, 0f)
        assertTrue(changes >= 4)
    }

    @Test
    fun speechBeforeTheFirstSelectionDoesNotEndTheTest() {
        TypingTest.arm("i need water", "English")
        TypingTest.onSpoken("something said earlier", TypingTest.MODE_BLINK)
        assertTrue(TypingTest.isActive)
        assertNull(TypingTest.lastResult)
    }

    @Test
    fun letterModeEndsOnlyWhenTheTextMatches() {
        TypingTest.arm("hi there", "English")
        TypingTest.onSelection()
        TypingTest.onTextChanged("hi the", TypingTest.MODE_GAZE_LETTER)
        assertTrue(TypingTest.isActive)
        TypingTest.onSelection()
        TypingTest.onTextChanged("Hi there", TypingTest.MODE_GAZE_LETTER)
        assertFalse(TypingTest.isActive)
        assertEquals("Hi there", TypingTest.lastResult!!.typed)
    }

    @Test
    fun armingAgainDiscardsTheOldResult() {
        TypingTest.arm("a b", "English")
        TypingTest.onSelection()
        TypingTest.onSpoken("a b", TypingTest.MODE_BLINK)
        TypingTest.arm("c d", "English")
        assertNull(TypingTest.lastResult)
        assertEquals("c d", TypingTest.trial!!.target)
    }
}
