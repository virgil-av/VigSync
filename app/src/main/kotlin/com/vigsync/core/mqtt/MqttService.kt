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

        val runtimeState = MqttServiceRuntime.state
        fun isServiceRunning() = runtimeState.value.isRunning
        fun isSyncActive() = runtimeState.value.isSyncing
    }

    private val CHANNEL_ID = "VigSyncMqttChannel"
    private val NOTIFICATION_ID = 1
    private val NETWORK_RECOVERY_DEBOUNCE_MS = 2_000L
    private var callLogObserver: CallLogObserver? = null
    private var lastNetworkRecoveryAtMs = 0L

    // Dual-SIM management: CRITICAL - Keep strong references to prevent GC
    private val subscriptionListeners = ConcurrentHashMap<Int, Any>()
    private lateinit var subscriptionManager: SubscriptionManager

    private val subChangedListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            Log.d("MqttService", "Subscriptions changed, refreshing listeners")
            if (runtimeState.value.isSyncing) {
                registerSubscriptionListeners()
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            handleNetworkCapabilities(connectivityManager.getNetworkCapabilities(network))
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            handleNetworkCapabilities(capabilities)
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

        updateRuntime(lastAction = "created")
    }

    override fun onDestroy() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w("MqttService", "Network callback was not registered", e)
        }
        subscriptionManager.removeOnSubscriptionsChangedListener(subChangedListener)

        SyncManager.getInstance(applicationContext).stop()
        stopObservers()
        MqttServiceRuntime.reset()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SyncManager.getInstance(applicationContext).start()
        updateRuntime(isRunning = true, lastAction = intent?.action ?: "restart")
        
        when (intent?.action) {
            ACTION_START -> updateForeground()
            ACTION_STOP -> {
                stopObservers()
                SyncManager.getInstance(applicationContext).stop()
                updateRuntime(isRunning = false, isSyncing = false, observersActive = false, lastAction = ACTION_STOP)
                stopSelf()
            }
            ACTION_START_SYNC -> startObservers()
            ACTION_STOP_SYNC -> stopObservers()
            null -> {
                updateForeground()
                if (runtimeState.value.isSyncing) {
                    updateRuntime(isSyncing = false, observersActive = false, lastAction = "restart")
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
        if (runtimeState.value.isSyncing) return
        updateRuntime(isSyncing = true, observersActive = true, lastAction = ACTION_START_SYNC)
        updateForeground()
        
        val canReadCallLog = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canReadCallLog) {
            callLogObserver = CallLogObserver(this).also { it.register() }
        } else {
            Log.w("MqttService", "Call log observer inactive: READ_CALL_LOG denied")
        }

        registerSubscriptionListeners()
        updateForeground()
    }

    private fun registerSubscriptionListeners() {
        try {
            val canReadPhone = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!canReadPhone) {
                Log.w("MqttService", "Cannot register SIM listeners: READ_PHONE_STATE denied")
                return
            }

            val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (activeSubscriptions.isNullOrEmpty()) {
                Log.i("MqttService", "No active SIMs found, registering Default listener as fallback")
                registerListenerForSubId(telephonyManager, -1) 
                return
            }

            for (info in activeSubscriptions) {
                val subId = info.subscriptionId
                Log.d("MqttService", "Found Active SIM: $subId (${info.carrierName})")
                registerListenerForSubId(telephonyManager, subId)
            }
        } catch (e: Exception) {
            Log.e("MqttService", "Failed to register sub listeners", e)
        }
    }

    private fun registerListenerForSubId(baseManager: TelephonyManager, subId: Int) {
        if (subscriptionListeners.containsKey(subId)) return

        val subTelephonyManager = if (subId == -1) baseManager else baseManager.createForSubscriptionId(subId)
        
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
        Log.d("MqttService", "Registered Telephony listener for SubId: $subId")
    }

    private fun handleCallStateChange(state: Int, subId: Int) {
        if (state == TelephonyManager.CALL_STATE_IDLE) {
            Log.d("MqttService", "SIM $subId went IDLE, prompting log check")
            callLogObserver?.processNewCalls()
        }
    }

    private fun stopObservers() {
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
                Log.e("MqttService", "Error unregistering listener for $subId", e)
            }
        }
        subscriptionListeners.clear()

        updateRuntime(isSyncing = false, observersActive = false, lastAction = ACTION_STOP_SYNC)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "VigSync MQTT Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val state = runtimeState.value
        val text = when {
            state.isSyncing && state.networkAvailable -> "Sync engine, observers, and network active"
            state.isSyncing -> "Sync engine and event observers active"
            state.isRunning && state.networkAvailable -> "MQTT sync engine active"
            state.isRunning -> "MQTT sync engine waiting for network"
            else -> "Starting sync engine..."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VigSync Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }

    private fun handleNetworkCapabilities(capabilities: NetworkCapabilities?) {
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val now = System.currentTimeMillis()
        updateRuntime(networkAvailable = hasInternet, lastAction = if (hasInternet) "network_available" else "network_unavailable")

        if (NetworkRecoveryPolicy.shouldReconnect(
                hasUsableInternet = hasInternet,
                nowMs = now,
                lastReconnectAtMs = lastNetworkRecoveryAtMs,
                debounceMs = NETWORK_RECOVERY_DEBOUNCE_MS
            )
        ) {
            lastNetworkRecoveryAtMs = now
            SyncManager.getInstance(applicationContext).handleNetworkChange()
        }
        updateForeground()
    }

    private fun updateRuntime(
        isRunning: Boolean? = null,
        isSyncing: Boolean? = null,
        observersActive: Boolean? = null,
        networkAvailable: Boolean? = null,
        lastAction: String? = null
    ) {
        MqttServiceRuntime.reduce { state ->
            state.copy(
                isRunning = isRunning ?: state.isRunning,
                isSyncing = isSyncing ?: state.isSyncing,
                observersActive = observersActive ?: state.observersActive,
                networkAvailable = networkAvailable ?: state.networkAvailable,
                lastAction = lastAction ?: state.lastAction
            )
        }
    }
}
