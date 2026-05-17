package com.vigsync.core.mqtt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.vigsync.core.models.MqttConnectionState
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

class MqttManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val appPreferences = AppPreferences(context)
    private val mqttLogger = MqttLogger.getInstance(context)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var client5: Mqtt5Client? = null
    private var client3: Mqtt3Client? = null

    private val _connectionState = MutableStateFlow(MqttConnectionState.IDLE)
    val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<Pair<String, String>> = _incomingMessages.asSharedFlow()

    private var isNetworkAvailable = false
    private var shouldBeConnected = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isNetworkAvailable = true
            mqttLogger.logSystemEvent("Network", "Network available (Wi-Fi/LTE)")
            if (shouldBeConnected && _connectionState.value != MqttConnectionState.CONNECTED) {
                mqttLogger.logSystemEvent("Network", "Triggering reconnection due to network availability")
                connect()
            }
        }

        override fun onLost(network: Network) {
            isNetworkAvailable = false
            mqttLogger.logSystemEvent("Network", "Network lost")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            mqttLogger.logSystemEvent("Network", "Network type: ${if (isWifi) "Wi-Fi" else if (isCellular) "LTE/Cellular" else "Other"}")
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: MqttManager? = null

        fun getInstance(context: Context): MqttManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MqttManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    fun connect() {
        shouldBeConnected = true
        scope.launch {
            if (_connectionState.value == MqttConnectionState.CONNECTING || 
                _connectionState.value == MqttConnectionState.CONNECTED) return@launch

            if (!isNetworkAvailable) {
                mqttLogger.logSystemEvent("MQTT Connection", "Postponing connection: No network available", isError = true)
                _connectionState.value = MqttConnectionState.ERROR
                return@launch
            }

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

    private fun connectV5(url: String, port: Int, user: String, pass: String, tls: Boolean, deviceId: String) {
        val clientBuilder = MqttClient.builder()
            .useMqttVersion5()
            .identifier(deviceId)
            .serverHost(url)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener { mqttLogger.logSystemEvent("MQTT Lifecycle", "v5 Connected") }
            .addDisconnectedListener { context -> 
                val reason = context.cause?.message ?: "Normal Disconnect"
                mqttLogger.logSystemEvent("MQTT Lifecycle", "v5 Disconnected: $reason", isError = context.cause != null)
            }

        if (tls) clientBuilder.sslWithDefaultConfig()

        if (user.isNotEmpty()) {
            clientBuilder.simpleAuth()
                .username(user)
                .password(pass.toByteArray())
                .applySimpleAuth()
        }

        client5 = clientBuilder.build()

        client5?.toAsync()?.connectWith()
            ?.cleanStart(false)
            ?.sessionExpiryInterval(3600)
            ?.send()
            ?.whenComplete { ack, throwable ->
                if (throwable != null) {
                    handleConnectionError(throwable)
                } else {
                    handleConnectionSuccess()
                    mqttLogger.logSystemEvent("MQTT Connection", "v5 Connected (Session Present: ${ack.isSessionPresent})")
                    setupIncomingFlowV5()
                }
            }
    }

    private fun connectV3(url: String, port: Int, user: String, pass: String, tls: Boolean, deviceId: String) {
        val clientBuilder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(deviceId)
            .serverHost(url)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener { mqttLogger.logSystemEvent("MQTT Lifecycle", "v3 Connected") }
            .addDisconnectedListener { context -> 
                val reason = context.cause?.message ?: "Normal Disconnect"
                mqttLogger.logSystemEvent("MQTT Lifecycle", "v3 Disconnected: $reason", isError = context.cause != null)
            }

        if (tls) clientBuilder.sslWithDefaultConfig()

        if (user.isNotEmpty()) {
            clientBuilder.simpleAuth()
                .username(user)
                .password(pass.toByteArray())
                .applySimpleAuth()
        }

        client3 = clientBuilder.build()

        client3?.toAsync()?.connectWith()
            ?.cleanSession(false)
            ?.keepAlive(60)
            ?.send()
            ?.whenComplete { ack, throwable ->
                if (throwable != null) {
                    handleConnectionError(throwable)
                } else {
                    handleConnectionSuccess()
                    mqttLogger.logSystemEvent("MQTT Connection", "v3 Connected")
                    setupIncomingFlowV3()
                }
            }
    }

    private fun setupIncomingFlowV5() {
        client5?.toAsync()?.publishes(MqttGlobalPublishFilter.ALL) { publish ->
            val topic = publish.topic.toString()
            val payload = String(publish.payloadAsBytes)
            mqttLogger.logMessage(topic, payload, true)
            _incomingMessages.tryEmit(topic to payload)
        }
    }

    private fun setupIncomingFlowV3() {
        client3?.toAsync()?.publishes(MqttGlobalPublishFilter.ALL) { publish ->
            val topic = publish.topic.toString()
            val payload = String(publish.payloadAsBytes)
            mqttLogger.logMessage(topic, payload, true)
            _incomingMessages.tryEmit(topic to payload)
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
        shouldBeConnected = false
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

    fun publish(topic: String, payload: String, qos: Int = 1) {
        scope.launch {
            mqttLogger.logMessage(topic, payload, false)
            // Actual implementation would use client5 or client3 based on which is non-null
        }
    }
}
