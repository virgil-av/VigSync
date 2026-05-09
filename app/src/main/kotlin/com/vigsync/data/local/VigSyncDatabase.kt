package com.vigsync.data.local

import android.content.Context
import androidx.room.*
import com.vigsync.core.models.EventDirection
import com.vigsync.core.models.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface VigSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventEntity)

    @Query("SELECT * FROM events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<EventEntity>>

    @Query("SELECT COUNT(*) FROM events WHERE payloadHash = :hash")
    suspend fun countEventHash(hash: String): Int

    @Query("SELECT COUNT(*) FROM events WHERE sourceDevice = :deviceName")
    fun getEventCountForDevice(deviceName: String): Flow<Int>

    @Query("DELETE FROM events")
    suspend fun clearAllEvents()

    @Query("DELETE FROM events WHERE sourceDevice = :deviceName")
    suspend fun clearEventsForDevice(deviceName: String)
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

@Database(entities = [EventEntity::class], version = 7)
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
