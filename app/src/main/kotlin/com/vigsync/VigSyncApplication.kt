package com.vigsync

import android.app.Application
import android.util.Log
import com.vigsync.core.SyncManager
import com.vigsync.core.mqtt.MqttManager

class VigSyncApplication : Application() {
    
    // Strict Singleton enforced by Application Lifecycle
    lateinit var syncManager: SyncManager
        private set

    lateinit var crashHandler: com.vigsync.core.GlobalCrashHandler
        private set

    lateinit var mqttManager: MqttManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        crashHandler = com.vigsync.core.GlobalCrashHandler(this)
        
        Log.d("VigSync", "Application: onCreate() - Initializing")
        crashHandler.checkAndLogLastCrash()

        syncManager = SyncManager.getInstance(this)
        mqttManager = MqttManager.getInstance(this)

        // Trigger auto-connect if last connection was successful
        mqttManager.autoConnectIfNeeded()
    }

    companion object {
        private var instance: VigSyncApplication? = null
        fun getInstance(): VigSyncApplication = instance!!
    }
}
