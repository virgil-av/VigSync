package com.vigsync.feature.ui.screens

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigsync.core.SyncManager
import com.vigsync.core.mqtt.LogEntry
import com.vigsync.core.mqtt.MqttLogger
import com.vigsync.core.mqtt.MqttService
import com.vigsync.data.local.DeviceStatusEntity
import com.vigsync.data.local.VigSyncDatabase
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val syncManager = SyncManager.getInstance(application)
    private val database = VigSyncDatabase.getInstance(application)
    private val appPreferences = AppPreferences(application)
    
    val connectionStatus = syncManager.getMqttManager().connectionStatus
    val localDeviceId: String = syncManager.getLocalDeviceId()
    
    val pairedDevices: StateFlow<List<DeviceStatusEntity>> = database.dao().getAllDeviceStatus()
        .map { list -> list.filter { it.deviceId != localDeviceId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentEvents: Flow<List<LogEntry>> = MqttLogger.logs.map { it.filter { entry -> entry.status == "RECEIVED" || entry.status == "SENT" } }

    private val _isServiceRunning = MutableStateFlow(MqttService.isServiceRunning())
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isSyncActive = MutableStateFlow(MqttService.isSyncActive())
    val isSyncActive: StateFlow<Boolean> = _isSyncActive.asStateFlow()

    val shareCalls = appPreferences.shareCalls
    val shareSms = appPreferences.shareSms
    val shareNotifications = appPreferences.shareNotifications

    // Permission Safeguard Logic
    fun validateSharingStates() {
        viewModelScope.launch {
            val calls = appPreferences.shareCalls.first()
            val sms = appPreferences.shareSms.first()
            val notifications = appPreferences.shareNotifications.first()

            val context = getApplication<Application>()
            
            val hasCallPerm = context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED &&
                             context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            
            val hasSmsPerm = context.checkSelfPermission(android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
            
            val enabledListeners = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val hasNotifPerm = enabledListeners.contains(context.packageName)

            var needsUpdate = false
            var newCalls = calls
            var newSms = sms
            var newNotif = notifications

            if (calls && !hasCallPerm) {
                newCalls = false
                needsUpdate = true
            }
            if (sms && !hasSmsPerm) {
                newSms = false
                needsUpdate = true
            }
            if (notifications && !hasNotifPerm) {
                newNotif = false
                needsUpdate = true
            }

            if (needsUpdate) {
                appPreferences.saveSharingSettings(newCalls, newSms, newNotif)
                MqttLogger.logApp("Safeguard: Disabled sharing options due to missing permissions", "WARNING")
            }
        }
    }

    fun updateSharingPreferences(calls: Boolean, sms: Boolean, notifications: Boolean) {
        viewModelScope.launch {
            appPreferences.saveSharingSettings(calls, sms, notifications)
        }
    }

    fun retryConnection() {
        syncManager.restart()
    }

    init {
        validateSharingStates()
        
        viewModelScope.launch {
            if (appPreferences.brokerUrl.first().isNotEmpty()) {
                val intent = android.content.Intent(getApplication(), MqttService::class.java).apply {
                    action = MqttService.ACTION_START
                }
                getApplication<Application>().startForegroundService(intent)
                
                if (appPreferences.syncEnabled.first()) {
                    val syncIntent = android.content.Intent(getApplication(), MqttService::class.java).apply {
                        action = MqttService.ACTION_START_SYNC
                    }
                    getApplication<Application>().startForegroundService(syncIntent)
                }
            }
        }
    }

    fun removeDevice(deviceId: String) {
        syncManager.removeDevice(deviceId)
    }

    fun renameDevice(deviceId: String, newLabel: String) {
        viewModelScope.launch {
            database.dao().updateDeviceLabel(deviceId, newLabel.ifBlank { null })
        }
    }

    val deviceEventCounts: StateFlow<Map<String, Int>> = database.dao().getAllEvents()
        .map { events ->
            events.groupBy { it.sourceDevice ?: "Unknown" }
                .mapValues { it.value.size }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun toggleSync(permissionsGranted: Boolean = true) {
        val intent = android.content.Intent(getApplication(), MqttService::class.java)
        viewModelScope.launch {
            if (isSyncActive.value) {
                intent.action = MqttService.ACTION_STOP_SYNC
                getApplication<Application>().startForegroundService(intent)
                appPreferences.saveSyncEnabled(false)
            } else if (permissionsGranted) {
                validateSharingStates() // One last check
                
                val startIntent = android.content.Intent(getApplication(), MqttService::class.java).apply {
                    action = MqttService.ACTION_START
                }
                getApplication<Application>().startForegroundService(startIntent)
                
                intent.action = MqttService.ACTION_START_SYNC
                getApplication<Application>().startForegroundService(intent)
                appPreferences.saveSyncEnabled(true)
            }
            
            delay(800)
            _isSyncActive.value = MqttService.isSyncActive()
            _isServiceRunning.value = MqttService.isServiceRunning()
        }
    }

    fun toggleService(permissionsGranted: Boolean = true) {
        val intent = android.content.Intent(getApplication(), MqttService::class.java)
        if (isServiceRunning.value) {
            intent.action = MqttService.ACTION_STOP
            getApplication<Application>().stopService(intent)
        } else if (permissionsGranted) {
            intent.action = MqttService.ACTION_START
            getApplication<Application>().startForegroundService(intent)
        }
        
        viewModelScope.launch {
            delay(500)
            _isServiceRunning.value = MqttService.isServiceRunning()
            _isSyncActive.value = MqttService.isSyncActive()
        }
    }
}
