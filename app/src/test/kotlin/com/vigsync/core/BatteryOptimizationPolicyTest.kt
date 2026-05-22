package com.vigsync.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryOptimizationPolicyTest {
    @Test
    fun `does nothing when battery optimization is already ignored`() {
        assertEquals(
            BatteryOptimizationRequest.NONE,
            BatteryOptimizationPolicy.request(
                sdkInt = 35,
                isIgnoringOptimizations = true,
                canRequestExemption = true
            )
        )
    }

    @Test
    fun `requests exemption on Android M and newer when activity is available`() {
        assertEquals(
            BatteryOptimizationRequest.REQUEST_EXEMPTION,
            BatteryOptimizationPolicy.request(
                sdkInt = 35,
                isIgnoringOptimizations = false,
                canRequestExemption = true
            )
        )
    }

    @Test
    fun `falls back to app settings when exemption request is unavailable`() {
        assertEquals(
            BatteryOptimizationRequest.OPEN_APP_SETTINGS,
            BatteryOptimizationPolicy.request(
                sdkInt = 35,
                isIgnoringOptimizations = false,
                canRequestExemption = false
            )
        )
    }
}
