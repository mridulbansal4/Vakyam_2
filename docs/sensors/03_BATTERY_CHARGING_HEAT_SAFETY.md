# 03 — Overnight Bedside Charging Heat Safety (battery thermistor)

> Prerequisite: shared types, `CaregiverAlert`, `SensorHealthController` and UI wiring from [00_SENSOR_INTEGRATION_OVERVIEW.md](00_SENSOR_INTEGRATION_OVERVIEW.md). Feeds the tier logic in [02_THERMAL_ADAPTIVE_PERFORMANCE.md](02_THERMAL_ADAPTIVE_PERFORMANCE.md).

## 1. Problem → behaviour

In clinical and home AAC setups, the phone is permanently on a fast charger, mounted to a wheelchair or bedside stand, often under or against bedding. Running camera + ML Kit + Gemma **while fast-charging** is the classic recipe for battery swelling. The patient can't notice or act, so the app must alert the caregiver.

| Level | Condition | Action | UI |
|---|---|---|---|
| `NORMAL` | below thresholds | — | — |
| `WARM` | **charging** and battery ≥ **42 °C** (exit ≤ 40 °C) | one soft chime (`TONE_PROP_BEEP`); thermal policy already in `BALANCED` (≥ 41 °C, doc 02) | amber strip: "Phone is warm while charging — check airflow around the charger" |
| `HOT` | **charging** and ≥ **45 °C** (exit ≤ 43 °C), **or** unplugged and ≥ **48 °C** (exit ≤ 46 °C) | alarm-stream chime (`TONE_CDMA_ALERT_CALL_GUARD`) every 30 s; spoken once: "Caregiver, the phone is overheating. Please unplug the charger."; thermal policy forces `POWER_SAVER` (≥ 44 °C) | red strip (highest priority): "Phone is overheating — unplug the charger and improve airflow" |
| Unplug while HOT | charger removed | alert clears **immediately**, because the caregiver's action fixed the cause | strip gone |

Thresholds rationale: Li-ion cells are typically specified for charging between 0 and 45 °C. 42 °C gives the caregiver time to act before the charging limit. When discharging, the limit is higher (~60 °C), so unplugged only alerts at 48 °C.

## 2. Hardware facts (iQOO 13)

```
dumpsys battery:  AC powered: true   status: 2 (charging)   temperature: 370   (= 37.0 °C)
dumpsys thermalservice:  Temperature{mValue=39.9, mType=2, mName=battery}
```

* `ACTION_BATTERY_CHANGED` → `BatteryManager.EXTRA_TEMPERATURE` is in **tenths of °C** (`370` → `37.0 °C`). This is the battery-pack thermistor, the only real temperature in °C an app can read without privileges.
* The broadcast is **sticky**: `registerReceiver` returns the current value immediately. It fires on every battery change (roughly every 1 % or temperature step), so polling isn't needed.
* `EXTRA_PLUGGED != 0` means connected to AC/USB/wireless. That's the right test for "charging heat", even when the battery is full and only trickle charging.

## 3. Where it fits

| Concern | File | Change |
|---|---|---|
| Battery broadcast receiver | `sensors/BatteryThermalMonitor.kt` | **new** |
| Level decision with hysteresis (pure) | `sensors/BatteryHeatGuard.kt` | **new** |
| Chime / TTS / banner / tier feed | `SensorHealthController.onBatteryReading` | doc 00 |
| Spoken caregiver message | `Presenter.speakCaregiverMessage` → existing `other/AudioManager.speakText` | doc 00 §4.5 |
| Strip | `sensorBanner` (`CHARGING_HOT` > `THERMAL_CRITICAL` > `CHARGING_WARM`) | doc 00 |
| Debug | dev panel `Battery : 37.0 °C plugged=true heat=NORMAL` | doc 00 |

Lifecycle choice: unlike the other monitors, the battery monitor starts in the controller's constructor (`MainActivity.onCreate`) and stops in `release()` (`onDestroy`). It keeps working when the Activity is paused, which is exactly the overnight scenario. A screen-off overnight guarantee would need a foreground service. That's out of scope here and noted in §7.

## 4. Implementation

### 4.1 `sensors/BatteryHeatGuard.kt`

