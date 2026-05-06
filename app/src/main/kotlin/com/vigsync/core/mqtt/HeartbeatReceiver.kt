package com.vigsync.core.mqtt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vigsync.core.SyncManager

class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MqttLogger.logApp("Alarm triggered: Sending heartbeat", "TRACE")
        val syncManager = SyncManager.getInstance(context)
        
        // 1. Send the actual MQTT heartbeat
        syncManager.sendHeartbeat()
        
        // 2. Schedule the next one to keep the loop going
        syncManager.scheduleNextHeartbeat()
    }
}
