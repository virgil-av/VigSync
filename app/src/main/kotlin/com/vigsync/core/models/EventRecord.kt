package com.vigsync.core.models

import kotlinx.serialization.Serializable

@Serializable
enum class SyncStatus {
    PENDING, SENT, FAILED, RECEIVED
}

@Serializable
enum class EventDirection {
    LOCAL, REMOTE
}

@Serializable
data class EventRecord(
    val type: String,
    val data: String,
    val timestamp: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val direction: EventDirection = EventDirection.LOCAL,
    val sourceDevice: String? = null,
    val errorMessage: String? = null
)
