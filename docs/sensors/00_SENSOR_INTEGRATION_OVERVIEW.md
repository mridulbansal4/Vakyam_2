# 00 — Sensor Safeguards: Shared Integration (read this first)

This file holds the pieces shared by all three sensor features, so each sensor doc stays focused:

| Doc | Sensor (real iQOO 13 hardware) | What it protects |
|---|---|---|
| [02_THERMAL_ADAPTIVE_PERFORMANCE.md](02_THERMAL_ADAPTIVE_PERFORMANCE.md) | `PowerManager` thermal status + thermal headroom (+ battery temp) | Cursor stutter / camera kill when OpenCV + ML Kit + Gemma overheat the SoC |
| [03_BATTERY_CHARGING_HEAT_SAFETY.md](03_BATTERY_CHARGING_HEAT_SAFETY.md) | Battery thermistor via `ACTION_BATTERY_CHANGED` | Battery swelling/heat damage while fast-charging on a bedside/wheelchair mount |
| [04_COLOR_TEMPERATURE_CCT.md](04_COLOR_TEMPERATURE_CCT.md) | `stk_stk6b9x light_cct` (vendor type 66557) + `tcs3743` lux | Eye strain during 8–14 h/day eye typing |

---

## 1. Facts verified on the connected iQOO 13 (model `I2501`, Android 16 / SDK 36)

These were read with `adb shell dumpsys sensorservice`, `dumpsys thermalservice` and `dumpsys battery` and drive every design decision below:

| Fact | Consequence |
|---|---|
| `Thermal Status: 1 (LIGHT)` while `CPU0..7 = 99.6 °C`, `GPU ≈ 66 °C`, `skin = 41 °C`, `battery = 39.9 °C` | Raw SoC °C cannot be used with 39/43/44 °C thresholds. Those numbers only make sense for **battery/skin**. Use the OS thermal status + headroom (doc 02). Apps cannot read skin/CPU temps without system permission. |
| `cmd thermalservice headroom 10` → `0.83` | Headroom is available (API 30+); `1.0` ≈ SEVERE. |
| Color-temperature sensor is **`stk_stk6b9x light_cct`**, not the tcs3743; 16 floats per event; index **6** tracked 1762 → 2220 → 3204 → 4333 K | Find it by `stringType == "android.sensor.light_cct"` / type `66557`; read `values[6]` (doc 04). |
| `lsm6dsvx Sensor Temperature Sensor` (type 66565) exists | That is the **IMU die temperature**, not room, not body, not SoC. Show it only as a debug value; never call it a fever/room thermometer. |
| `cmd thermalservice override-status`, `dumpsys battery set temp` both exist | Thermal and charging scenarios can be triggered on stage, on demand, end-to-end. |

---

## 2. Where this fits in the existing architecture

```
MainActivity (View, lifecycle, CameraX analyzer)
 ├─ owns SensorHealthController  ← NEW (com.demo.opencv.sensors)
 │    ├─ ThermalMonitor + ThermalPolicy + FrameRateGovernor (02)
 │    ├─ BatteryThermalMonitor + BatteryHeatGuard           (03)
 │    ├─ AmbientColorMonitor + WarmTintPolicy               (04)
 │    └─ CaregiverAlert (chimes), banner priority, debug injector
 ├─ analyzer: governor → presenter.onFrame()
 └─ renders: sensorBanner, warmTintOverlay

Presenter      ← setFaceRoiDetection(), speakCaregiverMessage()
Model          ← setFaceRoiDetection()
GemmaManager   ← setPerformanceTier()  (token budget / defer LLM)
UIViewModel    ← sensorHealth LiveData → dev_mode_display "Sensor Health" panel
```

Why the controller lives in `MainActivity` and **not** in `AppLiveData`: `AppLiveData` is only published from `Presenter.onFrame()`, and the frame-rate governor deliberately skips frames when the phone is hot, so anything driven by `AppLiveData` would slow down exactly when it matters. The controller runs on the main looper, independent of frames.

---

## 3. Frontend placement

