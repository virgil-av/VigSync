package com.vigsync.core.mqtt

import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.vigsync.VigSyncApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.CompletableFuture

class MqttManager {
    private var client: Mqtt5AsyncClient? = null
    
    // Tied to Application lifecycle
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _messages = MutableSharedFlow<Mqtt5Publish>()
    val messages = _messages.asSharedFlow()

    private val _connectionStatus = MutableSharedFlow<Boolean>(replay = 1)
    val connectionStatus = _connectionStatus.asSharedFlow()

    init {
        MqttLogger.logApp("MqttManager: Created (hash: ${this.hashCode()})", "TRACE")
    }

    fun connect(
        brokerUrl: String = "broker.hivemq.com",
        port: Int = 1883,
        clientId: String = UUID.randomUUID().toString(),
        useTls: Boolean = false,
        username: String? = null,
        password: String? = null
    ): CompletableFuture<Mqtt5ConnAck?> {
        val clientBuilder = Mqtt5Client.builder()
            .identifier(clientId)
            .serverHost(brokerUrl)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()

        if (useTls) {
            clientBuilder.apply { sslWithDefaultConfig() }
        }

        val asyncClient = clientBuilder.buildAsync()
        this.client = asyncClient
        
        val connectBuilder = asyncClient.connectWith()
            .cleanStart(false)
            .noSessionExpiry()

        if (!username.isNullOrBlank()) {
            connectBuilder.simpleAuth()
                .username(username)
                .password(password?.toByteArray() ?: ByteArray(0))
                .applySimpleAuth()
        }

        _connectionStatus.tryEmit(false)

        return connectBuilder.send().thenApply { connAck: Mqtt5ConnAck? ->
            MqttLogger.log("Connected to broker: $brokerUrl", "INFO")
            _connectionStatus.tryEmit(true)
            connAck
        }.exceptionally { throwable: Throwable ->
            MqttLogger.log("Connection failed: ${throwable.message}", "ERROR")
            _connectionStatus.tryEmit(false)
            null
        }
    }

    fun subscribe(topic: String): CompletableFuture<Void> {
        return client?.subscribeWith()
            ?.topicFilter(topic)
            ?.callback { publish ->
                // STAGE 1: Bridge the threading gap
                scope.launch {
                    val threadName = Thread.currentThread().name
                    MqttLogger.log("Received on $topic (Thread: $threadName)", "RECEIVED")
                    
                    // Forward to strict singleton SyncManager
                    VigSyncApplication.getInstance().syncManager.onRawMessageReceived(
                        publish.topic.toString(), 
                        publish.payloadAsBytes
                    )
                }
            }
            ?.send()?.thenAccept { 
                MqttLogger.log("Subscribed to $topic", "SUCCESS")
            } ?: CompletableFuture.completedFuture(null)
    }

    fun publish(topic: String, payload: ByteArray): CompletableFuture<com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult> {
        val client = this.client
        if (client == null) {
            val future = CompletableFuture<com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult>()
            future.completeExceptionally(Exception("Client not connected"))
            return future
        }
        return client.publishWith()
            .topic(topic)
            .payload(payload)
            .send().thenApply { 
                MqttLogger.log("Published to $topic", "SENT")
                it
            }
    }

    fun disconnect(): CompletableFuture<Void> {
        return client?.disconnect() ?: CompletableFuture.completedFuture(null)
    }
}
