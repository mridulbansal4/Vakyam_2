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
    fun testLookIndexToStringValidIndices() {
        assertEquals("Straight", userDataManager.lookIndexToString(0))
        assertEquals("Left", userDataManager.lookIndexToString(1))
        assertEquals("Right", userDataManager.lookIndexToString(2))
        assertEquals("Up", userDataManager.lookIndexToString(3))
        assertEquals("Down", userDataManager.lookIndexToString(4))
        assertEquals("Closed", userDataManager.lookIndexToString(5))
        assertEquals("Left Up", userDataManager.lookIndexToString(6))
        assertEquals("Right Up", userDataManager.lookIndexToString(7))
    }

    @Test
    fun testLookIndexToStringOutOfBounds() {
        assertEquals("Unknown (-1)", userDataManager.lookIndexToString(-1))
        assertEquals("Unknown (8)", userDataManager.lookIndexToString(8))
        assertEquals("Unknown (100)", userDataManager.lookIndexToString(100))
    }

    @Test
    fun testDefaultTemplateArraysInitialized() {
        assertNotNull(userDataManager.leftEyeFileNames)
        assertEquals(6, userDataManager.leftEyeFileNames.size)
        assertNotNull(userDataManager.rightEyeFileNames)
        assertEquals(6, userDataManager.rightEyeFileNames.size)
    }
}
