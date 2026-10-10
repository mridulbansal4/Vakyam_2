# 02 — Thermal-Aware Graceful Degradation (SoC heat → adaptive pipeline)

> Prerequisite: shared types, `SensorHealthController` and UI wiring from [00_SENSOR_INTEGRATION_OVERVIEW.md](00_SENSOR_INTEGRATION_OVERVIEW.md).

## 1. What the "temperature sensor" really reads — measured on this phone

```
dumpsys thermalservice   (iQOO 13, charging, app idle-ish)
Thermal Status: 1 (LIGHT)
CPU0..7  = 99.6 °C     GPU0..9 ≈ 64–68 °C     nsp (NPU) ≈ 62–66 °C
skin     = 40.97 °C    battery  = 39.9 °C      socd = 86
cmd thermalservice headroom 10  →  0.8347
```

Two conclusions:

1. **SoC sensors read 65–100 °C during normal operation.** Fixed thresholds like "> 44 °C = critical" applied to CPU temperature would sit at critical all the time. The 39/43/44 °C numbers in the original proposal only make sense for **battery/skin** temperature. Third-party apps can't read skin/CPU sensors (that needs `HardwarePropertiesManager` with system/device-owner privilege).
2. The correct, public signals are:
   * `PowerManager.getCurrentThermalStatus()` + `OnThermalStatusChangedListener` (API 29+): the OEM's own throttling decision (NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN).
   * `PowerManager.getThermalHeadroom(forecastSeconds)` (API 30+): a forecast where `1.0` ≈ SEVERE. It's an early warning, *before* the OS throttles.
   * Battery temperature from `ACTION_BATTERY_CHANGED` (doc 03): the one real °C value an app can read.

There's also `lsm6dsvx Sensor Temperature Sensor` (vendor type 66565). It's the **IMU die temperature**, so show it in debug at most. It isn't a fever, room, or SoC thermometer.

## 2. Where the heat comes from in *this* codebase

Per frame, on `cameraExecutor` (`MainActivity.bindCamera`, 30 fps target):

| Step | Code | Cost |
|---|---|---|
| YUV → RGB full frame | `ImageFormatUtils.rgb` | medium |
| RGB → RGBA → `Bitmap` full frame | `Model.classifyUprightFrame` | medium |
| **ML Kit face + contours + classification on the full frame** | `GoogleFaceDetector.detect` (`CONTOUR_MODE_ALL`, `CLASSIFICATION_MODE_ALL`) | **largest** |
| Eye crops → 44×18 gray, template MSE, iris threshold | `Model`, `EyeDetection` | small (already ROI-cropped) |
| Gemma 4 E2B sentence expansion (GPU→CPU fallback), up to 64 tokens, 25 s timeout | `GemmaManager.expandWithGemma` | big bursts |

Note: **eye-ROI cropping is already done** (eyes are cropped to 44×18 before OpenCV), so "crop OpenCV to the eye ROI to save 70 %" doesn't apply here. The remaining full-frame cost is the RGBA/Bitmap conversion plus ML Kit. The real "ROI" lever is running **ML Kit on a crop around the last face**.

Constraint from existing code: `BallisticGestureDetector` resets its baseline when frames are > 200 ms apart, and `ProfessionalTransitCalibrationHarnessTest.testThermalThrottlingAndVariableFramePacing` proves saccades are still recognised down to 10 fps. So **never go below 10 fps**. 20 fps (50 ms) is comfortably safe.

## 3. The policy

| Tier | Enter when (any) | Actions | UI |
|---|---|---|---|
| `FULL` | status ≤ LIGHT, headroom < 0.90, battery < 41 °C | 30 fps, full-frame ML Kit, Gemma 64 tokens | nothing |
| `BALANCED` | status = MODERATE, **or** headroom ≥ 0.90, **or** battery ≥ 41 °C | 24 fps; **ML Kit on a face crop** (full re-scan every 30 frames or on loss); Gemma 20 tokens (32 for Hindi) | dev panel only |
| `POWER_SAVER` | status ≥ SEVERE, **or** headroom ≥ 1.0, **or** battery ≥ 44 °C | 20 fps; face crop; **Gemma deferred**: instant `GemmaManager.fallbackSentence()`; word prediction already uses the dictionary (`BlurryInput`) when the model is Gemma | orange strip: "Device warm — cooling down while keeping the keyboard active" |

