package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigsync.core.models.ImportConfig
import com.vigsync.core.models.MqttConnectionState
import com.vigsync.core.mqtt.MqttLogger
import com.vigsync.core.mqtt.MqttManager
import com.vigsync.data.local.PairedDeviceEntity
import com.vigsync.data.local.VigSyncDatabase
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val database = VigSyncDatabase.getInstance(application)
    private val deviceDao = database.deviceDao()
    private val appPreferences = AppPreferences(application)
    private val mqttManager = MqttManager.getInstance(application)
    private val mqttLogger = MqttLogger.getInstance(application)

    val connectionState = mqttManager.connectionState
    val pairedDevices = deviceDao.getAllDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _syncingDevices = MutableStateFlow<Set<String>>(emptySet())
    val syncingDevices: StateFlow<Set<String>> = _syncingDevices.asStateFlow()

    private val _pairingError = MutableSharedFlow<String>()
    val pairingError = _pairingError.asSharedFlow()

    fun pairDevice(jsonString: String, onFirstDevice: () -> Unit) {
        viewModelScope.launch {
            try {
                val config = Json { ignoreUnknownKeys = true }.decodeFromString<ImportConfig>(jsonString)
                if (config.deviceId == null || config.brokerUrl == null) {
                    _pairingError.emit("Invalid QR Code: Missing required fields")
                    return@launch
                }

                val currentBroker = appPreferences.brokerUrl.first()
                val existingDevices = pairedDevices.value

                // If not the first device, check broker consistency
                if (existingDevices.isNotEmpty() && config.brokerUrl != currentBroker) {
                    _pairingError.emit("Error: All devices must use the same MQTT broker ($currentBroker)")
                    return@launch
                }

                // If first device, save MQTT settings
                if (existingDevices.isEmpty()) {
                    appPreferences.saveServerConfig(
                        url = config.brokerUrl,
                        port = config.port?.toString() ?: "1883",
                        user = config.username ?: "",
                        pass = config.password ?: "",
                        tls = config.useTls ?: false,
                        version = "5" // Default to v5 for new setups
                    )
                    onFirstDevice()
                }

                // Save device to database
                val newDevice = PairedDeviceEntity(
                    deviceId = config.deviceId,
                    deviceName = config.deviceName ?: "Unknown Device",
                    topicPrefix = config.topicPrefix ?: "vigsync",
                    sharedKey = config.sharedKey ?: ""
                )
                deviceDao.insertDevice(newDevice)
                
                // Show syncing state for 2 minutes (simulated)
                _syncingDevices.update { it + config.deviceId }
                mqttLogger.logSystemEvent("Pairing", "Successfully paired with ${newDevice.deviceName}")
                
                // Keep syncing UI for a bit (user mentioned 2 mins, but we can do shorter for UX or stick to 2 mins)
                delay(120000) 
                _syncingDevices.update { it - config.deviceId }
                
            } catch (e: Exception) {
                _pairingError.emit("Failed to pair device: ${e.message}")
                mqttLogger.logSystemEvent("Pairing Error", e.message ?: "Unknown error", isError = true)
            }
        }
    }

    fun removeDevice(device: PairedDeviceEntity) {
        viewModelScope.launch {
            deviceDao.deleteDevice(device)
            mqttLogger.logSystemEvent("Device", "Removed device: ${device.deviceName}")
        }
    }
}
