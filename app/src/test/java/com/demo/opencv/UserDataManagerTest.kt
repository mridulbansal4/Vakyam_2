package com.demo.opencv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class UserDataManagerTest {

    private lateinit var userDataManager: UserDataManager

    @Before
    fun setUp() {
        userDataManager = UserDataManager()
    }

    @Test
    fun testGazeIndexToStringValidIndices() {
        assertEquals("Straight", userDataManager.gazeIndexToString(0))
        assertEquals("Left", userDataManager.gazeIndexToString(1))
        assertEquals("Right", userDataManager.gazeIndexToString(2))
        assertEquals("Up", userDataManager.gazeIndexToString(3))
        assertEquals("Down", userDataManager.gazeIndexToString(4))
        assertEquals("Closed", userDataManager.gazeIndexToString(5))
        assertEquals("Left Up", userDataManager.gazeIndexToString(6))
        assertEquals("Right Up", userDataManager.gazeIndexToString(7))
    }

    @Test
    fun testGazeIndexToStringOutOfBounds() {
        assertEquals("Unknown (-1)", userDataManager.gazeIndexToString(-1))
        assertEquals("Unknown (8)", userDataManager.gazeIndexToString(8))
        assertEquals("Unknown (100)", userDataManager.gazeIndexToString(100))
    }

    @Test
    fun testDefaultTemplateArraysInitialized() {
        assertNotNull(userDataManager.leftEyeFileNames)
        assertEquals(6, userDataManager.leftEyeFileNames.size)
        assertNotNull(userDataManager.rightEyeFileNames)
        assertEquals(6, userDataManager.rightEyeFileNames.size)
    }
}
