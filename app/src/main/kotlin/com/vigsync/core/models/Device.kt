package com.vigsync.core.models

import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String,
    val name: String,
    var batteryLevel: Int = 0,
    var isOnline: Boolean = false,
    var lastSeen: Long = 0
)
