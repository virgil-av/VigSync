package com.vigsync.core

import android.content.Context
import android.content.IntentFilter
import android.util.Log
import com.vigsync.core.crypto.EncryptionManager
import com.vigsync.core.models.RawMessage
import com.vigsync.data.local.EventEntity
import com.vigsync.data.local.VigSyncDatabase
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

class SyncManager private constructor(context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = context.applicationContext
    private val eventExporter = EventExporter(context)
    private val serverConfigManager = ServerConfigManager(context)
    private val statusProvider = DeviceStatusProvider(context)
    private val appPreferences = AppPreferences(context)

    private val eventBuffer = ConcurrentHashMap<String, BufferedEvent>()
    private val BUFFER_WINDOW = 5000L // 5 seconds
    private var periodicStatusJob: Job? = null

    data class BufferedEvent(
        val type: String,
        val data: String,
        val timestamp: Long,
        val priority: Int,
        var job: Job? = null
    )

    private val eventDebounceCache = ConcurrentHashMap<String, Long>()
    private val DEBOUNCE_WINDOW = 5000L // 5 seconds

    private val database: VigSyncDatabase by lazy { 
        VigSyncDatabase.getInstance(appContext!!) 
    }

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
        Log.d("SyncManager", "Local SyncManager Initialized")
        exportCurrentStatus() 
        startPeriodicStatusUpdates()
    }

    private fun startPeriodicStatusUpdates() {
        periodicStatusJob?.cancel()
        periodicStatusJob = scope.launch {
            while (isActive) {
                exportCurrentStatus()
                delay(60 * 1000) 
            }
        }
    }

    fun exportCurrentStatus() {
        scope.launch {
            val msg = RawMessage(
                senderId = getLocalDeviceId(),
                senderName = android.os.Build.MODEL,
                batteryLevel = statusProvider.getBatteryLevel(),
                timestamp = System.currentTimeMillis()
            )
            eventExporter.exportMessage("status", msg)
        }
    }

    fun getLocalDeviceId(): String {
        val androidId = android.provider.Settings.Secure.getString(appContext!!.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        return "${android.os.Build.MODEL}_$androidId"
    }

    fun publishEvent(type: String, data: String) {
        val now = System.currentTimeMillis()
        val eventKey = "${type}_$data"
        
        // 1. Global Debounce
        val lastSeen = eventDebounceCache[eventKey] ?: 0L
        if (now - lastSeen < DEBOUNCE_WINDOW) {
            Log.d("SyncManager", "Ignored duplicate $type event within window")
            return
        }
        eventDebounceCache[eventKey] = now

        val eventId = "${type}_${data.hashCode()}"
        
        if (type == "CALL" || type.contains("MISSED")) {
            saveEventLocally(type, data, System.currentTimeMillis())
            return
        }

        val buffered = BufferedEvent(type, data, System.currentTimeMillis(), 0)
        eventBuffer[eventId] = buffered
        scheduleBufferPublication(eventId)
    }

    private fun scheduleBufferPublication(id: String) {
        eventBuffer[id]?.job?.cancel()
        val job = scope.launch {
            delay(BUFFER_WINDOW)
            eventBuffer[id]?.let { 
                saveEventLocally(it.type, it.data, it.timestamp)
                eventBuffer.remove(id)
            }
        }
        eventBuffer[id]?.job = job
    }

    private fun saveEventLocally(type: String, data: String, timestamp: Long) {
        scope.launch {
            val event = EventEntity(
                type = type,
                data = data,
                timestamp = timestamp,
                syncStatus = com.vigsync.core.models.SyncStatus.SENT,
                direction = com.vigsync.core.models.EventDirection.LOCAL,
                sourceDevice = "Local Device",
                payloadHash = "${type}_${data}_${timestamp}".hashCode().toString()
            )
            database.dao().insertEvent(event)
            
            val sharedKey = appPreferences.sharedKey.first()
            val encryptedData = if (!sharedKey.isNullOrEmpty()) {
                EncryptionManager(sharedKey).encrypt(data) ?: data
            } else data
            
            val msg = RawMessage(
                type = type,
                data = encryptedData,
                senderId = getLocalDeviceId(),
                senderName = android.os.Build.MODEL,
                timestamp = timestamp,
                batteryLevel = statusProvider.getBatteryLevel()
            )
            eventExporter.exportMessage("events", msg)
            
            Log.d("SyncManager", "Event exported: $type")
        }
    }

    fun stop() {
        periodicStatusJob?.cancel()
        eventBuffer.values.forEach { it.job?.cancel() }
        eventBuffer.clear()
        Log.d("SyncManager", "Local Stop triggered")
    }

    fun getEventExporter() = eventExporter
    fun getServerConfigManager() = serverConfigManager

    fun getDao() = database.dao()
}
