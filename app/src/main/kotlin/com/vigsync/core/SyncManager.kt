package com.vigsync.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.vigsync.core.crypto.EncryptionManager
import com.vigsync.core.mqtt.MqttManager
import com.vigsync.core.mqtt.MqttLogger
import com.vigsync.core.models.Device
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*

import com.vigsync.core.models.SyncStatus
import com.vigsync.core.models.EventRecord
import com.vigsync.core.models.EventDirection
import com.vigsync.data.local.*

class SyncManager private constructor(context: Context) {
    private val appPreferences = AppPreferences(context)
    private val mqttManager = MqttManager()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val appContext = context.applicationContext
    
    private val database: VigSyncDatabase by lazy { 
        VigSyncDatabase.getInstance(appContext) 
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
        scope.launch {
            loadPairedDevices()
            startHeartbeat()
            startEventDrivenWorker()
        }
    }

    fun onRawMessageReceived(topic: String, payload: ByteArray) {
        val threadName = Thread.currentThread().name
        MqttLogger.logApp("STAGE 1: RAW Packet on $topic (Thread: $threadName)", "TRACE")
        
        scope.launch {
            try {
                val raw = RawMessage(topic = topic, payload = String(payload))
                database.dao().insertRaw(raw)
                MqttLogger.logApp("STAGE 2: Stored in HOT STORAGE (ID: pending)", "TRACE")
            } catch (e: Exception) {
                MqttLogger.logApp("STAGE 2 ERROR: DB Insert fail: ${e.message}", "ERROR")
            }
        }
    }

    private fun startEventDrivenWorker() {
        MqttLogger.logApp("SyncManager: Event-Driven Worker Started", "TRACE")
        scope.launch {
            // STAGE 3: Use Flow to observe Hot Storage reactively
            database.dao().getRecentRaw().collect { list ->
                val unprocessed = list.filter { !it.isProcessed }.sortedBy { it.timestamp }
                if (unprocessed.isNotEmpty()) {
                    MqttLogger.logApp("STAGE 3: Reactive Trigger - ${unprocessed.size} items", "TRACE")
                }
                
                for (msg in unprocessed) {
                    try {
                        MqttLogger.logApp("STAGE 3.1: Processing ID ${msg.id}", "TRACE")
                        if (msg.topic.contains("/status/")) {
                            updateDeviceStatus(msg)
                        } else if (msg.topic.contains("/events/")) {
                            processIncomingEvent(msg)
                        }
                        database.dao().updateRaw(msg.copy(isProcessed = true))
                        MqttLogger.logApp("STAGE 5: ID ${msg.id} COMPLETED", "SUCCESS")
                    } catch (e: Exception) {
                        MqttLogger.logApp("Processor ERROR on ID ${msg.id}: ${e.message}", "ERROR")
                    }
                }
            }
        }
        
        // Background cleanup task
        scope.launch {
            while(true) {
                delay(300000) // 5 mins
                val threshold = System.currentTimeMillis() - 3600000 // 1 hour
                database.dao().cleanupRaw(threshold)
                MqttLogger.logApp("Cleanup: Deleted old raw messages", "TRACE")
            }
        }
    }

    private fun loadPairedDevices() {
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

    private fun savePairedDevices() {
        scope.launch {
            val json = Json.encodeToString(_pairedDevicesList.value)
            appPreferences.savePairedDevices(json)
        }
    }

    fun addPairedDevice(device: Device) {
        if (_pairedDevicesList.value.none { it.id == device.id }) {
            _pairedDevicesList.update { it + device }
            savePairedDevices()
            
            // Insert placeholder status so it shows up on Dashboard immediately
            scope.launch {
                val entity = DeviceStatusEntity(
                    deviceId = device.id,
                    name = device.name,
                    batteryLevel = 0,
                    isOnline = false,
                    lastSeen = System.currentTimeMillis()
                )
                database.dao().updateDeviceStatus(entity)
                
                val prefix = appPreferences.topicPrefix.first() ?: return@launch
                mqttManager.subscribe("$prefix/status/${device.id}")
                MqttLogger.logApp("SyncManager: Paired with ${device.name}, awaiting heartbeat", "TRACE")
            }
        }
    }

    fun removeDevice(deviceId: String) {
        _pairedDevicesList.update { currentList ->
            currentList.filterNot { it.id == deviceId }
        }
        savePairedDevices()
        scope.launch {
            database.dao().deleteDeviceStatus(deviceId)
            MqttLogger.logApp("SyncManager: Removed device $deviceId", "TRACE")
        }
    }

    fun getLocalDeviceId(): String {
        val androidId = android.provider.Settings.Secure.getString(appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
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
                brokerUrl = url, 
                port = port,
                useTls = tls,
                username = username,
                password = password
            )
            mqttManager.subscribe("$prefix/events/+")
            mqttManager.subscribe("$prefix/status/+")
        }
    }

