package com.vigsync.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImportConfig(
    val brokerUrl: String? = null,
    val port: Int? = null,
    val sharedKey: String? = null,
    val topicPrefix: String? = null,
    val username: String? = null,
    val password: String? = null,
    val deviceName: String? = null,
    val deviceId: String? = null,
    @SerialName("SLL/TLS") val useTls: Boolean? = null
)
