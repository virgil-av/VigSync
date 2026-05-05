package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.vigsync.core.SyncManager
import com.vigsync.core.mqtt.MqttLogger
import kotlinx.coroutines.flow.map

import androidx.lifecycle.viewModelScope
import com.vigsync.core.mqtt.MqttService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import com.vigsync.data.local.VigSyncDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val syncManager = SyncManager.getInstance(application)
    private val database = VigSyncDatabase.getInstance(application)
    private val appPreferences = com.vigsync.data.prefs.AppPreferences(application)
    
    val connectionStatus = syncManager.getMqttManager().connectionStatus
    
    val localDeviceId = syncManager.getLocalDeviceId()
    val pairedDevices = database.dao().getAllDeviceStatus()
        .map { list -> list.filter { it.deviceId != localDeviceId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentEvents = MqttLogger.logs.map { it.filter { entry -> entry.status == "RECEIVED" || entry.status == "SENT" } }

    private val _isServiceRunning = MutableStateFlow<Boolean>(MqttService.isServiceRunning())
    val isServiceRunning = _isServiceRunning.asStateFlow()

    private val _isSyncActive = MutableStateFlow<Boolean>(MqttService.isSyncActive())
    val isSyncActive = _isSyncActive.asStateFlow()

    val shareCalls = appPreferences.notifCalls
    val shareSms = appPreferences.notifSms
    val shareNotifications = appPreferences.notifOther

    fun updateSharingPreferences(calls: Boolean, sms: Boolean, notifications: Boolean) {
        viewModelScope.launch {
            appPreferences.saveNotifSettings(calls, sms, notifications)
        }
    }

    init {
        // Ensure service is running for MQTT if we have config
        viewModelScope.launch {
            if (appPreferences.brokerUrl.first().isNotEmpty()) {
                val intent = android.content.Intent(getApplication(), MqttService::class.java).apply {
                    action = MqttService.ACTION_START
                }
                getApplication<Application>().startForegroundService(intent)
                
                // If sync was enabled previously, start it
                if (appPreferences.syncEnabled.first()) {
                    val syncIntent = android.content.Intent(getApplication(), MqttService::class.java).apply {
                        action = MqttService.ACTION_START_SYNC
                    }
                    getApplication<Application>().startForegroundService(syncIntent)
                }
            }
        }
    }

    fun removeDevice(deviceId: String) {
        syncManager.removeDevice(deviceId)
    }

    fun renameDevice(deviceId: String, newLabel: String) {
        viewModelScope.launch {
            database.dao().updateDeviceLabel(deviceId, newLabel.ifBlank { null })
        }
    }

    fun getEventCount(deviceName: String) = database.dao().getEventCountForDevice(deviceName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun toggleSync(permissionsGranted: Boolean = true) {
        val intent = android.content.Intent(getApplication(), MqttService::class.java)
        viewModelScope.launch {
            if (isSyncActive.value) {
                intent.action = MqttService.ACTION_STOP_SYNC
                getApplication<Application>().startForegroundService(intent)
                appPreferences.saveSyncEnabled(false)
            } else if (permissionsGranted) {
                // Ensure the main service is also running
                val startIntent = android.content.Intent(getApplication(), MqttService::class.java).apply {
                    action = MqttService.ACTION_START
                }
                getApplication<Application>().startForegroundService(startIntent)
                
                intent.action = MqttService.ACTION_START_SYNC
                getApplication<Application>().startForegroundService(intent)
                appPreferences.saveSyncEnabled(true)
            }
            
            delay(800)
            _isSyncActive.value = MqttService.isSyncActive()
            _isServiceRunning.value = MqttService.isServiceRunning()
        }
    }

    fun toggleService(permissionsGranted: Boolean = true) {
        // This is now more about the whole MQTT service
        val intent = android.content.Intent(getApplication(), MqttService::class.java)
        if (isServiceRunning.value) {
            intent.action = MqttService.ACTION_STOP
            getApplication<Application>().stopService(intent)
        } else if (permissionsGranted) {
            intent.action = MqttService.ACTION_START
            getApplication<Application>().startForegroundService(intent)
        }
        
        viewModelScope.launch {
            delay(500)
            _isServiceRunning.value = MqttService.isServiceRunning()
            _isSyncActive.value = MqttService.isSyncActive()
        }
    }
}
