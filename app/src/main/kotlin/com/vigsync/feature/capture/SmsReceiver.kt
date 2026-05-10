package com.vigsync.feature.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import com.vigsync.core.SyncManager

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val canReadSms = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_SMS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (!canReadSms) {
                Log.w("VigSync", "SmsReceiver: Missing READ_SMS permission.")
                return
            }

            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return
            
            val sender = messages[0].displayOriginatingAddress ?: "Unknown"
            val body = messages.joinToString(separator = "") { it.displayMessageBody ?: "" }
            
            // --- DUAL SIM DETECTION ---
            val subId = intent.getIntExtra("subscription", -1)
            val slotId = intent.getIntExtra("slot", -1)
            
            val simLabel = getSimLabel(context, subId, slotId)
            val enrichedData = "[$simLabel] SMS from $sender: $body"
            
            Log.d("VigSync", enrichedData)
            SyncManager.getInstance(context).publishEvent("SMS", enrichedData)
        }
    }

    private fun getSimLabel(context: Context, subId: Int, slotId: Int): String {
        if (subId == -1) return "Unknown SIM"
        
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return "SIM Slot ${slotId + 1}"
            
        return try {
            val canReadPhoneState = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (canReadPhoneState) {
                val info = sm.getActiveSubscriptionInfo(subId)
                if (info != null) {
                    val carrier = info.carrierName?.toString() ?: "Unknown Carrier"
                    return "SIM ${info.simSlotIndex + 1} - $carrier"
                }
            }
            "SIM Slot ${slotId + 1}"
        } catch (e: Exception) {
            "SIM Slot ${slotId + 1}"
        }
    }
}
