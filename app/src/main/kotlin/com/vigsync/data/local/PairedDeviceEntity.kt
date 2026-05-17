package com.vigsync.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val topicPrefix: String,
    val sharedKey: String,
    val lastSeen: Long = System.currentTimeMillis(),
    val isOnline: Boolean = false,
    val batteryLevel: Int? = null
)
