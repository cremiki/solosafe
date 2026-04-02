package com.solosafe.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarm_events")
data class AlarmEventEntity(
    @PrimaryKey val id: String,
    val operatorId: String,
    val type: String,
    val lat: Double?,
    val lng: Double?,
    val sessionId: String?,
    val synced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "work_sessions")
data class WorkSessionEntity(
    @PrimaryKey val id: String,
    val operatorId: String,
    val sessionType: String,
    val preset: String,
    val status: String = "active",
    val startedAt: Long = System.currentTimeMillis(),
    val plannedEndAt: Long? = null,
    val endedAt: Long? = null,
)

@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "pending_heartbeats")
data class PendingHeartbeat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operatorId: String,
    val state: String,
    val batteryPhone: Int,
    val batteryTag: Int?,
    val lat: Double?,
    val lng: Double?,
    val timestamp: Long = System.currentTimeMillis(),
)
