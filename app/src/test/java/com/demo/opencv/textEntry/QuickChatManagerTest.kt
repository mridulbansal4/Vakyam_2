package com.demo.opencv.textEntry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class QuickChatManagerTest {

    private lateinit var quickChatManager: QuickChatManager

    @Before
    fun setUp() {
        quickChatManager = QuickChatManager()
        quickChatManager.mode = 0
        quickChatManager.updateDisplays()
    }

    @Test
    fun testInitialModeIsZero() {
        assertEquals(0, quickChatManager.mode)
        assertNotNull(quickChatManager.quickChatData)
        assertNotNull(quickChatManager.quickChatData.texts)
        assertEquals(6, quickChatManager.quickChatData.texts.size)
    }

    @Test
    fun testSelectCategory1() {
        // Selection 1 with isGazeType = false selects Category 1
        quickChatManager.manageUserInput(1, false)
        assertEquals(1, quickChatManager.mode)
        assertNotNull(quickChatManager.quickChatData.texts)
        assertEquals(6, quickChatManager.quickChatData.texts.size)
    }

    @Test
    fun testReturnToCategoryFromMode1() {
        quickChatManager.manageUserInput(1, false)
        assertEquals(1, quickChatManager.mode)

        // Selection 0 is back button
        quickChatManager.manageUserInput(0, false)
        assertEquals(0, quickChatManager.mode)
    }

    @Test
    fun testGazeInputSelection() {
        // Gaze type 3 maps to selection 0 (back) in keyboardInputMat
        // Gaze type 1 maps to selection 3 (Category 3)
        quickChatManager.manageUserInput(1, true)
        assertEquals(3, quickChatManager.mode)

        // Now trigger back via gaze type 3 (which maps to selection 0)
        quickChatManager.manageUserInput(3, true)
        assertEquals(0, quickChatManager.mode)
    }

    @Test
    fun testOutOfBoundsSelectionIgnored() {
        quickChatManager.manageUserInput(-1, false)
        quickChatManager.manageUserInput(10, false)
        assertEquals(0, quickChatManager.mode)

        quickChatManager.manageUserInput(-5, true)
        quickChatManager.manageUserInput(100, true)
        assertEquals(0, quickChatManager.mode)
    }

    @Test
    fun testSetModeSafeguards() {
        quickChatManager.setMode(3)
        assertEquals(3, quickChatManager.mode)

        // Out of bounds setMode ignored
        quickChatManager.setMode(10)
        assertEquals(3, quickChatManager.mode)
        quickChatManager.setMode(-1)
        assertEquals(3, quickChatManager.mode)
    }
}
