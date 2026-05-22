package com.vigsync.core

enum class BootRecoveryAction {
    NONE,
    START_SERVICE,
    START_SYNC
}

object BootRecoveryPolicy {
    fun action(
        serviceEnabled: Boolean,
        syncEnabled: Boolean,
        brokerUrl: String,
        sharedKey: String?
    ): BootRecoveryAction {
        if (!serviceEnabled && !syncEnabled) return BootRecoveryAction.NONE
        if (brokerUrl.isBlank() || sharedKey.isNullOrBlank()) return BootRecoveryAction.NONE
        return if (syncEnabled) BootRecoveryAction.START_SYNC else BootRecoveryAction.START_SERVICE
    }
}
