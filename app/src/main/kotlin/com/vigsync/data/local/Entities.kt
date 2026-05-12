package com.vigsync.data.local

import androidx.room.Entity
import androidx.room.Index
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

@Entity(
    tableName = "events",
    indices = [Index(value = ["sourceDeviceId"])]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val data: String,
    val timestamp: Long,
    val syncStatus: SyncStatus,
    val direction: EventDirection,
    val sourceDeviceId: String?,
    val sourceDeviceName: String?,
    val errorMessage: String? = null,
    val payloadHash: String // For deduplication
)

@Entity(tableName = "device_status")
data class DeviceStatusEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val batteryLevel: Int,
    val isOnline: Boolean,
    val lastSeen: Long,
    val customLabel: String? = null,
    val pairingTimestamp: Long? = null
)

@Entity(tableName = "host_protocols")
data class HostProtocolEntity(
    @PrimaryKey val host: String,
    val version: Int // 3 or 5
)
