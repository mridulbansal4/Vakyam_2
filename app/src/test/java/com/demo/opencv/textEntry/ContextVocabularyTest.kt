package com.demo.opencv.textEntry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextVocabularyTest {

    @Test
    fun foodQuestionOffersFoodsFirst() {
        val words = ContextVocabulary.wordsFor("What do you want to eat?", false)
        assertEquals("rice", words.first())
        assertTrue("roti" in words && "dal" in words)
        // "want" also asks for a need, but the food answers lead
        assertTrue(words.indexOf("rice") < words.indexOf("water"))
    }

    @Test
    fun placeQuestionOffersPlacesFirst() {
        val words = ContextVocabulary.wordsFor("Where do you want to go?", false)
        assertEquals("park", words.first())
        assertTrue("home" in words && "garden" in words && "ghar" in words)
    }

    @Test
    fun hinglishQuestionOffersHinglishWordsFirst() {
        val food = ContextVocabulary.wordsFor("aapko khane me kya chahiye", true)
        assertEquals(listOf("khana", "chawal", "rice"), food.take(3))
        val place = ContextVocabulary.wordsFor("kahan jaana hai", true)
        assertEquals(listOf("ghar", "bahar", "park"), place.take(3))
    }

    @Test
    fun painOutranksPlaceForWhereDoesItHurt() {
        assertEquals("head", ContextVocabulary.wordsFor("Where does it hurt?", false).first())
        assertEquals("sir", ContextVocabulary.wordsFor("dard kahan ho raha hai", true).first())
    }

    @Test
    fun otherTopics() {
        assertEquals("water", ContextVocabulary.wordsFor("Would you like something to drink?", false).first())
        assertEquals("good", ContextVocabulary.wordsFor("How are you feeling today?", false).first())
        assertEquals("mom", ContextVocabulary.wordsFor("Who should I call?", false).first())
        assertEquals("now", ContextVocabulary.wordsFor("When do you want to sleep?", false).first())
        assertEquals("tv", ContextVocabulary.wordsFor("What would you like to do?", false).first())
    }

    @Test
    fun nothingRecognisedGivesNoWords() {
        assertTrue(ContextVocabulary.wordsFor("", false).isEmpty())
        assertTrue(ContextVocabulary.wordsFor(null, true).isEmpty())
        assertTrue(ContextVocabulary.wordsFor("The weather is nice", false).isEmpty())
    }

    @Test
    fun everyWordIsTypableOnTheEnglishLetters() {
        val questions = listOf("eat", "drink", "hurt", "where", "who", "when", "watch", "how are you", "need")
        for (q in questions) {
            for (w in ContextVocabulary.wordsFor(q, false) + ContextVocabulary.wordsFor(q, true)) {
                assertTrue("$q: $w", w.isNotEmpty() && w.all { it in 'a'..'z' || it in 'A'..'Z' })
            }
        }
    }

    @Test
    fun mergeKeepsInstantWordsFirstAndDropsRepeats() {
        assertEquals(listOf("rice", "roti", "pulao"),
            ContextVocabulary.merge(listOf("rice", "roti"), listOf("Rice", "pulao", "roti")))
        assertEquals(listOf("biryani"), ContextVocabulary.merge(emptyList(), listOf("biryani")))
    }
}
