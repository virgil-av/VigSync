package com.vigsync.core

object DeviceStalenessPolicy {
    fun offlineThreshold(now: Long, staleAfterMs: Long): Long = now - staleAfterMs

    fun shouldMarkOffline(lastSeen: Long, now: Long, staleAfterMs: Long): Boolean {
        return lastSeen < offlineThreshold(now, staleAfterMs)
    }
}
