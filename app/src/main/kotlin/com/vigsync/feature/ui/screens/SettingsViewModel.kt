package com.vigsync.feature.ui.screens

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigsync.core.SyncManager
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DiscoveredApp(
    val packageName: String,
    val name: String,
    val icon: Drawable?,
    val isEnabled: Boolean
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val appPreferences = AppPreferences(application)
    private val packageManager = application.packageManager

    // Server Config Flows
    val brokerUrl = appPreferences.brokerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val brokerPort = appPreferences.brokerPort.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "1883")
    val brokerUser = appPreferences.brokerUser.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val brokerPass = appPreferences.brokerPass.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")
    val topicPrefix = appPreferences.topicPrefix.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "vigsync")
    val useTls = appPreferences.useTls.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val notifCalls = appPreferences.notifCalls.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val notifSms = appPreferences.notifSms.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val notifOther = appPreferences.notifOther.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    val discoveredApps: StateFlow<List<DiscoveredApp>> = combine(
        appPreferences.observedAppPackages,
        appPreferences.disabledAppPackages
    ) { observed, disabled ->
        observed.map { pkg ->
            val info = try {
                packageManager.getApplicationInfo(pkg, 0)
            } catch (e: Exception) { null }
            
            DiscoveredApp(
                packageName = pkg,
                name = info?.let { packageManager.getApplicationLabel(it).toString() } ?: pkg,
                icon = info?.loadIcon(packageManager),
                isEnabled = pkg !in disabled
            )
        }.sortedBy { it.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveServerConfig(url: String, port: String, user: String, pass: String, prefix: String, tls: Boolean) {
        viewModelScope.launch {
            appPreferences.saveServerConfig(url, port, user, pass, prefix, tls)
            SyncManager.getInstance(getApplication()).getServerConfigManager().generateConfigFile()
        }
    }

    fun updateNotifSettings(calls: Boolean, sms: Boolean, other: Boolean) {
        viewModelScope.launch {
            appPreferences.saveNotifSettings(calls, sms, other)
        }
    }

    fun toggleAppSync(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.toggleAppDisabled(packageName, !enabled)
        }
    }
}
