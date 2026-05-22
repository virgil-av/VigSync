package com.vigsync.core.mqtt

data class QueuedPublish(
    val topic: String,
    val payload: ByteArray
) {
    fun payloadString(): String = payload.toString(Charsets.UTF_8)
}

data class EnqueueResult(
    val droppedOldest: Boolean,
    val size: Int
)

class MqttPublishQueue(private val capacity: Int) {
    private val entries = ArrayDeque<QueuedPublish>()

    fun enqueue(topic: String, payload: ByteArray): EnqueueResult {
        var droppedOldest = false
        if (entries.size >= capacity) {
            entries.removeFirst()
            droppedOldest = true
        }
        entries.addLast(QueuedPublish(topic, payload.copyOf()))
        return EnqueueResult(droppedOldest = droppedOldest, size = entries.size)
    }

    fun poll(): QueuedPublish? {
        return if (entries.isEmpty()) null else entries.removeFirst()
    }

    fun requeueFirst(publish: QueuedPublish) {
        if (entries.size >= capacity) {
            entries.removeLast()
        }
        entries.addFirst(QueuedPublish(publish.topic, publish.payload.copyOf()))
    }

    fun clear() {
        entries.clear()
    }

    fun size(): Int = entries.size
}
