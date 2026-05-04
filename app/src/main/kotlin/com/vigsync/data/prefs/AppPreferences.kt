package com.vigsync.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "secure_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        val BROKER_URL = stringPreferencesKey("broker_url")
        val BROKER_PORT = stringPreferencesKey("broker_port")
        val TOPIC_PREFIX = stringPreferencesKey("topic_prefix")
        val PAIRED_DEVICES = stringPreferencesKey("paired_devices")
        val BROKER_USERNAME = stringPreferencesKey("broker_username")
        val USE_TLS = booleanPreferencesKey("use_tls")
        val MQTT_VERSION = intPreferencesKey("mqtt_version") // 3 for 3.1.1, 5 for v5
        
        // Sync Notification Controls
        val NOTIF_CALLS = booleanPreferencesKey("notif_calls")
        val NOTIF_SMS = booleanPreferencesKey("notif_sms")
        val NOTIF_OTHER = booleanPreferencesKey("notif_other")

        // Keys for EncryptedSharedPreferences
        private const val KEY_SHARED_KEY = "shared_key"
        private const val KEY_BROKER_PASSWORD = "broker_password"
    }

    val mqttVersion: Flow<Int> = context.dataStore.data.map { it[MQTT_VERSION] ?: 5 }

    suspend fun saveMqttVersion(version: Int) {
        context.dataStore.edit { it[MQTT_VERSION] = version }
    }

    val brokerUsername: Flow<String?> = context.dataStore.data.map { it[BROKER_USERNAME] }
    
    val brokerPassword: Flow<String?> = context.dataStore.data.map { 
        encryptedPrefs.getString(KEY_BROKER_PASSWORD, null)
    }

    val useTls: Flow<Boolean> = context.dataStore.data.map { it[USE_TLS] ?: false }

    suspend fun saveMqttAuth(username: String?, password: String?, tls: Boolean) {
        context.dataStore.edit { prefs ->
            if (username == null) prefs.remove(BROKER_USERNAME) else prefs[BROKER_USERNAME] = username
            prefs[USE_TLS] = tls
        }
        encryptedPrefs.edit().apply {
            if (password == null) remove(KEY_BROKER_PASSWORD) else putString(KEY_BROKER_PASSWORD, password)
        }.apply()
    }

    val pairedDevices: Flow<String?> = context.dataStore.data.map { it[PAIRED_DEVICES] }

    suspend fun savePairedDevices(json: String) {
        context.dataStore.edit { it[PAIRED_DEVICES] = json }
    }

    val brokerUrl: Flow<String> = context.dataStore.data.map { it[BROKER_URL] ?: "broker.hivemq.com" }
    val brokerPort: Flow<String> = context.dataStore.data.map { it[BROKER_PORT] ?: "1883" }
    
    val sharedKey: Flow<String?> = context.dataStore.data.map { 
        encryptedPrefs.getString(KEY_SHARED_KEY, null)
    }
    
    val topicPrefix: Flow<String?> = context.dataStore.data.map { it[TOPIC_PREFIX] }

    suspend fun saveBrokerConfig(url: String, port: String) {
        context.dataStore.edit {
            it[BROKER_URL] = url
            it[BROKER_PORT] = port
        }
    }

    suspend fun saveSharedKey(key: String) {
        encryptedPrefs.edit().putString(KEY_SHARED_KEY, key).apply()
    }

    suspend fun saveTopicPrefix(prefix: String) {
        context.dataStore.edit { it[TOPIC_PREFIX] = prefix }
    }

    val notifCalls: Flow<Boolean> = context.dataStore.data.map { it[NOTIF_CALLS] ?: true }
    val notifSms: Flow<Boolean> = context.dataStore.data.map { it[NOTIF_SMS] ?: true }
    val notifOther: Flow<Boolean> = context.dataStore.data.map { it[NOTIF_OTHER] ?: true }

    suspend fun saveNotifSettings(calls: Boolean, sms: Boolean, other: Boolean) {
        context.dataStore.edit {
            it[NOTIF_CALLS] = calls
            it[NOTIF_SMS] = sms
            it[NOTIF_OTHER] = other
        }
    }
}
