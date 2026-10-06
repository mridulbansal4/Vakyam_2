package com.demo.opencv.textEntry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEntryBroadcastParsingTest {

    @Test
    fun testHyphenSplittingPreservesInnerHyphens() {
        // Simulates the TextEntryManager broadcast receiver splitting logic
        val message = "SP-I am thirty-five years old and well-rested."
        val splitArray = message.split("-", limit = 2)

        assertEquals(2, splitArray.size)
        assertEquals("SP", splitArray[0])
        assertEquals("I am thirty-five years old and well-rested.", splitArray[1])
    }

    @Test
    fun testHyphenSplittingSimple() {
        val message = "SP-I want chicken for dinner."
        val splitArray = message.split("-", limit = 2)

        assertEquals(2, splitArray.size)
        assertEquals("SP", splitArray[0])
        assertEquals("I want chicken for dinner.", splitArray[1])
    }

    @Test
    fun testHyphenSplittingMalformedInputWithoutHyphen() {
        val message = "SPOnlyNoHyphen"
        val splitArray = message.split("-", limit = 2)

        // With split limit 2, length is 1, code should reject without throwing ArrayIndexOutOfBoundsException
        assertTrue(splitArray.size < 2)
    }

    @Test
    fun testHyphenSplittingEmptyContent() {
        val message = "SP-"
        val splitArray = message.split("-", limit = 2)

        assertEquals(2, splitArray.size)
        assertEquals("SP", splitArray[0])
        assertEquals("", splitArray[1])
    }

    @Test
    fun testNwpWordChoices() {
        val message = "NWP-apple, banana, orange"
        val splitArray = message.split("-", limit = 2)

        assertEquals(2, splitArray.size)
        assertEquals("NWP", splitArray[0])
        val words = splitArray[1].split(", ")
        assertEquals(3, words.size)
        assertEquals("apple", words[0])
        assertEquals("banana", words[1])
        assertEquals("orange", words[2])
    }
}
