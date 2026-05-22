package com.vigsync.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BootRecoveryPolicyTest {
    @Test
    fun `does not restore when service and sync are not desired`() {
        assertEquals(
            BootRecoveryAction.NONE,
            BootRecoveryPolicy.action(
                serviceEnabled = false,
                syncEnabled = false,
                brokerUrl = "broker.example.com",
                sharedKey = "key"
            )
        )
    }

    @Test
    fun `does not restore when config is incomplete`() {
        assertEquals(
            BootRecoveryAction.NONE,
            BootRecoveryPolicy.action(
                serviceEnabled = true,
                syncEnabled = false,
                brokerUrl = "",
                sharedKey = "key"
            )
        )
        assertEquals(
            BootRecoveryAction.NONE,
            BootRecoveryPolicy.action(
                serviceEnabled = false,
                syncEnabled = true,
                brokerUrl = "broker.example.com",
                sharedKey = null
            )
        )
    }

    @Test
    fun `restores service only mode`() {
        assertEquals(
            BootRecoveryAction.START_SERVICE,
            BootRecoveryPolicy.action(
                serviceEnabled = true,
                syncEnabled = false,
                brokerUrl = "broker.example.com",
                sharedKey = "key"
            )
        )
    }

    @Test
    fun `restores sync mode when sync was desired`() {
        assertEquals(
            BootRecoveryAction.START_SYNC,
            BootRecoveryPolicy.action(
                serviceEnabled = false,
                syncEnabled = true,
                brokerUrl = "broker.example.com",
                sharedKey = "key"
            )
        )
    }
}
