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

    // Dual-SIM management
    private val subscriptionListeners = ConcurrentHashMap<Int, Any>()
    private lateinit var subscriptionManager: SubscriptionManager

    private val subChangedListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            Log.d("VigSyncService", "Subscriptions changed, restarting observers")
            if (isMonitoring) {
                stopMonitoring()
                startMonitoring()
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
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    private fun startMonitoring() {
        if (isMonitoring) return
        updateForeground()
        
        val canReadCallLog = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canReadCallLog) {
            callLogObserver = CallLogObserver(this).also { it.register() }
        }

        registerSubscriptionListeners()
        isMonitoring = true
        Log.d("VigSyncService", "Monitoring active (Multi-SIM observers started)")
    }

    private fun registerSubscriptionListeners() {
        try {
            val canReadPhone = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!canReadPhone) return

            val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList ?: return
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            for (info in activeSubscriptions) {
                val subId = info.subscriptionId
                if (subscriptionListeners.containsKey(subId)) continue

                val subTelephonyManager = telephonyManager.createForSubscriptionId(subId)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            if (state == TelephonyManager.CALL_STATE_IDLE) {
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
                                callLogObserver?.processNewCalls()
                            }
                        }
                    }
                    subTelephonyManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
                    subscriptionListeners[subId] = listener
                }
                Log.d("VigSyncService", "Registered call observer for subId: $subId (${info.carrierName})")
            }
        } catch (e: Exception) {
            Log.e("VigSyncService", "Failed to register sub listeners", e)
        }
    }

    private fun stopMonitoring() {
        callLogObserver?.unregister()
        callLogObserver = null
        
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        for ((subId, listener) in subscriptionListeners) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && listener is TelephonyCallback) {
                telephonyManager.createForSubscriptionId(subId).unregisterTelephonyCallback(listener)
            } else if (listener is android.telephony.PhoneStateListener) {
                telephonyManager.createForSubscriptionId(subId).listen(listener, android.telephony.PhoneStateListener.LISTEN_NONE)
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
