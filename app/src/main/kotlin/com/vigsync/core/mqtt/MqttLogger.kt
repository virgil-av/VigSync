package com.vigsync.core.mqtt

import android.content.Context
import android.util.Log
import com.vigsync.data.local.MqttLogEntity
import com.vigsync.data.local.SystemLogEntity
import com.vigsync.data.local.VigSyncDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MqttLogger private constructor(context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val database = VigSyncDatabase.getInstance(context.applicationContext)
    private val debugDao = database.debugDao()

    companion object {
        @Volatile
        private var INSTANCE: MqttLogger? = null

        fun getInstance(context: Context): MqttLogger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MqttLogger(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun logMessage(topic: String, payload: String, isIncoming: Boolean) {
        scope.launch {
            val log = MqttLogEntity(
                topic = topic,
                payload = payload,
                isIncoming = isIncoming
            )
            debugDao.insertMqttLog(log)
            Log.d("MqttLogger", "${if (isIncoming) "RECV" else "SENT"} [$topic]: $payload")
        }
    }

    fun logSystemEvent(event: String, details: String? = null, isError: Boolean = false) {
        scope.launch {
            val log = SystemLogEntity(
                event = event,
                details = details,
                isError = isError
            )
            debugDao.insertSystemLog(log)
            if (isError) {
                Log.e("MqttLogger", "SYSTEM ERROR: $event - $details")
            } else {
                Log.i("MqttLogger", "SYSTEM EVENT: $event - $details")
            }
        }
    }
}
