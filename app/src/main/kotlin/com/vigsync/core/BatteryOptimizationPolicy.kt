package com.vigsync.core

enum class BatteryOptimizationRequest {
    NONE,
    REQUEST_EXEMPTION,
    OPEN_APP_SETTINGS
}

object BatteryOptimizationPolicy {
    fun request(
        sdkInt: Int,
        isIgnoringOptimizations: Boolean,
        canRequestExemption: Boolean
    ): BatteryOptimizationRequest {
        if (isIgnoringOptimizations) return BatteryOptimizationRequest.NONE
        if (sdkInt >= 23 && canRequestExemption) return BatteryOptimizationRequest.REQUEST_EXEMPTION
        return BatteryOptimizationRequest.OPEN_APP_SETTINGS
    }
}
