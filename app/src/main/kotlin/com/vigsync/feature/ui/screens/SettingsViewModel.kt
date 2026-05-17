package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigsync.core.models.MqttConnectionState
import com.vigsync.core.models.ImportConfig
import com.vigsync.core.mqtt.MqttLogger
import com.vigsync.core.mqtt.MqttManager
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val appPreferences = AppPreferences(application)
    private val mqttManager = MqttManager.getInstance(application)

    // Server Config Flows
    val brokerUrl = appPreferences.brokerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "broker.hivemq.com")
    val brokerPort = appPreferences.brokerPort.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "1883")
    val brokerUser = appPreferences.brokerUser.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val brokerPass = appPreferences.brokerPass.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val useTls = appPreferences.useTls.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val mqttVersion = appPreferences.mqttVersion.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "5")

    val notifCalls = appPreferences.notifCalls.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val notifSms = appPreferences.notifSms.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val notifOther = appPreferences.notifOther.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    val connectionState = mqttManager.connectionState

    fun saveServerConfig(url: String, port: String, user: String, pass: String, tls: Boolean, version: String) {
        viewModelScope.launch {
            appPreferences.saveServerConfig(url, port, user, pass, tls, version)
        }
    }

    fun updateAndConnect(url: String, port: String, user: String, pass: String, tls: Boolean, version: String) {
        viewModelScope.launch {
            // Await the persistence of settings
            appPreferences.saveServerConfig(url, port, user, pass, tls, version)
            // Now safe to connect
            mqttManager.connect()
        }
    }

    fun connect() {
        mqttManager.connect()
    }

    fun disconnect() {
        mqttManager.disconnect()
    }

    fun updateNotifSettings(calls: Boolean, sms: Boolean, other: Boolean) {
        viewModelScope.launch {
            appPreferences.saveNotifSettings(calls, sms, other)
        }
    }

    fun sendTestNotification() {
        mqttManager.showNotification(
            title = "Test Notification",
            message = "This is a test alert from VigSync Client",
            deviceName = "Local Device"
        )
    }

    fun importFromJson(jsonString: String) {
        viewModelScope.launch {
            try {
                val config = Json.decodeFromString<ImportConfig>(jsonString)
                
                // Update basic MQTT settings
                val importedPort = config.port?.toString() ?: brokerPort.value
                val importedTls = config.useTls ?: (importedPort == "8883")
                
                appPreferences.saveServerConfig(
                    url = config.brokerUrl ?: brokerUrl.value,
                    port = importedPort,
                    user = config.username ?: brokerUser.value,
                    pass = config.password ?: brokerPass.value,
                    tls = importedTls,
                    version = mqttVersion.value
                )

                // Update paired device info
                appPreferences.savePairedDevice(
                    id = config.deviceId,
                    name = config.deviceName,
                    prefix = config.topicPrefix,
                    key = config.sharedKey
                )
                
                MqttLogger.getInstance(getApplication()).logSystemEvent("JSON Import", "Successfully imported configuration for ${config.deviceName}")
            } catch (e: Exception) {
                MqttLogger.getInstance(getApplication()).logSystemEvent("JSON Import", "Failed to parse JSON: ${e.message}", isError = true)
            }
        }
    }
}
