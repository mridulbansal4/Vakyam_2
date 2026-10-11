package com.demo.opencv.textEntry

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typing through the real TextEntryManager with the shipped word lists, as the patient does with their eyes. */
class ContextWordPredictionTest {

    private val assets = BlurryInput.AssetSource { File(HinglishDetectorTest.ASSETS, it).inputStream() }

    private fun manager() = TextEntryManager().apply {
        blurryInput.initialize("English", assets, HinglishDetectorTest.assetLexicon())
        LLMEnabled = false
    }

    // Letter mode: ABCDEF = 4, GHIJKLM = 5, NOPQRST = 1, UVWXYZ = 2
    private fun TextEntryManager.typeGroupsOf(word: String, groups: Int = Int.MAX_VALUE) {
        val inputFor = mapOf('0' to 4, '1' to 5, '2' to 1, '3' to 2)
        blurryInput.processWord(word).take(groups).forEach { manageUserInput(inputFor.getValue(it), false) }
    }

    private fun TextEntryManager.firstPage(): List<String> {
        val options = getDisplays().Options
        return listOf(options[8], options[9], options[6]) // word slots 1, 2, 3 in letter mode
    }

    @Test
    fun riceIsFirstForAFoodQuestion() {
        for (groups in 1..4) {
            val m = manager()
            m.updateContext("What do you want to eat?")
            m.typeGroupsOf("rice", groups)
            assertEquals("after $groups letters: ${m.firstPage()}", "rice", m.firstPage()[0])
        }
    }

    @Test
    fun parkIsFirstForAPlaceQuestion() {
        for (groups in 1..4) {
            val m = manager()
            m.updateContext("Where do you want to go?")
            m.typeGroupsOf("park", groups)
            assertEquals("after $groups letters: ${m.firstPage()}", "park", m.firstPage()[0])
        }
    }

    @Test
    fun withoutContextTheOldOrderIsKept() {
        val m = manager()
        m.typeGroupsOf("rice")
        assertFalse(m.firstPage().toString(), "rice" in m.firstPage())
        assertTrue("rice" in m.wordPredictions) // still reachable on a later page
    }

    @Test
    fun contextHeardAfterTypingReordersTheFirstPage() {
        val m = manager()
        m.typeGroupsOf("rice", 2)
        assertFalse("rice" in m.firstPage())
        m.updateContext("What do you want to eat?")
        assertEquals("rice", m.firstPage()[0])
    }

    @Test
    fun aNewQuestionDropsTheOldLlmWords() {
        val m = manager()
        m.updateContext("What do you want to eat?")
        m.llmContextWords = listOf("biryani")
        m.updateContext("What do you want to eat?") // same question again, e.g. reopening the screen
        assertTrue("biryani" in m.blurryInput.contextWords)
        m.updateContext("Where do you want to go?")
        assertFalse("biryani" in m.blurryInput.contextWords)
        assertEquals("park", m.blurryInput.contextWords.first())
        m.updateContext("")
        assertTrue(m.blurryInput.contextWords.isEmpty())
    }
}
