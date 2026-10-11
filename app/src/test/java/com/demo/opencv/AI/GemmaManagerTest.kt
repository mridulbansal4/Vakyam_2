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
        assertEquals("Keywords: water. Context: ", turn)

        val turnBlank = GemmaManager.buildUserTurn("water", "   ")
        assertEquals("Keywords: water. Context: ", turnBlank)
    }

    @Test
    fun testUserTurnMatchesFewShotFormat() {
        for (lang in listOf("English", "Hinglish")) {
            val (_, examples) = GemmaManager.promptAndExamples(lang)
            val noContextExample = examples.first { it.first.endsWith("Context: ") }.first
            val keywords = noContextExample.removePrefix("Keywords: ").removeSuffix(". Context: ")
            assertEquals(noContextExample, GemmaManager.buildUserTurn(keywords, ""))
        }
    }

    @Test
    fun testSingleKeywordExpandsToSentence() {
        assertEquals("I would like some water, please.", GemmaManager.fallbackSentence("water", "English"))
        assertEquals("I would like something to eat, please.", GemmaManager.fallbackSentence("food", "English"))
        assertEquals("Could I please have some tea?", GemmaManager.fallbackSentence("tea", "English"))
        assertEquals("I need to use the washroom, please.", GemmaManager.fallbackSentence("Toilet", "English"))
        assertEquals("Can someone please help me?", GemmaManager.fallbackSentence("help", "English"))
        assertEquals("I want book, please.", GemmaManager.fallbackSentence("book", "English"))
        assertEquals("I want AC, please.", GemmaManager.fallbackSentence(" AC, ", "English"))

        assertEquals("Mujhe paani chahiye.", GemmaManager.fallbackSentence("water", "Hinglish"))
        assertEquals("Mujhe paani chahiye.", GemmaManager.fallbackSentence("paani", "Hinglish"))
        assertEquals("Mujhe dawai leni hai.", GemmaManager.fallbackSentence("dawai", "Hinglish"))
        assertEquals("Kripya meri madad karein.", GemmaManager.fallbackSentence("help", "Hinglish"))
        assertEquals("Mujhe kitaab chahiye.", GemmaManager.fallbackSentence("kitaab", "Hinglish"))
    }

    @Test
    fun testStandaloneRepliesAreNotExpanded() {
        assertEquals("Yes.", GemmaManager.fallbackSentence("yes", "English"))
        assertEquals("Thanks.", GemmaManager.fallbackSentence("thanks", "English"))
        assertEquals("Nahi.", GemmaManager.fallbackSentence("nahi", "Hinglish"))
    }

    @Test
    fun testBareKeywordReplyIsDetected() {
        assertTrue(GemmaManager.isBareKeyword("Water.", "water"))
        assertTrue(GemmaManager.isBareKeyword("\"water\"", "water"))
        assertTrue(GemmaManager.isBareKeyword("Water, cold.", "water, cold"))
        assertTrue(GemmaManager.isBareKeyword("   ", "water"))
        assertFalse(GemmaManager.isBareKeyword("I would like some water, please.", "water"))
        assertFalse(GemmaManager.isBareKeyword("Mujhe thanda paani chahiye.", "paani, thanda"))
    }

    @Test
    fun testParseWordListKeepsCleanSingleWords() {
        val raw = "Rice, roti,\n1. dal, the, ice cream, \"soup\", rice, \u092A\u093E\u0928\u0940, *chicken*, I, want, khichdi."
        assertEquals(listOf("rice", "roti", "dal", "soup", "chicken", "khichdi"), GemmaManager.parseWordList(raw))
        assertEquals(listOf("rice", "roti"), GemmaManager.parseWordList("rice, roti, dal", limit = 2))
        assertTrue(GemmaManager.parseWordList("").isEmpty())
        assertTrue(GemmaManager.parseWordList("I would like to eat rice.").isEmpty()) // a sentence is not a list
    }

    @Test
    fun testWordListPromptsAndExamples() {
        assertEquals("Context: where do you want to go", GemmaManager.buildWordListTurn("  where do you want to go "))
        for (lang in listOf("English", "Hinglish")) {
            val (system, examples) = GemmaManager.wordListPromptAndExamples(lang)
            assertTrue(lang, system.contains("comma"))
            assertFalse(lang, GemmaManager.hasDevanagari(system))
            assertEquals(2, examples.size)
            for ((user, model) in examples) {
                assertTrue(user, user.startsWith("Context: "))
                val words = GemmaManager.parseWordList(model)
                assertTrue("$lang example parses to ${words.size} words", words.size >= 25)
                assertFalse(GemmaManager.hasDevanagari(model))
            }
        }
        assertTrue(GemmaManager.wordListPromptAndExamples("Hinglish").first.contains("never in Devanagari"))
        assertEquals("rice", GemmaManager.parseWordList(GemmaManager.wordListPromptAndExamples("English").second[0].second).first())
        assertEquals("park", GemmaManager.parseWordList(GemmaManager.wordListPromptAndExamples("English").second[1].second).first())
    }

    @Test
    fun testPromptsForbidBareKeywordReplies() {
        for (lang in listOf("English", "Hinglish")) {
            val (system, _) = GemmaManager.promptAndExamples(lang)
            assertTrue(lang, system.contains("NEVER output only the raw keyword or a single word."))
        }
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
    fun testCleanSentenceKeepsHyphens() {
        val raw = "I am well-rested and feel good."
        val cleaned = GemmaManager.cleanSentence(raw)
        assertTrue(cleaned.contains("-"))
        assertEquals("I am well-rested and feel good.", cleaned)
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
        assertEquals("Part-time.", fallbackWithHyphen)

        val fallbackEmpty = GemmaManager.fallbackSentence("")
        assertEquals("Yes.", fallbackEmpty)

        val fallbackTrailingPunct = GemmaManager.fallbackSentence("I need help!")
        assertEquals("I need help!.", fallbackTrailingPunct.removeSuffix(".."))
    }

    @Test
    fun testHinglishFallback() {
        assertEquals("Haan.", GemmaManager.fallbackSentence("", "Hinglish"))
        assertEquals("Paani chahiye.", GemmaManager.fallbackSentence("paani chahiye", "Hinglish"))
        assertEquals("Yes.", GemmaManager.fallbackSentence("", "English"))
        assertFalse(GemmaManager.hasDevanagari(GemmaManager.fallbackSentence("", "Hindi")))
        assertTrue(GemmaManager.hasDevanagari("\u092A\u093E\u0928\u0940"))
        assertFalse(GemmaManager.hasDevanagari("Mujhe paani chahiye."))
    }

    @Test
    fun testHinglishTokenBudget() {
        val manager = GemmaManager()
        assertEquals(64, manager.outputTokenBudget("English"))
        assertEquals(64, manager.outputTokenBudget("Hinglish"))
        manager.setPerformanceTier(com.demo.opencv.sensors.PerformanceTier.POWER_SAVER)
        assertEquals(20, manager.outputTokenBudget("English"))
        assertEquals(32, manager.outputTokenBudget("Hinglish"))
    }

    @Test
    fun testHinglishPromptStaysInRomanScript() {
        val (system, examples) = GemmaManager.promptAndExamples("Hinglish")
        assertTrue(system.contains("English (Roman) letters"))
        assertTrue(system.contains("never in Devanagari"))
        assertEquals(5, examples.size)
        for ((user, model) in examples) {
            assertTrue(user, user.startsWith("Keywords: "))
            assertFalse(model, GemmaManager.hasDevanagari(user + model))
        }
        assertTrue(examples.any { it.first.contains("AC") }) // code-mixed
        assertTrue(examples.any { !it.first.endsWith("Context: ") }) // with context
        // No prompt anywhere is in Devanagari; an old "Hindi" setting gets the English prompt.
        for (lang in listOf("English", "Hindi")) {
            val (s, ex) = GemmaManager.promptAndExamples(lang)
            assertFalse(GemmaManager.hasDevanagari(s + ex))
            assertEquals(5, ex.size)
        }
    }

    @Test
    fun testHinglishContextIsUnderstoodInBothPrompts() {
        for (lang in listOf("English", "Hinglish")) {
            val (system, examples) = GemmaManager.promptAndExamples(lang)
            assertTrue(lang, system.contains("\"aapko khane me kya chahiye\" means \"what do you want to eat\""))
            // A Hinglish question is answered by meaning, in the prompt's own language.
            val (_, answer) = examples.single { it.first.endsWith("Context: aapko khane me kya chahiye") }
            if (lang == "English") assertEquals("I want to eat rice with curd.", answer)
            else assertEquals("Mujhe roti aur dal khani hai.", answer)
        }
    }
}
