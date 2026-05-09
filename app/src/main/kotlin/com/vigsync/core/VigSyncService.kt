package com.vigsync.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vigsync.R
import com.vigsync.feature.capture.CallLogObserver

class VigSyncService : Service() {

    companion object {
        const val ACTION_START = "com.vigsync.app.START_SERVICE"
        const val ACTION_STOP = "com.vigsync.app.STOP_SERVICE"
        const val ACTION_START_MONITORING = "com.vigsync.app.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.vigsync.app.STOP_MONITORING"
        
        private var isRunning = false
        fun isServiceRunning() = isRunning

        private var isMonitoring = false
        fun isMonitoringActive() = isMonitoring
    }

    private val CHANNEL_ID = "VigSyncServiceChannel"
    private val NOTIFICATION_ID = 1
    private var callLogObserver: CallLogObserver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                createNotification(), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onDestroy() {
        stopMonitoring()
        isRunning = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        
        when (intent?.action) {
            ACTION_START -> {
                Log.d("VigSyncService", "Service Started")
                updateForeground()
            }
            ACTION_STOP -> stopSelf()
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
            null -> {
                updateForeground()
                if (isMonitoring) {
                    isMonitoring = false 
                    startMonitoring()
                }
            }
        }
        return START_STICKY
    }

    private fun updateForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                createNotification(), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    private fun startMonitoring() {
        if (isMonitoring) return
        
        updateForeground()
        
        val canReadCallLog = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CALL_LOG
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (canReadCallLog) {
            callLogObserver = CallLogObserver(this).also { it.register() }
            Log.d("VigSyncService", "Monitoring active (Observers started)")
        } else {
            Log.w("VigSyncService", "Monitoring partially active (Call Log missing permission)")
        }
        
        isMonitoring = true
    }

    private fun stopMonitoring() {
        callLogObserver?.unregister()
        callLogObserver = null
        isMonitoring = false
        Log.d("VigSyncService", "Monitoring stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "VigSync Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val text = if (isMonitoring) "Local monitoring active" else "Service active"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VigSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }
}
