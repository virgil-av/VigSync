package com.vigsync.feature.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.vigsync.core.SyncManager

class CallReceiver : BroadcastReceiver() {
    
    companion object {
        private var lastState: String? = null
        private var lastStateChangeTime: Long = 0
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown"
            
            val now = System.currentTimeMillis()
            
            // 1. Debounce rapid identical state changes (within 2 seconds)
            if (state == lastState && (now - lastStateChangeTime) < 2000) {
                return
            }
            
            lastState = state
            lastStateChangeTime = now

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // 2. Ignore "Unknown" if we expect the system to eventually provide a number
                    if (number == "Unknown") {
                        Log.d("VigSync", "Ignored RINGING broadcast with Unknown number")
                        return
                    }
                    Log.d("VigSync", "Incoming call from: $number")
                    SyncManager.getInstance(context).publishEvent("CALL", "Incoming: $number")
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    Log.d("VigSync", "Call answered")
                    SyncManager.getInstance(context).publishEvent("CALL", "Answered")
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    Log.d("VigSync", "Call ended")
                    SyncManager.getInstance(context).publishEvent("CALL", "Ended")
                }
            }
        }
    }
}
