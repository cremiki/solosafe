package com.solosafe.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.solosafe.app.SoloSafeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Sends SMS alerts to emergency contacts when an alarm triggers.
 * Reads authorized numbers from SharedPreferences.
 */
object SmsAlertManager {

    @Suppress("MissingPermission")
    suspend fun sendAlertSms(
        context: Context,
        alarmType: String,
        operatorName: String,
        lat: Double?,
        lng: Double?,
    ) = withContext(Dispatchers.IO) {
        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("SoloSafe", "SMS permission not granted, skipping")
            return@withContext
        }

        val prefs = context.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        val numbersStr = prefs.getString("authorized_numbers", "") ?: ""
        val numbers = numbersStr.split(",").filter { it.isNotBlank() }

        if (numbers.isEmpty()) {
            Log.w("SoloSafe", "No emergency numbers configured, skipping SMS")
            return@withContext
        }

        val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val gpsText = if (lat != null && lng != null) "$lat,$lng" else "non disponibile"
        val mapsLink = if (lat != null && lng != null) "https://maps.google.com/?q=$lat,$lng" else ""

        val message = "ALLARME $alarmType - Operatore: $operatorName - GPS: $gpsText - $timestamp $mapsLink".take(160)

        val smsManager = context.getSystemService(SmsManager::class.java)
            ?: SmsManager.getDefault()

        for (number in numbers) {
            try {
                smsManager.sendTextMessage(number.trim(), null, message, null, null)
                Log.d("SoloSafe", "SMS sent to $number: $alarmType")
            } catch (e: Exception) {
                Log.e("SoloSafe", "SMS to $number failed: ${e.message}")
            }
        }

        Log.d("SoloSafe", "SMS alert sent to ${numbers.size} contacts for $alarmType")
    }
}
