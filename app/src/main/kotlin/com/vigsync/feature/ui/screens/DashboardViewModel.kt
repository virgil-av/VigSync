package com.vigsync.feature.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.vigsync.core.mqtt.MqttManager

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val mqttManager = MqttManager.getInstance(application)
    
    val connectionState = mqttManager.connectionState
}
