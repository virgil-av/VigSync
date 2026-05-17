package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.vigsync.data.local.VigSyncDatabase
import com.vigsync.data.local.EventEntity
import com.vigsync.data.prefs.AppPreferences
import com.vigsync.core.crypto.EncryptionManager
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

    val devices: StateFlow<List<String>> = database.dao().getAllEvents()
        .map { items -> 
            items.map { it.sourceDeviceName }.distinct().filterNotNull().sorted()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val events: StateFlow<List<EventEntity>> = combine(
        database.dao().getAllEvents(),
        selectedDevice,
        selectedType
    ) { allEvents, deviceFilter, typeFilter ->
        allEvents.filter { event ->
            val matchesDevice = deviceFilter == null || event.sourceDeviceName == deviceFilter
            val matchesType = typeFilter == null || when(typeFilter) {
                "CALL" -> event.type.contains("CALL")
                else -> event.type == typeFilter
            }
            matchesDevice && matchesType
        }.distinctBy { it.timestamp to it.data }
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

    fun repairEncryptedEvents() {
        viewModelScope.launch(Dispatchers.IO) {
            val allEvents = database.dao().getAllEvents().first()
            
            allEvents.forEach { event ->
                if (event.data.startsWith("[Encrypted] ")) {
                    val base64 = event.data.removePrefix("[Encrypted] ")
                    val deviceId = event.sourceDeviceId
                    if (deviceId != null) {
                        val device = database.deviceDao().getDeviceById(deviceId)
                        val key = device?.sharedKey
                        if (key != null) {
                            try {
                                val decrypted = EncryptionManager(key).decrypt(base64)
                                if (decrypted != null) {
                                    database.dao().insertEvent(event.copy(data = decrypted))
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }
}
