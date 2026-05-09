package com.vigsync.core

import android.content.Context
import android.os.Environment
import android.util.Log
import com.vigsync.core.crypto.EncryptionManager
import com.vigsync.core.models.RawMessage
import com.vigsync.data.prefs.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class EventExporter(private val context: Context) {

    private val fileName = "vigsync_events.jsonl"
    private val jsonPretty = Json { prettyPrint = true }
    private val jsonLine = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val appPreferences = AppPreferences(context)
    
    fun getExportFile(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) 
            ?: File(context.filesDir, "documents").apply { if (!exists()) mkdirs() }
            
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, fileName)
    }

    /**
     * Exports a message to the log file.
     * Format: topic_suffix|encrypted_payload
     */
    fun exportMessage(topicSuffix: String, message: RawMessage) {
        val file = getExportFile()
        val rawJson = jsonLine.encodeToString(message)
        
        // Encrypt the JSON line
        val sharedKey = runBlocking { appPreferences.sharedKey.first() }
        val encryptedData = if (!sharedKey.isNullOrEmpty()) {
            EncryptionManager(sharedKey).encrypt(rawJson) ?: rawJson
        } else {
            Log.w("EventExporter", "No shared key found, exporting as plain text")
            rawJson
        }
        
        // Save as topic_suffix|encrypted_data
        val lineToSave = "$topicSuffix|$encryptedData\n"
        
        try {
            FileOutputStream(file, true).use { fos ->
                fos.write(lineToSave.toByteArray())
                fos.flush()
            }
            Log.d("EventExporter", "Exported (Encrypted) to $topicSuffix: ${rawJson.take(50)}...")
        } catch (e: IOException) {
            Log.e("EventExporter", "Failed to write to export file", e)
        }
    }

    fun resetFile() {
        val file = getExportFile()
        if (file.exists()) {
            file.delete()
        }
        try {
            file.createNewFile()
        } catch (e: IOException) {
            Log.e("EventExporter", "Failed to reset export file", e)
        }
    }

    fun readAndBeautify(): String {
        val file = getExportFile()
        if (!file.exists()) return "Log file does not exist."
        
        val sharedKey = runBlocking { appPreferences.sharedKey.first() }
        val encryptionManager = if (!sharedKey.isNullOrEmpty()) EncryptionManager(sharedKey) else null

        return try {
            val lines = file.readLines()
            if (lines.isEmpty()) return "Log file is empty."
            
            lines.joinToString(separator = "\n\n") { line ->
                if (line.isBlank() || !line.contains("|")) return@joinToString ""
                val payload = line.substringAfter("|")
                try {
                    val decrypted = encryptionManager?.decrypt(payload) ?: payload
                    val element = jsonLine.parseToJsonElement(decrypted)
                    jsonPretty.encodeToString(element)
                } catch (e: Exception) {
                    "// [Encrypted Data] or Failed to parse: $payload"
                }
            }.trim()
        } catch (e: Exception) {
            Log.e("EventExporter", "Failed to read export file", e)
            "Error reading log file: ${e.message}"
        }
    }
}
