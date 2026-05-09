package com.vigsync.core

import android.content.Context
import android.util.Log
import com.vigsync.core.models.RawMessage
import com.vigsync.data.local.EventEntity
import com.vigsync.data.local.VigSyncDatabase
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class SyncManager private constructor(context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = context.applicationContext
    private val eventExporter = EventExporter(context)
    private val serverConfigManager = ServerConfigManager(context)
    private val statusProvider = DeviceStatusProvider(context)

    private val eventBuffer = ConcurrentHashMap<String, BufferedEvent>()
    private val BUFFER_WINDOW = 5000L // 5 seconds

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
        startPeriodicStatusUpdates()
    }

    private fun startPeriodicStatusUpdates() {
        scope.launch {
            while (isActive) {
                exportCurrentStatus()
                delay(5 * 60 * 1000) // Every 5 minutes
            }
        }
    }

    fun exportCurrentStatus() {
        scope.launch {
            val msg = RawMessage(
                senderId = getLocalDeviceId(),
                senderName = android.os.Build.MODEL,
                batteryLevel = statusProvider.getBatteryLevel(),
                isCharging = statusProvider.isCharging(),
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
        val eventId = "${type}_${data.hashCode()}"
        
        if (type == "CALL") {
            saveEventLocally(type, data, System.currentTimeMillis())
            return
        }

        scheduleBufferPublication(eventId)
        
        val buffered = BufferedEvent(type, data, System.currentTimeMillis(), 0)
        eventBuffer[eventId] = buffered
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
            
            val msg = RawMessage(
                type = type,
                data = data,
                senderId = getLocalDeviceId(),
                senderName = android.os.Build.MODEL,
                timestamp = timestamp,
                batteryLevel = statusProvider.getBatteryLevel(),
                isCharging = statusProvider.isCharging()
            )
            eventExporter.exportMessage("events", msg)
            
            Log.d("SyncManager", "Event saved and exported: $type")
        }
    }

    fun getEventExporter() = eventExporter
    fun getServerConfigManager() = serverConfigManager

    fun getDao() = database.dao()
}
