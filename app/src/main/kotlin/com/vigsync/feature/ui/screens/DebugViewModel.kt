package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigsync.data.local.MqttLogEntity
import com.vigsync.data.local.SystemLogEntity
import com.vigsync.data.local.VigSyncDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DebugViewModel(application: Application) : AndroidViewModel(application) {
    private val database = VigSyncDatabase.getInstance(application)
    private val debugDao = database.debugDao()

    val mqttLogs: StateFlow<List<MqttLogEntity>> = debugDao.getAllMqttLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val systemLogs: StateFlow<List<SystemLogEntity>> = debugDao.getAllSystemLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearMqttLogs() {
        viewModelScope.launch {
            debugDao.clearMqttLogs()
        }
    }

    fun clearSystemLogs() {
        viewModelScope.launch {
            debugDao.clearSystemLogs()
        }
    }
}
