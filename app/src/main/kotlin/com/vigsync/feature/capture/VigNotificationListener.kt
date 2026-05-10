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
    private val DEDUPE_WINDOW_MILLIS = 8000L
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
                Log.d("VigSync", "Ignored notification from DISABLED app: $packageName")
                return@launch
            }

            if (isNoisyNotification(sbn)) {
                Log.d("VigSync", "Ignored noisy notification from $packageName")
                return@launch
            }

            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extractBody(sbn)

            // Deduplication Logic: Ignore if it's likely handled by Dialer/SMS observers
            if (isLikelyRedundant(sbn)) {
                Log.d("VigSync", "Ignored redundant notification from $packageName")
                return@launch
            }

            val eventKey = "$packageName|$title|$text"
            val now = System.currentTimeMillis()
            
            if (isDuplicate(eventKey, now)) {
                Log.d("VigSync", "Ignored duplicate notification from $packageName")
                return@launch
            }

            val pm = applicationContext.packageManager
            val appLabel = try {
                val info = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(info).toString()
            } catch (e: Exception) { packageName }

            var eventType = "NOTIFICATION"
            var eventData = "$appLabel|$packageName|$title: $text"

            // --- SPECIAL VOIP MISSED CALL DETECTION ---
            if (packageName == "com.whatsapp") {
                val isCallCategory = sbn.notification.category == Notification.CATEGORY_CALL
                val isMissedInTitle = title.contains("Missed", ignoreCase = true)
                val isMissedInText = text.contains("Missed", ignoreCase = true)
                
                if (isCallCategory || isMissedInTitle || isMissedInText) {
                    // Only log if it's actually missed (heuristically)
                    if (isMissedInTitle || isMissedInText) {
                        eventType = "WHATSAPP MISSED CALL"
                        eventData = "From: $title"
                    } else if (isCallCategory) {
                        // This might be an active call, we skip active calls as requested
                        // and wait for the "Missed" notification which usually follows if not answered.
                        Log.d("VigSync", "Ignored active WhatsApp call notification")
                        return@launch
                    }
                }
            }

            Log.d("VigSync", "Event Captured ($eventType) from $packageName: $title - $text")
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
            // Filter out summary notifications like "2 new messages"
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
            "com.samsung.android.incallui",
            "com.whatsapp",
            "org.telegram.messenger"
        )
        // SMS packages
        val smsApps = setOf("com.google.android.apps.messaging", "com.android.messaging", "com.samsung.android.messaging")
        
        // We now filter out normal call notifications more aggressively as we focus on missed calls via log
        if (pkg in dialers && (category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_MISSED_CALL || category == Notification.CATEGORY_MESSAGE)) {
            // Exceptions: we might want to keep the system missed call notification if log fails? 
            // No, user wants specifically to differentiate and focus on missed calls.
            // Let's rely on CallLogObserver for system missed calls.
            return true 
        }
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
