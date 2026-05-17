package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.vigsync.data.local.VigSyncDatabase
import com.vigsync.data.local.EventEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class EventsViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
    private val database = VigSyncDatabase.getInstance(application)

    val selectedType: StateFlow<String?> = savedStateHandle.getStateFlow("eventType", null)

    val events: StateFlow<List<EventEntity>> = combine(
        database.dao().getAllEvents(),
        selectedType
    ) { allEvents, typeFilter ->
        allEvents.filter { event ->
            typeFilter == null || when(typeFilter) {
                "CALL" -> event.type.contains("CALL")
                else -> event.type == typeFilter
            }
        }.distinctBy { it.timestamp to it.data }
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
}
