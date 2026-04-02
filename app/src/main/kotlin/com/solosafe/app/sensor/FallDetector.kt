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
 * Soglia letta da SharedPreferences ad ogni campione per aggiornamento in tempo reale.
 * G calcolato come: sqrt(x²+y²+z²)/9.81 — valore 1.0 = fermo, >2.5 = impatto.
 */
class FallDetector(
    private val context: Context,
    private val defaultThresholds: PresetThresholds,
) : SensorEventListener {

    sealed class Event {
        data object PreAlarm : Event()
        data object Alarm : Event()
        data object Cancelled : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 5)
    val events: SharedFlow<Event> = _events

    private val prefs by lazy { context.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE) }
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var confirmJob: Job? = null
    private var isRunning = false
    private var lastPeakTime = 0L
    private var sampleCount = 0

    private var postPeakSamples = mutableListOf<Float>()
    private var inConfirmPhase = false

    /** Read current threshold from SharedPreferences (real-time) */
    private val currentThresholdG: Float
        get() = prefs.getFloat("fall_threshold_g", defaultThresholds.fallThresholdG)

    private val isEnabled: Boolean
        get() = prefs.getBoolean("fall_enabled", defaultThresholds.fallEnabled)

    fun start() {
        if (isRunning) return
        if (!isEnabled) {
            Log.d("SoloSafe", "FallDetector: disabled in settings, skipping")
            return
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            isRunning = true
            Log.d("SoloSafe", "FallDetector started: threshold=${currentThresholdG}g")
        }
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(this)
        confirmJob?.cancel()
        isRunning = false
        inConfirmPhase = false
        Log.d("SoloSafe", "FallDetector stopped")
    }

    fun cancelAlarm() {
        confirmJob?.cancel()
        inConfirmPhase = false
        postPeakSamples.clear()
        scope.launch { _events.emit(Event.Cancelled) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isEnabled) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val totalG = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        // Debug log every 50 samples (~1s at SENSOR_DELAY_UI)
        sampleCount++
        if (sampleCount % 50 == 0) {
            Log.d("SoloSafe", "FallDetector G=%.2f (threshold=%.1f, enabled=%b)".format(totalG, currentThresholdG, isEnabled))
        }

        if (inConfirmPhase) {
            postPeakSamples.add(totalG)
            return
        }

        // Read threshold from prefs each time
        val threshold = currentThresholdG
        val now = System.currentTimeMillis()
        if (totalG > threshold && now - lastPeakTime > 5000) {
            lastPeakTime = now
            Log.d("SoloSafe", "FALL PEAK: %.2fg > %.1fg threshold".format(totalG, threshold))
            startConfirmation()
        }
    }

    private fun startConfirmation() {
        inConfirmPhase = true
        postPeakSamples.clear()
        scope.launch { _events.emit(Event.PreAlarm) }

        confirmJob?.cancel()
        confirmJob = scope.launch {
            delay(defaultThresholds.fallConfirmSec * 1000L)

            val variance = if (postPeakSamples.size > 5) {
                val mean = postPeakSamples.average()
                postPeakSamples.map { (it - mean) * (it - mean) }.average()
            } else {
                0.0
            }

            inConfirmPhase = false
            postPeakSamples.clear()

            if (variance < 0.05) {
                Log.d("SoloSafe", "Fall confirmed — immobile (variance=%.4f)".format(variance))
                _events.emit(Event.Alarm)
            } else {
                Log.d("SoloSafe", "Fall cancelled — movement (variance=%.4f)".format(variance))
                _events.emit(Event.Cancelled)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun destroy() {
        stop()
        scope.cancel()
    }
}
