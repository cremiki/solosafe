package com.solosafe.app.data.repository

import com.solosafe.app.data.local.SoloSafeDao
import com.solosafe.app.data.local.entities.*
import com.solosafe.app.data.remote.SupabaseClient
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SoloSafeRepository @Inject constructor(
    private val dao: SoloSafeDao,
    private val supabase: SupabaseClient,
) {
    // Config
    suspend fun getOperatorId(): String? = dao.getConfig("operator_id")
    suspend fun getCompanyId(): String? = dao.getConfig("company_id")
    suspend fun getDefaultPreset(): String = dao.getConfig("default_preset") ?: "WAREHOUSE"
    suspend fun getDefaultSessionType(): String = dao.getConfig("default_session_type") ?: "turno"
    suspend fun getDefaultDuration(): Int = dao.getConfig("default_duration_hours")?.toIntOrNull() ?: 8

    suspend fun saveOperatorConfig(config: SupabaseClient.OperatorConfig) {
        dao.setConfig(AppConfigEntity("operator_id", config.id))
        dao.setConfig(AppConfigEntity("company_id", config.company_id))
        dao.setConfig(AppConfigEntity("operator_name", config.name))
        dao.setConfig(AppConfigEntity("default_preset", config.default_preset))
        dao.setConfig(AppConfigEntity("default_session_type", config.default_session_type))
        dao.setConfig(AppConfigEntity("default_duration_hours", config.default_duration_hours.toString()))
        dao.setConfig(AppConfigEntity("allow_preset_change", config.allow_preset_change.toString()))
        dao.setConfig(AppConfigEntity("locale", config.locale))
    }

    // Heartbeat
    suspend fun sendHeartbeat(state: String, batteryPhone: Int, batteryTag: Int?, lat: Double?, lng: Double?) {
        val operatorId = getOperatorId() ?: return
        try {
            supabase.sendHeartbeat(operatorId, state, batteryPhone, batteryTag, lat, lng)
            // Sync any pending heartbeats
            syncPendingHeartbeats()
        } catch (e: Exception) {
            // Offline: store locally
            dao.insertPendingHeartbeat(PendingHeartbeat(
                operatorId = operatorId, state = state,
                batteryPhone = batteryPhone, batteryTag = batteryTag,
                lat = lat, lng = lng,
            ))
        }
    }

    private suspend fun syncPendingHeartbeats() {
        val pending = dao.getPendingHeartbeats()
        if (pending.isEmpty()) return
        val synced = mutableListOf<Long>()
        for (hb in pending) {
            try {
                supabase.sendHeartbeat(hb.operatorId, hb.state, hb.batteryPhone, hb.batteryTag, hb.lat, hb.lng)
                synced.add(hb.id)
            } catch (_: Exception) { break }
        }
        if (synced.isNotEmpty()) dao.deletePendingHeartbeats(synced)
    }

    // Sessions
    suspend fun getActiveSession(): WorkSessionEntity? = dao.getActiveSession()

    suspend fun startSession(sessionType: String, preset: String, durationHours: Int?): String {
        val operatorId = getOperatorId() ?: throw IllegalStateException("Operator not configured")
        val companyId = getCompanyId() ?: throw IllegalStateException("Company not configured")
        val plannedEnd = durationHours?.let {
            Instant.now().plus(it.toLong(), ChronoUnit.HOURS).toString()
        }

        val sessionId = try {
            supabase.startSession(operatorId, companyId, sessionType, preset, plannedEnd)
        } catch (e: Exception) {
            UUID.randomUUID().toString() // Offline fallback
        }

        val entity = WorkSessionEntity(
            id = sessionId,
            operatorId = operatorId,
            sessionType = sessionType,
            preset = preset,
            plannedEndAt = durationHours?.let { System.currentTimeMillis() + it * 3600_000L },
        )
        dao.insertSession(entity)
        return sessionId
    }

    suspend fun endSession() {
        val session = dao.getActiveSession() ?: return
        dao.endSession(session.id)
        try { supabase.endSession(session.id) } catch (_: Exception) {}
    }

    // Alarms
    suspend fun sendAlarm(type: String, lat: Double?, lng: Double?) {
        val operatorId = getOperatorId() ?: return
        val companyId = getCompanyId() ?: return
        val session = dao.getActiveSession()
        val alarmId = try {
            supabase.sendAlarm(operatorId, companyId, type, lat, lng, session?.id)
        } catch (e: Exception) {
            UUID.randomUUID().toString()
        }
        dao.insertAlarm(AlarmEventEntity(
            id = alarmId, operatorId = operatorId, type = type,
            lat = lat, lng = lng, sessionId = session?.id,
        ))
    }

    // QR Config
    suspend fun loadConfigFromToken(token: String): Boolean {
        val config = supabase.getOperatorConfig(token) ?: return false
        saveOperatorConfig(config)
        return true
    }

    // Cleanup
    suspend fun cleanup() {
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        dao.cleanupOldAlarms(weekAgo)
    }
}
