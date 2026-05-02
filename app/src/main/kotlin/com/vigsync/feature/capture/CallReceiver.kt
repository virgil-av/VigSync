package com.vigsync.feature.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

import com.vigsync.core.SyncManager

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown"
            
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
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
