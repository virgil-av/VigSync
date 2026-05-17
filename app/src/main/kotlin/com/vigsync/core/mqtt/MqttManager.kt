package com.vigsync.core.mqtt

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck
import com.vigsync.core.models.MqttConnectionState
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

class MqttManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val appPreferences = AppPreferences(context)
    private val mqttLogger = MqttLogger.getInstance(context)

    private var client5: Mqtt5Client? = null
    private var client3: Mqtt3Client? = null

    private val _connectionState = MutableStateFlow(MqttConnectionState.IDLE)
    val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    companion object {
        @Volatile
        private var INSTANCE: MqttManager? = null

        fun getInstance(context: Context): MqttManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MqttManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun connect() {
        scope.launch {
            if (_connectionState.value == MqttConnectionState.CONNECTING || 
                _connectionState.value == MqttConnectionState.CONNECTED) return@launch

            val url = appPreferences.brokerUrl.first()
            val port = appPreferences.brokerPort.first().toIntOrNull() ?: 1883
            val user = appPreferences.brokerUser.first()
            val pass = appPreferences.brokerPass.first()
            val tls = appPreferences.useTls.first()
            val version = appPreferences.mqttVersion.first()
            val deviceId = "vigsync_client_${UUID.randomUUID().toString().take(8)}"

            _connectionState.value = MqttConnectionState.CONNECTING
            mqttLogger.logSystemEvent("MQTT Connection", "Connecting to $url:$port (v$version, TLS=$tls)")

            try {
                if (version == "5") {
                    connectV5(url, port, user, pass, tls, deviceId)
                } else {
                    connectV3(url, port, user, pass, tls, deviceId)
                }
            } catch (e: Exception) {
                handleConnectionError(e)
            }
        }
    }

    private suspend fun connectV5(url: String, port: Int, user: String, pass: String, tls: Boolean, deviceId: String) {
        val clientBuilder = MqttClient.builder()
            .useMqttVersion5()
            .identifier(deviceId)
            .serverHost(url)
            .serverPort(port)

        if (tls) {
            clientBuilder.sslWithDefaultConfig()
        }

        if (user.isNotEmpty()) {
            clientBuilder.simpleAuth()
                .username(user)
                .password(pass.toByteArray())
                .applySimpleAuth()
        }

        client5 = clientBuilder.build()

        client5?.toAsync()?.connect()?.whenComplete { ack, throwable ->
            if (throwable != null) {
                handleConnectionError(throwable)
            } else {
                handleConnectionSuccess()
                mqttLogger.logSystemEvent("MQTT Connection", "v5 Connected (Session Present: ${ack.isSessionPresent})")
            }
        }
    }

    private suspend fun connectV3(url: String, port: Int, user: String, pass: String, tls: Boolean, deviceId: String) {
        val clientBuilder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(deviceId)
            .serverHost(url)
            .serverPort(port)

        if (tls) {
            clientBuilder.sslWithDefaultConfig()
        }

        if (user.isNotEmpty()) {
            clientBuilder.simpleAuth()
                .username(user)
                .password(pass.toByteArray())
                .applySimpleAuth()
        }

        client3 = clientBuilder.build()

        client3?.toAsync()?.connect()?.whenComplete { ack, throwable ->
            if (throwable != null) {
                handleConnectionError(throwable)
            } else {
                handleConnectionSuccess()
                mqttLogger.logSystemEvent("MQTT Connection", "v3 Connected")
            }
        }
    }

    private fun handleConnectionSuccess() {
        _connectionState.value = MqttConnectionState.CONNECTED
        scope.launch {
            appPreferences.saveLastConnectedSuccess(true)
        }
    }

    private fun handleConnectionError(t: Throwable) {
        _connectionState.value = MqttConnectionState.ERROR
        mqttLogger.logSystemEvent("MQTT Error", t.message ?: "Unknown connection error", isError = true)
        scope.launch {
            appPreferences.saveLastConnectedSuccess(false)
        }
    }

    fun disconnect() {
        scope.launch {
            mqttLogger.logSystemEvent("MQTT Connection", "Disconnecting...")
            _connectionState.value = MqttConnectionState.DISCONNECTED
            
            client5?.toAsync()?.disconnect()
            client3?.toAsync()?.disconnect()
            
            client5 = null
            client3 = null
            
            _connectionState.value = MqttConnectionState.IDLE
            appPreferences.saveLastConnectedSuccess(false)
        }
    }

    fun autoConnectIfNeeded() {
        scope.launch {
            if (appPreferences.lastConnectedSuccess.first()) {
                mqttLogger.logSystemEvent("Auto-Connect", "Triggering automatic connection based on last success")
                connect()
            }
        }
    }
}
