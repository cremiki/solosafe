package com.solosafe.app.service

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Manages session expiry countdown and notifications.
 * T-15min: warning
 * T0: session expired — ask to extend
 * T+5: pre-alarm if no response
 * T+8: automatic alarm SESSION_EXPIRED
 */
class SessionExpiryManager {

    sealed class Event {
        data object Warning10 : Event()      // T-10 min (sound)
        data object Warning5 : Event()       // T-5 min (sound)
        data object Warning15 : Event()      // T-15 min (text only)
        data object Expired : Event()        // T0
        data object PreAlarm : Event()       // T+5 min
        data object Alarm : Event()          // T+8 min — auto alarm
        data class Extended(val newEndTime: Long) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 5)
    val events: SharedFlow<Event> = _events

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null
    private var endTime: Long = 0
    private var warned15 = false
    private var warned10 = false
    private var warned5 = false
    private var expiredNotified = false
    private var preAlarmNotified = false

    fun start(plannedEndTimeMs: Long) {
        stop()
        endTime = plannedEndTimeMs
        warned15 = false
        warned10 = false
        warned5 = false
        expiredNotified = false
        preAlarmNotified = false

        Log.d("SoloSafe", "SessionExpiry started: end at ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(endTime))}")

        monitorJob = scope.launch {
            while (isActive) {
                delay(10_000) // Check every 10 seconds
                val remaining = endTime - System.currentTimeMillis()
                val remainingMin = remaining / 60_000

                // T-15 (text only)
                if (!warned15 && remaining in 0..15 * 60_000) {
                    warned15 = true
                    Log.d("SoloSafe", "SessionExpiry: T-15 warning")
                    _events.emit(Event.Warning15)
                }

                // T-10 (sound alert)
                if (!warned10 && remaining in 0..10 * 60_000) {
                    warned10 = true
                    Log.d("SoloSafe", "SessionExpiry: T-10 sound warning")
                    _events.emit(Event.Warning10)
                }

                // T-5 (sound alert)
                if (!warned5 && remaining in 0..5 * 60_000) {
                    warned5 = true
                    Log.d("SoloSafe", "SessionExpiry: T-5 sound warning")
                    _events.emit(Event.Warning5)
                }

                // T0
                if (!expiredNotified && remaining <= 0) {
                    expiredNotified = true
                    Log.d("SoloSafe", "SessionExpiry: T0 expired")
                    _events.emit(Event.Expired)
                }

                // T+5
                if (!preAlarmNotified && remaining <= -5 * 60_000) {
                    preAlarmNotified = true
                    Log.d("SoloSafe", "SessionExpiry: T+5 pre-alarm")
                    _events.emit(Event.PreAlarm)
                }

                // T+8
                if (remaining <= -8 * 60_000) {
                    Log.d("SoloSafe", "SessionExpiry: T+8 ALARM")
                    _events.emit(Event.Alarm)
                    break
                }
            }
        }
    }

    /** Extend session by given hours */
    fun extend(extraHours: Int) {
        endTime += extraHours * 3600_000L
        warned15 = false
        warned10 = false
        warned5 = false
        expiredNotified = false
        preAlarmNotified = false
        scope.launch {
            _events.emit(Event.Extended(endTime))
        }
        Log.d("SoloSafe", "Session extended by ${extraHours}h, new end: ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(endTime))}")
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
