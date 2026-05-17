package com.vigsync.core

import android.content.Context
import android.util.Log
import com.vigsync.data.local.VigSyncDatabase
import kotlinx.coroutines.*

class SyncManager private constructor(context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = context.applicationContext

    private val database: VigSyncDatabase by lazy { 
        VigSyncDatabase.getInstance(appContext!!) 
    }

    companion object {
        @Volatile
        private var INSTANCE: SyncManager? = null

        fun getInstance(context: Context): SyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncManager(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
    }

    init {
        Log.d("SyncManager", "Client SyncManager Initialized")
    }

    fun stop() {
        Log.d("SyncManager", "SyncManager Stop triggered")
    }

    fun getDao() = database.dao()
}