| Surface | File | Shows |
|---|---|---|
| **Global status strip** (`sensorBanner`) between the top bar and the fragment area | `res/layout/activity_main.xml` | Highest-priority message: charging HOT > thermal CRITICAL > charging WARM. Visible in every mode (Text, QuickChat, Calibration…). |
| **Warm tint overlay** (`warmTintOverlay`) over the fragment area | `activity_main.xml` | Amber layer, alpha 0–0.14, follows room CCT. Touches pass through. |
| **Sensor Health panel** (`sensorHealthText`) | `res/layout/dev_mode_fragment.xml` (Settings screen, already a `ScrollView`) | Live raw values for judges/devs: thermal status, headroom, battery °C, tier, measured FPS, frame ms, CCT K, lux, tint alpha. |

### 3.1 `activity_main.xml` — replace the inner `LinearLayout` that wraps `fragmentContainerView`

```xml
    <TextView
        android:id="@+id/sensorBanner"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:padding="8dp"
        android:textColor="@color/white"
        android:textSize="18sp"
        android:textStyle="bold"
        android:visibility="gone" />

    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent">

        <androidx.fragment.app.FragmentContainerView
            android:id="@+id/fragmentContainerView"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />

        <View
            android:id="@+id/warmTintOverlay"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="#00FF9329"
            android:clickable="false"
            android:focusable="false"
            android:importantForAccessibility="no" />
    </FrameLayout>
```

Non-clickable views do not consume touches, so the caregiver can still tap the fragment underneath the overlay.

### 3.2 `dev_mode_fragment.xml` — add directly after `@+id/textView4`

```xml
        <TextView
            android:id="@+id/sensorHealthText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:fontFamily="monospace"
            android:padding="8dp"
            android:text="Sensor health: waiting…"
            android:textColor="#4A4A4A"
            android:textSize="14sp" />
```

### 3.3 `res/values/strings.xml` (add; translate into `values-hi`)

```xml
    <string name="Sensor_ThermalCritical">Device warm — cooling down while keeping the keyboard active</string>
    <string name="Sensor_ChargingWarm">Phone is warm while charging — check airflow around the charger</string>
    <string name="Sensor_ChargingHot">Phone is overheating — unplug the charger and improve airflow</string>
    <string name="Sensor_ChargingHotSpoken">Caregiver, the phone is overheating. Please unplug the charger.</string>
```

---

## 4. Shared code (package `com.demo.opencv.sensors`)

Path: `app/src/main/java/com/demo/opencv/sensors/`

### 4.1 `SensorHealth.kt` — shared types

```kotlin
package com.demo.opencv.sensors

import androidx.annotation.StringRes
import com.demo.opencv.R
import java.util.Locale

enum class PerformanceTier(val targetFps: Int) {
    FULL(30),
    BALANCED(24),
    POWER_SAVER(20) // never below 10: BallisticGestureDetector resets its baseline on gaps > 200 ms
}

enum class ChargingHeat { NORMAL, WARM, HOT }

/** Declaration order is display priority: the first active banner wins. */
enum class SensorBanner(@StringRes val messageRes: Int, val colorArgb: Int) {
    CHARGING_HOT(R.string.Sensor_ChargingHot, 0xFFC62828.toInt()),
    THERMAL_CRITICAL(R.string.Sensor_ThermalCritical, 0xFFEF6C00.toInt()),
    CHARGING_WARM(R.string.Sensor_ChargingWarm, 0xFFF9A825.toInt())
}

data class SensorHealthSnapshot(
    val thermalStatus: Int = -1,
    val thermalHeadroom: Float = Float.NaN,
    val batteryTempC: Float = Float.NaN,
    val plugged: Boolean = false,
    val chargingHeat: ChargingHeat = ChargingHeat.NORMAL,
    val tier: PerformanceTier = PerformanceTier.FULL,
    val measuredFps: Float = 0f,
    val frameMs: Float = Float.NaN,
    val cctKelvin: Float = Float.NaN,
    val ambientLux: Float = Float.NaN,
    val warmTintAlpha: Float = 0f
) {
    fun toDebugText(): String = buildString {
        appendLine("Thermal   : ${thermalStatusName(thermalStatus)}  headroom=${fmt(thermalHeadroom, 2)}")
        appendLine("Battery   : ${fmt(batteryTempC)} °C  plugged=$plugged  heat=$chargingHeat")
        appendLine("Tier      : $tier  target=${tier.targetFps} fps  measured=${fmt(measuredFps)} fps  frame=${fmt(frameMs)} ms")
        append("Light     : CCT=${fmt(cctKelvin, 0)} K  lux=${fmt(ambientLux, 0)}  tint=${fmt(warmTintAlpha, 2)}")
    }

    private fun fmt(v: Float, decimals: Int = 1): String =
        if (v.isNaN()) "--" else String.format(Locale.US, "%.${decimals}f", v)

    private fun thermalStatusName(s: Int): String = when (s) {
        0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"
        4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"; else -> "UNKNOWN"
    }
}
```

