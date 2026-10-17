# 04 — Ambient Color Temperature (CCT): Eye-Comfort Tint + Optional Vision Normalisation

> Prerequisite: shared types, `SensorHealthController` and UI wiring from [00_SENSOR_INTEGRATION_OVERVIEW.md](00_SENSOR_INTEGRATION_OVERVIEW.md).
> Priority: **nice-to-have**. Ship docs 02–03 first.

## 1. Correction: which chip actually measures CCT on this phone

The proposal named the tcs3743. On the iQOO 13 the tcs3743 is the **proximity + ambient lux** sensor (front, top). The colour-temperature sensor is a separate Sensortek part:

```
0x000a27e7) stk_stk6b9x light_cct Non-wakeup | sensortek | type: android.sensor.light_cct(66557) | perm: n/a | on-change
events (16 floats):
 0.03, 11.49, 3.29, 0.00, 0.00, 3.19, 1762.00, 0, 0, 0, 0, 1.00, 0, 0, 0, 0
 0.03, 11.49, 3.29, 1.05, 0.00, 1.06, 2220.00, ...
 0.03, 11.49, 3.29, 2.10, 0.99, 1.06, 3204.00, ...
 0.03,  8.36, 3.29, 2.10, 0.99, 1.06, 4333.00, ...
0x00000033) tcs3743 Ambient Light Sensor Non-wakeup | type: android.sensor.light(5)
events: 167.17, 167.05, 16095.00   ← values[0] = lux
```

* It's a **vendor sensor** (type ≥ 65536) with `perm: n/a`, so it's listed by `SensorManager.getSensorList(Sensor.TYPE_ALL)` and needs no permission. Find it by `stringType == "android.sensor.light_cct"`, falling back to type `66557`.
* **`values[6]` is CCT in Kelvin** (it tracks 1762 → 4333 K as the light changes; the other fields are vendor channels). Confirm on your unit with test C2 below before the demo.
* The only client seen in the logs is the stock camera app (`com.android.camera.utils.SensorManagerUtils`). That strongly suggests it sits on the **rear, next to the main cameras**. On a mount, the back faces the mount or wall. CCT is a spectral *ratio*, so shade lowers lux but changes CCT much less, and room light is diffuse. Verify with test C3.
* Android's camera HAL already applies auto-white-balance to the frames that reach `ImageAnalysis`, so the camera image itself is colour-corrected.

## 2. Feature A — eye-comfort warm tint (recommended)

AAC users stare at the screen from ~40 cm for 8–14 h/day. A cold 6500 K white UI under a warm 2700 K bedside lamp is a strong brightness and colour mismatch (glare, discomfort). The tint matches the UI white point to the room, like True Tone. Position it as a **comfort** feature, not a medical treatment.

| Room CCT | Tint alpha (amber `#FF9329` overlay) |
|---|---|
| ≥ 5000 K (daylight, cool LEDs, hospital fluorescent) | 0 (neutral) |
| 3500 K | ≈ 0.07 |
| ≤ 2700 K (warm bedside lamp, tungsten) | 0.14 (max; text stays readable) |

* Interpolation is linear in **mired** (10⁶/K), which is perceptually uniform, unlike Kelvin.
* Readings are smoothed with an EMA in mired space (α = 0.2 at the ~5 Hz sensor rate, a ~1 s time constant). The overlay repaints only when alpha changes by ≥ 0.01, so a single spike can't flicker the screen.
* The overlay covers the fragment area (keyboard, quick chat, calibration). It's display-only and doesn't affect what the camera sees.
* Watch out for vivo **Eye protection** / Android **Night Light**: if one is on, the screen is already warm, and stacking both looks orange. Turn the system feature off on the demo phone, or add a Settings toggle that sets `WarmTintPolicy(maxAlpha = 0f)`.

## 3. Feature B — vision normalisation (experimental, default OFF)

The idea: under warm light, the red channel carries most of the energy. Brown/dark irises reflect relatively more red than blue, so iris-to-sclera contrast is weakest in red and strongest in green/blue. A green-weighted grayscale (0.15 R + 0.70 G + 0.15 B) for the iris detector could keep the iris edge under tungsten light.

Honest constraints from this codebase:
* CameraX frames are already AWB-corrected, and `EyeDetection.irisDetection` already uses an **adaptive** threshold (`darkPercentile`), so the expected gain is small.
* Calibration templates (`EyeDetection.runEyeModel`, MSE) were captured with standard `COLOR_RGB2GRAY`. Changing the grayscale for the **template path would break matching**. The weighting may only feed the **iris path** (`detector.irisDetection(primaryEyeMat)`).
* So it ships only if an A/B run shows a higher `irisFound` rate under a warm lamp (procedure in §6.3).

