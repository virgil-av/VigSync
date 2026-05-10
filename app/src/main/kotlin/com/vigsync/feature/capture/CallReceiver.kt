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
            if (state == lastState && (now - lastStateChangeTime) < 2000) return
            
            lastState = state
            lastStateChangeTime = now

            if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                Log.d("VigSync", "Call state IDLE: Triggering log check")
                // We'll try to find the service and trigger it if possible, 
                // but usually the ContentObserver in CallLogObserver handles this.
            }
        }
    }
}