### 4.2 `CaregiverAlert.kt` — distinct, rate-limited chimes

`STREAM_ALARM` is used on purpose: it still plays when the phone is on silent/DND, which is what a caregiver alert needs. The existing calibration/selection beep (`TONE_CDMA_PIP` on `STREAM_MUSIC`) stays different so caregivers learn the sounds apart.

```kotlin
package com.demo.opencv.sensors

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Main-thread only. One repeating chime per key; keys are independent. */
class CaregiverAlert(private val repeatMs: Long = 30_000L) {

    private val handler = Handler(Looper.getMainLooper())
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_ALARM, VOLUME)
    } catch (e: RuntimeException) {
        Log.w(TAG, "ToneGenerator unavailable", e)
        null
    }
    private val active = HashMap<String, Runnable>()

    fun start(key: String, toneType: Int) {
        if (active.containsKey(key)) return
        val repeat = object : Runnable {
            override fun run() {
                play(toneType)
                handler.postDelayed(this, repeatMs)
            }
        }
        active[key] = repeat
        handler.post(repeat)
    }

    fun chimeOnce(toneType: Int) = play(toneType)

    fun stop(key: String) {
        active.remove(key)?.let { handler.removeCallbacks(it) }
    }

    fun release() {
        active.values.forEach { handler.removeCallbacks(it) }
        active.clear()
        tone?.release()
    }

    private fun play(toneType: Int) {
        try {
            tone?.startTone(toneType, TONE_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to play caregiver tone", e)
        }
    }

    companion object {
        private const val TAG = "CaregiverAlert"
        private const val VOLUME = 70
        private const val TONE_MS = 600
    }
}
```

### 4.3 `SensorHealthController.kt` — the single integration point

All three features are wired here. Each sensor doc explains its own monitor/policy classes; this is the final assembled form.

