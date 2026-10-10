package com.demo.opencv.textEntry

import com.demo.opencv.metrics.TypingTestPhrases
import com.demo.opencv.textEntry.HinglishDetector.Companion.ENGLISH
import com.demo.opencv.textEntry.HinglishDetector.Companion.HINGLISH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HinglishDetectorTest {

    private val lexicon = assetLexicon()
    private val detector = HinglishDetector(lexicon)

    private fun detect(vararg words: String, context: String = "", previous: String = ENGLISH) =
        detector.detect(words.toList(), context, previous)

    @Test
    fun pureEnglishStaysEnglish() {
        assertEquals(ENGLISH, detect("water", "cold", "please"))
        assertEquals(ENGLISH, detect("pain", "back"))
        assertEquals(ENGLISH, detect("call", "nurse", previous = HINGLISH))
    }

    @Test
    fun pureHinglishIsHinglish() {
        assertEquals(HINGLISH, detect("paani", "chahiye"))
        assertEquals(HINGLISH, detect("dard", "peeth"))
        assertEquals(HINGLISH, detect("mujhe", "neend", "aa", "rahi", "hai"))
    }

    @Test
    fun codeMixedIsHinglish() {
        assertEquals(HINGLISH, detect("paani", "cold"))
        assertEquals(HINGLISH, detect("doctor", "ko", "bulao"))
        assertEquals(HINGLISH, detect("AC", "band", "karo"))
        // "AC" is an English word and "band" is shared, so without a Hinglish-only word they stay English.
        assertEquals(ENGLISH, detect("AC", "band", previous = HINGLISH))
    }

    @Test
    fun wordsSharedWithEnglishDoNotDecideAlone() {
        // "main", "to", "me", "do", "the" are English and Hinglish spellings at once.
        assertEquals(ENGLISH, detect("main"))
        assertEquals(ENGLISH, detect("to", "me"))
        assertEquals(HINGLISH, detect("main", previous = HINGLISH))
        assertEquals(HINGLISH, detect("to", "me", previous = HINGLISH))
        // One unambiguous word settles it either way.
        assertEquals(ENGLISH, detect("to", "me", "please"))
        assertEquals(HINGLISH, detect("main", "theek"))
    }

    @Test
    fun stoplistWordsAreNotHinglishOnly() {
        for (word in listOf("din", "pal", "jab", "sun", "par", "mat", "mile", "papa")) {
            assertFalse(word, lexicon.isHinglishOnly(word))
        }
        assertEquals(ENGLISH, detect("sun"))
    }

    @Test
    fun indianEnglishLoanwordsFollowTheRestOfTheSentence() {
        assertEquals(ENGLISH, detect("chai"))
        assertEquals(ENGLISH, detect("call", "didi"))
        assertEquals(ENGLISH, detector.detectText("I want some chai and a samosa."))
        assertEquals(HINGLISH, detect("chai", "pilao"))
        assertEquals(HINGLISH, detect("didi", "ko", "bulao"))
        assertEquals(HINGLISH, detect("chai", previous = HINGLISH))
        assertTrue(lexicon.isHinglish("chai")) // still offered as a Hinglish word
    }

    @Test
    fun namesOnlyFollowThePreviousUtterance() {
        assertEquals(ENGLISH, detect("Harshad"))
        assertEquals(HINGLISH, detect("Harshad", previous = HINGLISH))
    }

    @Test
    fun contextDecidesWhenTheKeywordsCannot() {
        assertEquals(HINGLISH, detect("Harshad", context = "aapko kya chahiye"))
        assertEquals(HINGLISH, detect("main", context = "aapko khane me kya chahiye?"))
        assertEquals(ENGLISH, detect("main", context = "what would you like"))
        // The keywords outrank the context.
        assertEquals(ENGLISH, detect("water", context = "aapko kya chahiye"))
        assertEquals(HINGLISH, detect("paani", context = "what would you like"))
    }

    @Test
    fun previousResultIsStickyAndDefaultsToEnglish() {
        assertEquals(ENGLISH, detector.detect(emptyList(), null, null))
        assertEquals(HINGLISH, detector.detect(emptyList(), null, HINGLISH))
        assertEquals(ENGLISH, detector.detect(null, null, "Hindi"))
    }

    @Test
    fun variantSpellingsAreRecognised() {
        assertEquals(HINGLISH, detect("pani"))
        assertEquals(HINGLISH, detect("nahin"))
        assertEquals(HINGLISH, detect("bohot", "acha"))
        assertTrue(lexicon.isHinglish("Paani"))
        assertTrue(lexicon.isHinglish("mujhey"))
    }

    @Test
    fun hinglishSettingMakesEverythingHinglish() {
        assertEquals(HINGLISH, detector.resolve(HINGLISH, listOf("water", "cold"), null, ENGLISH))
        assertEquals(HINGLISH, detector.resolveText(HINGLISH, "I need water.", ENGLISH))
        assertEquals(HINGLISH, detector.resolve(HINGLISH, emptyList(), null, null))
        // Language = English keeps auto-detection.
        assertEquals(ENGLISH, detector.resolve(ENGLISH, listOf("water", "cold"), null, HINGLISH))
        assertEquals(HINGLISH, detector.resolveText(ENGLISH, "Mujhe paani chahiye", ENGLISH))
        assertEquals(ENGLISH, detector.resolveText(null, "I need water.", null))
    }

    @Test
    fun detectTextReadsFinishedSentences() {
        assertEquals(HINGLISH, detector.detectText("Mujhe thanda paani chahiye."))
        assertEquals(HINGLISH, detector.detectText("AC band kar do please."))
        assertEquals(ENGLISH, detector.detectText("Could you please bring me some cold water?"))
        assertEquals(ENGLISH, detector.detectText("I am having pain in my back."))
        assertEquals(HINGLISH, detector.detectText("Harshad!", HINGLISH))
        assertEquals(ENGLISH, detector.detectText("Harshad!"))
        assertEquals(ENGLISH, detector.detectText("OK.", HINGLISH))
    }

    @Test
    fun englishSentencesAreNotMistakenForHinglish() {
        val sentences = listOf(
            "I want to have chicken for dinner.",
            "I am hot, can you turn the AC down by two degrees?",
            "Could you please bring me some cold water?",
            "I am happy, thank you so much.",
            "Please call the nurse.",
            "Turn off the light.",
            "I need to use the bathroom.",
            "Can you help me sit up?",
            "My head hurts a lot today.",
            "Where is my phone?",
            "I love you.",
            "Is the doctor coming?",
        )
        for (s in sentences) assertEquals(s, ENGLISH, detector.detectText(s))
    }

    @Test
    fun hinglishSentencesAreRecognised() {
        val sentences = listOf(
            "Mujhe thanda paani chahiye.",
            "Meri peeth mein dard ho raha hai.",
            "Doctor ko bulao.",
            "Main theek hoon.",
            "Bahut dhanyavaad.",
            "Mujhe bathroom jana hai.",
            "Pankha band kar do.",
            "Kya aap mujhe utha sakte hain?",
            "Mujhe bhookh lagi hai.",
            "Aaj mera sar dard kar raha hai.",
        )
        for (s in sentences) assertEquals(s, HINGLISH, detector.detectText(s))
    }

    @Test
    fun nothingThePatientSeesOrHearsIsInDevanagari() {
        val devanagari = Regex("[\u0900-\u097F]")
        val files = listOf(File(ASSETS, HinglishLexicon.WORDS_ASSET), File(ASSETS, HinglishLexicon.BIGRAMS_ASSET),
            File("src/main/res/values-hi/strings.xml"), File("src/main/res/values/strings.xml"))
        for (f in files) assertFalse(f.path, devanagari.containsMatchIn(f.readText()))
        assertFalse(File(ASSETS, "HindiWords.txt").exists())
        for (phrase in TypingTestPhrases.forLanguage("Hindi") + TypingTestPhrases.forLanguage("Hinglish")) {
            assertFalse(phrase, devanagari.containsMatchIn(phrase))
        }
    }

    @Test
    fun interleaveAlternatesTwoPrimaryThenOneSecondary() {
        assertEquals(
            listOf("p1", "p2", "s1", "p3", "p4", "s2", "s3", "s4"),
            HinglishLexicon.interleave(listOf("p1", "p2", "p3", "p4"), listOf("s1", "s2", "s3", "s4")),
        )
        assertEquals(listOf("p1", "p2", "s1", "p3", "p4", "p5"), HinglishLexicon.interleave(listOf("p1", "p2", "p3", "p4", "p5"), listOf("s1")))
        assertEquals(listOf("a", "b"), HinglishLexicon.interleave(listOf("a", "b"), emptyList()))
        assertEquals(listOf("a", "b"), HinglishLexicon.interleave(emptyList(), listOf("a", "b")))
        assertEquals(listOf("main", "do", "hai"), HinglishLexicon.interleave(listOf("main", "do"), listOf("main", "hai")))
    }

    @Test
    fun typingTestPhrasesAreTypeableAndDetected() {
        val english = File(ASSETS, HinglishLexicon.ENGLISH_ASSET).readLines().map { it.trim().lowercase() }.toSet()
        for (phrase in TypingTestPhrases.forLanguage("Hinglish")) {
            for (w in phrase.split(" ")) assertTrue("$phrase: $w", w in lexicon.words || w in english)
            assertEquals(phrase, HINGLISH, detector.detectText(phrase))
        }
        for (phrase in TypingTestPhrases.forLanguage("English")) assertEquals(phrase, ENGLISH, detector.detectText(phrase))
    }

    @Test
    fun lexiconAssetIsWellFormed() {
        val entries = File(ASSETS, HinglishLexicon.WORDS_ASSET).useLines { HinglishLexicon.parse(it) }
        assertTrue("size ${entries.size}", entries.size in 1000..1500)
        val canonical = entries.map { it.word }
        assertEquals("duplicate canonical spellings", canonical.size, canonical.toSet().size)
        for (e in entries) {
            assertTrue(e.word, e.word.all { it in 'a'..'z' })
            assertTrue(e.word, e.variants.all { v -> v.all { it in 'a'..'z' } })
        }
        for (w in listOf("hai", "hoon", "mujhe", "ko", "ka", "ki", "mein", "raha", "rahi", "kar", "do", "maine", "nahi", "chahiye")) {
            assertTrue(w, w in canonical)
        }
    }

    @Test
    fun bigramAssetUsesKnownWords() {
        val english = File(ASSETS, HinglishLexicon.ENGLISH_ASSET).readLines().map { it.trim().lowercase() }.toSet()
        val lines = File(ASSETS, HinglishLexicon.BIGRAMS_ASSET).readLines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 150)
        for (line in lines) {
            val parts = line.split(" ")
            assertEquals(line, 3, parts.size)
            parts[2].toInt()
            for (w in parts.take(2)) assertTrue(line, lexicon.words.contains(w) || w in english)
        }
    }

    companion object {
        val ASSETS = File("src/main/assets")

        fun assetLexicon(): HinglishLexicon = HinglishLexicon.load { File(ASSETS, it).inputStream() }
    }
}
