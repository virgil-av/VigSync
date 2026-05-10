package com.vigsync.core

import android.content.Context
import android.os.Environment
import android.util.Log
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

@Serializable
data class ServerConfig(
    val broker_url: String,
    val broker_port: Int,
    val username: String,
    val password: String,
    val topic_prefix: String,
    val export_file_path: String,
    val device_id: String,
    val device_name: String,
    val shared_key: String?,
    val use_tls: Boolean
)

class ServerConfigManager(private val context: Context) {

    private val fileName = "vigsync_server_config.json"
    private val json = Json { prettyPrint = true }
    private val appPreferences = AppPreferences(context)

    fun getConfigFile(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, "documents").apply { if (!exists()) mkdirs() }
        return File(dir, fileName)
    }

    suspend fun generateConfigFile() {
        val syncManager = SyncManager.getInstance(context)
        val url = appPreferences.brokerUrl.first()
        val port = appPreferences.brokerPort.first().toIntOrNull() ?: 1883
        val user = appPreferences.brokerUser.first()
        val pass = appPreferences.brokerPass.first()
        val prefix = appPreferences.topicPrefix.first()
        val deviceId = syncManager.getLocalDeviceId()
        val deviceName = android.os.Build.MODEL
        val sharedKey = appPreferences.sharedKey.first()
        val useTls = appPreferences.useTls.first()
        
        val logFilePath = syncManager.getEventExporter().getExportFile().absolutePath

        val config = ServerConfig(
            broker_url = url,
            broker_port = port,
            username = user,
            password = pass,
            topic_prefix = prefix,
            export_file_path = logFilePath,
            device_id = deviceId,
            device_name = deviceName,
            shared_key = sharedKey,
            use_tls = useTls
        )

        val jsonString = json.encodeToString(config)
        val file = getConfigFile()

        try {
            FileOutputStream(file).use { fos ->
                fos.write(jsonString.toByteArray())
            }
            Log.d("ServerConfig", "Generated config file at ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("ServerConfig", "Failed to generate config file", e)
        }
    }
}
