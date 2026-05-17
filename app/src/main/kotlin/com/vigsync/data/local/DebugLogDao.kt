package com.vigsync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DebugLogDao {
    @Insert
    suspend fun insertMqttLog(log: MqttLogEntity)

    @Insert
    suspend fun insertSystemLog(log: SystemLogEntity)

    @Query("SELECT * FROM mqtt_logs ORDER BY timestamp DESC LIMIT 200")
    fun getAllMqttLogs(): Flow<List<MqttLogEntity>>

    @Query("SELECT * FROM system_logs ORDER BY timestamp DESC LIMIT 200")
    fun getAllSystemLogs(): Flow<List<SystemLogEntity>>

    @Query("DELETE FROM mqtt_logs")
    suspend fun clearMqttLogs()

    @Query("DELETE FROM system_logs")
    suspend fun clearSystemLogs()
}
