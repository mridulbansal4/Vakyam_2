package com.demo.opencv.features.blinkkeyboard

import com.demo.opencv.personal.PersonalLexicon
import com.demo.opencv.textEntry.HinglishDetector
import com.demo.opencv.textEntry.HinglishDetectorTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WordPredictionServiceTest {

    private class MemoryStore : PersonalLexicon.Store {
        var saved = PersonalLexicon.Snapshot()
        override fun load() = saved
        override fun save(snapshot: PersonalLexicon.Snapshot) {
            saved = snapshot
        }
    }

    private var now = 1L
    private val lexicon = PersonalLexicon(MemoryStore()) { now++ }
    private val hinglish = HinglishDetectorTest.assetLexicon()
    private val english = WordPredictionService.loadDictionary { File(HinglishDetectorTest.ASSETS, it).inputStream() }
    private var lastSpoken = HinglishDetector.ENGLISH
    private var setting = HinglishDetector.ENGLISH
    private val service = WordPredictionService(lexicon, english, hinglish, { lastSpoken }, { setting })

    @Test
    fun hinglishSettingPutsHinglishFirstEvenAfterEnglishWords() {
        setting = HinglishDetector.HINGLISH
        val words = service.predict("pa", previousWord = "water", limit = 3, typedWords = listOf("water"))
        assertEquals(2, words.count { it in hinglish.words && it !in english })
    }

    @Test
    fun hinglishWordsAreDictionaryWords() {
        assertTrue(service.isDictionaryWord("paani"))
        assertTrue(service.isDictionaryWord("pani")) // variant spelling stays lowercase too
        assertTrue(service.isDictionaryWord("chahiye"))
        assertTrue(service.isDictionaryWord("water"))
        assertFalse(service.isDictionaryWord("harshad")) // still becomes a proper noun
    }

    @Test
    fun paaniIsPredictedFromPa() {
        lastSpoken = HinglishDetector.HINGLISH
        assertTrue(service.predict("pa").toString(), "paani" in service.predict("pa"))
        lastSpoken = HinglishDetector.ENGLISH
        assertTrue("paani" in service.predict("pa", limit = 6))
    }

    @Test
    fun englishTypingKeepsTwoEnglishSlotsOfThree() {
        val words = service.predict("pa", limit = 3)
        val englishPa = english.filter { it.startsWith("pa") && it.all(Char::isLetter) && it == it.lowercase() && it.length > 1 }
        assertEquals(englishPa.take(2), words.take(2))
        assertTrue(words[2] in hinglish.words)
    }

    @Test
    fun theSentenceSoFarPicksTheLanguage() {
        val words = service.predict("pa", previousWord = "mujhe", limit = 3, typedWords = listOf("mujhe"))
        assertEquals(2, words.count { it in hinglish.words && it !in english })
    }

    @Test
    fun learnedWordsStayFirst() {
        lexicon.recordUtterance(listOf("pakoda"))
        assertEquals("pakoda", service.predict("pa").first())
        assertEquals(listOf("pakoda"), service.predict("", null))
    }
}
