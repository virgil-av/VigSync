package com.vigsync.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mqtt_logs")
data class MqttLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val payload: String,
    val isIncoming: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "system_logs")
data class SystemLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val event: String,
    val details: String?,
    val isError: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
