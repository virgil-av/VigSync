package com.vigsync.core.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck
import com.vigsync.core.SyncManager
import com.vigsync.data.local.HostProtocolEntity
import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class MqttConnectionStatus {
    CONNECTED, CONNECTING, DISCONNECTED, RECONNECTING
}

class MqttManager {
    private var client5: Mqtt5AsyncClient? = null
    private var client3: Mqtt3AsyncClient? = null
    private var currentVersion: Int = 5
    private val connectionMutex = Mutex()
    private val pendingSubscriptions = mutableListOf<String>()

    private var currentConfig: ConnectionConfig? = null

    data class ConnectionConfig(
        val url: String,
        val port: Int,
        val clientId: String,
        val useTls: Boolean,
        val user: String?,
        val pass: String?
    )

    // Tied to Application lifecycle
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _connectionStatus = MutableStateFlow(MqttConnectionStatus.DISCONNECTED)
    val connectionStatus = _connectionStatus.asStateFlow()

    init {
        MqttLogger.logApp("MqttManager: Created (hash: ${this.hashCode()})", "TRACE")
    }

    suspend fun connect(
        context: Context,
        brokerUrl: String,
        port: Int,
        clientId: String = UUID.randomUUID().toString(),
        useTls: Boolean = false,
        username: String? = null,
        password: String? = null
    ) {
        val newConfig = ConnectionConfig(brokerUrl, port, clientId, useTls, username, password)
        
        connectionMutex.withLock {
            if (_connectionStatus.value == MqttConnectionStatus.CONNECTED && currentConfig == newConfig) {
                MqttLogger.log("Already connected to the same broker, skipping.", "INFO")
                return@withLock
            }

            _connectionStatus.value = MqttConnectionStatus.CONNECTING
            val cleanUrl = brokerUrl
                .replace("mqtt://", "", ignoreCase = true)
                .replace("tcp://", "", ignoreCase = true)
                .replace("ssl://", "", ignoreCase = true)
                .trim()

            // Step 0: Kill any existing "Zombie" connections but KEEP subscriptions for now
            // as we might be reconnecting to the same broker or a known one.
            MqttLogger.log("Stopping previous connections before new attempt...", "INFO")
            disconnectInternal(clearSubscriptions = false).await()

            val dao = SyncManager.getInstance(context).getDao()
            val knownVersion = dao.getProtocolForHost(cleanUrl)

            if (knownVersion != null) {
                MqttLogger.log("Known host found: $cleanUrl (v$knownVersion)", "INFO")
                val success = tryConnectOnce(context, cleanUrl, port, clientId, useTls, username, password, knownVersion)
                if (success) {
                    currentConfig = newConfig
                    return@withLock
                }
                MqttLogger.log("Known version failed, starting re-detection dance", "WARNING")
            }

            // --- PAIRING DANCE ---
            
            // Stage A: Try MQTT 5 (3 attempts)
            MqttLogger.log("Dance Stage A: Attempting MQTT v5 for $cleanUrl", "INFO")
            for (i in 1..3) {
                if (tryConnectOnce(context, cleanUrl, port, clientId, useTls, username, password, 5)) {
                    MqttLogger.log("v5 Success! Saving to Registry", "SUCCESS")
                    dao.saveHostProtocol(HostProtocolEntity(cleanUrl, 5))
                    currentConfig = newConfig
                    return@withLock
                }
                if (i < 3) delay(2000)
            }

            // Stage B: Try MQTT 3 (3 attempts)
            MqttLogger.log("Dance Stage B: Attempting MQTT v3 for $cleanUrl", "INFO")
            for (i in 1..3) {
                if (tryConnectOnce(context, cleanUrl, port, clientId, useTls, username, password, 3)) {
                    MqttLogger.log("v3 Success! Saving to Registry", "SUCCESS")
                    dao.saveHostProtocol(HostProtocolEntity(cleanUrl, 3))
                    currentConfig = newConfig
                    return@withLock
                }
                if (i < 3) delay(2000)
            }

            MqttLogger.log("Dance Failed: Connectivity issue for $cleanUrl", "ERROR")
            _connectionStatus.value = MqttConnectionStatus.DISCONNECTED
            currentConfig = null
        }
    }

    private suspend fun tryConnectOnce(
        context: Context,
        url: String, port: Int, clientId: String, useTls: Boolean, 
        user: String?, pass: String?, version: Int
    ): Boolean {
        return try {
            MqttLogger.log("Trial: Connecting v$version to $url", "INFO")
            val future = if (version == 5) {
                connectV5(context, url, port, clientId, useTls, user, pass)
            } else {
                connectV3(context, url, port, clientId, useTls, user, pass)
            }
            val ack = future.await()
            ack != null
        } catch (e: Exception) {
            val error = e.cause?.message ?: e.message ?: "Unknown"
            MqttLogger.log("Trial v$version failed: $error", "TRACE")
            false
        }
    }

