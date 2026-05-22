package com.vigsync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventBufferPolicyTest {
    @Test
    fun `call events publish immediately`() {
        assertEquals(EventPublishMode.IMMEDIATE, EventBufferPolicy.publishMode("CALL"))
        assertEquals(EventPublishMode.IMMEDIATE, EventBufferPolicy.publishMode("MISSED_CALL"))
    }

    @Test
    fun `non call events are buffered`() {
        assertEquals(EventPublishMode.BUFFERED, EventBufferPolicy.publishMode("SMS"))
        assertEquals(EventPublishMode.BUFFERED, EventBufferPolicy.publishMode("APP_NOTIFICATION"))
    }

    @Test
    fun `duplicate events inside debounce window are rejected`() {
        assertFalse(
            EventBufferPolicy.shouldAcceptEvent(
                lastSeenAt = 1_000L,
                now = 1_500L,
                debounceWindowMs = 1_000L
            )
        )
    }

    @Test
    fun `events at or after debounce window are accepted`() {
        assertTrue(
            EventBufferPolicy.shouldAcceptEvent(
                lastSeenAt = 1_000L,
                now = 2_000L,
                debounceWindowMs = 1_000L
            )
        )
    }

    @Test
    fun `event identifiers are stable`() {
        assertEquals("SMS_hello", EventBufferPolicy.eventKey("SMS", "hello"))
        assertEquals(EventBufferPolicy.eventId("SMS", "hello"), EventBufferPolicy.eventId("SMS", "hello"))
    }
}
