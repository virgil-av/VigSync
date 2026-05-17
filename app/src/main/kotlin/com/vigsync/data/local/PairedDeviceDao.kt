package com.vigsync.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedDeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: PairedDeviceEntity)

    @Query("SELECT * FROM paired_devices")
    fun getAllDevices(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices WHERE deviceId = :id")
    suspend fun getDeviceById(id: String): PairedDeviceEntity?

    @Delete
    suspend fun deleteDevice(device: PairedDeviceEntity)

    @Query("UPDATE paired_devices SET isOnline = :online, batteryLevel = :battery, lastSeen = :timestamp WHERE deviceId = :id")
    suspend fun updateStatus(id: String, online: Boolean, battery: Int?, timestamp: Long)
}