```kotlin
package com.demo.opencv.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.demo.opencv.AI.GemmaManager
import com.demo.opencv.BuildConfig
import com.demo.opencv.ContractInterface
import com.demo.opencv.R
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Create in MainActivity.onCreate (after views + viewModel), start() in onResume,
 * stop() in onPause, release() in onDestroy. Every Listener callback runs on the main thread.
 */
class SensorHealthController(
    context: Context,
    private val presenter: ContractInterface.Presenter,
    private val listener: Listener
) {
    interface Listener {
        fun onBannerChanged(banner: SensorBanner?)
        fun onWarmTintChanged(alpha: Float)
        fun onSnapshot(snapshot: SensorHealthSnapshot)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val alert = CaregiverAlert()
    private val banners: EnumSet<SensorBanner> = EnumSet.noneOf(SensorBanner::class.java)
    private var shownBanner: SensorBanner? = null
    private var snapshot = SensorHealthSnapshot()
    private var started = false

    val frameGovernor = FrameRateGovernor()

    @Volatile private var lastFrameMs = Float.NaN

    // 02 — thermal
    private val thermalPolicy = ThermalPolicy()
    private var tierApplied = false
    private val thermalMonitor = ThermalMonitor(appContext) { status, headroom ->
        publish { it.copy(thermalStatus = status, thermalHeadroom = headroom) }
        reevaluateTier()
    }

    // 03 — battery
    private val heatGuard = BatteryHeatGuard()
    private val batteryMonitor = BatteryThermalMonitor(appContext) { tempC, plugged ->
        onBatteryReading(tempC, plugged)
    }

    // 04 — color temperature
    private val tintPolicy = WarmTintPolicy()
    private var cctOverrideUntilMs = 0L
    private val colorMonitor = AmbientColorMonitor(appContext) { kelvin, lux ->
        if (SystemClock.elapsedRealtime() >= cctOverrideUntilMs) onColorReading(kelvin, lux)
    }

    private val processedFrames = AtomicInteger()
    private var lastTickMs = 0L
    private val ticker = object : Runnable {
        override fun run() {
            reevaluateTier() // lets ThermalPolicy step back down after its recovery hold
            val now = SystemClock.elapsedRealtime()
            val elapsed = if (lastTickMs == 0L) TICK_MS else (now - lastTickMs).coerceAtLeast(1L)
            lastTickMs = now
            val fps = processedFrames.getAndSet(0) * 1000f / elapsed // 0 while suspended, by design
            publish { it.copy(measuredFps = fps, frameMs = lastFrameMs) }
            main.postDelayed(this, TICK_MS)
        }
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            intent ?: return
            if (intent.hasExtra("cct")) {
                cctOverrideUntilMs = SystemClock.elapsedRealtime() + DEBUG_CCT_HOLD_MS
                onColorReading(intent.getFloatExtra("cct", Float.NaN), intent.getFloatExtra("lux", 100f))
            }
        }
    }
    private var debugRegistered = false

    init {
        // Battery heat matters most overnight, so it lives for the whole Activity, not just while resumed.
        batteryMonitor.start()
        Log.i(TAG, "cct=${colorMonitor.cctSensor?.name ?: "none"}")
    }

    fun start() {
        if (started) return
        started = true
        thermalMonitor.start()
        colorMonitor.start()
        if (BuildConfig.DEBUG && !debugRegistered) {
            ContextCompat.registerReceiver(appContext, debugReceiver, IntentFilter(DEBUG_ACTION), ContextCompat.RECEIVER_EXPORTED)
            debugRegistered = true
        }
        main.post(ticker)
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(ticker)
        lastTickMs = 0L
        thermalMonitor.stop()
        colorMonitor.stop()
        if (debugRegistered) {
            appContext.unregisterReceiver(debugReceiver)
            debugRegistered = false
        }
    }

    fun release() {
        stop()
        batteryMonitor.stop()
        alert.release()
    }

    /** Camera analyzer thread, after presenter.onFrame(). */
    fun onFrameProcessed(processingMs: Long) {
        processedFrames.incrementAndGet()
        val prev = lastFrameMs
        lastFrameMs = if (prev.isNaN()) processingMs.toFloat() else prev + 0.1f * (processingMs - prev)
    }

    // ---------------------------------------------------------------- 02
    private fun reevaluateTier() {
        val previous = thermalPolicy.tier
        val tier = thermalPolicy.evaluate(
            snapshot.thermalStatus, snapshot.thermalHeadroom, snapshot.batteryTempC, SystemClock.elapsedRealtime()
        )
        if (tier != previous || !tierApplied) applyTier(tier)
    }

    private fun applyTier(tier: PerformanceTier) {
        tierApplied = true
        frameGovernor.targetFps = tier.targetFps
        presenter.setFaceRoiDetection(tier != PerformanceTier.FULL)
        GemmaManager.getInstance().setPerformanceTier(tier)
        setBanner(SensorBanner.THERMAL_CRITICAL, tier == PerformanceTier.POWER_SAVER)
        Log.i(TAG, "performance tier -> $tier")
        publish { it.copy(tier = tier) }
    }

    // ---------------------------------------------------------------- 03
    private fun onBatteryReading(tempC: Float, plugged: Boolean) {
        val previous = heatGuard.level
        val level = heatGuard.update(tempC, plugged)
        publish { it.copy(batteryTempC = tempC, plugged = plugged, chargingHeat = level) }
        if (level != previous) {
            setBanner(SensorBanner.CHARGING_WARM, level == ChargingHeat.WARM)
            setBanner(SensorBanner.CHARGING_HOT, level == ChargingHeat.HOT)
            when (level) {
                ChargingHeat.HOT -> {
                    alert.start(ALERT_HEAT, ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)
                    presenter.speakCaregiverMessage(appContext.getString(R.string.Sensor_ChargingHotSpoken))
                }
                ChargingHeat.WARM -> {
                    alert.stop(ALERT_HEAT)
                    if (previous == ChargingHeat.NORMAL) alert.chimeOnce(ToneGenerator.TONE_PROP_BEEP)
                }
                ChargingHeat.NORMAL -> alert.stop(ALERT_HEAT)
            }
            Log.i(TAG, "charging heat $previous -> $level at $tempC °C plugged=$plugged")
        }
        reevaluateTier()
    }

    // ---------------------------------------------------------------- 04
    private fun onColorReading(kelvin: Float, lux: Float) {
        val changed = tintPolicy.update(kelvin)
        publish { it.copy(cctKelvin = tintPolicy.smoothedKelvin, ambientLux = lux, warmTintAlpha = tintPolicy.alpha) }
        if (changed) listener.onWarmTintChanged(tintPolicy.alpha)
    }

    // ---------------------------------------------------------------- shared
    private fun setBanner(banner: SensorBanner, on: Boolean) {
        if (on) banners.add(banner) else banners.remove(banner)
        val top = banners.firstOrNull()
        if (top != shownBanner) {
            shownBanner = top
            listener.onBannerChanged(top)
        }
    }

    private fun publish(change: (SensorHealthSnapshot) -> SensorHealthSnapshot) {
        snapshot = change(snapshot)
        listener.onSnapshot(snapshot)
    }

    companion object {
        private const val TAG = "SensorHealth"
        const val DEBUG_ACTION = "com.vakyam.debug.SENSOR"
        private const val TICK_MS = 1_000L
        private const val DEBUG_CCT_HOLD_MS = 60_000L
        private const val ALERT_HEAT = "heat"
    }
}
```

