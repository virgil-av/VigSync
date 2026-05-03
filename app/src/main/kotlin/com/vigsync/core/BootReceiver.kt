package com.vigsync.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vigsync.core.mqtt.MqttService
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = AppPreferences(context)
            CoroutineScope(Dispatchers.IO).launch {
                // Only start if it was previously active (heuristically)
                // For now, if we have a broker URL and shared key, we likely want to be active
                val url = prefs.brokerUrl.first()
                val key = prefs.sharedKey.first()
                
                if (url.isNotEmpty() && key != null) {
                    val serviceIntent = Intent(context, MqttService::class.java).apply {
                        action = MqttService.ACTION_START
                    }
                    context.startForegroundService(serviceIntent)
                }
            }
        }
    }
}
