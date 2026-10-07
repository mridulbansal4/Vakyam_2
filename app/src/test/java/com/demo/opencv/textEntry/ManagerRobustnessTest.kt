package com.demo.opencv.textEntry

import com.demo.opencv.socialMedia.SocialMediaManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerRobustnessTest {

    @Test
    fun testBlurryInputProcessWordNormalAndEdgeCases() {
        val blurry = BlurryInput()

        // Null and empty strings
        assertEquals("", blurry.processWord(null))
        assertEquals("", blurry.processWord(""))

        // Normal word "hello": h -> 1, e -> 0, l -> 1, l -> 1, o -> 2
        assertEquals("10112", blurry.processWord("hello"))

        // Word with accents: "café" -> c (0), a (0), f (0), e (0)
        assertEquals("0000", blurry.processWord("café"))

        // Word with special characters & numbers: numbers/symbols are stripped
        assertEquals("0000", blurry.processWord("cafe 123!"))
    }

    @Test
    fun testBlurryInputPagingBoundaryChecks() {
        val blurry = BlurryInput()
        val list = listOf("apple", "banana", "cherry", "date", "elderberry")

        // Invalid page parameters
        assertNull(blurry.getWordPage(null, 1, 3))
        assertNull(blurry.getWordPage(emptyList(), 1, 3))
        assertNull(blurry.getWordPage(list, 0, 3))
        assertNull(blurry.getWordPage(list, -1, 3))
        assertNull(blurry.getWordPage(list, 1, 0))

        // Page 1 with 3 items per page: [apple, banana, cherry]
        val page1 = blurry.getWordPage(list, 1, 3)
        assertNotNull(page1)
        assertEquals(3, page1!!.size)
        assertEquals("apple", page1[0])
        assertEquals("banana", page1[1])
        assertEquals("cherry", page1[2])

        // Page 2: [date, elderberry, null]
        val page2 = blurry.getWordPage(list, 2, 3)
        assertNotNull(page2)
        assertEquals("date", page2!![0])
        assertEquals("elderberry", page2[1])
        assertNull(page2[2])

        // Page 3: does not exist -> null
        val page3 = blurry.getWordPage(list, 3, 3)
        assertNull(page3)
    }

    @Test
    fun testKeyboardManagerBoundaryAndLayerSwitching() {
        val km = KeyboardManager()
        km.initialize()

        // Layer 1 initial state
        assertEquals(1, km.layer)
        assertEquals(0, km.page)
        assertEquals("", km.textInput)

        // Invalid out-of-bounds selections do not crash or corrupt state
        km.processInput(-10)
        km.processInput(100)
        assertEquals(1, km.layer)

        // Valid selection in layer 1 switches to layer 2
        // selection 1 (left gaze -> input 1)
        km.processInput(1)
        assertEquals(2, km.layer)
        assertEquals(1, km.page)

        // Select a character in layer 2 (e.g. selection 0 -> up gaze -> input 0)
        // layer2Options[1] is "ghijkl", index 0 is 'g'
        km.processInput(3) // gaze 3 is up -> input 0
        assertEquals(1, km.layer)
        assertEquals("g", km.textInput)

        // Backspace operation when deleting
        // Enter layer 2 page 5 (" *#")
        km.processInput(7) // gaze 7 is right-up -> input 5
        assertEquals(2, km.layer)
        assertEquals(5, km.page)
        // input 1 is '*' (delete)
        km.processInput(1) // gaze 1 -> input 1 -> delete '*'
        assertEquals(1, km.layer)
        assertEquals("", km.textInput)

        // Backspace on empty text does not throw StringIndexOutOfBoundsException
        km.processInput(7)
        km.processInput(1)
        assertEquals("", km.textInput)
    }

    @Test
    fun testQuickChatManagerBoundsAndTransitions() {
        val qc = QuickChatManager()
        qc.setMode(0)

        // Out-of-bounds input should be ignored
        qc.manageUserInput(-5, true)
        qc.manageUserInput(100, true)
        qc.manageUserInput(-5, false)
        qc.manageUserInput(100, false)
        assertEquals(0, qc.mode)

        // Selection 1 with isGazeType=false enters category 1
        qc.manageUserInput(1, false)
        assertEquals(1, qc.mode)

        // Selection 0 is back button, returns to main mode
        qc.manageUserInput(0, false)
        assertEquals(0, qc.mode)
    }

    @Test
    fun testSocialMediaManagerBoundsCheck() {
        val sm = SocialMediaManager()
        // Invalid selections should be discarded without throwing ArrayIndexOutOfBoundsException
        sm.manageUserInput(-1, true)
        sm.manageUserInput(20, true)
        sm.manageUserInput(-1, false)
        sm.manageUserInput(20, false)
        assertTrue(true) // Reaching here means no crash
    }
}