### 4.4 `UIViewModel.java` — add a second LiveData

```java
    private final MutableLiveData<SensorHealthSnapshot> sensorHealth = new MutableLiveData<>();
    public void postSensorHealth(SensorHealthSnapshot snapshot) { sensorHealth.setValue(snapshot); } // main thread
    public LiveData<SensorHealthSnapshot> getSensorHealth() { return sensorHealth; }
```
(import `com.demo.opencv.sensors.SensorHealthSnapshot`)

### 4.5 `ContractInterface.java` — new methods

```java
    interface Model {
        // ...existing...
        void setFaceRoiDetection(boolean enabled);  // any thread
    }

    interface Presenter {
        // ...existing...
        void setFaceRoiDetection(boolean enabled);
        void speakCaregiverMessage(String text);
    }
```
Only `Model` and `Presenter` implement these interfaces (verified), so nothing else breaks. Implementations are in doc 02; `speakCaregiverMessage` simply calls `audioManager.speakText(text)`.

### 4.6 `MainActivity.java` — wiring

Fields:
```java
    private volatile SensorHealthController sensorController;
    private TextView sensorBanner;
    private View warmTintOverlay;
    private static final int WARM_TINT_RGB = 0xFFFF9329;

    private final SensorHealthController.Listener sensorListener = new SensorHealthController.Listener() {
        @Override public void onBannerChanged(@Nullable SensorBanner banner) {
            if (banner == null) { sensorBanner.setVisibility(View.GONE); return; }
            sensorBanner.setText(banner.getMessageRes());
            sensorBanner.setBackgroundColor(banner.getColorArgb());
            sensorBanner.setVisibility(View.VISIBLE);
        }
        @Override public void onWarmTintChanged(float alpha) {
            warmTintOverlay.setBackgroundColor(ColorUtils.setAlphaComponent(WARM_TINT_RGB, Math.round(alpha * 255)));
        }
        @Override public void onSnapshot(@NonNull SensorHealthSnapshot snapshot) {
            if (viewModel != null) viewModel.postSensorHealth(snapshot);
        }
    };
```

In `onCreate`, right after `viewModel = new ViewModelProvider(this).get(UIViewModel.class);`:
```java
        sensorBanner = findViewById(R.id.sensorBanner);
        warmTintOverlay = findViewById(R.id.warmTintOverlay);
        sensorController = new SensorHealthController(this, presenter, sensorListener);
```
(CameraX binds asynchronously after `onCreate` returns, so the controller exists before the first frame; the analyzer still null-checks it.)

Lifecycle:
```java
    @Override
    protected void onResume() {
        super.onResume();
        if (sensorController != null) sensorController.start();
    }

    @Override
    protected void onPause() {
        if (sensorController != null) sensorController.stop();
        super.onPause();
    }
```
and in the existing `onDestroy()` add `if (sensorController != null) sensorController.release();` before `presenter.onDestroy()`.

