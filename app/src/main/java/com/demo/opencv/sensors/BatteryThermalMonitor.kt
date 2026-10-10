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
