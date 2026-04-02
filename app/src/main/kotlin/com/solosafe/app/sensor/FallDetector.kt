package com.solosafe.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.sqrt

/**
 * Rileva cadute tramite accelerometro.
 * Logica: picco G > soglia → attende fallConfirmSec → se immobile → FALL event.
 */
class FallDetector(
    private val context: Context,
    private val thresholds: PresetThresholds,
) : SensorEventListener {

    sealed class Event {
        data object PreAlarm : Event()   // caduta rilevata, countdown in corso
        data object Alarm : Event()      // confermata — nessuna risposta
        data object Cancelled : Event()  // utente ha risposto
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 5)
    val events: SharedFlow<Event> = _events

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var confirmJob: Job? = null
    private var isRunning = false
    private var lastPeakTime = 0L

    // Post-peak immobility tracking
    private var postPeakSamples = mutableListOf<Float>()
    private var inConfirmPhase = false

    fun start() {
        if (isRunning || !thresholds.fallEnabled) return
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            isRunning = true
            Log.d("SoloSafe", "FallDetector started: threshold=${thresholds.fallThresholdG}g, confirm=${thresholds.fallConfirmSec}s")
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
        Log.d("SoloSafe", "FallDetector alarm cancelled by user")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val totalG = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        if (inConfirmPhase) {
            // Tracking immobility after peak
            postPeakSamples.add(totalG)
            return
        }

        // Detect peak
        val now = System.currentTimeMillis()
        if (totalG > thresholds.fallThresholdG && now - lastPeakTime > 5000) {
            lastPeakTime = now
            Log.d("SoloSafe", "Fall peak detected: ${totalG}g (threshold: ${thresholds.fallThresholdG}g)")
            startConfirmation()
        }
    }

    private fun startConfirmation() {
        inConfirmPhase = true
        postPeakSamples.clear()

        scope.launch { _events.emit(Event.PreAlarm) }

        confirmJob?.cancel()
        confirmJob = scope.launch {
            // Wait for confirm period
            delay(thresholds.fallConfirmSec * 1000L)

            // Check if person remained immobile (low variance in G readings)
            val variance = if (postPeakSamples.size > 5) {
                val mean = postPeakSamples.average()
                postPeakSamples.map { (it - mean) * (it - mean) }.average()
            } else {
                0.0 // No data = assume immobile
            }

            inConfirmPhase = false
            postPeakSamples.clear()

            if (variance < 0.05) {
                // Immobile after fall → real alarm
                Log.d("SoloSafe", "Fall confirmed — immobile after peak (variance=$variance)")
                _events.emit(Event.Alarm)
            } else {
                Log.d("SoloSafe", "Fall cancelled — movement detected (variance=$variance)")
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
