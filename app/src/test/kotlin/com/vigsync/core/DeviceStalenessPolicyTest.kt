package com.vigsync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStalenessPolicyTest {
    @Test
    fun `offline threshold subtracts stale window from now`() {
        assertEquals(7_000L, DeviceStalenessPolicy.offlineThreshold(now = 10_000L, staleAfterMs = 3_000L))
    }

    @Test
    fun `device is offline only after stale threshold`() {
        assertFalse(DeviceStalenessPolicy.shouldMarkOffline(lastSeen = 7_000L, now = 10_000L, staleAfterMs = 3_000L))
        assertTrue(DeviceStalenessPolicy.shouldMarkOffline(lastSeen = 6_999L, now = 10_000L, staleAfterMs = 3_000L))
    }
}
