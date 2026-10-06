package com.demo.opencv.AI

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaManagerTest {

    @Test
    fun testSingletonInstance() {
        val instance1 = GemmaManager.getInstance()
        val instance2 = GemmaManager.getInstance()
        assertSame("GemmaManager.getInstance() should return the same singleton instance", instance1, instance2)
    }

    @Test
    fun testBuildUserTurnWithContext() {
        val turn = GemmaManager.buildUserTurn("chicken, rice", "What do you want for dinner?")
        assertEquals("Keywords: chicken, rice. Context: What do you want for dinner?", turn)
    }

    @Test
    fun testBuildUserTurnWithoutContext() {
        val turn = GemmaManager.buildUserTurn("water", "")
        assertEquals("Keywords: water", turn)

        val turnBlank = GemmaManager.buildUserTurn("water", "   ")
        assertEquals("Keywords: water", turnBlank)
    }

    @Test
    fun testCleanSentencePrefixesAndQuotes() {
        val raw = "Sentence: \"I want to have chicken for dinner.\""
        val cleaned = GemmaManager.cleanSentence(raw)
        assertEquals("I want to have chicken for dinner.", cleaned)

        val rawOutput = "Output: *I am happy today.*"
        val cleanedOutput = GemmaManager.cleanSentence(rawOutput)
        assertEquals("I am happy today.", cleanedOutput)
    }

    @Test
    fun testCleanSentenceMultiline() {
        val raw = "Sentence: First line of output.\nSecond line explanation that should be ignored."
        val cleaned = GemmaManager.cleanSentence(raw)
        assertEquals("First line of output.", cleaned)
    }

    @Test
    fun testCleanSentenceReplacesHyphens() {
        val raw = "I am well-rested and feel good."
        val cleaned = GemmaManager.cleanSentence(raw)
        assertFalse(cleaned.contains("-"))
        assertEquals("I am well rested and feel good.", cleaned)
    }

    @Test
    fun testCleanSentenceEmpty() {
        val cleaned = GemmaManager.cleanSentence("")
        assertEquals("", cleaned)

        val cleanedWhitespace = GemmaManager.cleanSentence("   \n\n  ")
        assertEquals("", cleanedWhitespace)
    }

    @Test
    fun testFallbackSentence() {
        val fallback = GemmaManager.fallbackSentence("cold water please")
        assertEquals("Cold water please.", fallback)

        val fallbackWithHyphen = GemmaManager.fallbackSentence("part-time")
        assertEquals("Part time.", fallbackWithHyphen)

        val fallbackEmpty = GemmaManager.fallbackSentence("")
        assertEquals("Yes.", fallbackEmpty)

        val fallbackTrailingPunct = GemmaManager.fallbackSentence("I need help!")
        assertEquals("I need help!.", fallbackTrailingPunct.removeSuffix(".."))
    }
}
