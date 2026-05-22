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
    suspend fun getUnprocessedList(): List<RawMessage>

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

@Query("UPDATE device_status SET isOnline = :isOnline, lastSeen = :lastSeen, batteryLevel = :batteryLevel WHERE deviceId = :deviceId")
    suspend fun updateHeartbeat(deviceId: String, isOnline: Boolean, lastSeen: Long, batteryLevel: Int)

    @Query("SELECT * FROM device_status WHERE deviceId = :deviceId")
    suspend fun getDeviceStatus(deviceId: String): DeviceStatusEntity?

    @Query("SELECT * FROM device_status ORDER BY pairingTimestamp ASC")
    fun getAllDeviceStatus(): Flow<List<DeviceStatusEntity>>

    @Query("UPDATE device_status SET customLabel = :label WHERE deviceId = :deviceId")
    suspend fun updateDeviceLabel(deviceId: String, label: String?)

    @Query("UPDATE device_status SET isOnline = 0 WHERE lastSeen < :threshold")
    suspend fun markDevicesOfflineBefore(threshold: Long)

    @Query("DELETE FROM device_status WHERE deviceId = :deviceId")
    suspend fun deleteDeviceStatus(deviceId: String)

    @Query("""
        SELECT e.*, d.customLabel as deviceAlias 
        FROM events e 
        LEFT JOIN device_status d ON e.sourceDeviceId = d.deviceId 
        ORDER BY e.timestamp DESC
        LIMIT 500
    """)
    fun getAllEventsWithLabels(): Flow<List<EventWithLabel>>

    @Query("SELECT COUNT(*) FROM events WHERE sourceDeviceId = :deviceId OR sourceDeviceName = :deviceId")
    fun getEventCountForDevice(deviceId: String): Flow<Int>

    @Query("DELETE FROM events")
    suspend fun clearAllEvents()

    @Query("DELETE FROM events WHERE sourceDeviceId = :deviceId OR sourceDeviceName = :deviceId")
    suspend fun clearEventsForDevice(deviceId: String)

    @Query("SELECT version FROM host_protocols WHERE host = :host")
    suspend fun getProtocolForHost(host: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveHostProtocol(hostProtocol: HostProtocolEntity)
}

data class EventWithLabel(
    @Embedded val event: EventEntity,
    val deviceAlias: String?
) {
    val resolvedDeviceName: String
        get() = deviceAlias ?: event.sourceDeviceName ?: "Unknown"
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

@Database(entities = [RawMessage::class, EventEntity::class, DeviceStatusEntity::class, HostProtocolEntity::class], version = 8)
@TypeConverters(Converters::class)
abstract class VigSyncDatabase : RoomDatabase() {
    abstract fun dao(): VigSyncDao

    companion object {
        @Volatile
        private var INSTANCE: VigSyncDatabase? = null

        fun getInstance(context: Context): VigSyncDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VigSyncDatabase::class.java,
                    "vigsync_db"
                )
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
                .build().also { INSTANCE = it }
            }
        }
    }
}
