package com.vigsync.core.mqtt

object NetworkRecoveryPolicy {
    fun shouldReconnect(
        hasUsableInternet: Boolean,
        nowMs: Long,
        lastReconnectAtMs: Long,
        debounceMs: Long
    ): Boolean {
        if (!hasUsableInternet) return false
        return lastReconnectAtMs == 0L || nowMs - lastReconnectAtMs >= debounceMs
    }
}
