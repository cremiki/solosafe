package com.solosafe.app.data.local

import androidx.room.*
import com.solosafe.app.data.local.entities.*

@Dao
interface SoloSafeDao {
    // Alarm events
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(event: AlarmEventEntity)

    @Query("SELECT * FROM alarm_events WHERE synced = 0 ORDER BY createdAt")
    suspend fun getUnsyncedAlarms(): List<AlarmEventEntity>

    @Query("UPDATE alarm_events SET synced = 1 WHERE id = :id")
    suspend fun markAlarmSynced(id: String)

    @Query("DELETE FROM alarm_events WHERE synced = 1 AND createdAt < :before")
    suspend fun cleanupOldAlarms(before: Long)

    // Work sessions
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WorkSessionEntity)

    @Query("SELECT * FROM work_sessions WHERE status = 'active' LIMIT 1")
    suspend fun getActiveSession(): WorkSessionEntity?

    @Query("UPDATE work_sessions SET status = 'completed', endedAt = :endedAt WHERE id = :id")
    suspend fun endSession(id: String, endedAt: Long = System.currentTimeMillis())

    // Config
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setConfig(config: AppConfigEntity)

    @Query("SELECT value FROM app_config WHERE `key` = :key")
    suspend fun getConfig(key: String): String?

    // Pending heartbeats
    @Insert
    suspend fun insertPendingHeartbeat(hb: PendingHeartbeat)

    @Query("SELECT * FROM pending_heartbeats ORDER BY timestamp LIMIT 50")
    suspend fun getPendingHeartbeats(): List<PendingHeartbeat>

    @Query("DELETE FROM pending_heartbeats WHERE id IN (:ids)")
    suspend fun deletePendingHeartbeats(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM alarm_events WHERE synced = 0")
    suspend fun countPendingAlarms(): Int
}

@Database(
    entities = [
        AlarmEventEntity::class,
        WorkSessionEntity::class,
        AppConfigEntity::class,
        PendingHeartbeat::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): SoloSafeDao
}
