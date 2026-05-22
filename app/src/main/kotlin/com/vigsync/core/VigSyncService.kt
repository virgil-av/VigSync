package com.vigsync.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vigsync.R
import com.vigsync.feature.capture.CallLogObserver
import java.util.concurrent.ConcurrentHashMap

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

    // Dual-SIM management: CRITICAL - Keep strong references to prevent GC
    private val subscriptionListeners = ConcurrentHashMap<Int, Any>()
    private lateinit var subscriptionManager: SubscriptionManager

    private val subChangedListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            Log.d("VigSyncService", "Subscriptions changed, refreshing listeners")
            if (isMonitoring) {
                registerSubscriptionListeners()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        subscriptionManager.addOnSubscriptionsChangedListener(subChangedListener)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onDestroy() {
        subscriptionManager.removeOnSubscriptionsChangedListener(subChangedListener)
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
            ACTION_STOP -> {
                stopMonitoring()
                SyncManager.getInstance(applicationContext).stop()
                stopSelf()
            }
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
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    private fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true
        updateForeground()
        
        val canReadCallLog = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canReadCallLog) {
            callLogObserver = CallLogObserver(this).also { it.register() }
        } else {
            Log.w("VigSyncService", "Call log observer inactive: READ_CALL_LOG denied")
        }

        registerSubscriptionListeners()
        updateForeground()
        Log.d("VigSyncService", "Monitoring active (Multi-SIM observers started)")
    }

    private fun registerSubscriptionListeners() {
        try {
            val canReadPhone = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!canReadPhone) {
                Log.w("VigSyncService", "Cannot register SIM listeners: READ_PHONE_STATE denied")
                return
            }

            val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (activeSubscriptions.isNullOrEmpty()) {
                Log.i("VigSyncService", "No active SIMs found, registering Default listener as fallback")
                registerListenerForSubId(telephonyManager, -1) 
                return
            }

            for (info in activeSubscriptions) {
                val subId = info.subscriptionId
                Log.d("VigSyncService", "Found Active SIM: $subId (${info.carrierName})")
                registerListenerForSubId(telephonyManager, subId)
            }
        } catch (e: Exception) {
            Log.e("VigSyncService", "Failed to register sub listeners", e)
        }
    }

    private fun registerListenerForSubId(baseManager: TelephonyManager, subId: Int) {
        if (subscriptionListeners.containsKey(subId)) return

        val subTelephonyManager = if (subId == -1) baseManager else baseManager.createForSubscriptionId(subId)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    if (state == TelephonyManager.CALL_STATE_IDLE) {
                        Log.d("VigSyncService", "SIM $subId went IDLE, prompting log check")
                        callLogObserver?.processNewCalls()
                    }
                }
            }
            subTelephonyManager.registerTelephonyCallback(mainExecutor, callback)
            subscriptionListeners[subId] = callback
        } else {
            val listener = object : android.telephony.PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    if (state == TelephonyManager.CALL_STATE_IDLE) {
                        Log.d("VigSyncService", "SIM $subId went IDLE, prompting log check")
                        callLogObserver?.processNewCalls()
                    }
                }
            }
            subTelephonyManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            subscriptionListeners[subId] = listener
        }
        Log.d("VigSyncService", "Registered Telephony listener for SubId: $subId")
    }

    private fun stopMonitoring() {
        callLogObserver?.unregister()
        callLogObserver = null
        
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        for ((subId, listener) in subscriptionListeners) {
            try {
                val subTelephonyManager = if (subId == -1) telephonyManager else telephonyManager.createForSubscriptionId(subId)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && listener is TelephonyCallback) {
                    subTelephonyManager.unregisterTelephonyCallback(listener)
                } else if (listener is android.telephony.PhoneStateListener) {
                    subTelephonyManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_NONE)
                }
            } catch (e: Exception) {
                Log.e("VigSyncService", "Error unregistering listener for $subId", e)
            }
        }
        subscriptionListeners.clear()
        
        isMonitoring = false
        Log.d("VigSyncService", "Monitoring stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "VigSync Monitoring Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
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
