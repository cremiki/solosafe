package com.solosafe.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import com.solosafe.app.SoloSafeApp

/**
 * Auto-answers incoming calls from authorized numbers when in PROTECTED state.
 * Waits 2 seconds before answering to allow caller ID to resolve.
 */
class AutoAnswerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (incomingNumber.isNullOrBlank()) return

        Log.d("SoloSafe", "Incoming call from: $incomingNumber")

        // Check if app is in PROTECTED state
        val prefs = context.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        val isProtected = prefs.getString("current_state", "standby") == "protected"
        if (!isProtected) {
            Log.d("SoloSafe", "Not in PROTECTED state, skip auto-answer")
            return
        }

        // Check if number is authorized
        val authorizedJson = prefs.getString("authorized_numbers", "[]") ?: "[]"
        val normalized = normalizeNumber(incomingNumber)
        val isAuthorized = authorizedJson.contains(normalized) ||
            authorizedJson.contains(incomingNumber)

        if (!isAuthorized) {
            Log.d("SoloSafe", "Number $incomingNumber not authorized, skip auto-answer")
            return
        }

        Log.d("SoloSafe", "Authorized number — auto-answering in 2s...")

        // Answer after 2 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            answerCall(context)
            Log.d("SoloSafe", "Auto-answered call from $incomingNumber")
        }, 2000)
    }

    @Suppress("MissingPermission")
    private fun answerCall(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                telecom.acceptRingingCall()
            }
        } catch (e: Exception) {
            Log.e("SoloSafe", "Auto-answer failed: ${e.message}")
        }
    }

    private fun normalizeNumber(number: String): String {
        return number.replace(Regex("[\\s\\-().+]"), "").takeLast(10)
    }
}
