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
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Man Down detector with auto-calibration.
 * On start: samples orientation for 3s as baseline.
 * Then monitors: if orientation deviates > 45° from baseline AND stays immobile > 10s → pre-alarm.
 * No response in 30s → full alarm (MAN_DOWN).
 */
class ManDownDetector(
    private val context: Context,
) : SensorEventListener {

    sealed class Event {
        data object Calibrating : Event()
        data object Ready : Event()
        data object PreAlarm : Event()
        data object Alarm : Event()
        data object Cancelled : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 5)
    val events: SharedFlow<Event> = _events

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false

    // Calibration
    private var isCalibrating = true
    private val calibrationSamples = mutableListOf<FloatArray>()
    private var baselineVector = floatArrayOf(0f, 0f, 9.81f) // default: upright
    private var calibrationJob: Job? = null

    // Detection
    private var deviationStartTime = 0L
    private var isDeviated = false
    private var isImmobile = true
    private var lastG = 0f
    private var preAlarmActive = false
    private var monitorJob: Job? = null

    // Thresholds
    private val deviationAngleDeg = 45f
    private val immobilityThreshold = 0.4f // G variance threshold
    private val deviationHoldSec = 10 // seconds deviated + immobile before pre-alarm
    private val alarmTimeoutSec = 30 // seconds after pre-alarm without response

    fun start() {
        if (isRunning) return
        accelerometer?.let {
            isCalibrating = true
            calibrationSamples.clear()
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            isRunning = true
            Log.d("SoloSafe", "ManDownDetector: calibrating for 3s...")
            scope.launch { _events.emit(Event.Calibrating) }

            // After 3s, finalize calibration
            calibrationJob = scope.launch {
                delay(3000)
                finalizeCalibration()
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(this)
        calibrationJob?.cancel()
        monitorJob?.cancel()
        isRunning = false
        isCalibrating = false
        preAlarmActive = false
        Log.d("SoloSafe", "ManDownDetector stopped")
    }

    fun cancelAlarm() {
        monitorJob?.cancel()
        preAlarmActive = false
        deviationStartTime = 0L
        isDeviated = false
        startMonitoring()
        scope.launch { _events.emit(Event.Cancelled) }
        Log.d("SoloSafe", "ManDownDetector alarm cancelled")
    }

    private fun finalizeCalibration() {
        isCalibrating = false
        if (calibrationSamples.size > 10) {
            // Average all samples as baseline
            val avgX = calibrationSamples.map { it[0] }.average().toFloat()
            val avgY = calibrationSamples.map { it[1] }.average().toFloat()
            val avgZ = calibrationSamples.map { it[2] }.average().toFloat()
            baselineVector = floatArrayOf(avgX, avgY, avgZ)
            Log.d("SoloSafe", "ManDownDetector calibrated: baseline=(${avgX}, ${avgY}, ${avgZ})")
        } else {
            Log.w("SoloSafe", "ManDownDetector: not enough samples, using default baseline")
        }
        scope.launch { _events.emit(Event.Ready) }
        startMonitoring()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (isCalibrating) {
            calibrationSamples.add(floatArrayOf(x, y, z))
            return
        }

        // Check angle deviation from baseline
        val angle = angleBetween(floatArrayOf(x, y, z), baselineVector)
        val nowDeviated = angle > deviationAngleDeg

        // Check immobility
        val totalG = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val delta = abs(totalG - lastG)
        lastG = totalG
        isImmobile = delta < immobilityThreshold

        if (nowDeviated && isImmobile && !preAlarmActive) {
            if (!isDeviated) {
                isDeviated = true
                deviationStartTime = System.currentTimeMillis()
            }
        } else if (!nowDeviated || !isImmobile) {
            isDeviated = false
            deviationStartTime = 0L
        }
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                delay(1000)

                if (isDeviated && deviationStartTime > 0 && !preAlarmActive) {
                    val elapsed = (System.currentTimeMillis() - deviationStartTime) / 1000
                    if (elapsed >= deviationHoldSec) {
                        // Pre-alarm
                        preAlarmActive = true
                        Log.d("SoloSafe", "ManDown pre-alarm: deviated ${elapsed}s + immobile")
                        _events.emit(Event.PreAlarm)

                        // Wait for response
                        delay(alarmTimeoutSec * 1000L)

                        if (preAlarmActive) {
                            // No response → full alarm
                            preAlarmActive = false
                            Log.d("SoloSafe", "ManDown ALARM: no response after ${alarmTimeoutSec}s")
                            _events.emit(Event.Alarm)
                        }
                    }
                }
            }
        }
    }

    /** Angle between two 3D vectors in degrees */
    private fun angleBetween(a: FloatArray, b: FloatArray): Float {
        val magA = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val magB = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (magA == 0f || magB == 0f) return 0f
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val cosAngle = (dot / (magA * magB)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosAngle).toDouble()).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun destroy() {
        stop()
        scope.cancel()
    }
}
