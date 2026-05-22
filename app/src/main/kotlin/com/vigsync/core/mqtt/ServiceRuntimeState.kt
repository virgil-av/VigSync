package com.vigsync.core.mqtt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ServiceRuntimeState(
    val isRunning: Boolean = false,
    val isSyncing: Boolean = false,
    val observersActive: Boolean = false,
    val networkAvailable: Boolean = false,
    val lastAction: String = "idle"
)

object MqttServiceRuntime {
    private val _state = MutableStateFlow(ServiceRuntimeState())
    val state: StateFlow<ServiceRuntimeState> = _state.asStateFlow()

    fun reduce(transform: (ServiceRuntimeState) -> ServiceRuntimeState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = ServiceRuntimeState()
    }
}
