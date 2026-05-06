package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.vigsync.core.mqtt.MqttLogger
import com.vigsync.data.local.VigSyncDatabase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class EventsViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
    private val database = VigSyncDatabase.getInstance(application)

    // Reactive selection from Navigation SavedStateHandle
    val selectedDevice: StateFlow<String?> = savedStateHandle.getStateFlow("deviceName", null)

    val devices = database.dao().getAllEvents()
        .map { events -> 
            events.mapNotNull { it.sourceDevice }.distinct().sorted()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val events = combine(database.dao().getAllEvents(), selectedDevice) { allEvents, filter ->
        if (filter == null) allEvents else allEvents.filter { it.sourceDevice == filter }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setSelectedDevice(deviceName: String?) {
        savedStateHandle["deviceName"] = deviceName
    }

    fun clearEvents() {
        viewModelScope.launch {
            database.dao().clearAllEvents()
            MqttLogger.clear()
        }
    }

    fun clearEventsForDevice(deviceName: String) {
        viewModelScope.launch {
            database.dao().clearEventsForDevice(deviceName)
        }
    }
}
