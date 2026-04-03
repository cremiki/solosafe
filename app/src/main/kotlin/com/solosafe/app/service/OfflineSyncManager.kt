package com.solosafe.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.solosafe.app.SoloSafeApp
import com.solosafe.app.data.local.AppDatabase
import com.solosafe.app.data.local.entities.AlarmEventEntity
import com.solosafe.app.data.local.entities.PendingHeartbeat
import com.solosafe.app.data.remote.SupabaseClient
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Offline-first sync: saves alarms and heartbeats locally when offline,
 * syncs to Supabase when connection returns.
 */
class OfflineSyncManager(
    private val context: Context,
    private val supabase: SupabaseClient,
    private val db: AppDatabase,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    fun startPeriodicSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                delay(30_000) // Every 30 seconds
                if (isOnline()) {
                    syncPendingAlarms()
                    syncPendingHeartbeats()
                }
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    /** Save alarm locally for offline sync */
    suspend fun saveAlarmOffline(
        operatorId: String, type: String, lat: Double?, lng: Double?, sessionId: String?
    ) {
        db.dao().insertAlarm(AlarmEventEntity(
            id = UUID.randomUUID().toString(),
            operatorId = operatorId,
            type = type,
            lat = lat,
            lng = lng,
            sessionId = sessionId,
            synced = false,
        ))
        Log.d("SoloSafe", "Alarm saved offline: type=$type")
    }

    /** Save heartbeat locally for offline sync */
    suspend fun saveHeartbeatOffline(
        operatorId: String, state: String, battery: Int, lat: Double?, lng: Double?
    ) {
        db.dao().insertPendingHeartbeat(PendingHeartbeat(
            operatorId = operatorId,
            state = state,
            batteryPhone = battery,
            batteryTag = null,
            lat = lat,
            lng = lng,
        ))
        Log.d("SoloSafe", "Heartbeat saved offline: state=$state")
    }

    private suspend fun syncPendingAlarms() {
        val pending = db.dao().getUnsyncedAlarms()
        if (pending.isEmpty()) return

        val prefs = context.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        val companyId = prefs.getString("company_id", "") ?: ""

        var synced = 0
        for (alarm in pending) {
            try {
                supabase.sendAlarm(alarm.operatorId, companyId, alarm.type, alarm.lat, alarm.lng, alarm.sessionId)
                db.dao().markAlarmSynced(alarm.id)
                synced++
            } catch (_: Exception) { break }
        }
        if (synced > 0) Log.d("SoloSafe", "Synced $synced pending alarms")
    }

    private suspend fun syncPendingHeartbeats() {
        val pending = db.dao().getPendingHeartbeats()
        if (pending.isEmpty()) return

        val synced = mutableListOf<Long>()
        for (hb in pending) {
            try {
                supabase.sendHeartbeat(hb.operatorId, hb.state, hb.batteryPhone, hb.batteryTag, hb.lat, hb.lng)
                synced.add(hb.id)
            } catch (_: Exception) { break }
        }
        if (synced.isNotEmpty()) {
            db.dao().deletePendingHeartbeats(synced)
            Log.d("SoloSafe", "Synced ${synced.size} pending heartbeats")
        }
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
