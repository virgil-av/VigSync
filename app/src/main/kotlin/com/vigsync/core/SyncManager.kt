package com.vigsync.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vigsync.R
import com.vigsync.core.models.Device
import com.vigsync.core.models.RawMessage
import com.vigsync.core.mqtt.MqttLogger
import com.vigsync.core.mqtt.MqttManager
import com.vigsync.data.local.DeviceStatusEntity
import com.vigsync.data.local.EventEntity
import com.vigsync.data.local.VigSyncDatabase
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class SyncManager private constructor(context: Context) {
    private val appPreferences = AppPreferences(context)
    private val mqttManager = MqttManager()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = context.applicationContext

    private val eventBuffer = ConcurrentHashMap<String, BufferedEvent>()
    private val BUFFER_WINDOW = 5000L // 5 seconds

    val SYNC_CHANNEL_ID = "vigsync_notifications"
    val SYNC_NOTIF_ID = 1001

    data class BufferedEvent(
        val type: String,
        val data: String,
        val timestamp: Long,
        val priority: Int,
        var job: Job? = null
    )
    // ----------------------------

    private val database: VigSyncDatabase by lazy { 
        VigSyncDatabase.getInstance(appContext!!) 
    }

    private val _pairedDevicesList = MutableStateFlow<List<Device>>(emptyList())
    val pairedDevicesList = _pairedDevicesList.asStateFlow()

    companion object {
        @Volatile
        private var INSTANCE: SyncManager? = null

        fun getInstance(context: Context): SyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncManager(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
    }

    init {
        MqttLogger.logApp("SyncManager: Strict Instance Created (hash: ${this.hashCode()})", "TRACE")
        try {
            createSyncNotificationChannel()
            scope.launch {
                try {
                    loadPairedDevices()
                    startHeartbeat()
                    startEventDrivenWorker()
                    
                    // Auto-start MQTT re-enabled after hardening MqttManager status flow
                    if (appPreferences.brokerUrl.first().isNotEmpty()) {
                        MqttLogger.logApp("SyncManager: Auto-starting MQTT connection", "INFO")
                        start()
                    }
                } catch (e: Exception) {
                    MqttLogger.logApp("SyncManager: Async initialization failed: ${e.message}", "ERROR")
                }
            }
        } catch (e: Exception) {
            MqttLogger.logApp("SyncManager: Initialization failed: ${e.message}", "ERROR")
        }
    }

    fun onRawMessageReceived(topic: String, payload: ByteArray) {
        scope.launch {
            try {
                val json = String(payload)
                val raw = com.vigsync.data.local.RawMessage(topic = topic, payload = json)
                database.dao().insertRaw(raw)
                MqttLogger.log("Message received on $topic", "RECEIVED")
            } catch (e: Exception) {
                MqttLogger.log("Failed to process message on $topic: ${e.message}", "ERROR")
            }
        }
    }

    fun startEventDrivenWorker() {
        MqttLogger.logApp("SyncManager: Event-Driven Worker Started", "TRACE")
        scope.launch {
            database.dao().getUnprocessedFlow()
                .distinctUntilChanged()
                .collect { messages ->
                    messages.forEach { raw ->
                        try {
                            val msg = Json.decodeFromString<RawMessage>(raw.payload)
                            
                            // 1. Update device registry
                            updateDeviceStatus(msg)
                            
                            // 2. Process events
                            if (raw.topic.contains("/events/")) {
                                processIncomingEvent(msg)
                            }
                            
                            database.dao().markAsProcessed(raw.id)
                        } catch (e: Exception) {
                            MqttLogger.logApp("Worker: Failed to parse message ${raw.id}", "WARNING")
                            database.dao().markAsProcessed(raw.id) // mark anyway to avoid loop
                        }
                    }
                }
        }
    }

    fun loadPairedDevices() {
        scope.launch {
            appPreferences.pairedDevices.first()?.let { json ->
                try {
                    val devices = Json.decodeFromString<List<Device>>(json)
                    _pairedDevicesList.value = devices
                } catch (e: Exception) {
                    Log.e("SyncManager", "Failed to load paired devices", e)
                }
            }
        }
    }

    fun savePairedDevices() {
        scope.launch {
            val json = Json.encodeToString(_pairedDevicesList.value)
            appPreferences.savePairedDevices(json)
        }
    }

    fun addPairedDevice(device: Device) {
        _pairedDevicesList.update { currentList ->
            if (currentList.any { it.id == device.id }) {
                currentList.map { if (it.id == device.id) device else it }
            } else {
                currentList + device
            }
        }
        savePairedDevices()
        
        // Ensure we are subscribed to this device's status and events
        scope.launch {
            val prefix = appPreferences.topicPrefix.first() ?: "vigsync/default"
            mqttManager.subscribe(appContext!!, "$prefix/events/${device.id}")
            mqttManager.subscribe(appContext!!, "$prefix/status/${device.id}")
            
            MqttLogger.logApp("SyncManager: Paired with ${device.name}, awaiting heartbeat", "TRACE")
        }
    }

    fun removeDevice(deviceId: String) {
        _pairedDevicesList.update { currentList ->
            currentList.filterNot { it.id == deviceId }
        }
        savePairedDevices()
        scope.launch {
            val prefix = appPreferences.topicPrefix.first() ?: "vigsync/default"
            // Explicitly unsubscribe so they don't pop back in
            mqttManager.unsubscribe("$prefix/events/$deviceId")
            mqttManager.unsubscribe("$prefix/status/$deviceId")

            database.dao().deleteDeviceStatus(deviceId)
            MqttLogger.logApp("SyncManager: Removed device $deviceId", "TRACE")
        }
    }

    fun getLocalDeviceId(): String {
        val androidId = android.provider.Settings.Secure.getString(appContext!!.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        return "${android.os.Build.MODEL}_$androidId"
    }

    private var connectionJob: Job? = null

    fun start() {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            val url = appPreferences.brokerUrl.first()
            val port = appPreferences.brokerPort.first().toInt()
            val prefix = appPreferences.topicPrefix.first() ?: "vigsync/default"
            val username = appPreferences.brokerUsername.first()
            val password = appPreferences.brokerPassword.first()
            val tls = appPreferences.useTls.first()
            
            mqttManager.connect(
                context = appContext!!,
                brokerUrl = url, 
                port = port,
                useTls = tls,
                username = username,
                password = password
            )
            
            // 1. Subscribe to own topics (for loopback/status check)
            mqttManager.subscribe(appContext!!, "$prefix/events/${getLocalDeviceId()}")
            mqttManager.subscribe(appContext!!, "$prefix/status/${getLocalDeviceId()}")

            // 2. Subscribe to each paired device explicitly (No Wildcards to prevent ghost re-adds)
            _pairedDevicesList.value.forEach { device ->
                mqttManager.subscribe(appContext!!, "$prefix/events/${device.id}")
                mqttManager.subscribe(appContext!!, "$prefix/status/${device.id}")
            }
            
            MqttLogger.logApp("SyncManager: Connected and subscribed to ${_pairedDevicesList.value.size} devices", "INFO")
        }
    }

    fun restart() {
        scope.launch {
            mqttManager.disconnect().thenAccept {
                start()
            }
        }
    }

    fun updateDeviceStatus(msg: RawMessage) {
        // Topic info is in RawMessage but in DB we only have payload, 
        // so we check senderId presence as heuristic for status msg
        if (msg.senderId == null) return
        
        scope.launch {
            val entity = DeviceStatusEntity(
                deviceId = msg.senderId,
                name = msg.senderName ?: "Unknown Device",
                batteryLevel = msg.batteryLevel ?: 0,
                isOnline = true,
                lastSeen = msg.timestamp
            )
            database.dao().updateDeviceStatus(entity)
        }
    }

    fun createSyncNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SYNC_CHANNEL_ID,
                "Sync Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows notifications from synced devices"
            }
            val manager = appContext!!.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showSyncNotification(title: String, message: String, deviceName: String) {
        val builder = NotificationCompat.Builder(appContext!!, SYNC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setSubText(deviceName)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(appContext!!)) {
            try {
                notify(System.currentTimeMillis().toInt(), builder.build())
            } catch (e: SecurityException) {
                // Handle missing permission for Android 13+
            }
        }
    }

    fun sendTestNotification() {
        showSyncNotification("Test", "This is a test notification from VigSync", "Local Device")
    }

    fun processIncomingEvent(msg: RawMessage) {
        scope.launch {
            // Deduplication
            val hash = msg.calculateHash()
            if (database.dao().countEventHash(hash) > 0) return@launch

            val event = EventEntity(
                type = msg.type ?: "OTHER",
                data = msg.data ?: "",
                timestamp = msg.timestamp,
                syncStatus = com.vigsync.core.models.SyncStatus.RECEIVED,
                direction = com.vigsync.core.models.EventDirection.REMOTE,
                sourceDevice = msg.senderName,
                payloadHash = hash
            )
            database.dao().insertEvent(event)

            // Show Notification based on settings
            val shouldNotify = when (msg.type) {
                "CALL" -> appPreferences.notifCalls.first()
                "SMS" -> appPreferences.notifSms.first()
                else -> appPreferences.notifOther.first()
            }

            if (shouldNotify) {
                showSyncNotification(
                    title = "${msg.type} from ${msg.senderName}",
                    message = msg.data ?: "",
                    deviceName = msg.senderName ?: "Unknown"
                )
            }
        }
    }

    fun logRemoteFailure(deviceId: String, error: String) {
        MqttLogger.log("Remote Error ($deviceId): $error", "ERROR")
    }

    fun startHeartbeat() {
        scope.launch {
            while (isActive) {
                sendHeartbeat()
                delay(60000) // 1 minute
            }
        }
    }

    fun sendHeartbeat() {
        scope.launch {
            val prefix = appPreferences.topicPrefix.first() ?: "vigsync/default"
            val topic = "$prefix/status/${getLocalDeviceId()}"
            val msg = RawMessage(
                senderId = getLocalDeviceId(),
                senderName = android.os.Build.MODEL,
                batteryLevel = getBatteryLevel(),
                timestamp = System.currentTimeMillis()
            )
            val json = Json.encodeToString(msg)
            mqttManager.publish(topic, json.toByteArray())
        }
    }

    fun getBatteryLevel(): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = appContext!!.registerReceiver(null, filter)
        val level: Int = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 0
    }

    fun publishEvent(type: String, data: String) {
        val eventId = "${type}_${data.hashCode()}"
        
        // Priority logic: Calls are high priority (send immediately)
        if (type == "CALL") {
            publishNow(type, data, System.currentTimeMillis())
            return
        }

        // Buffer logic for SMS/Others
        scheduleBufferPublication(eventId)
        
        val buffered = BufferedEvent(type, data, System.currentTimeMillis(), 0)
        eventBuffer[eventId] = buffered
    }

    fun extractPhoneNumber(data: String): String? {
        val regex = Regex("""\+?\d[\d\-\s]{7,}\d""")
        return regex.find(data)?.value
    }

    fun scheduleBufferPublication(id: String) {
        eventBuffer[id]?.job?.cancel()
        val job = scope.launch {
            delay(BUFFER_WINDOW)
            eventBuffer[id]?.let { 
                publishNow(it.type, it.data, it.timestamp)
                eventBuffer.remove(id)
            }
        }
        eventBuffer[id]?.job = job
    }

    fun publishNow(type: String, data: String, timestamp: Long) {
        scope.launch {
            val prefix = appPreferences.topicPrefix.first() ?: "vigsync/default"
            val topic = "$prefix/events/${getLocalDeviceId()}"
            val msg = RawMessage(
                type = type,
                data = data,
                senderId = getLocalDeviceId(),
                senderName = android.os.Build.MODEL,
                timestamp = timestamp
            )
            val json = Json.encodeToString(msg)
            mqttManager.publish(topic, json.toByteArray()).thenAccept {
                MqttLogger.log("Sent $type event", "SENT")
            }
        }
    }

    fun getMqttManager() = mqttManager

    fun getDao() = database.dao()
}
