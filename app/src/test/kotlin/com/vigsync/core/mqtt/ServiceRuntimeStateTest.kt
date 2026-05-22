package com.vigsync.core.mqtt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRuntimeStateTest {
    @Test
    fun `runtime reducer reports service and sync transitions`() {
        MqttServiceRuntime.reset()

        MqttServiceRuntime.reduce {
            it.copy(isRunning = true, lastAction = "start")
        }
        assertTrue(MqttServiceRuntime.state.value.isRunning)
        assertFalse(MqttServiceRuntime.state.value.isSyncing)

        MqttServiceRuntime.reduce {
            it.copy(isSyncing = true, observersActive = true, lastAction = "sync")
        }
        assertTrue(MqttServiceRuntime.state.value.isSyncing)
        assertTrue(MqttServiceRuntime.state.value.observersActive)

        MqttServiceRuntime.reset()
        assertFalse(MqttServiceRuntime.state.value.isRunning)
        assertFalse(MqttServiceRuntime.state.value.isSyncing)
        assertFalse(MqttServiceRuntime.state.value.observersActive)
    }
}
