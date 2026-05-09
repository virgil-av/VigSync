package com.vigsync.core.models

import kotlinx.serialization.Serializable

@Serializable
data class RawMessage(
    val type: String? = null,
    val data: String? = null,
    val senderId: String? = null,
    val senderName: String? = null,
    val batteryLevel: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)
