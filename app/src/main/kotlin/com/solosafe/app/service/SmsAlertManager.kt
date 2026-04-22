package com.solosafe.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.solosafe.app.SoloSafeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.*

/**
 * Sends SMS alerts to emergency contacts when an alarm triggers.
 * GSM-first with fallback to server-side Messagenet SMS.
 * Returns list of contacts reached via native SMS for server fallback tracking.
 */
object SmsAlertManager {

    /**
     * Unified SMS template matching server buildAlarmSMS() exactly.
     * Used for both native GSM and Messagenet fallback.
     */
    private fun buildAlarmSMS(
        type: String,
        operatorName: String,
        address: String? = null,
        beaconLabel: String? = null,
        lat: Double? = null,
        lng: Double? = null,
        battery: Int? = null,
    ): String {
        val typeLabels = mapOf(
            "FALL" to "🚨 ALLARME: CADUTA RILEVATA",
            "FALL_DETECTION" to "🚨 ALLARME: CADUTA RILEVATA",
            "MAN_DOWN" to "🚨 ALLARME: UOMO A TERRA",
            "IMMOBILITY" to "🚨 ALLARME: IMMOBILITÀ PROLUNGATA",
            "IMMOBILITY_DETECTION" to "🚨 ALLARME: IMMOBILITÀ PROLUNGATA",
            "SOS" to "🚨 SOS MANUALE",
            "SOS_BUTTON" to "🚨 SOS MANUALE",
            "TIMER" to "⏰ TIMER SCADUTO",
            "SESSION_EXPIRED" to "⏰ SESSIONE SCADUTA",
            "PREALARM" to "⚠️ PREALLARME: VERIFICA OPERATORE",
            "MALFUNCTION" to "🔧 GUASTO RILEVATO",
        )

        val header = typeLabels[type] ?: "🚨 ALLARME SoloSafe"

        // Posizione: beacon_label se disponibile, altrimenti address
        val posizione = if (beaconLabel != null) {
            "$beaconLabel${if (address != null) " - $address" else ""}"
        } else {
            address ?: "Posizione non disponibile"
        }

        // Maps link
        val mapsLink = if (lat != null && lng != null) {
            "https://maps.google.com/?q=$lat,$lng"
        } else {
            "Non disponibile"
        }

        // Timestamp
        val timestamp = SimpleDateFormat("HH:mm", Locale("it", "IT")).format(Date())

        // Battery
        val batteryStr = battery?.let { "$it%" } ?: "N/A"

        return """$header

Operatore: $operatorName
📍 $posizione
🗺 $mapsLink
🔋 Batteria: $batteryStr
⏰ $timestamp

Rispondi o chiama per prendere in carico."""
    }

    /** Check if GSM is available on device */
    private fun isGsmAvailable(context: Context): Boolean {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            return tm?.networkType != TelephonyManager.NETWORK_TYPE_UNKNOWN
        } catch (e: Exception) {
            Log.w("SoloSafe", "Failed to check GSM: ${e.message}")
            return false
        }
    }

    /** Get current battery level (0-100) */
    private fun getBatteryLevel(context: Context): Int? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.coerceIn(0, 100)
        } catch (e: Exception) {
            Log.w("SoloSafe", "Failed to get battery level: ${e.message}")
            null
        }
    }

    /**
     * Send native SMS to emergency contacts if GSM available.
     * Returns list of successfully reached phone numbers.
     * If GSM not available, returns empty list (server will handle fallback).
     */
    @Suppress("MissingPermission")
    suspend fun sendAlertSms(
        context: Context,
        alarmType: String,
        operatorName: String,
        lat: Double?,
        lng: Double?,
    ): List<String> = withContext(Dispatchers.IO) {
        val smsNativeReached = mutableListOf<String>()

        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("SoloSafe", "SMS permission not granted")
            return@withContext smsNativeReached
        }

        val prefs = context.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)

        // Try to read emergency_contacts JSON (new format, synced by SupabaseClient)
        val contacts = try {
            val emergencyContactsJson = prefs.getString("emergency_contacts_json", null)
            if (emergencyContactsJson != null) {
                val jsonArray = kotlinx.serialization.json.Json.parseToJsonElement(emergencyContactsJson).jsonArray
                jsonArray.mapNotNull { obj ->
                    val phone = obj.jsonObject["phone"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val name = obj.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                    phone to name
                }
            } else {
                // Fallback to legacy authorized_numbers (comma-separated)
                val numbersStr = prefs.getString("authorized_numbers", "") ?: ""
                numbersStr.split(",").filter { it.isNotBlank() }
                    .map { it.trim() to it.trim() }
            }
        } catch (e: Exception) {
            Log.w("SoloSafe", "Failed to parse emergency_contacts_json, using legacy: ${e.message}")
            val numbersStr = prefs.getString("authorized_numbers", "") ?: ""
            numbersStr.split(",").filter { it.isNotBlank() }
                .map { it.trim() to it.trim() }
        }

        if (contacts.isEmpty()) {
            Log.w("SoloSafe", "No emergency contacts configured")
            return@withContext smsNativeReached
        }

        // Check GSM availability
        val hasGsm = isGsmAvailable(context)
        Log.d("SoloSafe", "SMS: GSM available=$hasGsm")

        // Get battery level for SMS template
        val batteryLevel = getBatteryLevel(context)

        // Build unified SMS template
        val messageText = buildAlarmSMS(
            type = alarmType,
            operatorName = operatorName,
            address = null,  // TODO: reverse geocoding
            beaconLabel = null,  // TODO: BLE indoor positioning
            lat = lat,
            lng = lng,
            battery = batteryLevel
        )

        // Send native SMS only if GSM available
        if (hasGsm) {
            val smsManager = context.getSystemService(SmsManager::class.java)
                ?: SmsManager.getDefault()

            for ((phone, name) in contacts) {
                try {
                    smsManager.sendTextMessage(phone, null, messageText, null, null)
                    smsNativeReached.add(phone)
                    Log.d("SoloSafe", "SMS native sent to $phone ($name)")
                } catch (e: Exception) {
                    Log.e("SoloSafe", "SMS native to $phone failed: ${e.message}")
                }
            }
            Log.d("SoloSafe", "SMS native: ${smsNativeReached.size}/${contacts.size} reached")
        } else {
            Log.d("SoloSafe", "SMS: GSM not available, server will handle Messagenet fallback")
        }

        return@withContext smsNativeReached
    }
}