Anti-flapping:
* Going **up** (hotter) is immediate.
* Going **down** (cooler) needs the cooler target to hold for **20 s**, and steps **one tier at a time** (POWER_SAVER → BALANCED → FULL takes ≥ 40 s).
* Battery thresholds have 1.5 °C hysteresis (exit at 42.5 / 39.5 °C).
* Headroom is polled every 10 s. A `NaN` result (rate-limited or unsupported) keeps the previous value.

Frame-rate control is done in software in the analyzer, not by rebinding CameraX. Rebinding causes a visible camera blackout. Skipping a frame costs nothing, because `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` already handles back-pressure. Camera capture itself is cheap; the heat is in processing.

## 4. Where it fits

| Concern | File | Change |
|---|---|---|
| Status + headroom listener | `sensors/ThermalMonitor.kt` | **new** |
| Tier decision (pure) | `sensors/ThermalPolicy.kt` | **new** |
| Frame pacing (pure) | `sensors/FrameRateGovernor.kt` | **new** |
| Apply tier | `SensorHealthController.applyTier` | doc 00 |
| Analyzer gating | `MainActivity` analyzer → `getFrameGovernor().shouldProcess(now)` | doc 00 §4.6 |
| Face-crop detection | `vision/GoogleFaceDetector.java`, `vision/Model.java` | below |
| Token budget / defer | `AI/GemmaManager.kt` | below |
| UI | `sensorBanner` (THERMAL_CRITICAL), dev panel tier/fps/frame-ms | doc 00 |

## 5. Implementation

### 5.1 `sensors/ThermalMonitor.kt`

```kotlin
package com.demo.opencv.sensors

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/** Emits (status, headroom) on the main thread. status = -1 below API 29; headroom = NaN below API 30. */
class ThermalMonitor(
    context: Context,
    private val onReading: (status: Int, headroom: Float) -> Unit
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var statusListener: Any? = null // PowerManager.OnThermalStatusChangedListener (API 29)
    private var status = -1
    private var headroom = Float.NaN
    private var running = false

    private val headroomPoll = object : Runnable {
        override fun run() {
            readHeadroom()
            onReading(status, headroom)
            handler.postDelayed(this, HEADROOM_POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            status = powerManager.currentThermalStatus
            val listener = PowerManager.OnThermalStatusChangedListener { newStatus ->
                Log.i(TAG, "thermal status -> $newStatus")
                status = newStatus
                onReading(status, headroom)
            }
            powerManager.addThermalStatusListener(appContext.mainExecutor, listener)
            statusListener = listener
        }
        handler.post(headroomPoll)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(headroomPoll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (statusListener as? PowerManager.OnThermalStatusChangedListener)?.let {
                powerManager.removeThermalStatusListener(it)
            }
        }
        statusListener = null
    }

    private fun readHeadroom() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val h = powerManager.getThermalHeadroom(HEADROOM_FORECAST_S)
        if (!h.isNaN()) headroom = h
    }

    companion object {
        private const val TAG = "ThermalMonitor"
        private const val HEADROOM_POLL_MS = 10_000L
        private const val HEADROOM_FORECAST_S = 10
    }
}
```

### 5.2 `sensors/ThermalPolicy.kt`

