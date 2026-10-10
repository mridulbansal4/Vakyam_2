package com.demo.opencv.textEntry

import com.demo.opencv.personal.PersonalLexicon
import com.demo.opencv.personal.PersonalLexicon.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three gaze input paths all read from and write to the one [PersonalLexicon]. */
class PersonalVocabularyTest {

    private class MemoryStore : PersonalLexicon.Store {
        var saved = PersonalLexicon.Snapshot()
        override fun load() = saved
        override fun save(snapshot: PersonalLexicon.Snapshot) {
            saved = snapshot
        }
    }

    private var now = 1_000L
    private val lexicon = PersonalLexicon(MemoryStore()) { now++ }

    private fun KeyboardManager.type(text: String) {
        val pages = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz,.!?", " *#")
        for (c in text) {
            val page = pages.indexOfFirst { c in it }
            processInput(page, false)
            processInput(pages[page].indexOf(c), false)
        }
    }

    @Test
    fun aNameSpelledElsewhereIsOfferedByTheAmbiguousKeyboard() {
        val blurry = BlurryInput().apply {
            initialize(null, "English")
            setPersonalLexicon(lexicon)
        }
        assertTrue(blurry.getMatchingWords(blurry.processWord("har"), emptyList()).isEmpty())

        lexicon.recordUtterance(listOf("Harshad")) // e.g. spelled once on the blink keyboard
        assertEquals(listOf("Harshad"), blurry.getMatchingWords(blurry.processWord("har"), emptyList()))
    }

    @Test
    fun thePatientsUsualNextWordComesFirst() {
        val blurry = BlurryInput().apply {
            initialize(null, "English")
            setPersonalLexicon(lexicon)
        }
        repeat(3) { lexicon.recordUtterance(listOf("water")) }
        lexicon.recordUtterance(listOf("call", "wife"))
        // "water" and "wife" share the first ambiguous key; after "call" the pair wins over the more used word.
        val code = blurry.processWord("w")
        assertEquals(listOf("water", "wife"), blurry.getMatchingWords(code, emptyList()))
        assertEquals(listOf("wife", "water"), blurry.getMatchingWords(code, listOf("call")))
        assertEquals(listOf("wife", "water", "call"), blurry.getExactMatchingWords("", listOf("call")))
    }

    @Test
    fun hindiWordsStayOutOfEnglishPredictions() {
        val blurry = BlurryInput().apply { setPersonalLexicon(lexicon) }
        lexicon.recordUtterance(listOf("पानी"))
        blurry.initialize(null, "English")
        assertTrue(blurry.getExactMatchingWords("", emptyList()).isEmpty())
        blurry.initialize(null, "Hindi")
        assertEquals(listOf("पानी"), blurry.getExactMatchingWords("पा", emptyList()))
    }

    @Test
    fun theLetterKeyboardTeachesFinishedWords() {
        val keyboard = KeyboardManager().apply { setPersonalLexicon(lexicon) }
        keyboard.type("call sruj ")
        assertEquals("call sruj ", keyboard.textInput)
        assertEquals(setOf("call", "sruj"), lexicon.words(Script.LATIN).toSet())
        assertEquals(listOf("sruj"), lexicon.followers("call", Script.LATIN))

        keyboard.type("no") // not finished yet: no space after it
        assertEquals(2, lexicon.words(Script.LATIN).size)
    }

    @Test
    fun quickChatOffersTheMostSpokenSentences() {
        val quickChat = QuickChatManager().apply {
            initialize(null)
            setPersonalLexicon(lexicon)
        }
        lexicon.recordPhrase("call Harshad")
        repeat(2) { lexicon.recordPhrase("fan off please") }

        quickChat.manageUserInput(0, false) // the slot that used to do nothing on the home page
        assertEquals(QuickChatManager.MY_PHRASES_MODE, quickChat.mode)
        assertEquals(listOf("fan off please", "call Harshad", "", "", ""), quickChat.quickChatData.texts.drop(1))

        quickChat.manageUserInput(2, false) // speaking a sentence counts as using it again
        assertEquals(2, lexicon.phraseEntries().first { it.text == "call Harshad" }.count)
        // The page does not reorder while it is open, even though the counts are now tied.
        assertEquals("fan off please", quickChat.quickChatData.texts[1])

        quickChat.manageUserInput(5, false) // empty slot
        quickChat.manageUserInput(0, false)
        assertEquals(0, quickChat.mode)
    }

    @Test
    fun quickChatWithNothingLearnedShowsTheHint() {
        val quickChat = QuickChatManager().apply { initialize(null) }
        quickChat.manageUserInput(0, false)
        assertEquals(QuickChatManager.MY_PHRASES_MODE, quickChat.mode)
        assertTrue(quickChat.quickChatData.texts[1].isNotEmpty())
        quickChat.manageUserInput(1, false) // nothing to speak
        quickChat.manageUserInput(0, false)
        assertEquals(0, quickChat.mode)
    }
}
