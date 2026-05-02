package com.vigsync

import android.app.Application
import com.vigsync.core.SyncManager
import com.vigsync.core.mqtt.MqttLogger

class VigSyncApplication : Application() {
    
    // Strict Singleton enforced by Application Lifecycle
    lateinit var syncManager: SyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        MqttLogger.logApp("Application: onCreate() - Initializing strict singleton", "TRACE")
        syncManager = SyncManager.getInstance(this)
        MqttLogger.logApp("Application: SyncManager hash: ${syncManager.hashCode()}", "TRACE")
    }

    companion object {
        private var instance: VigSyncApplication? = null
        fun getInstance(): VigSyncApplication = instance!!
    }
}
