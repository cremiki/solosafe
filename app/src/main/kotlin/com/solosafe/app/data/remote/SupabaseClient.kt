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

    /**
     * Fetch operator tunables (cascade settings, thresholds) by id and persist
     * them to SharedPreferences so CallCascadeManager and other runtime code
     * can read them without going through Room/DI.
     */
    suspend fun syncOperatorTunables(context: android.content.Context, operatorId: String) = withContext(Dispatchers.IO) {
        try {
            val cfg = client.from("operators")
                .select { filter { eq("id", operatorId) } }
                .decodeSingleOrNull<OperatorConfig>() ?: return@withContext
            val prefs = context.getSharedPreferences(com.solosafe.app.SoloSafeApp.PREFS_NAME, android.content.Context.MODE_PRIVATE)

            // DEBUG: Log DB values before saving
            android.util.Log.d("SoloSafe", "syncOperatorTunables: DB values — cascade_max_rounds=${cfg.cascade_max_rounds} (type=${cfg.cascade_max_rounds.javaClass.simpleName}), timeout=${cfg.cascade_timeout_seconds}s, delay=${cfg.cascade_delay_seconds}s")

            val editor = prefs.edit()
                .putInt("cascade_max_rounds", cfg.cascade_max_rounds)
                .putInt("cascade_timeout_seconds", cfg.cascade_timeout_seconds)
                .putInt("cascade_delay_seconds", cfg.cascade_delay_seconds)
                .putInt("battery_alert_threshold", cfg.battery_alert_threshold)
                .putInt("default_session_hours", cfg.default_session_hours)

            // Pull latest app_config_log entries for this operator and write them
            // to SharedPreferences using the EXACT keys that detectors read
            // (fall_enabled, fall_threshold_g, immobility_enabled, immobility_seconds,
            // malore_enabled, malore_angle). Strip "default:" prefix that
            // self-logged settings rows sometimes add.
            try {
                val logEntries = client.from("app_config_log")
                    .select {
                        filter { eq("operator_id", operatorId) }
                        order(column = "created_at", order = io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                        limit(100)
                    }
                    .decodeList<ConfigLogEntry>()

                // Walk newest→oldest, take first occurrence of each param
                val seen = mutableSetOf<String>()
                for (entry in logEntries) {
                    if (entry.param_name in seen) continue
                    seen += entry.param_name
                    val raw = entry.new_value.removePrefix("default:")
                    when (entry.param_name) {
                        // Boolean toggles
                        "fall_enabled", "immobility_enabled", "malore_enabled" -> {
                            editor.putBoolean(entry.param_name, raw.equals("true", ignoreCase = true))
                        }
                        // Float thresholds
                        "fall_threshold_g", "immobility_seconds", "malore_angle" -> {
                            raw.toFloatOrNull()?.let { editor.putFloat(entry.param_name, it) }
                        }
                        else -> {
                            // Unknown params: keep as string
                            editor.putString(entry.param_name, raw)
                        }
                    }
                }
                android.util.Log.d("SoloSafe", "Tunables synced: rounds=${cfg.cascade_max_rounds} timeout=${cfg.cascade_timeout_seconds}s delay=${cfg.cascade_delay_seconds}s, ${seen.size} alarm params from log")
            } catch (e: Exception) {
                android.util.Log.w("SoloSafe", "app_config_log sync failed: ${e.message}")
            }

            editor.apply()

            // DEBUG: Verify saved values
            val saved = prefs.getInt("cascade_max_rounds", -999)
            android.util.Log.d("SoloSafe", "syncOperatorTunables: SAVED to SharedPreferences — cascade_max_rounds=$saved (type=${saved.javaClass.simpleName})")

            // Also pull emergency contacts so SmsAlertManager and CallCascadeManager
            // can read them from prefs without hitting the network on alarm
            syncContacts(context, operatorId)
        } catch (e: Exception) {
            android.util.Log.w("SoloSafe", "syncOperatorTunables failed: ${e.message}")
        }
    }

    @Serializable
    data class ConfigLogEntry(val param_name: String, val new_value: String)

    @Serializable
    data class EmergencyContactRow(
        val id: String? = null,
        val position: Int = 1,
        val name: String = "",
        val phone: String = "",
        val sms_enabled: Boolean = true,
        val telegram_enabled: Boolean = true,
        val call_enabled: Boolean = true,
        val telegram_chat_id: Long? = null,
    )

    /** Pull emergency contacts for this operator and persist as JSON in prefs.
     *  Also pushes any local-only authorized_numbers to Supabase so the
     *  dashboard sees them. */
    suspend fun syncContacts(context: android.content.Context, operatorId: String) = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences(com.solosafe.app.SoloSafeApp.PREFS_NAME, android.content.Context.MODE_PRIVATE)

            // 1) Fetch what's already in DB
            var dbContacts = client.from("emergency_contacts")
                .select {
                    filter { eq("operator_id", operatorId) }
                    order(column = "position", order = io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                }
                .decodeList<EmergencyContactRow>()

            // 2) Compare with local authorized_numbers and upsert any orphan
            val localPhones = prefs.getString("authorized_numbers", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val dbPhones = dbContacts.map { it.phone.replace(" ", "") }.toSet()
            val missing = localPhones.filter { it.replace(" ", "") !in dbPhones }
            if (missing.isNotEmpty()) {
                val nextPos = (dbContacts.maxOfOrNull { it.position } ?: 0) + 1
                missing.forEachIndexed { i, phone ->
                    try {
                        client.from("emergency_contacts").insert(buildJsonObject {
                            put("operator_id", operatorId)
                            put("position", nextPos + i)
                            put("name", "Contatto ${nextPos + i}")
                            put("phone", phone)
                            put("preferred_channel", "sms")
                            put("sms_enabled", true)
                            put("telegram_enabled", true)
                            put("call_enabled", true)
                            put("dtmf_required", false)
                            put("relation", "manager")
                        })
                    } catch (e: Exception) {
                        android.util.Log.w("SoloSafe", "Failed to upsert orphan contact $phone: ${e.message}")
                    }
                }
                // Refetch with the new rows
                dbContacts = client.from("emergency_contacts")
                    .select {
                        filter { eq("operator_id", operatorId) }
                        order(column = "position", order = io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                    }
                    .decodeList<EmergencyContactRow>()
                android.util.Log.d("SoloSafe", "Pushed ${missing.size} orphan contacts to Supabase")
            }

            // 3) Persist as JSON + flat list
            val json = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(EmergencyContactRow.serializer()),
                dbContacts,
            )
            prefs.edit().putString("emergency_contacts_json", json).apply()
            val phones = dbContacts.filter { it.call_enabled }.joinToString(",") { it.phone }
            prefs.edit().putString("authorized_numbers", phones).apply()
            android.util.Log.d("SoloSafe", "Contacts synced: ${dbContacts.size} contacts")
        } catch (e: Exception) {
            android.util.Log.w("SoloSafe", "syncContacts failed: ${e.message}")
        }
    }

    /** Insert a single emergency contact (called from app UI) */
    suspend fun addEmergencyContact(operatorId: String, phone: String, name: String = "") = withContext(Dispatchers.IO) {
        try {
            val existing = client.from("emergency_contacts")
                .select { filter { eq("operator_id", operatorId) } }
                .decodeList<EmergencyContactRow>()
            val nextPos = (existing.maxOfOrNull { it.position } ?: 0) + 1
            client.from("emergency_contacts").insert(buildJsonObject {
                put("operator_id", operatorId)
                put("position", nextPos)
                put("name", name.ifBlank { "Contatto $nextPos" })
                put("phone", phone)
                put("preferred_channel", "sms")
                put("sms_enabled", true)
                put("telegram_enabled", true)
                put("call_enabled", true)
                put("dtmf_required", false)
                put("relation", "manager")
            })
        } catch (e: Exception) {
            android.util.Log.w("SoloSafe", "addEmergencyContact failed: ${e.message}")
        }
    }

    /** Delete an emergency contact by phone (called from app UI) */
    suspend fun removeEmergencyContact(operatorId: String, phone: String) = withContext(Dispatchers.IO) {
        try {
            client.from("emergency_contacts").delete {
                filter {
                    eq("operator_id", operatorId)
                    eq("phone", phone)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SoloSafe", "removeEmergencyContact failed: ${e.message}")
        }
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
        val cascade_max_rounds: Int = 2,
        val cascade_timeout_seconds: Int = 25,
        val cascade_delay_seconds: Int = 10,
        val heartbeat_interval_minutes: Int = 5,
        val default_session_hours: Int = 8,
        val battery_alert_threshold: Int = 20,
    )
}
