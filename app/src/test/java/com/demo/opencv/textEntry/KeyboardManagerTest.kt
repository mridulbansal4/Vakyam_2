package com.demo.opencv.textEntry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class KeyboardManagerTest {

    private lateinit var keyboardManager: KeyboardManager

    @Before
    fun setUp() {
        keyboardManager = KeyboardManager()
        keyboardManager.initialize()
    }

    @Test
    fun testInitialState() {
        assertEquals("", keyboardManager.textInput)
        assertEquals(1, keyboardManager.layer)
        assertEquals(0, keyboardManager.page)
        val displays = keyboardManager.displays
        assertNotNull(displays)
        assertNotNull(displays.Options)
        assertEquals(6, displays.Options.size)
        assertEquals("a b c d e f", displays.Options[0])
    }

    @Test
    fun testLayer1ToLayer2Transition() {
        // Look 1 corresponds to input 1 ("g h i j k l")
        keyboardManager.processInput(1)
        assertEquals(2, keyboardManager.layer)
        assertEquals(1, keyboardManager.page)

        val displays = keyboardManager.displays
        assertNotNull(displays)
        assertEquals("g", displays.Options[0])
        assertEquals("h", displays.Options[1])
        assertEquals("i", displays.Options[2])
        assertEquals("j", displays.Options[3])
        assertEquals("k", displays.Options[4])
        assertEquals("l", displays.Options[5])
    }

    @Test
    fun testCharacterSelectionAndReturnToLayer1() {
        // Look 3 corresponds to input 0 (page 0: "abcdef")
        keyboardManager.processInput(3)
        assertEquals(2, keyboardManager.layer)
        assertEquals(0, keyboardManager.page)

        // Look 3 corresponds to input 0 -> 'a'
        keyboardManager.processInput(3)
        assertEquals(1, keyboardManager.layer)
        assertEquals("a", keyboardManager.textInput)
    }

    @Test
    fun testBackspaceGesture() {
        // Enter "a"
        keyboardManager.processInput(3)
        keyboardManager.processInput(3)
        assertEquals("a", keyboardManager.textInput)

        // Enter page 5 (look 7 -> input 5: " *#")
        keyboardManager.processInput(7)
        assertEquals(2, keyboardManager.layer)
        assertEquals(5, keyboardManager.page)

        // Input 1 is '*' (delete) via look 1
        keyboardManager.processInput(1)
        assertEquals(1, keyboardManager.layer)
        assertEquals("", keyboardManager.textInput)
    }

    @Test
    fun testLeftUpLookEntersPage4() {
        // Look 6 (LeftUp) -> input 4 (Page 4: "yz,.!?")
        keyboardManager.processInput(6)
        assertEquals(2, keyboardManager.layer)
        assertEquals(4, keyboardManager.page)

        // Look 3 (Up) -> input 0 -> 'y'
        keyboardManager.processInput(3)
        assertEquals(1, keyboardManager.layer)
        assertEquals("y", keyboardManager.textInput)
    }

    @Test
    fun testDirectTouchInput() {
        // Direct button index 5 (Page 5) without look translation
        keyboardManager.processInput(5, false)
        assertEquals(2, keyboardManager.layer)
        assertEquals(5, keyboardManager.page)

        // Direct button index 0 (Space ' ')
        keyboardManager.processInput(0, false)
        assertEquals(1, keyboardManager.layer)
        assertEquals(" ", keyboardManager.textInput)
    }

    @Test
    fun testClearGesture() {
        // Enter "ab"
        keyboardManager.processInput(3)
        keyboardManager.processInput(3) // 'a'
        keyboardManager.processInput(3)
        keyboardManager.processInput(1) // 'b' (look 1 -> input 1)
        assertEquals("ab", keyboardManager.textInput)

        // Enter page 5 (look 7 -> input 5)
        keyboardManager.processInput(7)
        // Look 2 -> input 2 -> '#' (clear)
        keyboardManager.processInput(2)
        assertEquals(1, keyboardManager.layer)
        assertEquals("", keyboardManager.textInput)
    }

    @Test
    fun testBackspaceOnEmptyTextDoesNotAppendAsterisk() {
        // On empty text, trigger delete
        keyboardManager.processInput(7) // page 5
        keyboardManager.processInput(1) // delete '*'
        assertEquals(1, keyboardManager.layer)
        assertEquals("", keyboardManager.textInput)
    }

    @Test
    fun testInvalidGestureOutOfBoundsDoesNotCrash() {
        keyboardManager.processInput(-10)
        keyboardManager.processInput(999)
        assertEquals(1, keyboardManager.layer)
        assertEquals("", keyboardManager.textInput)
    }

    @Test
    fun testReset() {
        keyboardManager.processInput(3)
        keyboardManager.processInput(3)
        assertEquals("a", keyboardManager.textInput)

        keyboardManager.reset()
        assertEquals("", keyboardManager.textInput)
        assertEquals(1, keyboardManager.layer)
        assertEquals(0, keyboardManager.page)
    }
}
