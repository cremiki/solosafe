package com.solosafe.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.solosafe.app.SoloSafeApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.sqrt

/**
 * Rileva cadute tramite accelerometro.
 * Emette SOLO PreAlarm — l'alarm reale è gestito dal countdown UI.
 */
class FallDetector(
    private val context: Context,
    private val defaultThresholds: PresetThresholds,
) : SensorEventListener {

    sealed class Event {
        data object PreAlarm : Event()
        data object Cancelled : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 5)
    val events: SharedFlow<Event> = _events

    private val prefs by lazy { context.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE) }
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false
    private var lastPeakTime = 0L
    private var sampleCount = 0
    private var preAlarmActive = false

    private val currentThresholdG: Float
        get() = prefs.getFloat("fall_threshold_g", defaultThresholds.fallThresholdG)

    private val isEnabled: Boolean
        get() = prefs.getBoolean("fall_enabled", defaultThresholds.fallEnabled)

    fun start() {
        if (isRunning) return
        if (!isEnabled) { Log.d("SoloSafe", "FallDetector: disabled"); return }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            isRunning = true
            preAlarmActive = false
            Log.d("SoloSafe", "FallDetector started: threshold=${currentThresholdG}g")
        }
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(this)
        isRunning = false
        preAlarmActive = false
    }

    fun cancelAlarm() {
        preAlarmActive = false
        scope.launch { _events.emit(Event.Cancelled) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isEnabled || preAlarmActive) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val totalG = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        sampleCount++
        if (sampleCount % 100 == 0) {
            Log.d("SoloSafe", "FallDetector G=%.2f threshold=%.1f".format(totalG, currentThresholdG))
        }

        val threshold = currentThresholdG
        val now = System.currentTimeMillis()
        if (totalG > threshold && now - lastPeakTime > 10000) {
            lastPeakTime = now
            preAlarmActive = true
            Log.d("SoloSafe", "FALL PEAK: %.2fg > %.1fg → PreAlarm".format(totalG, threshold))
            scope.launch { _events.emit(Event.PreAlarm) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun destroy() {
        stop()
        scope.cancel()
    }
}
