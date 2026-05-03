package com.vigsync.data.local

import android.content.Context
import androidx.room.*
import com.vigsync.core.models.EventDirection
import com.vigsync.core.models.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface VigSyncDao {
    @Insert
    suspend fun insertRaw(message: RawMessage)

    @Query("SELECT * FROM raw_messages WHERE isProcessed = 0 ORDER BY timestamp ASC")
    fun getUnprocessedFlow(): Flow<List<RawMessage>>

    @Query("UPDATE raw_messages SET isProcessed = 1 WHERE id = :id")
    suspend fun markAsProcessed(id: Long)

    @Query("SELECT * FROM raw_messages ORDER BY timestamp DESC LIMIT 50")
    fun getRecentRaw(): Flow<List<RawMessage>>

    @Update
    suspend fun updateRaw(message: RawMessage)

    @Query("DELETE FROM raw_messages WHERE isProcessed = 1 AND timestamp < :threshold")
    suspend fun cleanupRaw(threshold: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventEntity)

    @Query("SELECT * FROM events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<EventEntity>>

    @Query("SELECT COUNT(*) FROM events WHERE payloadHash = :hash")
    suspend fun countEventHash(hash: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateDeviceStatus(status: DeviceStatusEntity)

    @Query("SELECT * FROM device_status ORDER BY lastSeen DESC")
    fun getAllDeviceStatus(): Flow<List<DeviceStatusEntity>>

    @Query("DELETE FROM device_status WHERE deviceId = :deviceId")
    suspend fun deleteDeviceStatus(deviceId: String)

    @Query("DELETE FROM events")
    suspend fun clearAllEvents()

    @Query("SELECT version FROM host_protocols WHERE host = :host")
    suspend fun getProtocolForHost(host: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveHostProtocol(hostProtocol: HostProtocolEntity)
}

class Converters {
    @TypeConverter
    fun fromSyncStatus(value: SyncStatus) = value.name
    @TypeConverter
    fun toSyncStatus(value: String) = SyncStatus.valueOf(value)

    @TypeConverter
    fun fromDirection(value: EventDirection) = value.name
    @TypeConverter
    fun toDirection(value: String) = EventDirection.valueOf(value)
}

@Database(entities = [RawMessage::class, EventEntity::class, DeviceStatusEntity::class, HostProtocolEntity::class], version = 2)
@TypeConverters(Converters::class)
abstract class VigSyncDatabase : RoomDatabase() {
    abstract fun dao(): VigSyncDao

    companion object {
        @Volatile
        private var INSTANCE: VigSyncDatabase? = null

        fun getInstance(context: Context): VigSyncDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VigSyncDatabase::class.java,
                    "vigsync_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
