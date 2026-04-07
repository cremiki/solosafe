package com.solosafe.app.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.solosafe.app.data.remote.SupabaseClient
import kotlinx.coroutines.*

/**
 * Manages GSM call cascade to emergency contacts.
 * Calls contacts in order of priority. If all GSM fail, triggers Twilio fallback.
 */
class CallCascadeManager(
    private val context: Context,
    private val supabase: SupabaseClient,
) {
    data class Contact(
        val name: String,
        val phone: String,
        val position: Int,
    )

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun startCascade(
        alarmEventId: String?,
        operatorId: String,
        operatorName: String,
        alarmType: String,
        contacts: List<Contact>,
        lat: Double?,
        lng: Double?,
    ) {
        if (contacts.isEmpty()) {
            Log.d("SoloSafe", "CallCascade: no contacts, triggering Twilio fallback")
            triggerTwilioFallback(operatorId, operatorName, alarmType, lat, lng, alarmEventId)
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("SoloSafe", "CallCascade: CALL_PHONE permission not granted, Twilio fallback")
            triggerTwilioFallback(operatorId, operatorName, alarmType, lat, lng, alarmEventId)
            return
        }

        // Read tunables from prefs (synced from dashboard via syncOperatorTunables)
        val prefs = context.getSharedPreferences(com.solosafe.app.SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        val maxRounds = prefs.getInt("cascade_max_rounds", 2).coerceAtLeast(1)
        val timeoutSec = prefs.getInt("cascade_timeout_seconds", 25).coerceIn(10, 60)
        val delaySec = prefs.getInt("cascade_delay_seconds", 10).coerceIn(0, 30)

        scope.launch {
            Log.d("SoloSafe", "CallCascade: waiting ${delaySec}s before first call (rounds=$maxRounds, timeout=${timeoutSec}s)")
            delay(delaySec * 1000L)

            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            var answered = false

            roundLoop@ for (round in 1..maxRounds) {
                Log.d("SoloSafe", "CallCascade: ===== round $round/$maxRounds =====")

                for (contact in contacts) {
                    Log.d("SoloSafe", "CallCascade: calling #${contact.position} ${contact.name} (${contact.phone})")
                    logEvent(alarmEventId, operatorId, "CALL_INITIATED", alarmType,
                        recipientName = contact.name, recipientPhone = contact.phone, channel = "gsm")

                    try {
                        placeCall(contact.phone, contact.name)
                    } catch (e: Exception) {
                        Log.e("SoloSafe", "CallCascade: call failed: ${e.message}")
                        logEvent(alarmEventId, operatorId, "CALL_FAILED", alarmType,
                            recipientName = contact.name, recipientPhone = contact.phone,
                            channel = "gsm", notes = e.message)
                        continue
                    }

                    // Warmup: 4s to let the call register as OFFHOOK
                    delay(4_000L)

                    // Poll for (timeoutSec - 4)s. If state IDLE → rejected/missed.
                    // If still active at the timeout, assume the call was answered.
                    val pollSeconds = (timeoutSec - 4).coerceAtLeast(5)
                    var endedByPeer = false
                    var i = 0
                    while (i < pollSeconds) {
                        @Suppress("DEPRECATION")
                        if (tm.callState == TelephonyManager.CALL_STATE_IDLE) {
                            endedByPeer = true
                            Log.d("SoloSafe", "CallCascade: state IDLE at ${4 + i}s — recipient ended")
                            break
                        }
                        delay(1_000L)
                        i++
                    }

                    if (!endedByPeer) {
                        // Still in call after timeout → assume answered. Stop cascade.
                        Log.d("SoloSafe", "CallCascade: still active at ${timeoutSec}s — assuming ANSWERED by ${contact.name}")
                        logEvent(alarmEventId, operatorId, "CALL_ANSWERED", alarmType,
                            recipientName = contact.name, recipientPhone = contact.phone,
                            channel = "gsm", responseBy = contact.name)
                        // Let the conversation continue naturally; do NOT hang up.
                        answered = true
                        break@roundLoop
                    }

                    // Not answered → ensure call is closed and move on quickly
                    forceEndCall()
                    delay(1_000L)
                    logEvent(alarmEventId, operatorId, "CALL_NO_ANSWER", alarmType,
                        recipientName = contact.name, recipientPhone = contact.phone, channel = "gsm")
                }

                if (round < maxRounds) {
                    Log.d("SoloSafe", "CallCascade: round $round complete, no answer — pause 5s before next round")
                    delay(5_000L)
                }
            }

            if (!answered) {
                Log.d("SoloSafe", "CallCascade: all rounds exhausted, triggering Twilio fallback")
                triggerTwilioFallback(operatorId, operatorName, alarmType, lat, lng, alarmEventId)
            }
        }
    }

    /**
     * Places a GSM call by delegating to the foreground SoloSafeService, which
     * is allowed to start activities from background (a normal Context is not
     * on Android 10+).
     */
    private fun placeCall(phone: String, name: String) {
        val svcIntent = Intent(context, SoloSafeService::class.java).apply {
            action = SoloSafeService.ACTION_PLACE_CALL
            putExtra(SoloSafeService.EXTRA_PHONE, phone)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svcIntent)
        } else {
            context.startService(svcIntent)
        }
        Log.d("SoloSafe", "CallCascade: delegated call to SoloSafeService for $name ($phone)")
    }

    /** Force-end the current call using multiple fallback strategies. */
    private fun forceEndCall() {
        // Strategy 1: TelecomManager.endCall() (API 28+, requires ANSWER_PHONE_CALLS)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    Log.w("SoloSafe", "CallCascade: ANSWER_PHONE_CALLS NOT granted — cannot end call. Cascade will fail.")
                } else {
                    val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    val ok = telecom?.endCall() ?: false
                    Log.d("SoloSafe", "CallCascade: TelecomManager.endCall=$ok")
                    if (ok) return
                }
            }
        } catch (e: Exception) {
            Log.w("SoloSafe", "CallCascade: TelecomManager.endCall failed: ${e.message}")
        }

        // Strategy 2: ITelephony reflection (works on most devices, deprecated)
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val getITelephony = TelephonyManager::class.java.getDeclaredMethod("getITelephony")
            getITelephony.isAccessible = true
            val iTelephony = getITelephony.invoke(tm)
            val endCall = iTelephony.javaClass.getDeclaredMethod("endCall")
            endCall.isAccessible = true
            endCall.invoke(iTelephony)
            Log.d("SoloSafe", "CallCascade: ITelephony.endCall invoked")
        } catch (e: Exception) {
            Log.w("SoloSafe", "CallCascade: ITelephony reflection failed: ${e.message}")
        }
    }

    private fun triggerTwilioFallback(
        operatorId: String, operatorName: String, alarmType: String,
        lat: Double?, lng: Double?, alarmEventId: String?
    ) {
        logEvent(alarmEventId, operatorId, "TWILIO_FALLBACK", alarmType, channel = "twilio")
        supabase.notifyAlarmService(operatorId, operatorName, alarmType, lat, lng)
    }

    private fun logEvent(
        alarmEventId: String?, operatorId: String, eventType: String, alarmType: String,
        recipientName: String? = null, recipientPhone: String? = null,
        channel: String? = null, responseBy: String? = null, notes: String? = null,
    ) {
        supabase.logAlarmEvent(alarmEventId, operatorId, eventType, alarmType,
            recipientName, recipientPhone, channel, responseBy, notes)
    }

    fun destroy() {
        scope.cancel()
    }

}