    fun restart() {
        scope.launch {
            mqttManager.disconnect().thenAccept {
                start()
            }
        }
    }

    private suspend fun updateDeviceStatus(msg: RawMessage) {
        try {
            val updatedDevice = Json.decodeFromString<Device>(msg.payload)
            val entity = DeviceStatusEntity(
                deviceId = updatedDevice.id,
                name = updatedDevice.name,
                batteryLevel = updatedDevice.batteryLevel,
                isOnline = true,
                lastSeen = System.currentTimeMillis()
            )
            database.dao().updateDeviceStatus(entity)
            MqttLogger.logApp("STAGE 4: Status stored for ${updatedDevice.id}", "SUCCESS")
        } catch (e: Exception) {
            MqttLogger.logApp("STAGE 4 ERROR: Status parse fail: ${e.message}", "ERROR")
        }
    }

    private suspend fun processIncomingEvent(msg: RawMessage) {
        val encryptedStr = msg.payload
        val key = appPreferences.sharedKey.first() ?: return
        val encryptionManager = EncryptionManager(key)
        
        val decrypted = try {
            encryptionManager.decrypt(encryptedStr)
        } catch (e: Exception) {
            logRemoteFailure("Decryption Failed", "${e.message}")
            null
        } ?: return

        val parts = decrypted.split("|", limit = 3)
        if (parts.size < 3) return
        
        val type = parts[0]
        val deviceName = parts[1]
        val data = parts[2]
        
        val payloadHash = encryptedStr.hashCode().toString()
        if (database.dao().countEventHash(payloadHash) > 0) return

        val eventEntity = EventEntity(
            type = type,
            data = data,
            timestamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.RECEIVED,
            direction = EventDirection.REMOTE,
            sourceDevice = deviceName,
            payloadHash = payloadHash
        )
        database.dao().insertEvent(eventEntity)
        MqttLogger.logApp("STAGE 4: Synced $type stored", "SUCCESS")
    }

    private fun logRemoteFailure(type: String, message: String) {
        val record = EventRecord(
            type = "SYNC ERROR",
            data = message,
            syncStatus = SyncStatus.FAILED,
            direction = EventDirection.REMOTE,
            errorMessage = type
        )
        MqttLogger.logEvent(record)
    }

    private fun startHeartbeat() {
        scope.launch {
            while (true) {
                sendHeartbeat()
                delay(60000)
            }
        }
    }

    private fun sendHeartbeat() {
        scope.launch {
            val prefix = appPreferences.topicPrefix.first() ?: return@launch
            val androidId = android.provider.Settings.Secure.getString(appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            val deviceId = "${android.os.Build.MODEL}_$androidId"
            
            val device = Device(
                id = deviceId,
                name = android.os.Build.MODEL,
                batteryLevel = getBatteryLevel(),
                isOnline = true,
                lastSeen = System.currentTimeMillis()
            )
            
            val json = Json.encodeToString(device)
            mqttManager.publish("$prefix/status/$deviceId", json.toByteArray())
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = appContext.registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level != -1 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 0
        } catch (e: Exception) { 0 }
    }

    fun publishEvent(type: String, data: String) {
        val timestamp = System.currentTimeMillis()
        MqttLogger.logEvent(EventRecord(type, data, timestamp, SyncStatus.PENDING))

        scope.launch {
            val key = appPreferences.sharedKey.first() ?: return@launch
            val prefix = appPreferences.topicPrefix.first() ?: return@launch
            
            val encryptionManager = EncryptionManager(key)
            val payload = "$type|${android.os.Build.MODEL}|$data"
            val encrypted = encryptionManager.encrypt(payload) ?: return@launch
            
            mqttManager.publish("$prefix/events/$type", encrypted.toByteArray()).thenAccept {
                MqttLogger.updateEventStatus(timestamp, SyncStatus.SENT)
            }.exceptionally {
                MqttLogger.updateEventStatus(timestamp, SyncStatus.FAILED, it.message)
                null
            }
        }
    }
    
    fun getMqttManager() = mqttManager
}
