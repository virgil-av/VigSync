package com.vigsync.core

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vigsync.R
import com.vigsync.core.crypto.EncryptionManager
import com.vigsync.core.models.Device
import com.vigsync.core.models.RawMessage
import com.vigsync.core.mqtt.HeartbeatReceiver
import com.vigsync.core.mqtt.MqttLogger
import com.vigsync.core.mqtt.MqttManager
import com.vigsync.data.local.DeviceStatusEntity
import com.vigsync.data.local.EventEntity
import com.vigsync.data.local.VigSyncDatabase
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    private val HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
    private val HEARTBEAT_REQUEST_CODE = 2001

    data class BufferedEvent(
        val type: String,
        val data: String,
        val timestamp: Long,
        val priority: Int,
        var job: Job? = null
    )

    private val database: VigSyncDatabase by lazy { 
        VigSyncDatabase.getInstance(appContext!!) 
    }

    private val _pairedDevicesList = MutableStateFlow<List<Device>>(emptyList())
    val pairedDevicesList = _pairedDevicesList.asStateFlow()

    private val processTrigger = Channel<Unit>(Channel.CONFLATED)

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
        MqttLogger.logApp("SyncManager: Initializing", "TRACE")
        try {
            createSyncNotificationChannel()
            scope.launch {
                try {
                    appPreferences.migrateIfNeeded()
                    loadPairedDevices()
                    startEventDrivenWorker()
                    
                    if (appPreferences.brokerUrl.first().isNotEmpty()) {
                        start()
                    }
                } catch (e: Exception) {
                    MqttLogger.logApp("SyncManager: Async init failed: ${e.message}", "ERROR")
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
                val msg = Json.decodeFromString<RawMessage>(json)
                val raw = com.vigsync.data.local.RawMessage(topic = topic, payload = json)
                database.dao().insertRaw(raw)
                processTrigger.trySend(Unit)
                
                val source = msg.senderName ?: "Unknown"
                val type = if (topic.contains("/events/")) "event" else "status"
                MqttLogger.log("Message from $source", "RECEIVED $source $type")
            } catch (e: Exception) {
                MqttLogger.log("Failed to process message on $topic: ${e.message}", "ERROR")
            }
        }
    }

    fun startEventDrivenWorker() {
        scope.launch {
            for (trigger in processTrigger) {
                try {
                    var messages = database.dao().getUnprocessedList()
                    while (messages.isNotEmpty()) {
                        messages.forEach { raw ->
                            try {
                                val msg = Json.decodeFromString<RawMessage>(raw.payload)
                                updateDeviceStatus(msg)
                                if (raw.topic.contains("/events/")) {
                                    processIncomingEvent(msg)
                                }
                                database.dao().markAsProcessed(raw.id)
                            } catch (e: Exception) {
                                database.dao().markAsProcessed(raw.id)
                            }
                        }
                        messages = database.dao().getUnprocessedList()
                    }
                } catch (e: Exception) {
                    MqttLogger.logApp("SyncManager: Worker error: ${e.message}", "ERROR")
                    delay(2000)
                }
            }
        }
        // Initial trigger
        processTrigger.trySend(Unit)
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
        
        scope.launch {
            val prefix = appPreferences.topicPrefix.first() ?: "vigsync/default"
            mqttManager.subscribe(appContext!!, "$prefix/events/${device.id}")
            mqttManager.subscribe(appContext!!, "$prefix/status/${device.id}")
        }
    }

    fun removeDevice(deviceId: String) {
        _pairedDevicesList.update { currentList ->
            currentList.filterNot { it.id == deviceId }
        }
        savePairedDevices()
        scope.launch {
            val prefix = appPreferences.topicPrefix.first() ?: "vigsync/default"
            mqttManager.unsubscribe("$prefix/events/$deviceId")
            mqttManager.unsubscribe("$prefix/status/$deviceId")
            database.dao().deleteDeviceStatus(deviceId)
        }
    }

    fun getLocalDeviceId(): String {
        val androidId = android.provider.Settings.Secure.getString(appContext!!.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        return "${android.os.Build.MODEL}_$androidId"
    }

    private val eventDebounceCache = ConcurrentHashMap<String, Long>()
    private val DEBOUNCE_WINDOW = 5000L // 5 seconds
    
    // CRITICAL: Ensure only one connection job exists
    private var connectionJob: Job? = null
    private var lastConnectionAttempt = 0L
    private val CONNECTION_RETRY_DELAY = 10000L // 10s debounce

    @SuppressLint("HardwareIds")
    fun start() {
        val now = System.currentTimeMillis()
        if (now - lastConnectionAttempt < CONNECTION_RETRY_DELAY) {
            MqttLogger.logApp("SyncManager: Connection attempt debounced", "TRACE")
            return
        }
        lastConnectionAttempt = now

        connectionJob?.cancel() // Kill any existing job
        connectionJob = scope.launch {
            val url = appPreferences.brokerUrl.first()
            if (url.isEmpty()) return@launch

            val port = try { appPreferences.brokerPort.first().toInt() } catch(e: Exception) { 1883 }
            val prefix = appPreferences.topicPrefix.first() ?: "vigsync/default"
            val username = appPreferences.brokerUsername.first()
            val password = appPreferences.brokerPassword.first()
            val tls = appPreferences.useTls.first()
            
            val androidId = Settings.Secure.getString(appContext?.contentResolver, Settings.Secure.ANDROID_ID) ?: "vigsync_client"
            
            mqttManager.connect(
                context = appContext!!,
                brokerUrl = url, 
                port = port,
                clientId = "vigsync_$androidId",
                useTls = tls,
                username = username,
                password = password
            )
            
            if (!isActive) return@launch // Double check if job was cancelled during connect dance

            // Subscribe to own topics
            mqttManager.subscribe(appContext!!, "$prefix/events/${getLocalDeviceId()}")
            mqttManager.subscribe(appContext!!, "$prefix/status/${getLocalDeviceId()}")

            // Subscribe to paired devices
            _pairedDevicesList.value.forEach { device ->
                mqttManager.subscribe(appContext!!, "$prefix/events/${device.id}")
                mqttManager.subscribe(appContext!!, "$prefix/status/${device.id}")
            }
            
            scheduleNextHeartbeat()
        }
    }

    fun handleNetworkChange() {
        start()
    }

    fun restart() {
        scope.launch {
            mqttManager.disconnect().thenAccept {
                start()
            }
        }
    }

    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        cancelHeartbeat()
        mqttManager.disconnect()
        MqttLogger.logApp("SyncManager: Stopped and Disconnected", "INFO")
    }

    suspend fun updateDeviceStatus(msg: RawMessage) {
        if (msg.senderId == null) return
        
        val dao = database.dao()
        val existing = dao.getDeviceStatus(msg.senderId)
        
        if (existing != null) {
            dao.updateHeartbeat(
                deviceId = msg.senderId,
                isOnline = true,
                lastSeen = msg.timestamp,
                batteryLevel = msg.batteryLevel ?: 0
            )
        } else {
            val entity = DeviceStatusEntity(
                deviceId = msg.senderId,
                name = msg.senderName ?: "Unknown Device",
                batteryLevel = msg.batteryLevel ?: 0,
                isOnline = true,
                lastSeen = msg.timestamp,
                pairingTimestamp = System.currentTimeMillis()
            )
            dao.updateDeviceStatus(entity)
        }
    }

    fun createSyncNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SYNC_CHANNEL_ID,
                "Sync Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
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
            } catch (e: SecurityException) {}
        }
    }

    fun sendTestNotification() {
        showSyncNotification("Test", "This is a test notification from VigSync", "Local Device")
    }

    suspend fun processIncomingEvent(msg: RawMessage) {
        val sharedKey = appPreferences.sharedKey.first()
        val decryptedData = if (!sharedKey.isNullOrEmpty() && !msg.data.isNullOrEmpty()) {
            try {
                EncryptionManager(sharedKey).decrypt(msg.data) ?: msg.data
            } catch (e: Exception) {
                "[Encrypted Content]"
            }
        } else msg.data ?: ""

        // Deduplication using decrypted data hash
        val hash = msg.calculateHash()
        if (database.dao().countEventHash(hash) > 0) return

        val isLocal = msg.senderId == getLocalDeviceId()

        val event = EventEntity(
            type = msg.type ?: "OTHER",
            data = decryptedData,
            timestamp = msg.timestamp,
            syncStatus = if (isLocal) com.vigsync.core.models.SyncStatus.SENT else com.vigsync.core.models.SyncStatus.RECEIVED,
            direction = if (isLocal) com.vigsync.core.models.EventDirection.LOCAL else com.vigsync.core.models.EventDirection.REMOTE,
            sourceDeviceId = msg.senderId,
            sourceDeviceName = msg.senderName,
            payloadHash = hash
        )
        database.dao().insertEvent(event)

        val deviceStatus = msg.senderId?.let { database.dao().getDeviceStatus(it) }
        val resolvedName = deviceStatus?.customLabel ?: msg.senderName ?: "Unknown"

        if (!isLocal) {
            val shouldNotify = when (msg.type) {
                "CALL" -> appPreferences.notifCalls.first()
                "SMS" -> appPreferences.notifSms.first()
                else -> appPreferences.notifOther.first()
            }

            if (shouldNotify) {
                showSyncNotification(
                    title = "${msg.type} from $resolvedName",
                    message = decryptedData,
                    deviceName = resolvedName
                )
            }
        }
    }

    fun scheduleNextHeartbeat() {
        val alarmManager = appContext?.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(appContext, HeartbeatReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext, HEARTBEAT_REQUEST_CODE, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancelHeartbeat() {
        val alarmManager = appContext?.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(appContext, HeartbeatReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext, HEARTBEAT_REQUEST_CODE, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
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
            mqttManager.publish(topic, json.toByteArray()).thenAccept {
                MqttLogger.log("Heartbeat sent", "SENT local status")
            }.exceptionally { e -> 
                null 
            }
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
        val now = System.currentTimeMillis()
        val eventKey = "${type}_$data"
        
        val lastSeen = eventDebounceCache[eventKey] ?: 0L
        if (now - lastSeen < DEBOUNCE_WINDOW) {
            MqttLogger.logApp("SyncManager: Ignored duplicate $type event within window", "TRACE")
            return
        }
        eventDebounceCache[eventKey] = now

        val eventId = "${type}_${data.hashCode()}"
        
        if (type == "CALL" || type.contains("MISSED")) {
            publishNow(type, data, System.currentTimeMillis())
            return
        }

        scheduleBufferPublication(eventId)
        
        val buffered = BufferedEvent(type, data, System.currentTimeMillis(), 0)
        eventBuffer[eventId] = buffered
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
            
            val sharedKey = appPreferences.sharedKey.first()
            val encryptedData = if (!sharedKey.isNullOrEmpty()) {
                EncryptionManager(sharedKey).encrypt(data) ?: data
            } else {
                data
            }

            val msg = RawMessage(
                type = type,
                data = encryptedData,
                senderId = getLocalDeviceId(),
                senderName = android.os.Build.MODEL,
                timestamp = timestamp
            )
            val json = Json.encodeToString(msg)
            mqttManager.publish(topic, json.toByteArray()).thenAccept {
                MqttLogger.log("Sent $type event", "SENT local alert")
            }.exceptionally { e ->
                null
            }
        }
    }

    fun getMqttManager() = mqttManager

    fun getDao() = database.dao()
}
