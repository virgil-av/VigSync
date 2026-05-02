package com.vigsync.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vigsync.core.models.EventDirection
import com.vigsync.core.models.SyncStatus

@Entity(tableName = "raw_messages")
data class RawMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isProcessed: Boolean = false
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val data: String,
    val timestamp: Long,
    val syncStatus: SyncStatus,
    val direction: EventDirection,
    val sourceDevice: String?,
    val errorMessage: String? = null,
    val payloadHash: String // For deduplication
)

@Entity(tableName = "device_status")
data class DeviceStatusEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val batteryLevel: Int,
    val isOnline: Boolean,
    val lastSeen: Long
)
