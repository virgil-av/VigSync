package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigsync.core.SyncManager
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val appPreferences = AppPreferences(application)

    val brokerUrl = appPreferences.brokerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "broker.hivemq.com")
    val brokerPort = appPreferences.brokerPort.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "1883")
    val brokerUsername = appPreferences.brokerUsername.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val brokerPassword = appPreferences.brokerPassword.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val useTls = appPreferences.useTls.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val notifCalls = appPreferences.notifCalls.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val notifSms = appPreferences.notifSms.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val notifOther = appPreferences.notifOther.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    fun saveMqttConfig(url: String, port: String, user: String, pass: String, tls: Boolean) {
        viewModelScope.launch {
            appPreferences.saveBrokerConfig(url, port)
            appPreferences.saveMqttAuth(user.ifBlank { null }, pass.ifBlank { null }, tls)
            SyncManager.getInstance(getApplication()).restart()
        }
    }

    fun updateNotifSettings(calls: Boolean, sms: Boolean, other: Boolean) {
        viewModelScope.launch {
            appPreferences.saveNotifSettings(calls, sms, other)
        }
    }

    fun sendTestNotification() {
        SyncManager.getInstance(getApplication()).sendTestNotification()
    }
}
