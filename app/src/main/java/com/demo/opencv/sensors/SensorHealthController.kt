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
        fun onRingLightChanged(on: Boolean)
        fun onSnapshot(snapshot: SensorHealthSnapshot)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val alert = CaregiverAlert()
    private val banners: EnumSet<SensorBanner> = EnumSet.noneOf(SensorBanner::class.java)
    private val simulations: EnumSet<SensorSimulation> = EnumSet.noneOf(SensorSimulation::class.java)
    private var shownBanner: SensorBanner? = null
    private var snapshot = SensorHealthSnapshot()
    private var started = false
    private val createdAtMs = SystemClock.elapsedRealtime()

    val frameGovernor = FrameRateGovernor()

    @Volatile private var lastFrameMs = Float.NaN

    // 02 — thermal
    private val thermalPolicy = ThermalPolicy()
    private var tierApplied = false
    private var realThermalStatus = -1
    private var realHeadroom = Float.NaN
    private val thermalMonitor = ThermalMonitor(appContext) { status, headroom ->
        realThermalStatus = status
        realHeadroom = headroom
        publishThermal()
        reevaluateTier()
    }

    // 03 — battery
    private val heatGuard = BatteryHeatGuard()
    private var realBatteryC = Float.NaN
    private var realPlugged = false
    private val batteryMonitor = BatteryThermalMonitor(appContext) { tempC, plugged ->
        realBatteryC = tempC
        realPlugged = plugged
        if (SensorSimulation.CHARGING_HOT !in simulations) onBatteryReading(tempC, plugged)
    }

    // 04 — color temperature, 05 — low light (same front light sensor)
    private val tintPolicy = WarmTintPolicy()
    private var cctOverrideUntilMs = 0L
    private var realKelvin = Float.NaN
    private var realCctLux = Float.NaN
    private val lowLight = LowLightPolicy()
    private var lastLux = Float.NaN
    private var ringOn = false
    private val colorMonitor = AmbientColorMonitor(
        appContext,
        onLux = { lux ->
            lastLux = lux
            evaluateLowLight()
        },
        onReading = { kelvin, lux ->
            realKelvin = kelvin
            realCctLux = lux
            when {
                SensorSimulation.WARM_LIGHT in simulations -> onColorReading(SIM_WARM_KELVIN, lux)
                SystemClock.elapsedRealtime() >= cctOverrideUntilMs -> onColorReading(kelvin, lux)
            }
        }
    )

    // 06 — blink rate
    private val blinkRate = BlinkRateMonitor()

    private val processedFrames = AtomicInteger()
    private var lastTickMs = 0L
    private val ticker = object : Runnable {
        override fun run() {
            reevaluateTier() // lets ThermalPolicy step back down after its recovery hold
            evaluateLowLight() // lux is on-change; the hold timers still need to advance
            if (blinkRate.tick(SystemClock.elapsedRealtime())) evaluateRest()
            if (SensorSimulation.WARM_LIGHT in simulations && colorMonitor.cctSensor == null) {
                onColorReading(SIM_WARM_KELVIN, lastLux)
            }
            val now = SystemClock.elapsedRealtime()
            val elapsed = if (lastTickMs == 0L) TICK_MS else (now - lastTickMs).coerceAtLeast(1L)
            lastTickMs = now
            val fps = processedFrames.getAndSet(0) * 1000f / elapsed
            publish {
                it.copy(
                    measuredFps = fps, frameMs = lastFrameMs, uptimeMin = minutesSinceStart(now),
                    blinksPerMin = blinkRate.blinksPerMin(now)
                )
            }
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
        blinkRate.reset()
        evaluateRest()
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

    /** Any thread, once per analysed camera frame. */
    fun onEyeSample(faceVisible: Boolean, eyesClosed: Boolean) {
        val now = SystemClock.elapsedRealtime()
        main.post { if (started && blinkRate.update(faceVisible, eyesClosed, now)) evaluateRest() }
    }

    // ---------------------------------------------------------------- test panel
    fun isSimulating(sim: SensorSimulation): Boolean = sim in simulations

    fun setSimulation(sim: SensorSimulation, on: Boolean) {
        if (on == (sim in simulations)) return
        if (on) simulations.add(sim) else simulations.remove(sim)
        Log.i(TAG, "simulation $sim -> $on")
        publish { it.copy(simulations = simulations.toSet()) }
        when (sim) {
            SensorSimulation.DARK_ROOM -> evaluateLowLight()
            SensorSimulation.HOT_PHONE -> {
                if (!on) resetTier()
                publishThermal()
                reevaluateTier()
            }
            SensorSimulation.CHARGING_HOT -> {
                if (on) {
                    onBatteryReading(SIM_BATTERY_C, true)
                } else {
                    onBatteryReading(realBatteryC, realPlugged)
                    resetTier()
                    reevaluateTier()
                }
            }
            SensorSimulation.WARM_LIGHT -> {
                if (on) {
                    onColorReading(SIM_WARM_KELVIN, lastLux)
                } else {
                    // The CCT sensor is on-change, so a steady room may never send a fresh reading.
                    tintPolicy.reset()
                    onColorReading(realKelvin, realCctLux)
                    if (!ringOn) listener.onWarmTintChanged(tintPolicy.alpha)
                }
            }
            SensorSimulation.TIRED_EYES -> evaluateRest()
        }
    }

    fun clearSimulations() {
        SensorSimulation.values().forEach { setSimulation(it, false) }
    }

    // ---------------------------------------------------------------- 02
    private fun publishThermal() {
        val simulated = SensorSimulation.HOT_PHONE in simulations
        publish {
            it.copy(
                thermalStatus = if (simulated) ThermalPolicy.THERMAL_STATUS_SEVERE else realThermalStatus,
                thermalHeadroom = if (simulated) Float.NaN else realHeadroom
            )
        }
    }

    private fun resetTier() {
        thermalPolicy.reset()
        tierApplied = false
    }

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
        val simulatedHeat = SensorSimulation.HOT_PHONE in simulations || SensorSimulation.CHARGING_HOT in simulations
        if (tier != PerformanceTier.FULL && !simulatedHeat && snapshot.firstSlowdownMin.isNaN()) {
            val minutes = minutesSinceStart(SystemClock.elapsedRealtime())
            Log.i(TAG, "first heat slow-down after $minutes min")
            publish { it.copy(firstSlowdownMin = minutes) }
        }
    }

    private fun minutesSinceStart(nowMs: Long) = (nowMs - createdAtMs) / 60_000f

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
        if (changed && !ringOn) listener.onWarmTintChanged(tintPolicy.alpha)
    }

    // ---------------------------------------------------------------- 05
    private fun evaluateLowLight() {
        lowLight.update(lastLux, SystemClock.elapsedRealtime())
        setRing(lowLight.ringOn || SensorSimulation.DARK_ROOM in simulations)
        publish {
            it.copy(
                frontLux = lastLux,
                roomLux = lowLight.roomLux(lastLux),
                ringContributionLux = lowLight.ringContributionLux,
                ringLight = ringOn
            )
        }
    }

    private fun setRing(on: Boolean) {
        if (on == ringOn) return
        ringOn = on
        Log.i(TAG, "ring light -> ${if (on) "ON" else "off"} (front=$lastLux lux)")
        listener.onRingLightChanged(on)
        // An amber tint over the ring would cut the light reaching the face.
        listener.onWarmTintChanged(if (on) 0f else tintPolicy.alpha)
    }

    // ---------------------------------------------------------------- 06
    private fun evaluateRest() {
        val due = blinkRate.restDue || SensorSimulation.TIRED_EYES in simulations
        if (due == snapshot.restDue) return
        Log.i(TAG, "rest reminder -> ${if (due) "ON" else "off"}")
        setBanner(SensorBanner.REST_EYES, due)
        publish { it.copy(restDue = due) }
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
        private const val SIM_BATTERY_C = 46f
        private const val SIM_WARM_KELVIN = 2700f
    }
}
