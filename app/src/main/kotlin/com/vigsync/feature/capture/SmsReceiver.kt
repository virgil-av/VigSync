package com.vigsync.feature.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

import com.vigsync.core.SyncManager

import android.telephony.SmsMessage

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val canReadSms = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_SMS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (!canReadSms) {
                Log.w("VigSync", "SmsReceiver: Missing READ_SMS permission, cannot process message.")
                return
            }

            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return
            
            val sender = messages[0].displayOriginatingAddress ?: "Unknown"
            val body = messages.joinToString(separator = "") { it.displayMessageBody ?: "" }
            
            Log.d("VigSync", "SMS from $sender: $body")
            SyncManager.getInstance(context).publishEvent("SMS", "$sender: $body")
        }
    }
}
