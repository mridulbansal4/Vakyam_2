package com.demo.opencv.textEntry

import com.demo.opencv.personal.PersonalLexicon
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlurryInputTest {

    private lateinit var blurryInput: BlurryInput

    @Before
    fun setUp() {
        blurryInput = BlurryInput()
    }

    @Test
    fun testProcessWordEncoding() {
        // 'h' (group 1), 'e' (group 0), 'l' (group 1), 'l' (group 1), 'o' (group 2)
        assertEquals("10112", blurryInput.processWord("hello"))

        // 'w' (3), 'o' (2), 'r' (2), 'l' (1), 'd' (0)
        assertEquals("32210", blurryInput.processWord("world"))
    }

    @Test
    fun testProcessWordNormalizationAccentsAndCase() {
        // Uppercase conversion
        assertEquals("10112", blurryInput.processWord("HELLO"))

        // Accents stripped: "café" -> "cafe" -> 'c'(0), 'a'(0), 'f'(0), 'e'(0)
        assertEquals("0000", blurryInput.processWord("café"))

        // Null string
        assertEquals("", blurryInput.processWord(null))

        // Numbers & symbols removed
        assertEquals("2022", blurryInput.processWord("test123!"))
    }

    @Test
    fun testGetMatchingWordsEmptyOrNullInput() {
        val emptyResult = blurryInput.getMatchingWords("", ArrayList())
        assertNotNull(emptyResult)
        assertTrue(emptyResult.isEmpty())

        val nullResult = blurryInput.getMatchingWords(null, ArrayList())
        assertNotNull(nullResult)
        assertTrue(nullResult.isEmpty())
    }

    @Test
    fun testGetWordPagePagination() {
        val words = listOf("apple", "banana", "cherry", "date", "fig")

        // Page 1 with 2 words per page
        val page1 = blurryInput.getWordPage(words, 1, 2)
        assertNotNull(page1)
        assertEquals(2, page1.size)
        assertEquals("apple", page1[0])
        assertEquals("banana", page1[1])

        // Page 2 with 2 words per page
        val page2 = blurryInput.getWordPage(words, 2, 2)
        assertNotNull(page2)
        assertEquals(2, page2.size)
        assertEquals("cherry", page2[0])
        assertEquals("date", page2[1])

        // Page 3 with 2 words per page (1 word remaining, second slot null)
        val page3 = blurryInput.getWordPage(words, 3, 2)
        assertNotNull(page3)
        assertEquals("fig", page3[0])
        assertNull(page3[1])

        // Page 4 does not exist -> returns null
        val page4 = blurryInput.getWordPage(words, 4, 2)
        assertNull(page4)

        // Invalid page 0 -> returns null
        assertNull(blurryInput.getWordPage(words, 0, 2))

        // Invalid negative page -> returns null
        assertNull(blurryInput.getWordPage(words, -1, 2))

        // Empty list -> returns null
        assertNull(blurryInput.getWordPage(emptyList(), 1, 2))

        // Null list -> returns null
        assertNull(blurryInput.getWordPage(null, 1, 2))
    }

    // ------------------------------------------------------------------ Hinglish interleave

    private val assets = BlurryInput.AssetSource { File(HinglishDetectorTest.ASSETS, it).inputStream() }
    private val hinglish = HinglishDetectorTest.assetLexicon()
    private val english = File(HinglishDetectorTest.ASSETS, "EnglishWords.txt").readLines()

    private fun latinKeyboard(lastSpoken: String = HinglishDetector.ENGLISH, lexicon: PersonalLexicon? = null) =
        BlurryInput().apply {
            initialize("English", assets, hinglish)
            setLastSpokenLanguage(lastSpoken)
            setPersonalLexicon(lexicon)
        }

    private fun englishStarting(prefix: String) = english.filter { it.startsWith(prefix) }.distinct()
    private fun hinglishStarting(prefix: String) = hinglish.words.filter { it.startsWith(prefix) }

    @Test
    fun englishTypistGetsTwoEnglishThenOneHinglishPerPage() {
        val words = latinKeyboard().getExactMatchingWords("pa", emptyList())
        val e = englishStarting("pa")
        val h = hinglishStarting("pa").filter { it !in e }
        assertEquals(listOf(e[0], e[1], h[0], e[2], e[3], h[1]), words.take(6))
        assertTrue("paani within the first two pages: $words", "paani" in words.take(6))
    }

    @Test
    fun hinglishTypistGetsTwoHinglishThenOneEnglishPerPage() {
        val words = latinKeyboard().getExactMatchingWords("pa", listOf("theek"))
        val h = hinglishStarting("pa")
        val e = englishStarting("pa").filter { it !in h }
        assertEquals(listOf(h[0], h[1], e[0], h[2], h[3], e[1]), words.take(6))
    }

    @Test
    fun lastSpokenLanguageOrdersTheFirstWord() {
        val english = latinKeyboard().getExactMatchingWords("pa", emptyList())
        val hindiFirst = latinKeyboard(HinglishDetector.HINGLISH).getExactMatchingWords("pa", emptyList())
        assertEquals(hinglishStarting("pa").take(2), hindiFirst.take(2))
        assertTrue(english[0] !in hinglishStarting("pa"))
    }

    @Test
    fun ambiguousKeyboardInterleavesTooAndOffersPaani() {
        val keyboard = latinKeyboard(HinglishDetector.HINGLISH)
        val words = keyboard.getMatchingWords(keyboard.processWord("paani"), emptyList())
        assertEquals("paani", words.first { it in hinglish.words })
        val firstPage = keyboard.getMatchingWords(keyboard.processWord("pa"), emptyList()).take(3)
        assertEquals(2, firstPage.count { it in hinglish.words && it !in english })
    }

    @Test
    fun bigramsAndPersonalWordsStayAheadOfTheDictionary() {
        val store = object : PersonalLexicon.Store {
            var saved = PersonalLexicon.Snapshot()
            override fun load() = saved
            override fun save(snapshot: PersonalLexicon.Snapshot) { saved = snapshot }
        }
        var now = 1L
        val lexicon = PersonalLexicon(store) { now++ }
        lexicon.recordUtterance(listOf("pakka"))
        val words = latinKeyboard(lexicon = lexicon).getExactMatchingWords("pa", listOf("mujhe"))
        // Hinglish bigrams "mujhe paani" and "mujhe pata" first, then the learned word, then the dictionary.
        assertEquals(listOf("paani", "pata", "pakka"), words.take(3))
        assertEquals(words.size, words.toSet().size)
    }

    @Test
    fun contextWordsLeadTypedLettersButNotTheNextWord() {
        val keyboard = latinKeyboard()
        keyboard.setContextWords(ContextVocabulary.wordsFor("What do you want to eat?", false))
        val rice = keyboard.processWord("rice")
        for (n in 1..rice.length) {
            assertEquals("rice", keyboard.getMatchingWords(rice.take(n), emptyList()).first())
            assertEquals("rice", keyboard.getMatchingWords(rice.take(n), listOf("I", "want")).first())
        }
        assertTrue("nice" in keyboard.getMatchingWords(rice, emptyList())) // the dictionary still follows
        assertEquals("rice", keyboard.getExactMatchingWords("r", emptyList()).first())
        // With nothing typed the next word stays grammatical
        assertEquals(listOf("chahiye", "do", "pilao"), keyboard.getExactMatchingWords("", listOf("paani")).take(3))

        keyboard.setContextWords(ContextVocabulary.wordsFor("Where do you want to go?", false))
        val park = keyboard.processWord("park")
        for (n in 1..park.length) assertEquals("park", keyboard.getMatchingWords(park.take(n), emptyList()).first())
    }

    @Test
    fun hinglishBigramsAreMergedIntoNextWordPrediction() {
        val next = latinKeyboard().getExactMatchingWords("", listOf("paani"))
        assertEquals(listOf("chahiye", "do", "pilao"), next.take(3))
    }

    @Test
    fun withoutHinglishTheEnglishOrderIsUnchanged() {
        val plain = BlurryInput().apply { initialize("English", assets, HinglishLexicon.EMPTY) }
        assertEquals(englishStarting("pa"), plain.getExactMatchingWords("pa", emptyList()))
        val code = plain.processWord("wat")
        val expected = english.filter { plain.processWord(it).startsWith(code) }.distinct()
        assertEquals(expected, plain.getMatchingWords(code, emptyList()))
    }

    @Test
    fun hinglishSettingTypesEnglishLettersWithHinglishFirst() {
        val keyboard = BlurryInput().apply { initialize("Hinglish", assets, hinglish) }
        val h = hinglishStarting("pa")
        val e = englishStarting("pa").filter { it !in h }
        // Even after an English word the Hinglish words lead, and English stays on every page.
        assertEquals(listOf(h[0], h[1], e[0], h[2], h[3], e[1]), keyboard.getExactMatchingWords("pa", listOf("water")).take(6))
        val all = keyboard.getExactMatchingWords("", emptyList()) + keyboard.getMatchingWords(keyboard.processWord("paani"), emptyList())
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { w -> w.all { it.code < 128 } })
        assertEquals("chahiye", keyboard.getExactMatchingWords("", listOf("paani")).first())
    }
}
