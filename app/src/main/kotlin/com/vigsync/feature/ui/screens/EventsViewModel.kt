package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
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
    val selectedType: StateFlow<String?> = savedStateHandle.getStateFlow("eventType", null)

    val devices = database.dao().getAllEvents()
        .map { events -> 
            events.mapNotNull { it.sourceDevice }.distinct().sorted()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val events = combine(database.dao().getAllEvents(), selectedDevice, selectedType) { allEvents, deviceFilter, typeFilter ->
        allEvents.filter { event ->
            val matchesDevice = deviceFilter == null || event.sourceDevice == deviceFilter
            val matchesType = typeFilter == null || when(typeFilter) {
                "CALL" -> event.type.contains("CALL")
                else -> event.type == typeFilter
            }
            matchesDevice && matchesType
        }
    }.stateIn(
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
        }
    }

    fun clearEventsForDevice(deviceName: String) {
        viewModelScope.launch {
            database.dao().clearEventsForDevice(deviceName)
        }
    }
}
