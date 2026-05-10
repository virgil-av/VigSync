package com.vigsync.feature.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class CallReceiver : BroadcastReceiver() {
    
    companion object {
        private var lastState: String? = null
        private var lastStateChangeTime: Long = 0
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            
            val now = System.currentTimeMillis()
            
            // Debounce rapid identical state changes (within 2 seconds)
            if (state == lastState && (now - lastStateChangeTime) < 2000) {
                return
            }
            
            lastState = state
            lastStateChangeTime = now

            // --- REDUCED NOISE: NO DIRECT EVENT PUBLISHING ---
            // We only use this receiver as a prompt for the CallLogObserver
            // specifically when a call ends (returns to IDLE).
            if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                Log.d("VigSync", "Call state IDLE: Prompting CallLogObserver check")
                // Note: CallLogObserver is a ContentObserver and usually fires automatically,
                // but we could trigger it manually here if we had a reference.
                // Since MqttService owns it, we'll let the system content observer handle it
                // and avoid direct CALL events here to eliminate "ended" spam.
            }
        }
    }
}