## 4. Where it fits

| Concern | File | Change |
|---|---|---|
| Find sensor, parse values (pure) | `sensors/CctReading.kt` | **new** |
| Listener for CCT + lux | `sensors/AmbientColorMonitor.kt` | **new** |
| K → tint alpha (pure) | `sensors/WarmTintPolicy.kt` | **new** |
| Wire + publish | `SensorHealthController.onColorReading` | doc 00 |
| Render | `MainActivity` → `warmTintOverlay.setBackgroundColor(ColorUtils.setAlphaComponent(0xFFFF9329, alpha*255))` | doc 00 §4.6 |
| Panel | `Light : CCT=2710 K lux=12 tint=0.14` | doc 00 |
| Feature B (optional) | `vision/Model.java` iris-path grayscale | §5.4 |

## 5. Implementation

### 5.1 `sensors/CctReading.kt`

```kotlin
package com.demo.opencv.sensors

import android.hardware.Sensor
import android.hardware.SensorManager

object CctReading {
    const val STRING_TYPE = "android.sensor.light_cct"
    const val VIVO_LIGHT_CCT_TYPE = 66557
    const val KELVIN_INDEX = 6 // stk_stk6b9x on iQOO 13; verify with test C2
    const val MIN_K = 1000f
    const val MAX_K = 15000f

    @JvmStatic
    fun kelvinFrom(values: FloatArray): Float? {
        if (values.size <= KELVIN_INDEX) return null
        val k = values[KELVIN_INDEX]
        return if (k in MIN_K..MAX_K) k else null
    }

    @JvmStatic
    fun findSensor(sensorManager: SensorManager): Sensor? {
        val all = sensorManager.getSensorList(Sensor.TYPE_ALL)
        return all.firstOrNull { it.stringType == STRING_TYPE }
            ?: all.firstOrNull { it.type == VIVO_LIGHT_CCT_TYPE }
            ?: all.firstOrNull { it.stringType?.contains("cct", ignoreCase = true) == true }
    }
}
```

### 5.2 `sensors/AmbientColorMonitor.kt`

```kotlin
package com.demo.opencv.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log

/** Emits (kelvin, lux) on the main thread. Does nothing if the phone has no CCT sensor. */
class AmbientColorMonitor(
    context: Context,
    private val onReading: (kelvin: Float, lux: Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val cctSensor: Sensor? = CctReading.findSensor(sensorManager)
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var lux = Float.NaN
    private var running = false
    private var lastRawLogMs = 0L

    fun start() {
        if (running) return
        running = true
        cctSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            ?: Log.w(TAG, "No CCT sensor; warm tint disabled")
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> lux = event.values[0]
            cctSensor?.type -> {
                logRaw(event.values)
                CctReading.kelvinFrom(event.values)?.let { onReading(it, lux) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun logRaw(values: FloatArray) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRawLogMs < RAW_LOG_INTERVAL_MS) return
        lastRawLogMs = now
        Log.d(TAG, "raw[${values.size}] " + values.joinToString { "%.2f".format(it) })
    }

    companion object {
        private const val TAG = "AmbientColor"
        private const val RAW_LOG_INTERVAL_MS = 2_000L
    }
}
```

### 5.3 `sensors/WarmTintPolicy.kt`

```kotlin
package com.demo.opencv.sensors

import kotlin.math.abs

/** Pure: CCT stream → smoothed Kelvin + overlay alpha. Interpolates in mired space. */
class WarmTintPolicy @JvmOverloads constructor(
    private val warmKelvin: Float = 2700f,
    private val neutralKelvin: Float = 5000f,
    private val maxAlpha: Float = 0.14f,
    private val smoothing: Float = 0.2f,
    private val minStep: Float = 0.01f
) {
    var smoothedKelvin = Float.NaN
        private set
    var alpha = 0f
        private set

    /** @return true when [alpha] changed enough to repaint. */
    fun update(kelvin: Float): Boolean {
        if (kelvin.isNaN() || kelvin < CctReading.MIN_K || kelvin > CctReading.MAX_K) return false
        val mired = 1_000_000f / kelvin
        val smoothedMired = if (smoothedKelvin.isNaN()) mired else {
            val previous = 1_000_000f / smoothedKelvin
            previous + smoothing * (mired - previous)
        }
        smoothedKelvin = 1_000_000f / smoothedMired

        val neutralMired = 1_000_000f / neutralKelvin
        val warmMired = 1_000_000f / warmKelvin
        val t = ((smoothedMired - neutralMired) / (warmMired - neutralMired)).coerceIn(0f, 1f)
        val target = t * maxAlpha
        if (abs(target - alpha) < minStep) return false
        alpha = target
        return true
    }
}
```