```kotlin
package com.demo.opencv.sensors

/** Pure: inputs → tier, with immediate escalation and slow, one-step-at-a-time recovery. */
class ThermalPolicy @JvmOverloads constructor(
    private val recoveryHoldMs: Long = 20_000L,
    private val balancedHeadroom: Float = 0.90f,
    private val powerSaverHeadroom: Float = 1.0f,
    private val warmBatteryC: Float = 41f,
    private val hotBatteryC: Float = 44f,
    private val batteryHysteresisC: Float = 1.5f
) {
    var tier = PerformanceTier.FULL
        private set
    private var batteryTier = PerformanceTier.FULL
    private var recoveryStartMs = -1L

    fun evaluate(thermalStatus: Int, headroom: Float, batteryTempC: Float, nowMs: Long): PerformanceTier {
        batteryTier = batteryTierFor(batteryTempC)
        val target = maxOf(statusTier(thermalStatus), headroomTier(headroom), batteryTier)
        when {
            target > tier -> {
                tier = target
                recoveryStartMs = -1L
            }
            target < tier -> {
                if (recoveryStartMs < 0L) {
                    recoveryStartMs = nowMs
                } else if (nowMs - recoveryStartMs >= recoveryHoldMs) {
                    tier = PerformanceTier.values()[tier.ordinal - 1]
                    recoveryStartMs = if (target < tier) nowMs else -1L
                }
            }
            else -> recoveryStartMs = -1L
        }
        return tier
    }

    private fun statusTier(status: Int) = when {
        status >= THERMAL_STATUS_SEVERE -> PerformanceTier.POWER_SAVER
        status >= THERMAL_STATUS_MODERATE -> PerformanceTier.BALANCED
        else -> PerformanceTier.FULL
    }

    private fun headroomTier(h: Float) = when {
        h.isNaN() -> PerformanceTier.FULL
        h >= powerSaverHeadroom -> PerformanceTier.POWER_SAVER
        h >= balancedHeadroom -> PerformanceTier.BALANCED
        else -> PerformanceTier.FULL
    }

    private fun batteryTierFor(t: Float): PerformanceTier {
        if (t.isNaN()) return PerformanceTier.FULL
        return when {
            t >= hotBatteryC -> PerformanceTier.POWER_SAVER
            batteryTier == PerformanceTier.POWER_SAVER && t > hotBatteryC - batteryHysteresisC -> PerformanceTier.POWER_SAVER
            t >= warmBatteryC -> PerformanceTier.BALANCED
            batteryTier >= PerformanceTier.BALANCED && t > warmBatteryC - batteryHysteresisC -> PerformanceTier.BALANCED
            else -> PerformanceTier.FULL
        }
    }

    companion object {
        // Mirrors android.os.PowerManager.THERMAL_STATUS_* so this class stays JVM-testable.
        const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3
    }
}
```

### 5.3 `sensors/FrameRateGovernor.kt`

A plain "minimum interval" gate on a 30 fps source can only produce 30/15/10 fps (a 50 ms gate on 33 ms frames gives 15). This governor uses a credit schedule (`nextDue += interval`), so 24 and 20 fps come out exactly, with a worst-case gap of 67 ms.

```kotlin
package com.demo.opencv.sensors

/** Thread-safe. Called from the camera analyzer for every delivered frame. */
class FrameRateGovernor(initialFps: Int = SOURCE_FPS) {

    @Volatile
    var targetFps: Int = initialFps
        set(value) { field = value.coerceIn(MIN_FPS, SOURCE_FPS) }

    private var nextDueMs = Double.NaN

    @Synchronized
    fun shouldProcess(nowMs: Long): Boolean {
        val fps = targetFps
        if (fps >= SOURCE_FPS) {
            nextDueMs = Double.NaN
            return true
        }
        val interval = 1000.0 / fps
        val now = nowMs.toDouble()
        if (!nextDueMs.isNaN() && now + TOLERANCE_MS < nextDueMs) return false
        nextDueMs = if (nextDueMs.isNaN() || now - nextDueMs > interval) now + interval else nextDueMs + interval
        return true
    }

    companion object {
        const val SOURCE_FPS = 30   // MainActivity.bindCamera TARGET_FRAME_RATE
        const val MIN_FPS = 10      // BallisticGestureDetector resets on gaps > 200 ms
        private const val TOLERANCE_MS = 5.0
    }
}
```

### 5.4 Face-crop detection (the `BALANCED` / `POWER_SAVER` CPU saver)

ML Kit runs on a square crop of ~1.6 × the last face box instead of the whole frame. The crop keeps native resolution (unlike downscaling), so eye contours, and therefore `NormalizedIrisEstimator` ratios, keep their precision. Points are shifted back to full-frame coordinates, so everything downstream (eye crops from `rgbMat`, bounding boxes) is unchanged.

**`vision/GoogleFaceDetector.java`**

```java
    public List<PointF> leftEyePoints, rightEyePoints; // full-frame coordinates
    private int offsetX = 0, offsetY = 0;

    public void detect(Bitmap bm) { detect(bm, 0, 0); }

    public void detect(Bitmap bm, int offsetX, int offsetY) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        // ...existing body of detect(Bitmap) unchanged...
    }

    private List<PointF> shifted(FaceContour contour) {
        if (contour == null) return null;
        List<PointF> src = contour.getPoints();
        List<PointF> out = new ArrayList<>(src.size());
        for (PointF p : src) out.add(new PointF(p.x + offsetX, p.y + offsetY));
        return out;
    }
```
In `analyze(List<Face>)`:
* in the "no faces" branch add `leftEyePoints = null; rightEyePoints = null;`
* in the "largest face" branch replace `faceBound = largestFace.getBoundingBox();` with
```java
            faceBound = new Rect(largestFace.getBoundingBox());
            faceBound.offset(offsetX, offsetY);
            leftEyeContour = largestFace.getContour(FaceContour.LEFT_EYE);
            rightEyeContour = largestFace.getContour(FaceContour.RIGHT_EYE);
            leftEyePoints = shifted(leftEyeContour);
            rightEyePoints = shifted(rightEyeContour);
```
(add `import java.util.ArrayList;`)

