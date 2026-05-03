package com.vigsync.core.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.vigsync.VigSyncApplication
import com.vigsync.data.local.HostProtocolEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MqttManager {
    private var client5: Mqtt5AsyncClient? = null
    private var client3: Mqtt3AsyncClient? = null
    private var currentVersion: Int = 5
    private val connectionMutex = Mutex()

    // Tied to Application lifecycle
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _connectionStatus = MutableSharedFlow<Boolean>(replay = 1)
    val connectionStatus = _connectionStatus.asSharedFlow()

    init {
        MqttLogger.logApp("MqttManager: Created (hash: ${this.hashCode()})", "TRACE")
    }

    suspend fun connect(
        brokerUrl: String,
        port: Int,
        clientId: String = UUID.randomUUID().toString(),
        useTls: Boolean = false,
        username: String? = null,
        password: String? = null
    ) {
        connectionMutex.withLock {
            val cleanUrl = brokerUrl
                .replace("mqtt://", "", ignoreCase = true)
                .replace("tcp://", "", ignoreCase = true)
                .replace("ssl://", "", ignoreCase = true)
                .trim()

            // Step 0: Kill any existing "Zombie" connections
            MqttLogger.log("Stopping previous connections before new attempt...", "INFO")
            disconnectInternal().await()

            val dao = VigSyncApplication.getInstance().syncManager.getDao()
            val knownVersion = dao.getProtocolForHost(cleanUrl)

            if (knownVersion != null) {
                MqttLogger.log("Known host found: $cleanUrl (v$knownVersion)", "INFO")
                val success = tryConnectOnce(cleanUrl, port, clientId, useTls, username, password, knownVersion)
                if (success) return@withLock
                MqttLogger.log("Known version failed, starting re-detection dance", "WARNING")
            }

            // --- PAIRING DANCE ---
            
            // Stage A: Try MQTT 5 (3 attempts)
            MqttLogger.log("Dance Stage A: Attempting MQTT v5 for $cleanUrl", "INFO")
            for (i in 1..3) {
                if (tryConnectOnce(cleanUrl, port, clientId, useTls, username, password, 5)) {
                    MqttLogger.log("v5 Success! Saving to Registry", "SUCCESS")
                    dao.saveHostProtocol(HostProtocolEntity(cleanUrl, 5))
                    return@withLock
                }
                if (i < 3) delay(2000)
            }

            // Stage B: Try MQTT 3 (3 attempts)
            MqttLogger.log("Dance Stage B: Attempting MQTT v3 for $cleanUrl", "INFO")
            for (i in 1..3) {
                if (tryConnectOnce(cleanUrl, port, clientId, useTls, username, password, 3)) {
                    MqttLogger.log("v3 Success! Saving to Registry", "SUCCESS")
                    dao.saveHostProtocol(HostProtocolEntity(cleanUrl, 3))
                    return@withLock
                }
                if (i < 3) delay(2000)
            }

            MqttLogger.log("Dance Failed: Connectivity issue for $cleanUrl", "ERROR")
            _connectionStatus.emit(false)
        }
    }

    private suspend fun tryConnectOnce(
        url: String, port: Int, clientId: String, useTls: Boolean, 
        user: String?, pass: String?, version: Int
    ): Boolean {
        return try {
            MqttLogger.log("Trial: Connecting v$version to $url", "INFO")
            val future = if (version == 5) {
                connectV5(url, port, clientId, useTls, user, pass)
            } else {
                connectV3(url, port, clientId, useTls, user, pass)
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
        url: String, port: Int, clientId: String, useTls: Boolean, 
        user: String?, pass: String?
    ): CompletableFuture<*> {
        var builder = MqttClient.builder()
            .useMqttVersion5()
            .identifier(clientId)
            .serverHost(url)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()

        if (useTls) builder = builder.sslWithDefaultConfig()

        val asyncClient = builder
            .addDisconnectedListener { 
                val reason = it.cause?.message ?: "Normal Closure"
                MqttLogger.log("Disconnected (v5): $reason", "ERROR")
                _connectionStatus.tryEmit(false)
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
            _connectionStatus.tryEmit(true)
            it
        }
    }

    private fun connectV3(
        url: String, port: Int, clientId: String, useTls: Boolean, 
        user: String?, pass: String?
    ): CompletableFuture<*> {
        var builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientId)
            .serverHost(url)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()

        if (useTls) builder = builder.sslWithDefaultConfig()

        val asyncClient = builder
            .addDisconnectedListener { 
                val reason = it.cause?.message ?: "Normal Closure"
                MqttLogger.log("Disconnected (v3): $reason", "ERROR")
                _connectionStatus.tryEmit(false)
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
            _connectionStatus.tryEmit(true)
            it
        }
    }

    fun subscribe(topic: String): CompletableFuture<Void> {
        return when (currentVersion) {
            5 -> client5?.subscribeWith()
                ?.topicFilter(topic)
                ?.callback { publish ->
                    scope.launch {
                        VigSyncApplication.getInstance().syncManager.onRawMessageReceived(
                            publish.topic.toString(), publish.payloadAsBytes
                        )
                    }
                }
                ?.send()?.thenAccept { MqttLogger.log("Subscribed (v5) to $topic", "SUCCESS") }
            else -> client3?.subscribeWith()
                ?.topicFilter(topic)
                ?.callback { publish ->
                    scope.launch {
                        VigSyncApplication.getInstance().syncManager.onRawMessageReceived(
                            publish.topic.toString(), publish.payloadAsBytes
                        )
                    }
                }
                ?.send()?.thenAccept { MqttLogger.log("Subscribed (v3) to $topic", "SUCCESS") }
        } ?: CompletableFuture.completedFuture(null)
    }

    fun publish(topic: String, payload: ByteArray): CompletableFuture<*> {
        val client = if (currentVersion == 5) client5 else client3
        if (client == null) {
            val future = CompletableFuture<Any>()
            future.completeExceptionally(Exception("Client not connected"))
            return future
        }
        return if (currentVersion == 5) {
            client5?.publishWith()?.topic(topic)?.payload(payload)?.send() ?: failedFuture<Any>(Exception("v5 client null"))
        } else {
            client3?.publishWith()?.topic(topic)?.payload(payload)?.send() ?: failedFuture<Any>(Exception("v3 client null"))
        }
    }

    private fun <T> failedFuture(ex: Throwable): CompletableFuture<T> {
        val f = CompletableFuture<T>()
        f.completeExceptionally(ex)
        return f
    }

    fun disconnect(): CompletableFuture<Void> {
        return disconnectInternal()
    }

    private fun disconnectInternal(): CompletableFuture<Void> {
        val f5 = client5?.disconnect() ?: CompletableFuture.completedFuture(null)
        val f3 = client3?.disconnect() ?: CompletableFuture.completedFuture(null)
        client5 = null
        client3 = null
        return CompletableFuture.allOf(f5, f3).thenAccept { }
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
