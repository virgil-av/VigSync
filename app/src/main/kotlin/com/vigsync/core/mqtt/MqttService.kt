package com.vigsync.core.mqtt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vigsync.R

import com.vigsync.core.SyncManager

import com.vigsync.feature.capture.CallLogObserver

class MqttService : Service() {

    companion object {
        const val ACTION_START = "com.vigsync.app.START_OBSERVERS"
        const val ACTION_STOP = "com.vigsync.app.STOP_OBSERVERS"
        private var isRunning = false
        fun isServiceRunning() = isRunning
    }

    private val CHANNEL_ID = "VigSyncMqttChannel"
    private val NOTIFICATION_ID = 1
    private var callLogObserver: CallLogObserver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Android 14+ requires calling startForeground immediately
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onDestroy() {
        stopObservers()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always ensure SyncManager is running when service is active
        SyncManager.getInstance(applicationContext).start()
        
        when (intent?.action) {
            ACTION_START -> startObservers()
            ACTION_STOP -> stopSelf()
            null -> {
                // If system restarts service, ensure it's in foreground and observers are active if they were before
                startForeground(NOTIFICATION_ID, createNotification())
                if (isRunning) {
                    isRunning = false // reset to allow restart
                    startObservers()
                }
            }
        }
        return START_STICKY
    }

    private fun startObservers() {
        if (isRunning) return
        
        // Ensure foreground is started even if we don't have all permissions
        startForeground(NOTIFICATION_ID, createNotification())
        
        val canReadCallLog = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CALL_LOG
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (canReadCallLog) {
            callLogObserver = CallLogObserver(this).also { it.register() }
            MqttLogger.log("Foreground observers started (Call Log active)", "SUCCESS")
        } else {
            MqttLogger.log("Foreground observers started (Call Log DISABLED - missing permission)", "WARNING")
        }
        
        isRunning = true
    }

    private fun stopObservers() {
        callLogObserver?.unregister()
        callLogObserver = null
        isRunning = false
        MqttLogger.log("Foreground observers stopped", "INFO")
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
