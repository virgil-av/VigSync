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
            val pendingResult = goAsync()
            val prefs = AppPreferences(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val action = BootRecoveryPolicy.action(
                        serviceEnabled = prefs.serviceEnabled.first(),
                        syncEnabled = prefs.syncEnabled.first(),
                        brokerUrl = prefs.brokerUrl.first(),
                        sharedKey = prefs.sharedKey.first()
                    )

                    val serviceAction = when (action) {
                        BootRecoveryAction.NONE -> null
                        BootRecoveryAction.START_SERVICE -> MqttService.ACTION_START
                        BootRecoveryAction.START_SYNC -> MqttService.ACTION_START_SYNC
                    }

                    if (serviceAction != null) {
                        val serviceIntent = Intent(context, MqttService::class.java).apply {
                            this.action = serviceAction
                        }
                        context.startForegroundService(serviceIntent)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