```kotlin
package com.demo.opencv.sensors

/** Pure: battery °C + plugged → NORMAL / WARM / HOT with hysteresis. */
class BatteryHeatGuard @JvmOverloads constructor(
    private val warmChargingC: Float = 42f,
    private val hotChargingC: Float = 45f,
    private val hotUnpluggedC: Float = 48f,
    private val hysteresisC: Float = 2f
) {
    var level = ChargingHeat.NORMAL
        private set

    fun update(tempC: Float, plugged: Boolean): ChargingHeat {
        if (tempC.isNaN()) return level
        val enterHot = if (plugged) hotChargingC else hotUnpluggedC
        val enterWarm = if (plugged) warmChargingC else hotUnpluggedC // unplugged has no WARM stage
        level = when {
            tempC >= enterHot -> ChargingHeat.HOT
            level == ChargingHeat.HOT && tempC > enterHot - hysteresisC -> ChargingHeat.HOT
            tempC >= enterWarm -> ChargingHeat.WARM
            level >= ChargingHeat.WARM && tempC > enterWarm - hysteresisC -> ChargingHeat.WARM
            else -> ChargingHeat.NORMAL
        }
        return level
    }
}
```

### 4.2 `sensors/BatteryThermalMonitor.kt`

`ACTION_BATTERY_CHANGED` is a protected system broadcast, so it's delivered to a `RECEIVER_NOT_EXPORTED` receiver. Using `ContextCompat` keeps lint and Android 14+ happy.

```kotlin
package com.demo.opencv.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

/** Emits (°C, plugged) on the main thread: once immediately (sticky) and on every battery change. */
class BatteryThermalMonitor(
    context: Context,
    private val onReading: (tempC: Float, plugged: Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            intent?.let { dispatch(it) }
        }
    }

    fun start() {
        if (registered) return
        val sticky = ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
        sticky?.let { dispatch(it) }
    }

    fun stop() {
        if (!registered) return
        appContext.unregisterReceiver(receiver)
        registered = false
    }

    private fun dispatch(intent: Intent) {
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        onReading(tenths / 10f, plugged)
    }
}
```

### 4.3 Controller hook (already in doc 00, repeated here for review)

```kotlin
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
        }
        reevaluateTier() // battery °C is the third input of ThermalPolicy (41 → BALANCED, 44 → POWER_SAVER)
    }
```

Note on TTS: `AudioManager.speakText` uses `QUEUE_FLUSH`, so it can cut off a sentence the patient is currently speaking. That's why the spoken message plays **once**, only on the transition into `HOT`. The repeating part is the chime.

## 5. Testing

### 5.1 Unit tests — `app/src/test/java/com/demo/opencv/sensors/BatteryHeatGuardTest.kt`

```kotlin
package com.demo.opencv.sensors

import com.demo.opencv.sensors.ChargingHeat.HOT
import com.demo.opencv.sensors.ChargingHeat.NORMAL
import com.demo.opencv.sensors.ChargingHeat.WARM
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryHeatGuardTest {

    @Test
    fun normalChargingTemperaturesAreSilent() {
        val g = BatteryHeatGuard()
        assertEquals(NORMAL, g.update(37.0f, true)) // measured on the iQOO 13 while charging
        assertEquals(NORMAL, g.update(41.9f, true))
    }

    @Test
    fun chargingEscalatesWarmThenHot() {
        val g = BatteryHeatGuard()
        assertEquals(WARM, g.update(42.0f, true))
        assertEquals(HOT, g.update(45.0f, true))
    }

    @Test
    fun hysteresisPreventsFlapping() {
        val g = BatteryHeatGuard()
        g.update(45.0f, true)
        assertEquals(HOT, g.update(44.0f, true))   // > 45 - 2
        assertEquals(WARM, g.update(43.0f, true))  // ≤ 43 leaves HOT, still ≥ 42
        assertEquals(WARM, g.update(40.5f, true))  // > 42 - 2
        assertEquals(NORMAL, g.update(40.0f, true))
    }

    @Test
    fun unpluggingClearsTheChargingAlertImmediately() {
        val g = BatteryHeatGuard()
        g.update(45.5f, true)
        assertEquals(NORMAL, g.update(45.5f, false))
    }

    @Test
    fun unpluggedOnlyAlertsWhenGenuinelyHot() {
        val g = BatteryHeatGuard()
        assertEquals(NORMAL, g.update(44.0f, false))
        assertEquals(HOT, g.update(48.0f, false))
        assertEquals(HOT, g.update(46.5f, false))
        assertEquals(NORMAL, g.update(46.0f, false))
    }

    @Test
    fun nanKeepsPreviousLevel() {
        val g = BatteryHeatGuard()
        g.update(42.5f, true)
        assertEquals(WARM, g.update(Float.NaN, true))
    }
}
```

