package com.vigsync.core.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
data class RawMessage(
    val topic: String? = null,
    val payload: String? = null,
    val type: String? = null,
    val data: String? = null,
    val senderId: String? = null,
    val senderName: String? = null,
    val batteryLevel: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun calculateHash(): String {
        val input = "$type$data$timestamp$senderId"
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
