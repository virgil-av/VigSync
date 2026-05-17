package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigsync.core.models.MqttConnectionState
import com.vigsync.core.mqtt.MqttLogger
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val appPreferences = AppPreferences(application)
    private val mqttLogger = MqttLogger.getInstance(application)

    // Server Config Flows
    val brokerUrl = appPreferences.brokerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val brokerPort = appPreferences.brokerPort.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "1883")
    val brokerUser = appPreferences.brokerUser.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val brokerPass = appPreferences.brokerPass.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val useTls = appPreferences.useTls.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    private val _connectionState = MutableStateFlow(MqttConnectionState.IDLE)
    val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    fun saveServerConfig(url: String, port: String, user: String, pass: String, tls: Boolean) {
        viewModelScope.launch {
            appPreferences.saveServerConfig(url, port, user, pass, tls)
        }
    }

    fun connect() {
        viewModelScope.launch {
            _connectionState.value = MqttConnectionState.CONNECTING
            mqttLogger.logSystemEvent("MQTT Connection", "Initiating connection to ${brokerUrl.value}:${brokerPort.value}")
            
            // TODO: Implement actual HiveMQ client connection logic in Step 3
            delay(2000) // Simulate connection delay
            
            _connectionState.value = MqttConnectionState.CONNECTED
            mqttLogger.logSystemEvent("MQTT Connection", "Successfully connected to ${brokerUrl.value}")
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            // TODO: Implement actual HiveMQ client disconnection logic in Step 3
            _connectionState.value = MqttConnectionState.DISCONNECTED
            mqttLogger.logSystemEvent("MQTT Connection", "Disconnecting...")
            
            delay(1000)
            
            _connectionState.value = MqttConnectionState.IDLE
            mqttLogger.logSystemEvent("MQTT Connection", "Disconnected")
        }
    }
}
