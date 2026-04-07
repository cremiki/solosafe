package com.solosafe.app.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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

        scope.launch {
            // Wait 10s after alarm before starting cascade (let SMS/Telegram fire first)
            Log.d("SoloSafe", "CallCascade: waiting 10s before first call")
            delay(10_000L)

            var answered = false
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

            for (contact in contacts) {
                if (answered) break

                Log.d("SoloSafe", "CallCascade: calling #${contact.position} ${contact.name} (${contact.phone})")
                logEvent(alarmEventId, operatorId, "CALL_INITIATED", alarmType,
                    recipientName = contact.name, recipientPhone = contact.phone, channel = "gsm")

                // Start GSM call
                try {
                    val callIntent = Intent(Intent.ACTION_CALL).apply {
                        data = Uri.parse("tel:${contact.phone}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(callIntent)
                } catch (e: Exception) {
                    Log.e("SoloSafe", "CallCascade: call failed: ${e.message}")
                    logEvent(alarmEventId, operatorId, "CALL_FAILED", alarmType,
                        recipientName = contact.name, recipientPhone = contact.phone,
                        channel = "gsm", notes = e.message)
                    continue
                }

                // Wait 3s for call to start
                delay(3_000L)

                // Poll up to 22s. OFFHOOK = answered. RINGING then IDLE = no answer.
                var detectedAnswer = false
                var wasRinging = false
                var i = 0
                while (i < 22) {
                    @Suppress("DEPRECATION")
                    val state = tm.callState
                    Log.d("SoloSafe", "CallCascade: poll[$i] state=$state contact=${contact.name}")
                    if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                        detectedAnswer = true
                        break
                    }
                    if (state == TelephonyManager.CALL_STATE_RINGING) {
                        wasRinging = true
                    }
                    if (state == TelephonyManager.CALL_STATE_IDLE && wasRinging) {
                        // Was ringing then went idle = call ended unanswered
                        break
                    }
                    delay(1_000L)
                    i++
                }

                // Always try to hang up any leftover call
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                            == PackageManager.PERMISSION_GRANTED) {
                        val ok = telecom?.endCall() ?: false
                        Log.d("SoloSafe", "CallCascade: endCall result=$ok")
                    } else {
                        Log.w("SoloSafe", "CallCascade: ANSWER_PHONE_CALLS not granted, cannot endCall")
                    }
                } catch (e: Exception) {
                    Log.w("SoloSafe", "CallCascade: endCall failed: ${e.message}")
                }
                // Give the system a moment to release the call before next dial
                delay(2_000L)

                if (detectedAnswer) {
                    Log.d("SoloSafe", "CallCascade: ANSWERED by ${contact.name}")
                    logEvent(alarmEventId, operatorId, "CALL_ANSWERED", alarmType,
                        recipientName = contact.name, recipientPhone = contact.phone,
                        channel = "gsm", responseBy = contact.name)
                    answered = true
                    break
                } else {
                    Log.d("SoloSafe", "CallCascade: NO ANSWER from ${contact.name}, moving to next")
                    logEvent(alarmEventId, operatorId, "CALL_NO_ANSWER", alarmType,
                        recipientName = contact.name, recipientPhone = contact.phone, channel = "gsm")
                    delay(2_000L)
                }
            }

            if (!answered) {
                Log.d("SoloSafe", "CallCascade: all GSM failed, triggering Twilio")
                triggerTwilioFallback(operatorId, operatorName, alarmType, lat, lng, alarmEventId)
            }
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
