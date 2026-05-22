package com.vigsync.core

enum class EventPublishMode {
    IMMEDIATE,
    BUFFERED
}

object EventBufferPolicy {
    fun eventKey(type: String, data: String): String = "${type}_$data"

    fun eventId(type: String, data: String): String = "${type}_${data.hashCode()}"

    fun publishMode(type: String): EventPublishMode {
        return if (type == "CALL" || type.contains("MISSED")) {
            EventPublishMode.IMMEDIATE
        } else {
            EventPublishMode.BUFFERED
        }
    }

    fun shouldAcceptEvent(lastSeenAt: Long?, now: Long, debounceWindowMs: Long): Boolean {
        return lastSeenAt == null || now - lastSeenAt >= debounceWindowMs
    }
}
