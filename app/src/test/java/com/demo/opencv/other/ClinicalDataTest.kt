package com.demo.opencv.other

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ClinicalDataTest {

    private lateinit var clinicalData: ClinicalData

    @Before
    fun setUp() {
        clinicalData = ClinicalData()
    }

    @Test
    fun testFifoEvictionWhenExceedingMaxCapacity() {
        for (i in 0 until 1050) {
            clinicalData.addNICPoint(i.toFloat(), (i * 2).toFloat())
            clinicalData.addLookLog("Look $i")
        }

        assertEquals(ClinicalData.MAX_DATA_POINTS, clinicalData.leftNICX.size)
        assertEquals(ClinicalData.MAX_DATA_POINTS, clinicalData.leftNICY.size)
        assertEquals(ClinicalData.MAX_DATA_POINTS, clinicalData.lookLog.size)

        // First 50 items should have been evicted; earliest should now be index 50
        assertEquals(50f, clinicalData.leftNICX[0], 0.001f)
        assertEquals(100f, clinicalData.leftNICY[0], 0.001f)
        assertEquals("Look 50", clinicalData.lookLog[0])

        // Last item should be index 1049
        assertEquals(1049f, clinicalData.leftNICX[999], 0.001f)
        assertEquals(2098f, clinicalData.leftNICY[999], 0.001f)
        assertEquals("Look 1049", clinicalData.lookLog[999])
    }

    @Test
    fun testNullLookLogIgnored() {
        clinicalData.addLookLog(null)
        assertEquals(0, clinicalData.lookLog.size)
    }

    @Test
    fun testClear() {
        clinicalData.addNICPoint(1.0f, 2.0f)
        clinicalData.addLookLog("Test entry")
        assertEquals(1, clinicalData.leftNICX.size)
        assertEquals(1, clinicalData.lookLog.size)

        clinicalData.clear()
        assertEquals(0, clinicalData.leftNICX.size)
        assertEquals(0, clinicalData.leftNICY.size)
        assertEquals(0, clinicalData.lookLog.size)
    }

    @Test
    fun testConcurrentAdditionsThreadSafety() {
        val executor = Executors.newFixedThreadPool(4)
        val iterationsPerThread = 300

        for (t in 0 until 4) {
            executor.submit {
                for (i in 0 until iterationsPerThread) {
                    clinicalData.addNICPoint(i.toFloat(), i.toFloat())
                    clinicalData.addLookLog("Thread $t: $i")
                }
            }
        }

        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        // Total added was 1200, but size is capped at MAX_DATA_POINTS (1000)
        assertEquals(ClinicalData.MAX_DATA_POINTS, clinicalData.leftNICX.size)
        assertEquals(ClinicalData.MAX_DATA_POINTS, clinicalData.leftNICY.size)
        assertEquals(ClinicalData.MAX_DATA_POINTS, clinicalData.lookLog.size)
    }
}
