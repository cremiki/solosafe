package com.solosafe.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Vibrator
import android.os.VibrationEffect
import android.util.Log
import com.solosafe.app.SoloSafeApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Rileva immobilità prolungata (Man Down).
 * Se l'operatore non si muove per immobilityPreAlarmSec → pre-allarme con vibrazione.
 * Se non risponde entro (immobilityAlarmSec - immobilityPreAlarmSec) → allarme reale.
 */
class ImmobilityDetector(
    private val context: Context,
    private val thresholds: PresetThresholds,
) : SensorEventListener {

    sealed class Event {
        data object PreAlarm : Event()   // vibrazione — "stai bene?"
        data object Alarm : Event()      // nessuna risposta
        data object Cancelled : Event()  // movimento o utente ha cancellato
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 5)
    val events: SharedFlow<Event> = _events

    private val prefs by lazy { context.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE) }
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var alarmJob: Job? = null
    private var isRunning = false

    private var lastG = 0f
    private var lastMovementTime = System.currentTimeMillis()
    private var inPreAlarm = false

    private val isEnabled: Boolean get() = prefs.getBoolean("immobility_enabled", true)
    private val preAlarmSec: Int get() = prefs.getFloat("immobility_seconds", thresholds.immobilityPreAlarmSec.toFloat()).toInt()
    private val alarmSec: Int get() = preAlarmSec + 30 // alarm 30s after pre-alarm

    // Movement threshold — must be high enough to ignore sensor noise on a still table
    // 0.15 is too sensitive, 0.4 filters out noise while detecting real movement
    private val movementThreshold = 0.4f

    fun start() {
        if (isRunning) return
        if (!isEnabled) { Log.d("SoloSafe", "ImmobilityDetector: disabled"); return }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            lastMovementTime = System.currentTimeMillis()
            isRunning = true
            startMonitoring()
            Log.d("SoloSafe", "ImmobilityDetector started: preAlarm=${preAlarmSec}s")
        }
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(this)
        alarmJob?.cancel()
        isRunning = false
        inPreAlarm = false
        Log.d("SoloSafe", "ImmobilityDetector stopped")
    }

    fun cancelAlarm() {
        alarmJob?.cancel()
        inPreAlarm = false
        lastMovementTime = System.currentTimeMillis()
        startMonitoring()
        scope.launch { _events.emit(Event.Cancelled) }
        Log.d("SoloSafe", "ImmobilityDetector alarm cancelled by user")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val totalG = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        val delta = abs(totalG - lastG)
        lastG = totalG

        if (delta > movementThreshold) {
            lastMovementTime = System.currentTimeMillis()

            // If in pre-alarm and person moves → cancel
            if (inPreAlarm) {
                Log.d("SoloSafe", "Movement detected during pre-alarm — cancelling")
                inPreAlarm = false
                alarmJob?.cancel()
                startMonitoring()
                scope.launch { _events.emit(Event.Cancelled) }
            }
        }
    }

    private fun startMonitoring() {
        alarmJob?.cancel()
        alarmJob = scope.launch {
            while (isActive) {
                delay(5000) // Check every 5 seconds

                val immobileFor = (System.currentTimeMillis() - lastMovementTime) / 1000
                Log.d("SoloSafe", "Immobility check: ${immobileFor}s / ${thresholds.immobilityPreAlarmSec}s (preAlarm=$inPreAlarm)")

                if (!inPreAlarm && immobileFor >= preAlarmSec) {
                    // Pre-alarm: vibrate
                    inPreAlarm = true
                    Log.d("SoloSafe", "Immobility pre-alarm: ${immobileFor}s without movement")
                    vibratePreAlarm()
                    _events.emit(Event.PreAlarm)
                }

                if (inPreAlarm && immobileFor >= alarmSec) {
                    // Full alarm
                    inPreAlarm = false
                    Log.d("SoloSafe", "Immobility alarm: ${immobileFor}s without movement")
                    _events.emit(Event.Alarm)
                    break
                }
            }
        }
    }

    private fun vibratePreAlarm() {
        try {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 500, 300, 500, 300, 500, 300, 1000),
                    -1 // don't repeat
                )
            )
        } catch (e: Exception) {
            Log.w("SoloSafe", "Vibration failed: ${e.message}")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun destroy() {
        stop()
        scope.cancel()
    }
}