**`vision/Model.java`**

```java
    private volatile boolean faceRoiDetection = false;
    private int framesSinceFullScan = 0;
    private static final int FULL_SCAN_EVERY_N_FRAMES = 30;

    @Override
    public void setFaceRoiDetection(boolean enabled) { faceRoiDetection = enabled; }

    private Rect detectionRoi(Mat frame) { // null = scan the full frame
        android.graphics.Rect face = faceDetector.faceBound;
        if (!faceRoiDetection || face == null || !faceDetector.faceDetected
                || ++framesSinceFullScan >= FULL_SCAN_EVERY_N_FRAMES) {
            framesSinceFullScan = 0;
            return null;
        }
        int half = (int) (Math.max(face.width(), face.height()) * 0.8);
        int x0 = Math.max(0, face.centerX() - half);
        int y0 = Math.max(0, face.centerY() - half);
        int x1 = Math.min(frame.cols(), face.centerX() + half);
        int y1 = Math.min(frame.rows(), face.centerY() + half);
        if (x1 - x0 < 96 || y1 - y0 < 96) return null;
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }
```
In `classifyUprightFrame`, replace the "google ml kit detection" block:
```java
        Rect roi = detectionRoi(rgbMat);
        Mat detectSrc = (roi != null) ? rgbMat.submat(roi) : rgbMat;
        Mat rgbaMat = new Mat();
        Imgproc.cvtColor(detectSrc, rgbaMat, Imgproc.COLOR_RGB2RGBA);
        if (detectSrc != rgbMat) detectSrc.release();
        bmp = Bitmap.createBitmap(rgbaMat.cols(), rgbaMat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgbaMat, bmp);
        rgbaMat.release();
        if (roi != null) faceDetector.detect(bmp, roi.x, roi.y); else faceDetector.detect(bmp);
```
Then switch the three places that read contour points to the shifted lists:
* left eye: `if (faceDetector.leftEyePoints != null) { List<PointF> leftEyePoints = faceDetector.leftEyePoints; ...`
* right eye: `if (faceDetector.rightEyePoints != null) { List<PointF> rightEyePoints = faceDetector.rightEyePoints; ...`
* primary-eye selection: replace `FaceContour primaryContour` with `List<PointF> primaryPoints`, set it from `faceDetector.leftEyePoints` / `faceDetector.rightEyePoints`, and use `primaryPoints` (not `primaryContour.getPoints()`) for the `NormalizedIrisEstimator` input.

With `faceRoiDetection == false` (tier FULL), `detectionRoi` returns `null` and offsets are 0, so behaviour is byte-identical to today.

Why it is safe:
* The face bound comes from the same upright rotation; `rotationIndex` only changes after 15/90 faceless frames, which already forces a full scan.
* A miss on the crop sets `faceDetected = false`, so the next frame is a full scan.
* A forced full scan every 30 frames re-acquires a face that moved out of the crop.

### 5.5 `AI/GemmaManager.kt`

```kotlin
    @Volatile private var tierOutputTokens = MAX_OUTPUT_TOKENS
    @Volatile private var llmDeferred = false

    fun setPerformanceTier(tier: PerformanceTier) {
        tierOutputTokens = if (tier == PerformanceTier.FULL) MAX_OUTPUT_TOKENS else REDUCED_OUTPUT_TOKENS
        llmDeferred = tier == PerformanceTier.POWER_SAVER
        Log.i(TAG, "performance tier $tier: maxTokens=$tierOutputTokens deferred=$llmDeferred")
    }

    private fun outputTokenBudget(language: String): Int =
        if (language == "Hindi") maxOf(tierOutputTokens, 32) else tierOutputTokens // Devanagari tokenizes longer
```
In `generate(...)`, right after the empty-keywords early return:
```kotlin
        if (llmDeferred) {
            Log.i(TAG, "Thermal POWER_SAVER: skipping Gemma, using fallback sentence")
            sendMessage(fallbackSentence(kw))
            return
        }
```
In `createConversation(...)` change `maxOutputToken = MAX_OUTPUT_TOKENS` to `maxOutputToken = outputTokenBudget(language)`.
In `companion object` add `private const val REDUCED_OUTPUT_TOKENS = 20`.
(add `import com.demo.opencv.sensors.PerformanceTier`)

