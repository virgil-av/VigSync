package com.vigsync.core.mqtt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryPolicyTest {
    @Test
    fun `ignores networks without usable internet`() {
        assertFalse(
            NetworkRecoveryPolicy.shouldReconnect(
                hasUsableInternet = false,
                nowMs = 10_000L,
                lastReconnectAtMs = 0L,
                debounceMs = 2_000L
            )
        )
    }

    @Test
    fun `allows first usable network recovery`() {
        assertTrue(
            NetworkRecoveryPolicy.shouldReconnect(
                hasUsableInternet = true,
                nowMs = 10_000L,
                lastReconnectAtMs = 0L,
                debounceMs = 2_000L
            )
        )
    }

    @Test
    fun `debounces duplicate network callbacks`() {
        assertFalse(
            NetworkRecoveryPolicy.shouldReconnect(
                hasUsableInternet = true,
                nowMs = 11_000L,
                lastReconnectAtMs = 10_000L,
                debounceMs = 2_000L
            )
        )
        assertTrue(
            NetworkRecoveryPolicy.shouldReconnect(
                hasUsableInternet = true,
                nowMs = 12_000L,
                lastReconnectAtMs = 10_000L,
                debounceMs = 2_000L
            )
        )
    }
}