    private fun connectV5(
        context: Context,
        url: String, port: Int, clientId: String, useTls: Boolean, 
        user: String?, pass: String?
    ): CompletableFuture<*> {
        val appContext = context.applicationContext
        var builder = MqttClient.builder()
            .useMqttVersion5()
            .identifier(clientId)
            .serverHost(url)
            .serverPort(port)
            .automaticReconnect()
                .initialDelay(1, TimeUnit.SECONDS)
                .maxDelay(10, TimeUnit.SECONDS)
                .applyAutomaticReconnect()

        if (useTls) builder = builder.sslWithDefaultConfig()

        val asyncClient = builder
            .addConnectedListener {
                MqttLogger.log("Mqtt v5 Connected (Auto-reconnect)", "SUCCESS")
                _connectionStatus.value = MqttConnectionStatus.CONNECTED
                // Re-subscribe to pending topics on auto-reconnect
                val subs = synchronized(pendingSubscriptions) { pendingSubscriptions.toList() }
                subs.forEach { subscribe(appContext, it) }
            }
            .addDisconnectedListener { 
                val reason = it.cause?.message ?: it.source.toString()
                MqttLogger.log("Disconnected (v5): $reason", "ERROR")
                _connectionStatus.value = if (it.source.toString().contains("RECONNECT")) MqttConnectionStatus.RECONNECTING else MqttConnectionStatus.DISCONNECTED
            }
            .buildAsync()

        this.client5 = asyncClient
        this.client3 = null
        this.currentVersion = 5

        val connectBuilder = asyncClient.connectWith()
            .cleanStart(false)
            .noSessionExpiry()

        if (!user.isNullOrBlank()) {
            connectBuilder.simpleAuth()
                .username(user)
                .password(pass?.toByteArray() ?: ByteArray(0))
                .applySimpleAuth()
        }

        return connectBuilder.send().thenApply {
            MqttLogger.log("Connected to v5 broker: $url", "INFO")
            _connectionStatus.value = MqttConnectionStatus.CONNECTED
            
            // Re-subscribe to pending topics
            val subs = synchronized(pendingSubscriptions) { pendingSubscriptions.toList() }
            subs.forEach { subscribe(appContext, it) }

            it
        }
    }

    private fun connectV3(
        context: Context,
        url: String, port: Int, clientId: String, useTls: Boolean, 
        user: String?, pass: String?
    ): CompletableFuture<*> {
        val appContext = context.applicationContext
        var builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientId)
            .serverHost(url)
            .serverPort(port)
            .automaticReconnect()
                .initialDelay(1, TimeUnit.SECONDS)
                .maxDelay(10, TimeUnit.SECONDS)
                .applyAutomaticReconnect()

        if (useTls) builder = builder.sslWithDefaultConfig()

        val asyncClient = builder
            .addConnectedListener {
                MqttLogger.log("Mqtt v3 Connected (Auto-reconnect)", "SUCCESS")
                _connectionStatus.value = MqttConnectionStatus.CONNECTED
                // Re-subscribe to pending topics on auto-reconnect
                val subs = synchronized(pendingSubscriptions) { pendingSubscriptions.toList() }
                subs.forEach { subscribe(appContext, it) }
            }
            .addDisconnectedListener { 
                val reason = it.cause?.message ?: "Normal Closure"
                MqttLogger.log("Disconnected (v3): $reason", "ERROR")
                _connectionStatus.value = if (reason.contains("RECONNECT", ignoreCase = true)) MqttConnectionStatus.RECONNECTING else MqttConnectionStatus.DISCONNECTED
            }
            .buildAsync()

        this.client3 = asyncClient
        this.client5 = null
        this.currentVersion = 3

        val connectBuilder = asyncClient.connectWith()
            .cleanSession(false)

        if (!user.isNullOrBlank()) {
            connectBuilder.simpleAuth()
                .username(user)
                .password(pass?.toByteArray() ?: ByteArray(0))
                .applySimpleAuth()
        }

