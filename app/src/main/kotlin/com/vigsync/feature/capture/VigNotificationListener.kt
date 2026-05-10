package com.vigsync.feature.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vigsync.core.SyncManager
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class VigNotificationListener : NotificationListenerService() {

    private val recentNotificationWindows = ConcurrentHashMap<String, Long>()
    private val DEDUPE_WINDOW_MILLIS = 10000L // 10s
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var appPreferences: AppPreferences

    override fun onCreate() {
        super.onCreate()
        appPreferences = AppPreferences(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        // 1. HARDCODED LOOP PROTECTION
        if (packageName == applicationContext.packageName) return

        scope.launch {
            // 2. DISCOVERY & FILTERING
            appPreferences.addObservedPackage(packageName)
            val disabledApps = appPreferences.disabledAppPackages.first()
            if (packageName in disabledApps) {
                return@launch
            }

            if (isNoisyNotification(sbn)) {
                return@launch
            }

            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val text = extractBody(sbn).trim()

            // Deduplication Logic: Use a "canonical" key
            val eventKey = "$packageName|${title.lowercase()}|${text.lowercase()}"
            val now = System.currentTimeMillis()
            
            if (isDuplicate(eventKey, now)) {
                Log.d("VigSync", "Blocked duplicate notification from $packageName")
                return@launch
            }

            // Deduplication Logic: Ignore if it's likely handled by Dialer/SMS observers
            if (isLikelyRedundant(sbn)) {
                return@launch
            }

            val pm = applicationContext.packageManager
            val appLabel = try {
                val info = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(info).toString()
            } catch (e: Exception) { packageName }

            var eventType = "NOTIFICATION"
            var eventData = "$appLabel|$packageName|$title: $text"

            // --- SPECIAL VOIP CALL DETECTION ---
            val category = sbn.notification.category
            val isCallCategory = category == Notification.CATEGORY_CALL || category == "call"
            
            // Heuristic for VoIP missed/lost calls across various apps
            val isMissed = title.contains("Missed", ignoreCase = true) || 
                           text.contains("Missed", ignoreCase = true) ||
                           title.contains("Lost", ignoreCase = true) ||
                           text.contains("Lost", ignoreCase = true)

            if (isMissed) {
                eventType = "VOIP MISSED CALL"
                eventData = "[$appLabel] From: $title"
            } else if (isCallCategory || text.contains("Ongoing call", ignoreCase = true)) {
                // Label correctly so it's not a generic notification
                eventType = "VOIP ACTIVE CALL"
                eventData = "[$appLabel] Active Call: $title"
                
                // Optional: skip active calls if user only wants missed
                // Log.d("VigSync", "Ignored active VoIP call")
                // return@launch 
            }

            Log.d("VigSync", "Event Captured ($eventType) from $packageName: $title")
            SyncManager.getInstance(applicationContext).publishEvent(eventType, eventData)
        }
    }

    private fun isNoisyNotification(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        
        // 1. WhatsApp noise
        if (sbn.packageName == "com.whatsapp") {
            val noisyPatterns = listOf("Checking for new messages", "WhatsApp Web is active", "WhatsApp Web is currently active")
            if (noisyPatterns.any { title.contains(it) || text.contains(it) }) return true
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
            if (text.matches(Regex("\\d+ new messages?"))) return true
        }

        // 2. General heartbeat / progress noise
        if (n.category == Notification.CATEGORY_PROGRESS || n.category == Notification.CATEGORY_SERVICE) return true
        
        return false
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
            "com.samsung.android.incallui"
        )
        // SMS packages
        val smsApps = setOf("com.google.android.apps.messaging", "com.android.messaging", "com.samsung.android.messaging")
        
        if (pkg in dialers && (category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_MISSED_CALL || category == Notification.CATEGORY_MESSAGE)) {
            return true 
        }
        if (pkg in smsApps) return true
        
        return false
    }

    private fun extractBody(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        
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
        
        // Thread-safe check-and-set
        synchronized(recentNotificationWindows) {
            val lastSeen = recentNotificationWindows[key]
            if (lastSeen != null && (now - lastSeen) <= DEDUPE_WINDOW_MILLIS) {
                return true
            }
            recentNotificationWindows[key] = now
            return false
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}