20 tokens covers the longest few-shot example ("I am hot, can you turn the AC down by two degrees?" ≈ 14 tokens). Conversations are created per request, so the new budget applies from the next sentence, and a generation already in progress finishes normally.

## 6. Testing

### 6.1 Unit tests

`app/src/test/java/com/demo/opencv/sensors/ThermalPolicyTest.kt`
```kotlin
package com.demo.opencv.sensors

import com.demo.opencv.sensors.PerformanceTier.BALANCED
import com.demo.opencv.sensors.PerformanceTier.FULL
import com.demo.opencv.sensors.PerformanceTier.POWER_SAVER
import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalPolicyTest {
    private val NONE = 0; private val LIGHT = 1; private val MODERATE = 2; private val SEVERE = 3
    private val nan = Float.NaN

    @Test
    fun coolDeviceStaysFull() {
        val p = ThermalPolicy()
        assertEquals(FULL, p.evaluate(LIGHT, 0.83f, 37f, 0L)) // values measured on the iQOO 13
    }

    @Test
    fun escalationIsImmediate() {
        val p = ThermalPolicy()
        assertEquals(BALANCED, p.evaluate(MODERATE, nan, nan, 0L))
        assertEquals(POWER_SAVER, p.evaluate(SEVERE, nan, nan, 10L))
    }

    @Test
    fun recoveryStepsDownOneTierPerHold() {
        val p = ThermalPolicy(recoveryHoldMs = 20_000L)
        p.evaluate(SEVERE, nan, nan, 0L)
        val t1 = 1_000L
        assertEquals(POWER_SAVER, p.evaluate(NONE, nan, nan, t1))
        assertEquals(POWER_SAVER, p.evaluate(NONE, nan, nan, t1 + 19_999L))
        assertEquals(BALANCED, p.evaluate(NONE, nan, nan, t1 + 20_000L))
        assertEquals(BALANCED, p.evaluate(NONE, nan, nan, t1 + 39_999L))
        assertEquals(FULL, p.evaluate(NONE, nan, nan, t1 + 40_000L))
    }

    @Test
    fun reheatingDuringRecoveryCancelsIt() {
        val p = ThermalPolicy(recoveryHoldMs = 20_000L)
        p.evaluate(SEVERE, nan, nan, 0L)
        p.evaluate(NONE, nan, nan, 1_000L)
        p.evaluate(SEVERE, nan, nan, 15_000L)
        assertEquals(POWER_SAVER, p.evaluate(NONE, nan, nan, 30_000L)) // timer restarted at 30 s
        assertEquals(BALANCED, p.evaluate(NONE, nan, nan, 50_000L))
    }

    @Test
    fun headroomGivesEarlyWarning() {
        assertEquals(BALANCED, ThermalPolicy().evaluate(LIGHT, 0.92f, nan, 0L))
        assertEquals(POWER_SAVER, ThermalPolicy().evaluate(LIGHT, 1.02f, nan, 0L))
        assertEquals(FULL, ThermalPolicy().evaluate(LIGHT, nan, nan, 0L))
    }

    @Test
    fun batteryTemperatureTiersWithHysteresis() {
        val p = ThermalPolicy(recoveryHoldMs = 0L)
        assertEquals(BALANCED, p.evaluate(NONE, nan, 41f, 0L))
        assertEquals(POWER_SAVER, p.evaluate(NONE, nan, 44f, 1L))
        assertEquals(POWER_SAVER, p.evaluate(NONE, nan, 42.6f, 2L)) // above 44 - 1.5
        p.evaluate(NONE, nan, 42.4f, 3L)
        assertEquals(BALANCED, p.evaluate(NONE, nan, 42.4f, 4L))
        p.evaluate(NONE, nan, 39.4f, 5L)
        assertEquals(FULL, p.evaluate(NONE, nan, 39.4f, 6L))
    }
}
```

