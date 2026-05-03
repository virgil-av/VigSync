package com.vigsync.feature.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vigsync.core.SyncManager
import java.util.concurrent.ConcurrentHashMap

class VigNotificationListener : NotificationListenerService() {

    private val recentNotificationWindows = ConcurrentHashMap<String, Long>()
    private val DEDUPE_WINDOW_MILLIS = 8000L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName == applicationContext.packageName) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "No Title"
        val text = extractBody(sbn)

        // Deduplication Logic: Ignore if it's likely handled by Dialer/SMS observers
        if (isLikelyRedundant(sbn)) {
            Log.d("VigSync", "Ignored redundant notification from $packageName")
            return
        }

        val eventKey = "$packageName|$title|$text"
        val now = System.currentTimeMillis()
        
        if (isDuplicate(eventKey, now)) {
            Log.d("VigSync", "Ignored duplicate notification from $packageName")
            return
        }

        Log.d("VigSync", "Notification from $packageName: $title - $text")
        SyncManager.getInstance(applicationContext).publishEvent("NOTIFICATION", "$packageName: $title - $text")
    }

    private fun isLikelyRedundant(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        val category = sbn.notification.category
        
        // System Dialer packages
        val dialers = setOf(
            "com.google.android.dialer", 
            "com.android.phone", 
            "com.android.server.telecom", 
            "com.samsung.android.dialer",
            "com.whatsapp",
            "org.telegram.messenger"
        )
        // SMS packages
        val smsApps = setOf("com.google.android.apps.messaging", "com.android.messaging", "com.samsung.android.messaging")
        
        if (pkg in dialers && (category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_MISSED_CALL || category == Notification.CATEGORY_MESSAGE)) return true
        if (pkg in smsApps) return true
        
        return false
    }

    private fun extractBody(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        
        // Try MessagingStyle (WhatsApp, etc.)
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        if (messagingStyle != null) {
            return messagingStyle.messages.mapNotNull { it.text?.toString() }
                .distinct()
                .joinToString(separator = "\n")
                .ifBlank { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "No Text" }
        }

        return extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: "No Text"
    }

    private fun isDuplicate(key: String, now: Long): Boolean {
        recentNotificationWindows.entries.removeIf { now - it.value > DEDUPE_WINDOW_MILLIS }
        val lastSeen = recentNotificationWindows.putIfAbsent(key, now)
        return lastSeen != null && now - lastSeen <= DEDUPE_WINDOW_MILLIS
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}
