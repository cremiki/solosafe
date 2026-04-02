package com.solosafe.app.data.remote

import com.solosafe.app.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.Dispatchers
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
            put("preset", preset)
            put("status", "active")
            plannedEnd?.let { put("planned_end", it) }
        }) {
            select()
        }.decodeSingle<SessionResponse>()
        result.id
    }

    suspend fun endSession(sessionId: String) = withContext(Dispatchers.IO) {
        client.from("work_sessions").update(buildJsonObject {
            put("status", "completed")
            put("ended_at", java.time.Instant.now().toString())
        }) {
            filter {
                eq("id", sessionId)
            }
        }
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
