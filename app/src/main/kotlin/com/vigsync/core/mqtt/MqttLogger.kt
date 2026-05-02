package com.vigsync.core.mqtt

import com.vigsync.core.models.EventRecord
import kotlinx.coroutines.flow.*

data class LogEntry(val message: String, val status: String, val timestamp: Long = System.currentTimeMillis())

object MqttLogger {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _appLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val appLogs: StateFlow<List<LogEntry>> = _appLogs.asStateFlow()

    private val _events = MutableStateFlow<List<EventRecord>>(emptyList())
    val events: StateFlow<List<EventRecord>> = _events.asStateFlow()

    fun log(message: String, status: String) {
        _logs.update { (listOf(LogEntry(message, status)) + it).take(100) }
    }

    fun logApp(message: String, status: String = "INFO") {
        _appLogs.update { (listOf(LogEntry(message, status)) + it).take(100) }
    }

    fun logEvent(event: EventRecord) {
        _events.update { (listOf(event) + it).take(100) }
    }

    fun updateEventStatus(timestamp: Long, status: com.vigsync.core.models.SyncStatus, error: String? = null) {
        _events.update { currentList ->
            currentList.map { 
                if (it.timestamp == timestamp) it.copy(syncStatus = status, errorMessage = error)
                else it
            }
        }
    }

    fun clear() {
        _logs.value = emptyList()
        _appLogs.value = emptyList()
        _events.value = emptyList()
    }
}
