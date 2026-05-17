package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.vigsync.core.models.MqttConnectionState
import kotlinx.coroutines.flow.*

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    // TODO: This should eventually come from a global MqttManager or Service
    val connectionState = MutableStateFlow(MqttConnectionState.IDLE).asStateFlow()
}
