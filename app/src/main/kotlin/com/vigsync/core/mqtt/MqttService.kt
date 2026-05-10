package com.vigsync.core.mqtt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.vigsync.R
import com.vigsync.core.SyncManager
import com.vigsync.feature.capture.CallLogObserver
import java.util.concurrent.ConcurrentHashMap

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

    // Dual-SIM management
    private val subscriptionListeners = ConcurrentHashMap<Int, Any>()
    private lateinit var subscriptionManager: SubscriptionManager

    private val subChangedListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            Log.d("MqttService", "Subscriptions changed, restarting observers")
            if (isSyncing) {
                stopObservers()
                startObservers()
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            SyncManager.getInstance(applicationContext).handleNetworkChange()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                SyncManager.getInstance(applicationContext).handleNetworkChange()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        }
        
        subscriptionManager.addOnSubscriptionsChangedListener(subChangedListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onDestroy() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(networkCallback)
        subscriptionManager.removeOnSubscriptionsChangedListener(subChangedListener)

        SyncManager.getInstance(applicationContext).cancelHeartbeat()
        stopObservers()
        isRunning = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SyncManager.getInstance(applicationContext).start()
        isRunning = true
        
        when (intent?.action) {
            ACTION_START -> updateForeground()
            ACTION_STOP -> stopSelf()
            ACTION_START_SYNC -> startObservers()
            ACTION_STOP_SYNC -> stopObservers()
            null -> {
                updateForeground()
                if (isSyncing) {
                    isSyncing = false
                    startObservers()
                }
            }
        }
        return START_STICKY
    }

    private fun updateForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    private fun startObservers() {
        if (isSyncing) return
        updateForeground()
        
        val canReadCallLog = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canReadCallLog) {
            callLogObserver = CallLogObserver(this).also { it.register() }
        }

        // Register per-subscription listeners for Call State
        registerSubscriptionListeners()
        
        isSyncing = true
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
                            handleCallStateChange(state, subId)
                        }
                    }
                    subTelephonyManager.registerTelephonyCallback(mainExecutor, callback)
                    subscriptionListeners[subId] = callback
                } else {
                    val listener = object : android.telephony.PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            handleCallStateChange(state, subId)
                        }
                    }
                    subTelephonyManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
                    subscriptionListeners[subId] = listener
                }
                Log.d("MqttService", "Registered call observer for subId: $subId (Carrier: ${info.carrierName})")
            }
        } catch (e: Exception) {
            Log.e("MqttService", "Failed to register sub listeners", e)
        }
    }

    private fun handleCallStateChange(state: Int, subId: Int) {
        if (state == TelephonyManager.CALL_STATE_IDLE) {
            // Prompt log check when any SIM goes idle
            callLogObserver?.processNewCalls()
        }
    }

    private fun stopObservers() {
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
        
        isSyncing = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "VigSync MQTT Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
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
