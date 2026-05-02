package com.vigsync.feature.capture

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import com.vigsync.core.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        observerScope.cancel()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        processNewCalls()
    }

    private fun processNewCalls() {
        observerScope.launch {
            processingMutex.withLock {
                try {
                    delay(2500) // Wait for system to write to log

                    val maxId = getMaxCallId()
                    if (lastProcessedEntryId == -1L) {
                        seedLastProcessedId()
                        return@withLock
                    }

                    val newCalls = queryNewCalls(lastProcessedEntryId)
                    for (call in newCalls) {
                        SyncManager.getInstance(appContext).publishEvent("CALL", "Call ${call.callType}: ${call.number} (Duration: ${call.durationSeconds}s)")
                        lastProcessedEntryId = call.entryId
                        preferences.edit().putLong("last_processed_call_id", lastProcessedEntryId).apply()
                    }
                } catch (error: Exception) {
                    Log.e("CallLogObserver", "Call processing error", error)
                }
            }
        }
    }

    private fun seedLastProcessedId() {
        try {
            appContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                null,
                null,
                "${CallLog.Calls._ID} DESC LIMIT 1"
            )?.use { cursor ->
                lastProcessedEntryId = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                preferences.edit().putLong("last_processed_call_id", lastProcessedEntryId).apply()
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

        return CallLogEvent(
            entryId = id,
            number = number,
            callType = mapCallType(typeValue),
            durationSeconds = durationSeconds
        )
    }

    private fun getMaxCallId(): Long {
        return try {
            appContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                null,
                null,
                "${CallLog.Calls._ID} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
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
    val durationSeconds: Long
)