### 5.2 On-device tests (iQOO 13) — real `ACTION_BATTERY_CHANGED` broadcasts

`dumpsys battery set …` **freezes** the reported battery state until `reset`, and sends a real `ACTION_BATTERY_CHANGED` to every app, ours included. vivo's own system UI may also react to a faked 46 °C, which is fine for a demo. **Always run `reset` afterwards.**

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -s SensorHealth CaregiverAlert
```

| # | Command | Pass criteria |
|---|---|---|
| B1 | (app open) | Panel `Battery : 37.0 °C plugged=true heat=NORMAL` matches `& $adb shell dumpsys battery` (`temperature: 370`) |
| B2 | `& $adb shell dumpsys battery set ac 1` then `set temp 425` | `heat=WARM`, amber strip, one soft chime, `Tier BALANCED` |
| B3 | `set temp 460` | `heat=HOT`, red strip (replaces amber), alarm-stream chime, spoken message once, `Tier POWER_SAVER` |
| B4 | wait 65 s | Chime repeats at 30 s and 60 s; speech does **not** repeat |
| B5 | `set temp 440` | Still HOT (hysteresis) |
| B6 | `set temp 420` | WARM: chime stops, amber strip |
| B7 | `set temp 460`, then `dumpsys battery unplug` | NORMAL immediately; strip gone; chime stops |
| B8 | Put the phone on the Settings screen at HOT, press Home, wait 35 s | Chime still repeats (monitor lives until `onDestroy`) |
| B9 | **`& $adb shell dumpsys battery reset`** | Values return to real hardware |
| B10 | Strip priority: `override-status 3` (doc 02) + `set temp 425` + `set ac 1` | Orange THERMAL_CRITICAL strip wins over amber CHARGING_WARM; then `set temp 460` → red CHARGING_HOT wins |

Real-heat check (optional, before the event): fast-charge from ~20 % with Text mode + Gemma running in a warm room and the phone lying on a pillow, logging `dumpsys battery | grep temperature` every 30 s. Confirm the WARM/HOT transitions happen at the logged temperatures.

## 6. Demo (45 s)

1. "The phone lives on a charger at the bedside. Running AI while fast-charging is the number-one cause of battery swelling."
2. `set ac 1`, `set temp 460`: red strip, alarm chime, spoken "Caregiver, the phone is overheating…", and the panel shows the tier dropping to POWER_SAVER to cut the heat source.
3. `dumpsys battery unplug`: "The caregiver unplugs, and the alert clears instantly."
4. `dumpsys battery reset`.

## 7. Limits and follow-ups

* Screen-off / app-killed overnight coverage needs a foreground service (`FOREGROUND_SERVICE` + a persistent notification). Add one only if the clinical setup actually turns the screen off. AAC devices normally keep the screen on.
* A notification for a caregiver who is out of earshot would need `POST_NOTIFICATIONS` (runtime permission on API 33+, `targetSdk 34` here). The in-app strip, chime, and speech need no permission.
* Thresholds are deliberately conservative constructor parameters; adjust after the real-heat check.

## 8. Acceptance criteria

- [ ] Panel battery °C equals `dumpsys battery` temperature / 10.
- [ ] WARM at 42 °C and HOT at 45 °C while plugged; HOT at 48 °C unplugged; hysteresis per the unit tests.
- [ ] Unplug clears a charging alert immediately.
- [ ] Speech once per HOT episode; chime every 30 s while HOT; works while the Activity is paused.
- [ ] `BatteryHeatGuardTest` green; `dumpsys battery reset` documented in every demo script.
