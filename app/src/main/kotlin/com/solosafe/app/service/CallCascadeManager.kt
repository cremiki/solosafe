package com.solosafe.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat
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

            for (contact in contacts) {
                Log.d("SoloSafe", "CallCascade: calling #${contact.position} ${contact.name} (${contact.phone})")
                logEvent(alarmEventId, operatorId, "CALL_INITIATED", alarmType,
                    recipientName = contact.name, recipientPhone = contact.phone, channel = "gsm")

                // Place call. Try direct startActivity first; if app is in background
                // (e.g. after first call ended), Android blocks it — fall back to a
                // full-screen-intent notification which bypasses the restriction.
                try {
                    placeCall(contact.phone, contact.name)
                } catch (e: Exception) {
                    Log.e("SoloSafe", "CallCascade: call failed: ${e.message}")
                    logEvent(alarmEventId, operatorId, "CALL_FAILED", alarmType,
                        recipientName = contact.name, recipientPhone = contact.phone,
                        channel = "gsm", notes = e.message)
                    continue
                }

                // Fixed 25s timeout. Outgoing calls report OFFHOOK while ringing,
                // so we can't reliably distinguish "answered" — just give it 25s then hang up.
                Log.d("SoloSafe", "CallCascade: ringing ${contact.name} for 25s")
                delay(25_000L)

                // Force hangup with multiple strategies
                Log.d("SoloSafe", "CallCascade: hanging up ${contact.name}")
                forceEndCall()
                delay(3_000L) // grace period for system to release call

                logEvent(alarmEventId, operatorId, "CALL_NO_ANSWER", alarmType,
                    recipientName = contact.name, recipientPhone = contact.phone, channel = "gsm")
            }

            Log.d("SoloSafe", "CallCascade: all contacts called, triggering Twilio fallback")
            triggerTwilioFallback(operatorId, operatorName, alarmType, lat, lng, alarmEventId)
        }
    }

    /**
     * Places a GSM call. Direct startActivity is blocked when the app is in
     * background, so we use a notification with full-screen intent which is
     * always allowed and immediately fires the call activity.
     */
    private fun placeCall(phone: String, name: String) {
        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        // Ensure channel exists (high importance for full-screen)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CALL_CHANNEL_ID,
                "SoloSafe Cascata Chiamate",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifiche di avvio chiamate cascata di emergenza"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            context,
            phone.hashCode(),
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle("SoloSafe — chiamata di emergenza")
            .setContentText("Chiamando $name…")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        nm.notify(CALL_NOTIF_ID, notification)
        Log.d("SoloSafe", "CallCascade: posted full-screen-intent notification for $name")
    }

    /** Force-end the current call using multiple fallback strategies. */
    private fun forceEndCall() {
        // Strategy 1: TelecomManager.endCall() (API 28+, requires ANSWER_PHONE_CALLS)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                    == PackageManager.PERMISSION_GRANTED) {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val ok = telecom?.endCall() ?: false
                Log.d("SoloSafe", "CallCascade: TelecomManager.endCall=$ok")
                if (ok) return
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

    companion object {
        private const val CALL_CHANNEL_ID = "solosafe_call_cascade"
        private const val CALL_NOTIF_ID = 7711
    }
}
