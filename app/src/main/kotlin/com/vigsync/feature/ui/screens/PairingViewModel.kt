package com.vigsync.feature.ui.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigsync.core.crypto.EncryptionManager
import com.vigsync.data.prefs.AppPreferences
import com.vigsync.feature.pairing.PairingData
import com.vigsync.feature.pairing.PairingManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

import com.vigsync.core.SyncManager
import com.vigsync.core.models.Device

class PairingViewModel(application: Application) : AndroidViewModel(application) {
    private val appPreferences = AppPreferences(application)
    private val pairingManager = PairingManager()

    private val _qrCode = MutableStateFlow<Bitmap?>(null)
    val qrCode: StateFlow<Bitmap?> = _qrCode

    private val _pairingComplete = MutableSharedFlow<String>()
    val pairingComplete = _pairingComplete.asSharedFlow()

    fun generateMyQr() {
        viewModelScope.launch {
            val url = appPreferences.brokerUrl.first()
            val port = appPreferences.brokerPort.first().toInt()
            var key = appPreferences.sharedKey.first()
            var prefix = appPreferences.topicPrefix.first()

            if (key == null) {
                key = EncryptionManager.generateRandomKey()
                appPreferences.saveSharedKey(key)
            }
            if (prefix == null) {
                prefix = "vigsync/${UUID.randomUUID()}"
                appPreferences.saveTopicPrefix(prefix)
            }

            val deviceId = android.os.Build.MODEL + "_" + android.os.Build.ID
            val data = PairingData(url, port, key!!, prefix, android.os.Build.MODEL, deviceId)
            val json = try {
                pairingManager.generatePairingJson(data)
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
            if (json.isNotEmpty()) {
                _qrCode.value = pairingManager.generateQrCode(json)
            }
        }
    }

    fun onScanResult(json: String) {
        val data = pairingManager.parsePairingJson(json) ?: return
        viewModelScope.launch {
            appPreferences.saveBrokerConfig(data.brokerUrl, data.port.toString())
            appPreferences.saveSharedKey(data.sharedKey)
            appPreferences.saveTopicPrefix(data.topicPrefix)
            
            // Add to paired devices
            val syncManager = SyncManager.getInstance(getApplication())
            val device = Device(
                id = data.deviceId,
                name = data.deviceName,
                isOnline = true,
                lastSeen = System.currentTimeMillis()
            )
            syncManager.addPairedDevice(device)
            syncManager.restart()
            
            _pairingComplete.emit(data.deviceName)
        }
    }
}
