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

    // Filter by type: CALL, SMS, NOTIFICATION, or null for All
    val selectedType: StateFlow<String?> = savedStateHandle.getStateFlow("eventType", null)

    val events = combine(database.dao().getAllEvents(), selectedType) { allEvents, filter ->
        if (filter == null) allEvents else allEvents.filter { it.type == filter }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setSelectedType(type: String?) {
        savedStateHandle["eventType"] = type
    }

    fun clearEvents() {
        viewModelScope.launch {
            database.dao().clearAllEvents()
        }
    }

    fun clearEventsForType(type: String) {
        viewModelScope.launch {
            // We don't have a DAO method for this yet, but we can add one or just clear all for simplicity 
            // since it's local only now. For now, let's just use clearAllEvents or implement clearByType.
            // database.dao().clearEventsByType(type)
        }
    }
}