`app/src/test/java/com/demo/opencv/sensors/FrameRateGovernorTest.kt`
```kotlin
package com.demo.opencv.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateGovernorTest {

    private fun run(targetFps: Int, frames: Int = 300, jitterMs: Long = 0L): Pair<Int, Long> {
        val g = FrameRateGovernor(targetFps)
        var accepted = 0
        var lastAccepted = -1L
        var maxGap = 0L
        for (i in 0 until frames) {
            val t = (i * 1000L / 30L) + if (i % 2 == 0) jitterMs else -jitterMs
            if (g.shouldProcess(t)) {
                if (lastAccepted >= 0) maxGap = maxOf(maxGap, t - lastAccepted)
                lastAccepted = t
                accepted++
            }
        }
        return accepted to maxGap
    }

    @Test fun fullPassesEverything() = assertEquals(300, run(30).first)

    @Test
    fun balancedIs24Fps() {
        val (n, gap) = run(24)
        assertTrue("got $n", n in 237..243)
        assertTrue("gap $gap", gap <= 70L)
    }

    @Test
    fun powerSaverIs20FpsAndKeepsGesturesAlive() {
        val (n, gap) = run(20, jitterMs = 3L)
        assertTrue("got $n", n in 197..203)
        assertTrue("gap $gap must stay < 200 ms for BallisticGestureDetector", gap < 200L)
    }

    @Test
    fun neverBelowTenFps() {
        val g = FrameRateGovernor()
        g.targetFps = 2
        assertEquals(FrameRateGovernor.MIN_FPS, g.targetFps)
    }
}
```

### 6.2 On-device tests (iQOO 13) — real OS events

`override-status` locks the thermal status reported to every listener (including ours) until `reset`. It doesn't heat or throttle the hardware.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -s SensorHealth ThermalMonitor GemmaManager
```

| # | Command / action | Pass criteria |
|---|---|---|
| T1 | App open on Settings | Panel shows `Thermal: LIGHT headroom≈0.8`, `Tier FULL`, measured ≈ 30 fps |
| T2 | `& $adb shell cmd thermalservice override-status 2` | Within 1 s: `thermal status -> 2`, `performance tier -> BALANCED`, measured ≈ 24 fps, frame ms lower than in T1 |
| T3 | `override-status 3`, then type keywords and FINISH in Text mode (LLM mode) | `tier -> POWER_SAVER`, orange strip, ≈ 20 fps, `skipping Gemma` log, sentence appears instantly |
| T4 | Flick left/right/up during T3 | Every gesture registers (20 fps ≥ 10 fps floor) |
| T5 | `& $adb shell cmd thermalservice reset` | Tier stays POWER_SAVER for 20 s → BALANCED → 20 s → FULL; strip disappears on leaving POWER_SAVER |
| T6 | `override-status 3` → `reset` → `override-status 3` within 10 s | No flapping; POWER_SAVER held |
| T7 | `& $adb shell cmd thermalservice headroom 10` vs panel | Same value within one poll interval (10 s) |

Measure the face-crop gain: in FULL vs BALANCED, read `frame=… ms` in the panel for 30 s each with the face centred. Record both numbers in the README; that's the evidence for the claim.

### 6.3 Soak test (real heat, no overrides)

Charge the phone, open Text mode with LLM on, run Gemma repeatedly for 15 min, and log every 30 s:
```powershell
1..30 | ForEach-Object {
  $t = Get-Date -Format HH:mm:ss
  $s = (& $adb shell "dumpsys thermalservice | grep -E 'Thermal Status|mName=battery|mName=skin'") -join ' | '
  "$t  $s" | Tee-Object -Append thermal_soak.log
  Start-Sleep 30
}
```
Pass: `SensorHealth` shows tier changes **before or at** OS status changes (headroom early warning), measured fps never drops below the tier target by more than 2, and no camera process restart in `logcat`.

## 7. Demo (75 s)

1. Panel: "CPU cores are at 99 °C right now and Android still calls that LIGHT. Raw °C would be a fake signal, so we use the OS thermal status and its 10-second forecast."
2. `override-status 2`: "Pre-emptive: we crop ML Kit to the face." Frame ms drops; fps 24.
3. `override-status 3`: orange strip; 20 fps; finish a word and the sentence appears instantly (LLM deferred). Flick to show the cursor is still smooth.
4. `reset`: tier walks down after 20 s, then again after another 20 s, with no flapping.
5. Always end with `cmd thermalservice reset`.

## 8. Acceptance criteria

- [ ] Tier escalates within 1 s of a status change; recovery is stepwise with 20 s holds.
- [ ] Measured fps: FULL ≈ 30, BALANCED ≈ 24, POWER_SAVER ≈ 20; never < 10.
- [ ] FULL tier output identical to current code (no face crop, 64 tokens).
- [ ] POWER_SAVER: no Gemma invocation; sentence returned < 100 ms.
- [ ] `ThermalPolicyTest`, `FrameRateGovernorTest`, and the existing `ProfessionalTransitCalibrationHarnessTest` pass.
