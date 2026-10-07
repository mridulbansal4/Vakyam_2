package com.demo.opencv.textEntry

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
}
