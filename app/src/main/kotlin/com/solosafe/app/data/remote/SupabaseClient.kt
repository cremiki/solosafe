package com.solosafe.app.data.remote

import android.util.Log
import com.solosafe.app.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseClient @Inject constructor() {

    // Lazy init — only created when first accessed, not at DI injection time
    val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Postgrest)
            install(Auth)
            install(Realtime)
        }
    }

    suspend fun sendHeartbeat(
        operatorId: String,
        state: String,
        batteryPhone: Int,
        batteryTag: Int? = null,
        lat: Double? = null,
        lng: Double? = null,
    ) = withContext(Dispatchers.IO) {
        client.from("operator_status").update(buildJsonObject {
            put("state", state)
            put("battery_phone", batteryPhone)
            batteryTag?.let { put("battery_tag", it) }
            lat?.let { put("last_lat", it) }
            lng?.let { put("last_lng", it) }
            put("last_seen", java.time.Instant.now().toString())
        }) {
            filter {
                eq("operator_id", operatorId)
            }
        }
    }

    suspend fun startSession(
        operatorId: String,
        companyId: String,
        sessionType: String,
        preset: String,
        plannedEnd: String?,
    ): String = withContext(Dispatchers.IO) {
        val result = client.from("work_sessions").insert(buildJsonObject {
            put("operator_id", operatorId)
            put("company_id", companyId)
            put("session_type", sessionType)
            put("preset_used", preset)
            plannedEnd?.let { put("planned_end", it) }
        }) {
            select()
        }.decodeSingle<SessionResponse>()
        Log.d("SoloSafe", "Work session INSERT OK: id=${result.id}")
        result.id
    }

    suspend fun endSession(sessionId: String) = withContext(Dispatchers.IO) {
        client.from("work_sessions").update(buildJsonObject {
            put("status", "completed")
            put("actual_end", java.time.Instant.now().toString())
        }) {
            filter { eq("id", sessionId) }
        }
        Log.d("SoloSafe", "Work session ended: $sessionId")
    }

    /** Close ALL active sessions for an operator (cleanup fallback) */
    suspend fun endAllSessions(operatorId: String) = withContext(Dispatchers.IO) {
        client.from("work_sessions").update(buildJsonObject {
            put("status", "completed")
            put("actual_end", java.time.Instant.now().toString())
        }) {
            filter { eq("operator_id", operatorId); eq("status", "active") }
        }
        Log.d("SoloSafe", "All active sessions closed for operator: $operatorId")
    }

    suspend fun sendAlarm(
        operatorId: String,
        companyId: String,
        type: String,
        lat: Double?,
        lng: Double?,
        sessionId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val result = client.from("alarm_events").insert(buildJsonObject {
            put("operator_id", operatorId)
            put("company_id", companyId)
            put("type", type)
            put("confirmation_level", "DIRECT")
            lat?.let { put("lat", it) }
            lng?.let { put("lng", it) }
            sessionId?.let { put("session_id", it) }
            put("sms_sent", false)
            put("sms_count", 0)
        }) {
            select()
        }.decodeSingle<AlarmResponse>()
        result.id
    }

    /** Log alarm event detail (fire-and-forget) */
    fun logAlarmEvent(
        alarmEventId: String?, operatorId: String, eventType: String, alarmType: String,
        recipientName: String? = null, recipientPhone: String? = null,
        channel: String? = null, responseBy: String? = null, notes: String? = null,
    ) {
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                client.from("alarm_event_log").insert(buildJsonObject {
                    alarmEventId?.let { put("alarm_event_id", it) }
                    put("operator_id", operatorId)
                    put("event_type", eventType)
                    put("alarm_type", alarmType)
                    recipientName?.let { put("recipient_name", it) }
                    recipientPhone?.let { put("recipient_phone", it) }
                    channel?.let { put("channel", it) }
                    responseBy?.let { put("response_by", it) }
                    notes?.let { put("notes", it) }
                })
            } catch (e: Exception) {
                Log.w("SoloSafe", "logAlarmEvent failed: ${e.message}")
            }
        }
    }

    /** Notify alarm service for call cascade (fire-and-forget) */
    fun notifyAlarmService(operatorId: String, operatorName: String, type: String, lat: Double?, lng: Double?) {
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("http://46.224.181.59:3001/alarm")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val body = """{"operator_id":"$operatorId","type":"$type","lat":${lat ?: "null"},"lng":${lng ?: "null"},"operator_name":"$operatorName"}"""
                conn.outputStream.write(body.toByteArray())
                val code = conn.responseCode
                Log.d("SoloSafe", "Alarm service notified: $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("SoloSafe", "Alarm service notify failed: ${e.message}")
            }
        }
    }

    /** Register a free user */
    suspend fun registerFreeUser(name: String, email: String, phone: String, company: String, deviceModel: String) = withContext(Dispatchers.IO) {
        client.from("free_users").insert(buildJsonObject {
            put("name", name)
            if (email.isNotBlank()) put("email", email)
            if (phone.isNotBlank()) put("phone", phone)
            if (company.isNotBlank()) put("company", company)
            put("device_model", deviceModel)
        })
        Log.d("SoloSafe", "Free user registered: $name")
    }

    suspend fun getOperatorConfig(configToken: String): OperatorConfig? = withContext(Dispatchers.IO) {
        try {
            client.from("operators")
                .select {
                    filter {
                        eq("config_token_permanent", configToken)
                    }
                }
                .decodeSingleOrNull<OperatorConfig>()
        } catch (e: Exception) {
            null
        }
    }

    /** Check if slots are available for this company */
    suspend fun checkSlotAvailable(companyId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Get max slots
            val company = client.from("companies")
                .select { filter { eq("id", companyId) } }
                .decodeSingleOrNull<CompanySlots>()
            val maxSlots = company?.concurrent_slots ?: 5

            // Count active sessions
            val activeSessions = client.from("work_sessions")
                .select { filter { eq("company_id", companyId); eq("status", "active") } }
                .decodeList<SessionResponse>()

            val available = activeSessions.size < maxSlots
            Log.d("SoloSafe", "Slot check: ${activeSessions.size}/$maxSlots — ${if (available) "OK" else "FULL"}")
            available
        } catch (e: Exception) {
            Log.e("SoloSafe", "Slot check failed: ${e.message}")
            true // Allow on error
        }
    }

    @Serializable
    data class CompanySlots(val concurrent_slots: Int = 5)

    /** Log a settings change to Supabase */
    suspend fun logConfigChange(
        operatorId: String, companyId: String,
        paramName: String, oldValue: String, newValue: String,
    ) = withContext(Dispatchers.IO) {
        try {
            client.from("app_config_log").insert(buildJsonObject {
                put("operator_id", operatorId)
                put("company_id", companyId)
                put("change_type", "settings")
                put("param_name", paramName)
                put("old_value", oldValue)
                put("new_value", newValue)
            })
        } catch (e: Exception) {
            Log.w("SoloSafe", "Config log failed: ${e.message}")
        }
    }

    /** Get authorized phone numbers for an operator (emergency contacts) */
    suspend fun getAuthorizedPhones(operatorId: String): List<String> = withContext(Dispatchers.IO) {
        try {
            client.from("emergency_contacts")
                .select {
                    filter { eq("operator_id", operatorId) }
                }
                .decodeList<EmergencyContact>()
                .map { it.phone }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Serializable
    data class SessionResponse(val id: String)

    @Serializable
    data class AlarmResponse(val id: String)

    @Serializable
    data class EmergencyContact(
        val phone: String,
        val name: String = "",
    )

    @Serializable
    data class OperatorConfig(
        val id: String,
        val company_id: String,
        val name: String,
        val default_preset: String = "WAREHOUSE",
        val default_session_type: String = "turno",
        val default_duration_hours: Int = 8,
        val allow_preset_change: Boolean = false,
        val locale: String = "it",
    )
}
