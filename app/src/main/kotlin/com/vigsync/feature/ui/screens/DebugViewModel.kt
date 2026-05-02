package com.vigsync.feature.ui.screens

import androidx.lifecycle.ViewModel
import com.vigsync.core.mqtt.LogEntry
import com.vigsync.core.mqtt.MqttLogger
import kotlinx.coroutines.flow.StateFlow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigsync.data.local.VigSyncDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class DebugViewModel(application: Application) : AndroidViewModel(application) {
    private val database = VigSyncDatabase.getInstance(application)
    
    val mqttLogs: StateFlow<List<LogEntry>> = MqttLogger.logs
    val appLogs: StateFlow<List<LogEntry>> = MqttLogger.appLogs

    // New flows for Storage tab
    val rawMessages = database.dao().getRecentRaw()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val deviceStatus = database.dao().getAllDeviceStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearLogs() {
        MqttLogger.clear()
    }
}
