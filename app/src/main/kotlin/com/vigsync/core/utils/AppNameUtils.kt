package com.vigsync.core.utils

import java.util.Locale

object AppNameUtils {
    private val commonApps = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.facebook.orca" to "Messenger",
        "org.telegram.messenger" to "Telegram",
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "com.twitter.android" to "X (Twitter)",
        "com.google.android.gm" to "Gmail",
        "com.microsoft.office.outlook" to "Outlook",
        "com.snapchat.android" to "Snapchat",
        "com.viber.voip" to "Viber",
        "com.skype.raider" to "Skype",
        "com.discord" to "Discord",
        "com.google.android.apps.messaging" to "Google Messages",
        "com.android.chrome" to "Chrome",
        "com.google.android.youtube" to "YouTube"
    )

    fun extractDisplayName(packageName: String): String {
        // 1. Check mapping for common apps
        commonApps[packageName.lowercase()]?.let { return it }

        // 2. Generic extraction
        return try {
            val parts = packageName.split(".")
            if (parts.size >= 2) {
                // If it starts with common prefixes, ignore the first part
                val startIndex = if (parts[0] in listOf("com", "org", "net", "io", "android")) 1 else 0
                
                // Take up to 2 parts and capitalize
                parts.drop(startIndex)
                    .take(2)
                    .joinToString(" ") { part ->
                        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    }
            } else {
                packageName // Fallback to raw if it's too short
            }
        } catch (e: Exception) {
            packageName // Absolute fallback
        }
    }
}
