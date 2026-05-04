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
import kotlinx.coroutines.flow.stateIn

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val syncManager = SyncManager.getInstance(application)
    private val database = VigSyncDatabase.getInstance(application)
    
    val connectionStatus = syncManager.getMqttManager().connectionStatus
    
    val localDeviceId = syncManager.getLocalDeviceId()
    val pairedDevices = database.dao().getAllDeviceStatus()
        .map { list -> list.filter { it.deviceId != localDeviceId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentEvents = MqttLogger.logs.map { it.filter { entry -> entry.status == "RECEIVED" || entry.status == "SENT" } }

    private val _isServiceRunning = MutableStateFlow<Boolean>(MqttService.isServiceRunning())
    val isServiceRunning = _isServiceRunning.asStateFlow()

    fun removeDevice(deviceId: String) {
        syncManager.removeDevice(deviceId)
    }

    fun renameDevice(deviceId: String, newLabel: String) {
        viewModelScope.launch {
            database.dao().updateDeviceLabel(deviceId, newLabel.ifBlank { null })
        }
    }

    fun toggleService(permissionsGranted: Boolean = true) {
        val intent = android.content.Intent(getApplication(), MqttService::class.java)
        if (isServiceRunning.value) {
            intent.action = MqttService.ACTION_STOP
            getApplication<Application>().stopService(intent)
        } else if (permissionsGranted) {
            intent.action = MqttService.ACTION_START
            getApplication<Application>().startForegroundService(intent)
        }
        
        // Polling status briefly as there's no broadcast for service state in this simple setup
        viewModelScope.launch {
            delay(500)
            _isServiceRunning.value = MqttService.isServiceRunning()
        }
    }
}
