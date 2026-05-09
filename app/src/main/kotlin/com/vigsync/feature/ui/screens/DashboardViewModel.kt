package com.vigsync.feature.ui.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import com.vigsync.core.SyncManager
import com.vigsync.core.VigSyncService
import com.vigsync.core.QrCodeGenerator
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.vigsync.data.local.VigSyncDatabase
import com.vigsync.data.prefs.AppPreferences

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val syncManager = SyncManager.getInstance(application)
    private val database = VigSyncDatabase.getInstance(application)
    private val appPreferences = AppPreferences(application)
    
    val localDeviceId: String = syncManager.getLocalDeviceId()
    
    private val _isServiceRunning = MutableStateFlow(VigSyncService.isServiceRunning())
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isSyncActive = MutableStateFlow(VigSyncService.isMonitoringActive())
    val isSyncActive: StateFlow<Boolean> = _isSyncActive.asStateFlow()

    val shareCalls: Flow<Boolean> = appPreferences.notifCalls
    val shareSms: Flow<Boolean> = appPreferences.notifSms
    val shareNotifications: Flow<Boolean> = appPreferences.notifOther

    val exportFilePath: String = syncManager.getEventExporter().getExportFile().absolutePath

    private val _formattedLog = MutableStateFlow("")
    val formattedLog: StateFlow<String> = _formattedLog.asStateFlow()

    private val _qrCode = MutableStateFlow<Bitmap?>(null)
    val qrCode: StateFlow<Bitmap?> = _qrCode.asStateFlow()

    fun updateSharingPreferences(calls: Boolean, sms: Boolean, notifications: Boolean) {
        viewModelScope.launch {
            appPreferences.saveNotifSettings(calls, sms, notifications)
        }
    }

    init {
        viewModelScope.launch {
            // Ensure shared key exists
            appPreferences.migrateIfNeeded()

            // Start main service
            val startIntent = android.content.Intent(getApplication(), VigSyncService::class.java).apply {
                action = VigSyncService.ACTION_START
            }
            getApplication<Application>().startForegroundService(startIntent)
            
            // If monitoring was enabled previously, start it
            if (appPreferences.syncEnabled.first()) {
                val syncIntent = android.content.Intent(getApplication(), VigSyncService::class.java).apply {
                    action = VigSyncService.ACTION_START_MONITORING
                }
                getApplication<Application>().startForegroundService(syncIntent)
            }
            
            delay(500)
            _isServiceRunning.value = VigSyncService.isServiceRunning()
            _isSyncActive.value = VigSyncService.isMonitoringActive()
        }
    }

    val deviceEventCounts: StateFlow<Map<String, Int>> = database.dao().getAllEvents()
        .map { events ->
            events.groupBy { it.sourceDevice ?: "Unknown" }
                .mapValues { it.value.size }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    fun toggleSync(permissionsGranted: Boolean = true) {
        val intent = android.content.Intent(getApplication(), VigSyncService::class.java)
        viewModelScope.launch {
            if (isSyncActive.value) {
                intent.action = VigSyncService.ACTION_STOP_MONITORING
                getApplication<Application>().startForegroundService(intent)
                appPreferences.saveSyncEnabled(false)
            } else if (permissionsGranted) {
                intent.action = VigSyncService.ACTION_START_MONITORING
                getApplication<Application>().startForegroundService(intent)
                appPreferences.saveSyncEnabled(true)
            }
            
            delay(800)
            _isSyncActive.value = VigSyncService.isMonitoringActive()
            _isServiceRunning.value = VigSyncService.isServiceRunning()
        }
    }

    fun toggleService(permissionsGranted: Boolean = true) {
        val intent = android.content.Intent(getApplication(), VigSyncService::class.java)
        if (isServiceRunning.value) {
            intent.action = VigSyncService.ACTION_STOP
            getApplication<Application>().stopService(intent)
        } else if (permissionsGranted) {
            intent.action = VigSyncService.ACTION_START
            getApplication<Application>().startForegroundService(intent)
        }
        
        viewModelScope.launch {
            delay(500)
            _isServiceRunning.value = VigSyncService.isServiceRunning()
            _isSyncActive.value = VigSyncService.isMonitoringActive()
        }
    }

    fun resetExportFile() {
        syncManager.getEventExporter().resetFile()
    }

    fun loadFormattedLog() {
        viewModelScope.launch {
            _formattedLog.value = "Processing log..."
            _formattedLog.value = syncManager.getEventExporter().readAndBeautify()
        }
    }

    fun generateMyQr() {
        viewModelScope.launch {
            val prefix = appPreferences.topicPrefix.first()
            val deviceId = localDeviceId
            val deviceName = android.os.Build.MODEL
            val sharedKey = appPreferences.sharedKey.first() ?: ""
            
            // Old Schema for compatibility
            val pairingData = """
                {
                    "id": "$deviceId",
                    "name": "$deviceName",
                    "topicPrefix": "$prefix",
                    "key": "$sharedKey"
                }
            """.trimIndent()
            
            _qrCode.value = QrCodeGenerator.generate(pairingData)
        }
    }
}