### 5.4 Feature B (experimental) — iris-path grayscale in `vision/Model.java`

```java
    private volatile boolean warmLightIrisGray = false;
    private Mat warmGrayWeights; // created lazily, after OpenCV is loaded

    public void setWarmLightIrisGrayscale(boolean enabled) { warmLightIrisGray = enabled; }

    private Mat irisGray(Mat rgbEye) { // only for detector.irisDetection, never for template matching
        if (warmGrayWeights == null) {
            warmGrayWeights = new Mat(1, 3, CvType.CV_32F);
            warmGrayWeights.put(0, 0, 0.15, 0.70, 0.15);
        }
        Mat gray = new Mat();
        Core.transform(rgbEye, gray, warmGrayWeights); // 3-channel 8U → 1-channel 8U
        return gray;
    }
```
In the left/right eye blocks of `classifyUprightFrame`, after `Imgproc.resize(...)` and **before** `Imgproc.cvtColor(..., COLOR_RGB2GRAY)`:
```java
                Mat leftEyeIris = warmLightIrisGray ? irisGray(leftEye) : null; // same for right
```
In the primary-eye selection, call `detector.irisDetection(primaryIrisMat)` where `primaryIrisMat` is the matching `*EyeIris` if non-null, else `primaryEyeMat`. Release both `*EyeIris` mats at the end of the method. Expose the toggle through `ContractInterface.Model` / `Presenter` like `setFaceRoiDetection`, and drive it from the controller with `cct < 3200 K` **only after** the A/B in §6.3 passes.

## 6. Testing

### 6.1 Unit tests — `app/src/test/java/com/demo/opencv/sensors/WarmTintPolicyTest.kt`

```kotlin
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
```

### 6.2 On-device tests (iQOO 13)

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -s SensorHealth AmbientColor
```

| # | Action | Pass criteria |
|---|---|---|
| C1 | Launch app | `SensorHealth: ... cct=stk_stk6b9x light_cct` in the log (not `none`); `dumpsys sensorservice` lists `com.vakyam.app` as a client of `0x000a27e7` |
| C2 | **Index check.** Watch the `raw[16]` logs for 20 s under a warm lamp (≤ 3000 K), then 20 s under a phone flashlight or daylight | Only index 6 moves between ~2000–3000 and ~5000–6500. If another index behaves like that, change `KELVIN_INDEX` |
| C3 | **Location check.** Cover the front top edge, then the rear camera island, with an opaque card | CCT/lux of `light_cct` changes only when the **rear** is covered → rear sensor confirmed (expected) |
| C4 | Move between a warm lamp and a cool light | Panel CCT follows within ~1–2 s; overlay fades smoothly, with no flicker |
| C5 | Phone on its real mount, back against the stand | CCT still within ~±500 K of the reading with the phone held free (if not, mount it with a gap or use the debug override for demos) |
| C6 | Debug injection: `& $adb shell am broadcast -a com.vakyam.debug.SENSOR -p com.vakyam.app --ef cct 2700` then `--ef cct 6500` | Tint goes to max, then back to neutral; real sensor ignored for 60 s after injection |
| C7 | Text mode under max tint | All 6 eye options and the selected highlight remain clearly distinguishable |

### 6.3 Feature B A/B procedure (only if you want to ship it)

Add a counter in `Presenter.onFrame` that logs every 300 frames: `frames with faceDetected` and `frames with irisFound` → `irisFoundRate`. Under a ≤ 3000 K lamp, run 2 minutes with the toggle off and 2 minutes on, same person and same mount, 3 repetitions. Ship only if the rate improves by ≥ 5 percentage points and Text-mode gesture accuracy doesn't regress.

## 7. Demo (40 s)

1. Panel: `CCT ≈ 4300 K`, tint 0.00. "This isn't the tcs3743. It's a separate spectral sensor, `stk6b9x light_cct`, that we found by enumerating the vendor sensors."
2. Hold a warm desk lamp near the back of the phone (or `--ef cct 2700`): the keyboard area warms smoothly over ~1 s.
3. Phone flashlight / `--ef cct 6500`: back to neutral.
4. Line: "Patients stare at this screen 12 hours a day; we match it to the room so it's not a cold white glare under a bedside lamp."

## 8. Acceptance criteria

- [ ] Sensor discovered at runtime; app works unchanged on phones without it.
- [ ] Index 6 verified on the demo unit (test C2) and the result recorded in this doc.
- [ ] Tint 0 at ≥ 5000 K, ≈ 0.14 at ≤ 2700 K, no visible flicker, touches pass through.
- [ ] Feature B stays OFF unless the A/B passes.
- [ ] `WarmTintPolicyTest` green.
