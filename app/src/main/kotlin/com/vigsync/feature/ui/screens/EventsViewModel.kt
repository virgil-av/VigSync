package com.vigsync.feature.ui.screens

import androidx.lifecycle.ViewModel
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigsync.core.mqtt.MqttLogger
import com.vigsync.data.local.VigSyncDatabase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class EventsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = VigSyncDatabase.getInstance(application)

    private val _selectedDevice = MutableStateFlow<String?>(null)
    val selectedDevice = _selectedDevice.asStateFlow()

    val devices = database.dao().getAllEvents()
        .map { events -> 
            events.mapNotNull { it.sourceDevice }.distinct().sorted()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val events = combine(database.dao().getAllEvents(), _selectedDevice) { allEvents, filter ->
        if (filter == null) allEvents else allEvents.filter { it.sourceDevice == filter }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setSelectedDevice(deviceName: String?) {
        _selectedDevice.value = deviceName
    }

    fun clearEvents() {
        viewModelScope.launch {
            database.dao().clearAllEvents()
            MqttLogger.clear()
        }
    }
}