Analyzer — replace the body of `analyze(...)` in `bindCamera`:
```java
            public void analyze(@NonNull ImageProxy image) {
                long now = SystemClock.elapsedRealtime();
                SensorHealthController sensors = sensorController;
                if (presenter.getPresenterState()
                        || (sensors != null && !sensors.getFrameGovernor().shouldProcess(now))) {
                    image.close();
                    return;
                }
                if (image.getFormat() == ImageFormat.YUV_420_888 && image.getPlanes().length == 3) {
                    Image newImage = image.getImage();
                    Mat rgbaMat = null;
                    try {
                        if (newImage != null) {
                            rgbaMat = ImageFormatUtils.rgb(newImage);
                            long t0 = SystemClock.elapsedRealtime();
                            presenter.onFrame(rgbaMat);
                            if (sensors != null) sensors.onFrameProcessed(SystemClock.elapsedRealtime() - t0);
                        }
                    } catch (Exception e) {
                        Log.e("MVPView", "Error analyzing frame", e);
                    } finally {
                        if (newImage != null) newImage.close();
                        if (rgbaMat != null) rgbaMat.release();
                    }
                } else {
                    Log.d("MVPView", "Image format not correct");
                }
                image.close();
            }
```
New imports: `android.os.SystemClock`, `android.view.View`, `android.widget.TextView`, `androidx.annotation.Nullable`, `androidx.core.graphics.ColorUtils`, `com.demo.opencv.sensors.*`.

### 4.7 `dev_mode_display.java` — Sensor Health panel

At the end of `onViewCreated`:
```java
        TextView sensorHealthText = view.findViewById(R.id.sensorHealthText);
        viewModel.getSensorHealth().observe(getViewLifecycleOwner(), s -> {
            if (s != null && sensorHealthText != null) sensorHealthText.setText(s.toDebugText());
        });
```

---

## 5. Test strategy (all three features)

**Layer 1: JVM unit tests (no phone).** Every decision is in a pure class with injected time, following the same pattern as `MotionSensorStabilizer.JerkProcessor` and `PhoneMotionTracker.Integrator`:

| Pure class | Test file |
|---|---|
| `ThermalPolicy`, `FrameRateGovernor` | `app/src/test/java/com/demo/opencv/sensors/ThermalPolicyTest.kt`, `FrameRateGovernorTest.kt` |
| `BatteryHeatGuard` | `BatteryHeatGuardTest.kt` |
| `WarmTintPolicy`, `CctReading` | `WarmTintPolicyTest.kt` |

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.demo.opencv.sensors.*"
```

**Layer 2: On-device, end-to-end, deterministic.** Use real OS hooks: `cmd thermalservice override-status`, `dumpsys battery set temp`, plus the debug-only broadcast `com.vakyam.debug.SENSOR`. The commands are in each sensor doc.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c; & $adb logcat -s SensorHealth ThermalMonitor AmbientColor CaregiverAlert
```

**Layer 3: Soak test.** Run 15 minutes of Text mode with Gemma enabled and record tier changes, measured FPS, and `dumpsys thermalservice`. See doc 02.

**Always reset after demos:**
```powershell
& $adb shell cmd thermalservice reset
& $adb shell dumpsys battery reset
```

---

## 6. Stage demo script (~3 minutes, one phone, Settings screen open)

Keep the **Settings** screen open (it shows the Sensor Health panel) and mirror the phone with `scrcpy` so judges can see the numbers.

| # | Action | What judges see | Doc |
|---|---|---|---|
| 1 | Point out the panel: thermal `LIGHT`, battery °C, tier `FULL`, ~30 fps, CCT K | Real hardware values, live | — |
| 2 | `cmd thermalservice override-status 2` | Tier `BALANCED`, 24 fps, frame ms drops (face-ROI detection) | 02 |
| 3 | `override-status 3`, then complete a word in Text mode | Orange banner, 20 fps, sentence appears instantly (Gemma deferred), cursor stays smooth | 02 |
| 4 | `cmd thermalservice reset` | Tier steps down after 20 s, then again after another 20 s, with no flapping | 02 |
| 5 | `dumpsys battery set ac 1` + `set temp 460` | Red strip, alarm-stream chime, spoken caregiver message, tier `POWER_SAVER` | 03 |
| 6 | `dumpsys battery unplug` | Alert clears immediately (the caregiver's action solved it). Then `dumpsys battery reset` | 03 |
| 7 | Hold a warm lamp / phone with night-light near the CCT sensor, or `am broadcast … --ef cct 2700` | Keyboard area warms smoothly; `--ef cct 6500` returns it to neutral | 04 |

Pitch line: *"Every sensor here has a concrete job: keeping the patient's only communication channel working, and telling the caregiver what is wrong when it can't."*
