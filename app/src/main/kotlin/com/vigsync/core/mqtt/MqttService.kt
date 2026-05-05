package com.vigsync.core.mqtt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vigsync.R

import com.vigsync.core.SyncManager

import com.vigsync.feature.capture.CallLogObserver

class MqttService : Service() {

    companion object {
        const val ACTION_START = "com.vigsync.app.START_SERVICE"
        const val ACTION_STOP = "com.vigsync.app.STOP_SERVICE"
        const val ACTION_START_SYNC = "com.vigsync.app.START_OBSERVERS"
        const val ACTION_STOP_SYNC = "com.vigsync.app.STOP_OBSERVERS"
        
        private var isRunning = false
        fun isServiceRunning() = isRunning

        private var isSyncing = false
        fun isSyncActive() = isSyncing
    }

    private val CHANNEL_ID = "VigSyncMqttChannel"
    private val NOTIFICATION_ID = 1
    private var callLogObserver: CallLogObserver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Android 14+ requires calling startForeground immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                createNotification(), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onDestroy() {
        stopObservers()
        isRunning = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always ensure SyncManager is running when service is active
        SyncManager.getInstance(applicationContext).start()
        isRunning = true
        
        when (intent?.action) {
            ACTION_START -> {
                MqttLogger.log("Service Started: Maintaining connection", "INFO")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID, 
                        createNotification(), 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                }
            }
            ACTION_STOP -> stopSelf()
            ACTION_START_SYNC -> startObservers()
            ACTION_STOP_SYNC -> stopObservers()
            null -> {
                // If system restarts service, ensure it's in foreground
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID, 
                        createNotification(), 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                }
                if (isSyncing) {
                    isSyncing = false // reset to allow restart
                    startObservers()
                }
            }
        }
        return START_STICKY
    }

    private fun startObservers() {
        if (isSyncing) return
        
        // Ensure foreground is started
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                createNotification(), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        val canReadCallLog = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CALL_LOG
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (canReadCallLog) {
            callLogObserver = CallLogObserver(this).also { it.register() }
            MqttLogger.log("Sync active (Observers started)", "SUCCESS")
        } else {
            MqttLogger.log("Sync active (Call Log DISABLED - missing permission)", "WARNING")
        }
        
        isSyncing = true
    }

    private fun stopObservers() {
        callLogObserver?.unregister()
        callLogObserver = null
        isSyncing = false
        MqttLogger.log("Sync stopped (Observers detached)", "INFO")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "VigSync MQTT Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VigSync Active")
            .setContentText("Synchronizing events via MQTT...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }
}
