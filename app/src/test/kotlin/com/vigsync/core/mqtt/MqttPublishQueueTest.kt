package com.vigsync.core.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttPublishQueueTest {
    @Test
    fun `queues disconnected publishes in fifo order`() {
        val queue = MqttPublishQueue(capacity = 3)

        queue.enqueue("topic/one", "one".toByteArray())
        queue.enqueue("topic/two", "two".toByteArray())

        assertEquals("topic/one", queue.poll()?.topic)
        assertEquals("topic/two", queue.poll()?.topic)
        assertNull(queue.poll())
    }

    @Test
    fun `queue capacity drops oldest item`() {
        val queue = MqttPublishQueue(capacity = 2)

        assertFalse(queue.enqueue("topic/one", "one".toByteArray()).droppedOldest)
        assertFalse(queue.enqueue("topic/two", "two".toByteArray()).droppedOldest)
        assertTrue(queue.enqueue("topic/three", "three".toByteArray()).droppedOldest)

        assertEquals("topic/two", queue.poll()?.topic)
        assertEquals("topic/three", queue.poll()?.topic)
        assertNull(queue.poll())
    }

    @Test
    fun `queued payload is copied`() {
        val queue = MqttPublishQueue(capacity = 2)
        val payload = "one".toByteArray()

        queue.enqueue("topic/one", payload)
        payload[0] = 'x'.code.toByte()

        assertEquals("one", queue.poll()?.payloadString())
    }

    @Test
    fun `failed publish can be requeued at front`() {
        val queue = MqttPublishQueue(capacity = 3)
        queue.enqueue("topic/one", "one".toByteArray())
        queue.enqueue("topic/two", "two".toByteArray())

        val failed = queue.poll()!!
        queue.requeueFirst(failed)

        assertEquals("topic/one", queue.poll()?.topic)
        assertEquals("topic/two", queue.poll()?.topic)
    }
}
