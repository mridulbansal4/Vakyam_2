package com.demo.opencv.personal

import com.demo.opencv.personal.PersonalLexicon.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalLexiconTest {

    private class MemoryStore(var saved: PersonalLexicon.Snapshot = PersonalLexicon.Snapshot()) : PersonalLexicon.Store {
        override fun load() = saved
        override fun save(snapshot: PersonalLexicon.Snapshot) {
            saved = snapshot
        }
    }

    private var now = 1_000L
    private val store = MemoryStore()
    private val lexicon = PersonalLexicon(store) { now++ }

    @Test
    fun wordsAreRankedByUseThenRecency() {
        lexicon.recordUtterance(listOf("water"))
        lexicon.recordUtterance(listOf("Harshad"))
        lexicon.recordUtterance(listOf("water"))
        lexicon.recordUtterance(listOf("fan"))
        assertEquals(listOf("water", "fan", "Harshad"), lexicon.words(Script.LATIN))
    }

    @Test
    fun aNameKeepsItsCapitalWhenTypedOnALowercaseKeyboard() {
        lexicon.recordUtterance(listOf("Harshad"))
        lexicon.recordWord("harshad")
        assertEquals(listOf("Harshad"), lexicon.words(Script.LATIN))
        assertEquals(2, lexicon.wordEntries().single().count)
    }

    @Test
    fun followersAreTheWordsThisPatientTypesNext() {
        lexicon.recordUtterance(listOf("call", "Harshad"))
        lexicon.recordUtterance(listOf("call", "Harshad"))
        lexicon.recordUtterance(listOf("call", "doctor"))
        assertEquals(listOf("Harshad", "doctor"), lexicon.followers("Call", Script.LATIN))
        assertTrue(lexicon.followers("doctor", Script.LATIN).isEmpty())
    }

    @Test
    fun recordWordLearnsThePairWithoutCountingThePreviousWordAgain() {
        lexicon.recordWord("good")
        lexicon.recordWord("morning", "good")
        assertEquals(listOf("morning"), lexicon.followers("good", Script.LATIN))
        assertEquals(1, lexicon.wordEntries().first { it.text == "good" }.count)
    }

    @Test
    fun scriptsDoNotLeakIntoEachOther() {
        lexicon.recordUtterance(listOf("पानी", "चाहिए"))
        lexicon.recordUtterance(listOf("water"))
        assertEquals(listOf("water"), lexicon.words(Script.LATIN))
        assertEquals(setOf("पानी", "चाहिए"), lexicon.words(Script.DEVANAGARI).toSet())
        assertEquals(listOf("चाहिए"), lexicon.followers("पानी", Script.DEVANAGARI))
        assertEquals(Script.DEVANAGARI, Script.forLanguage("Hindi"))
        assertEquals(Script.LATIN, Script.forLanguage("English"))
    }

    @Test
    fun theSpokenSentenceIsLearnedAsAPhrase() {
        lexicon.recordUtterance(listOf("water", "drink"), "I want to drink water.")
        lexicon.recordUtterance(listOf("fan", "off"))
        lexicon.recordPhrase("i want to drink water")
        assertEquals(listOf("i want to drink water", "fan off"), lexicon.phrases(5))
        assertEquals(listOf("i want to drink water"), lexicon.phrases(1))
    }

    @Test
    fun punctuationAndJunkAreNotLearnedAsWords() {
        lexicon.recordUtterance(listOf("water.", "!!", "", "  ", "123", "ok?"))
        assertEquals(setOf("water", "ok"), lexicon.words(Script.LATIN).toSet())
    }

    @Test
    fun forgettingAWordAlsoRemovesItsPairs() {
        lexicon.recordUtterance(listOf("call", "harshd", "now"))
        lexicon.forgetWord("harshd")
        assertFalse("harshd" in lexicon.words(Script.LATIN))
        assertTrue(lexicon.followers("call", Script.LATIN).isEmpty())
        assertTrue(lexicon.followers("harshd", Script.LATIN).isEmpty())

        lexicon.forgetPhrase("call harshd now")
        assertTrue(lexicon.phrases(5).isEmpty())
    }

    @Test
    fun everythingSurvivesARestart() {
        lexicon.recordUtterance(listOf("call", "Harshad"), "Call Harshad please")
        val reopened = PersonalLexicon(MemoryStore(store.saved))
        assertEquals(setOf("call", "Harshad"), reopened.words(Script.LATIN).toSet())
        assertEquals(listOf("Harshad"), reopened.followers("call", Script.LATIN))
        assertEquals(listOf("Call Harshad please"), reopened.phrases(5))
    }

    @Test
    fun theStoreStaysBoundedAndKeepsTheMostUsedWords() {
        repeat(5) { lexicon.recordWord("water") }
        for (i in 0 until PersonalLexicon.MAX_WORDS + 50) lexicon.recordWord("w" + wordSuffix(i))
        val words = lexicon.words(Script.LATIN)
        assertTrue(words.size <= PersonalLexicon.MAX_WORDS)
        assertEquals("water", words.first())
    }

    private fun wordSuffix(i: Int) = i.toString().map { 'a' + (it - '0') }.joinToString("")
}
