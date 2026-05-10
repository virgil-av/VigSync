package com.vigsync.feature.capture

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.SubscriptionManager
import android.util.Log
import com.vigsync.core.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CallLogObserver(
    private val context: Context
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val appContext = context.applicationContext
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = appContext.getSharedPreferences("call_log_observer", Context.MODE_PRIVATE)
    private var lastProcessedEntryId: Long = -1L
    private val processingMutex = Mutex()

    init {
        lastProcessedEntryId = preferences.getLong("last_processed_call_id", -1L)
    }

    fun register() {
        appContext.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, this)
        observerScope.launch {
            processingMutex.withLock {
                if (lastProcessedEntryId == -1L) {
                    seedLastProcessedId()
                }
            }
        }
    }

    fun unregister() {
        appContext.contentResolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        Log.d("VigSync", "Call Log changed, processing...")
        processNewCalls()
    }

    fun processNewCalls() {
        observerScope.launch {
            processingMutex.withLock {
                try {
                    // System needs time to write to log
                    delay(3000) 

                    if (lastProcessedEntryId == -1L) {
                        seedLastProcessedId()
                        return@withLock
                    }

                    val newCalls = queryNewCalls(lastProcessedEntryId)
                    Log.d("VigSync", "Found ${newCalls.size} new call entries since $lastProcessedEntryId")

                    for (call in newCalls) {
                        lastProcessedEntryId = call.entryId
                        preferences.edit().putLong("last_processed_call_id", lastProcessedEntryId).apply()

                        if (call.callType == "Missed") {
                            val simLabel = getSimLabel(call.subscriptionId)
                            val enrichedData = "System Phone|com.android.server.telecom|[$simLabel] From: ${call.number}"
                            Log.d("VigSync", "System Missed Call Captured: $enrichedData")
                            SyncManager.getInstance(appContext).publishEvent("SYSTEM MISSED CALL", enrichedData)
                        }
                    }
                } catch (error: Exception) {
                    Log.e("CallLogObserver", "Call processing error", error)
                }
            }
        }
    }

    private fun getSimLabel(subId: String?): String {
        if (subId == null) return "SIM Unknown"
        
        val sm = appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return "SIM $subId"
            
        return try {
            val canReadPhoneState = androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (canReadPhoneState) {
                val id = subId.toIntOrNull()
                if (id != null) {
                    val info = sm.getActiveSubscriptionInfo(id)
                    if (info != null) {
                        val carrier = info.carrierName?.toString() ?: "Unknown Carrier"
                        return "SIM ${info.simSlotIndex + 1} - $carrier"
                    }
                }
            }
            "SIM $subId"
        } catch (e: Exception) {
            "SIM $subId"
        }
    }

    private fun seedLastProcessedId() {
        try {
            // REMOVED 'LIMIT 1' which causes crashes on some devices
            appContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                null,
                null,
                "${CallLog.Calls._ID} DESC"
            )?.use { cursor ->
                lastProcessedEntryId = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                preferences.edit().putLong("last_processed_call_id", lastProcessedEntryId).apply()
                Log.d("CallLogObserver", "Seeded lastProcessedEntryId: $lastProcessedEntryId")
            }
        } catch (error: Exception) {
            Log.e("CallLogObserver", "Failed to seed call baseline", error)
        }
    }

    private fun queryNewCalls(sinceId: Long): List<CallLogEvent> {
        val results = mutableListOf<CallLogEvent>()
        val selection = "${CallLog.Calls._ID} > ?"
        val selectionArgs = arrayOf(sinceId.toString())

        try {
            appContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null,
                selection,
                selectionArgs,
                "${CallLog.Calls._ID} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    results.add(cursor.toCallLogEvent())
                }
            }
        } catch (error: Exception) {
            Log.e("CallLogObserver", "Call query failed", error)
        }
        return results
    }

    private fun Cursor.toCallLogEvent(): CallLogEvent {
        val id = getLong(getColumnIndexOrThrow(CallLog.Calls._ID))
        val number = getString(getColumnIndexOrThrow(CallLog.Calls.NUMBER)).orEmpty()
        val typeValue = getInt(getColumnIndexOrThrow(CallLog.Calls.TYPE))
        val durationSeconds = getLong(getColumnIndexOrThrow(CallLog.Calls.DURATION))
        
        var subId: String? = null
        try {
            val idx = getColumnIndex("subscription_id")
            if (idx != -1) subId = getString(idx)
        } catch (e: Exception) {}
        
        if (subId == null) {
            try {
                val idx = getColumnIndex("phone_account_id")
                if (idx != -1) subId = getString(idx)
            } catch (e: Exception) {}
        }

        return CallLogEvent(
            entryId = id,
            number = number,
            callType = mapCallType(typeValue),
            durationSeconds = durationSeconds,
            subscriptionId = subId
        )
    }

    private fun mapCallType(typeValue: Int): String {
        return when (typeValue) {
            CallLog.Calls.INCOMING_TYPE -> "Incoming"
            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
            CallLog.Calls.MISSED_TYPE -> "Missed"
            else -> "Unknown"
        }
    }
}

data class CallLogEvent(
    val entryId: Long,
    val number: String,
    val callType: String,
    val durationSeconds: Long,
    val subscriptionId: String?
)
