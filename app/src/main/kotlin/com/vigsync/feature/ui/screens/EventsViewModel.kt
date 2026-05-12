package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.vigsync.core.mqtt.MqttLogger
import com.vigsync.data.local.VigSyncDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class EventsViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
    private val database = VigSyncDatabase.getInstance(application)

    // Reactive selection from Navigation SavedStateHandle
    val selectedDevice: StateFlow<String?> = savedStateHandle.getStateFlow("deviceName", null)
    val selectedType: StateFlow<String?> = savedStateHandle.getStateFlow("eventType", null)

    val devices = database.dao().getAllEventsWithLabels()
        .map { items -> 
            items.map { it.resolvedDeviceName }.distinct().sorted()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val events = combine(database.dao().getAllEventsWithLabels(), selectedDevice, selectedType) { allEvents, deviceFilter, typeFilter ->
        val filtered = allEvents.filter { item ->
            val matchesDevice = deviceFilter == null || item.resolvedDeviceName == deviceFilter
            val matchesType = typeFilter == null || when(typeFilter) {
                "CALL" -> item.event.type.contains("CALL")
                else -> item.event.type == typeFilter
            }
            matchesDevice && matchesType
        }
        
        // Deduplication Logic: Group by timestamp and data, take first of each group
        filtered.distinctBy { it.event.timestamp to it.event.data }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setSelectedDevice(deviceName: String?) {
        savedStateHandle["deviceName"] = deviceName
    }

    fun setSelectedType(type: String?) {
        savedStateHandle["eventType"] = type
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
