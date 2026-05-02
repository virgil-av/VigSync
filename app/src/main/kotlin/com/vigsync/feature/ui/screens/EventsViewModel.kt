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

    val events = database.dao().getAllEvents()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun clearEvents() {
        viewModelScope.launch {
            database.dao().clearAllEvents()
            MqttLogger.clear()
        }
    }
}