        return connectBuilder.send().thenApply {
            MqttLogger.log("Connected to v3 broker: $url", "INFO")
            _connectionStatus.value = MqttConnectionStatus.CONNECTED
            
            // Re-subscribe to pending topics
            val subs = synchronized(pendingSubscriptions) { pendingSubscriptions.toList() }
            subs.forEach { subscribe(appContext, it) }

            it
        }
    }

    fun subscribe(context: Context, topic: String): CompletableFuture<Void> {
        synchronized(pendingSubscriptions) {
            if (!pendingSubscriptions.contains(topic)) {
                pendingSubscriptions.add(topic)
            }
        }

        val client = if (currentVersion == 5) client5 else client3
        if (client == null || !client.state.isConnected) {
            MqttLogger.log("Subscribe queued: $topic (waiting for connection)", "TRACE")
            return CompletableFuture.completedFuture(null)
        }

        return try {
            when (currentVersion) {
                5 -> client5?.subscribeWith()
                    ?.topicFilter(topic)
                    ?.callback { publish ->
                        scope.launch {
                            SyncManager.getInstance(context).onRawMessageReceived(
                                publish.topic.toString(), publish.payloadAsBytes
                            )
                        }
                    }
                    ?.send()?.thenAccept { MqttLogger.log("Subscribed (v5) to $topic", "SUCCESS") }
                else -> client3?.subscribeWith()
                    ?.topicFilter(topic)
                    ?.callback { publish ->
                        scope.launch {
                            SyncManager.getInstance(context).onRawMessageReceived(
                                publish.topic.toString(), publish.payloadAsBytes
                            )
                        }
                    }
                    ?.send()?.thenAccept { MqttLogger.log("Subscribed (v3) to $topic", "SUCCESS") }
            } ?: CompletableFuture.completedFuture(null)
        } catch (e: Exception) {
            MqttLogger.log("Subscribe error: ${e.message}", "ERROR")
            CompletableFuture.completedFuture(null)
        }
    }

    fun unsubscribe(topic: String): CompletableFuture<Void> {
        synchronized(pendingSubscriptions) {
            pendingSubscriptions.remove(topic)
        }

        val client = if (currentVersion == 5) client5 else client3
        if (client == null || !client.state.isConnected) {
            return CompletableFuture.completedFuture(null)
        }

        return try {
            if (currentVersion == 5) {
                client5?.unsubscribeWith()?.topicFilter(topic)?.send()?.thenAccept { } ?: CompletableFuture.completedFuture(null)
            } else {
                client3?.unsubscribeWith()?.topicFilter(topic)?.send()?.thenAccept { } ?: CompletableFuture.completedFuture(null)
            }
        } catch (e: Exception) {
            MqttLogger.log("Unsubscribe error: ${e.message}", "ERROR")
            CompletableFuture.completedFuture(null)
        }
    }

    fun publish(topic: String, payload: ByteArray): CompletableFuture<*> {
        val client = if (currentVersion == 5) client5 else client3
        if (client == null || !client.state.isConnected) {
            MqttLogger.log("Publish dropped: $topic (not connected)", "TRACE")
            return CompletableFuture.completedFuture(null)
        }
        return try {
            if (currentVersion == 5) {
                client5?.publishWith()?.topic(topic)?.payload(payload)?.send() ?: CompletableFuture.completedFuture(null)
            } else {
                client3?.publishWith()?.topic(topic)?.payload(payload)?.send() ?: CompletableFuture.completedFuture(null)
            }
        } catch (e: Exception) {
            MqttLogger.log("Publish error: ${e.message}", "ERROR")
            CompletableFuture.completedFuture(null)
        }
    }

    fun disconnect(): CompletableFuture<Void> {
        return try {
            disconnectInternal(clearSubscriptions = true)
        } catch (e: Exception) {
            MqttLogger.log("Disconnect error: ${e.message}", "ERROR")
            CompletableFuture.completedFuture(null)
        }
    }

    private fun disconnectInternal(clearSubscriptions: Boolean = false): CompletableFuture<Void> {
        if (clearSubscriptions) {
            synchronized(pendingSubscriptions) {
                pendingSubscriptions.clear()
            }
            currentConfig = null
        }
        
        val f5 = client5?.disconnect() ?: CompletableFuture.completedFuture(null)
        val f3 = client3?.disconnect() ?: CompletableFuture.completedFuture(null)
        
        client5 = null
        client3 = null

        return CompletableFuture.allOf(f5, f3).thenAccept { }
    }

    private fun <T> failedFuture(ex: Throwable): CompletableFuture<T> {
        val f = CompletableFuture<T>()
        f.completeExceptionally(ex)
        return f
    }

    private suspend fun <T> CompletableFuture<T>.await(): T {
        return suspendCancellableCoroutine { continuation ->
            whenComplete { result, throwable ->
                if (throwable != null) {
                    continuation.resumeWithException(throwable)
                } else {
                    continuation.resume(result)
                }
            }
            continuation.invokeOnCancellation {
                cancel(true)
            }
        }
    }
}
