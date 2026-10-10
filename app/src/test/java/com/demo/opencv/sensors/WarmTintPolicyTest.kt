package com.demo.opencv.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmTintPolicyTest {

    private fun converge(p: WarmTintPolicy, k: Float) = repeat(60) { p.update(k) }

    @Test
    fun warmLampGivesMaxTint() {
        val p = WarmTintPolicy()
        converge(p, 2700f)
        assertEquals(0.14f, p.alpha, 0.01f)
    }

    @Test
    fun daylightIsNeutral() {
        val p = WarmTintPolicy()
        converge(p, 6500f)
        assertEquals(0f, p.alpha, 0.0001f)
    }

    @Test
    fun midRangeInterpolatesInMired() {
        val p = WarmTintPolicy()
        converge(p, 3500f)
        assertEquals(0.07f, p.alpha, 0.01f) // 285.7 mired is ~50 % between 200 and 370
    }

    @Test
    fun singleSpikeDoesNotFlashTheScreen() {
        val p = WarmTintPolicy()
        converge(p, 6500f)
        p.update(2000f)
        assertTrue("alpha ${p.alpha}", p.alpha < 0.05f)
    }

    @Test
    fun invalidReadingsAreIgnored() {
        val p = WarmTintPolicy()
        assertFalse(p.update(0f))
        assertFalse(p.update(Float.NaN))
        assertFalse(p.update(40000f))
        assertTrue(p.smoothedKelvin.isNaN())
    }

    @Test
    fun tinyChangesDoNotRepaint() {
        val p = WarmTintPolicy()
        converge(p, 3000f)
        assertFalse(p.update(3010f))
    }

    @Test
    fun parsesIqooLightCctEvent() {
        val event = floatArrayOf(0.03f, 11.49f, 3.29f, 2.10f, 0.99f, 1.06f, 3204f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
        assertEquals(3204f, CctReading.kelvinFrom(event)!!, 0.01f)
        assertNull(CctReading.kelvinFrom(FloatArray(16))) // zeros = no reading
        assertNull(CctReading.kelvinFrom(floatArrayOf(1f, 2f)))
    }
}
