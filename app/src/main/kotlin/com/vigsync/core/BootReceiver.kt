package com.vigsync.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
                if (prefs.syncEnabled.first()) {
                    val serviceIntent = Intent(context, VigSyncService::class.java).apply {
                        action = VigSyncService.ACTION_START
                    }
                    context.startForegroundService(serviceIntent)
                    
                    val monitorIntent = Intent(context, VigSyncService::class.java).apply {
                        action = VigSyncService.ACTION_START_MONITORING
                    }
                    context.startForegroundService(monitorIntent)
                }
            }
        }
    }
}
