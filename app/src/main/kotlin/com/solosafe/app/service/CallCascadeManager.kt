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
        val callEnabled: Boolean = true,
    )

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun startCascade(
        alarmEventId: String?,
        operatorId: String,
        operatorName: String,
        alarmType: String,
        contacts: List<Contact> = emptyList(),  // Deprecated: ignored, uses emergency_contacts_json
        lat: Double?,
        lng: Double?,
    ) {
        // Read tunables from prefs (synced from dashboard via syncOperatorTunables)
        val prefs = context.getSharedPreferences(com.solosafe.app.SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        val maxRounds = prefs.getInt("cascade_max_rounds", 2).coerceAtLeast(1)
        val timeoutSec = prefs.getInt("cascade_timeout_seconds", 25).coerceIn(10, 60)
        val delaySec = prefs.getInt("cascade_delay_seconds", 10).coerceIn(0, 30)

        // Read emergency contacts from JSON (includes all contacts with call_enabled flag)
        val contactsJson = prefs.getString("emergency_contacts_json", "[]") ?: "[]"
        val allContacts = try {
            kotlinx.serialization.json.Json.decodeFromString<List<com.solosafe.app.data.remote.SupabaseClient.EmergencyContactRow>>(contactsJson)
        } catch (e: Exception) {
            Log.w("SoloSafe", "CallCascade: failed to parse emergency_contacts_json: ${e.message}")
            emptyList()
        }

        // Filter for call_enabled and convert to Contact objects, ordered by position
        val cascadeContacts = allContacts
            .filter { it.call_enabled }
            .sortedBy { it.position }
            .map { Contact(name = it.name, phone = it.phone, position = it.position, callEnabled = it.call_enabled) }

        if (cascadeContacts.isEmpty()) {
            Log.d("SoloSafe", "CallCascade: no contacts with call_enabled=true, triggering Twilio fallback")
            triggerTwilioFallback(operatorId, operatorName, alarmType, lat, lng, alarmEventId)
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("SoloSafe", "CallCascade: CALL_PHONE permission not granted, Twilio fallback")
            triggerTwilioFallback(operatorId, operatorName, alarmType, lat, lng, alarmEventId)
            return
        }

        // Debug: log configured tunables
        Log.d("SoloSafe", "CallCascade: CONFIGURATION — maxRounds=$maxRounds, timeout=${timeoutSec}s, delay=${delaySec}s")
        Log.d("SoloSafe", "CallCascade: CONTACTS FOR CASCADE — count=${cascadeContacts.size} (from emergency_contacts_json)")
        cascadeContacts.forEachIndexed { idx, c ->
            Log.d("SoloSafe", "  [$idx] position=${c.position} name='${c.name}' phone='${c.phone}' call_enabled=${c.callEnabled}")
        }

        scope.launch {
            Log.d("SoloSafe", "CallCascade: waiting ${delaySec}s before first call (rounds=$maxRounds, timeout=${timeoutSec}s)")
            delay(delaySec * 1000L)

            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            var answered = false

            roundLoop@ for (round in 1..maxRounds) {
                Log.d("SoloSafe", "CallCascade: ===== ROUND $round/$maxRounds START =====")

                for ((contactIdx, contact) in cascadeContacts.withIndex()) {
                    Log.d("SoloSafe", "CallCascade: Calling contatto ${contactIdx + 1}/${cascadeContacts.size}: ${contact.name} ${contact.phone}")

                    // FIX: Wait for phone to be in IDLE state before calling
                    // Previous call may still be OFFHOOK, causing false "answered" detection
                    @Suppress("DEPRECATION")
                    var waited = 0
                    while (tm.callState != TelephonyManager.CALL_STATE_IDLE && waited < 10) {
                        Log.d("SoloSafe", "CallCascade: waiting for IDLE (state=${tm.callState}, waited=${waited}s)")
                        delay(1_000L)
                        waited++
                    }
                    if (tm.callState != TelephonyManager.CALL_STATE_IDLE) {
                        Log.w("SoloSafe", "CallCascade: phone still not IDLE after 10s, proceeding anyway")
                    }

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

                    // FIX: Increased warmup from 4s to 6s to allow OS time to register outgoing call
                    // and stabilize TelephonyManager state before polling
                    delay(6_000L)

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

                    // FIX: Add 5s stabilization pause between calls
                    // TelephonyManager may still report OFFHOOK after forceEndCall(),
                    // causing false "answered" detection on the next call
                    Log.d("SoloSafe", "CallCascade: stabilization pause (5s) before next contact")
                    delay(5_000L)

                    logEvent(alarmEventId, operatorId, "CALL_NO_ANSWER", alarmType,
                        recipientName = contact.name, recipientPhone = contact.phone, channel = "gsm")
                }

                Log.d("SoloSafe", "CallCascade: ===== ROUND $round/$maxRounds END (no answer) =====")
                if (round < maxRounds) {
                    Log.d("SoloSafe", "CallCascade: round $round complete — pause 5s before next round")
                    delay(5_000L)
                }
            }

            if (!answered) {
                Log.d("SoloSafe", "CallCascade: ===== ALL ROUNDS EXHAUSTED ($maxRounds/$maxRounds) — TRIGGERING TWILIO FALLBACK =====")
                triggerTwilioFallback(operatorId, operatorName, alarmType, lat, lng, alarmEventId)
            } else {
                Log.d("SoloSafe", "CallCascade: ===== CASCADE STOPPED — CALL ANSWERED =====")
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
