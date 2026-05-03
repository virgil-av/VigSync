package com.vigsync.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {

    companion object {
        val BROKER_URL = stringPreferencesKey("broker_url")
        val BROKER_PORT = stringPreferencesKey("broker_port")
        val SHARED_KEY = stringPreferencesKey("shared_key")
        val TOPIC_PREFIX = stringPreferencesKey("topic_prefix")
        val PAIRED_DEVICES = stringPreferencesKey("paired_devices")
        val BROKER_USERNAME = stringPreferencesKey("broker_username")
        val BROKER_PASSWORD = stringPreferencesKey("broker_password")
        val USE_TLS = booleanPreferencesKey("use_tls")
        val MQTT_VERSION = intPreferencesKey("mqtt_version") // 3 for 3.1.1, 5 for v5
    }

    val mqttVersion: Flow<Int> = context.dataStore.data.map { it[MQTT_VERSION] ?: 5 }

    suspend fun saveMqttVersion(version: Int) {
        context.dataStore.edit { it[MQTT_VERSION] = version }
    }

    val brokerUsername: Flow<String?> = context.dataStore.data.map { it[BROKER_USERNAME] }
    val brokerPassword: Flow<String?> = context.dataStore.data.map { it[BROKER_PASSWORD] }
    val useTls: Flow<Boolean> = context.dataStore.data.map { it[USE_TLS] ?: false }

    suspend fun saveMqttAuth(username: String?, password: String?, tls: Boolean) {
        context.dataStore.edit { prefs ->
            if (username == null) prefs.remove(BROKER_USERNAME) else prefs[BROKER_USERNAME] = username
            if (password == null) prefs.remove(BROKER_PASSWORD) else prefs[BROKER_PASSWORD] = password
            prefs[USE_TLS] = tls
        }
    }

    val pairedDevices: Flow<String?> = context.dataStore.data.map { it[PAIRED_DEVICES] }

    suspend fun savePairedDevices(json: String) {
        context.dataStore.edit { it[PAIRED_DEVICES] = json }
    }

    val brokerUrl: Flow<String> = context.dataStore.data.map { it[BROKER_URL] ?: "broker.hivemq.com" }
    val brokerPort: Flow<String> = context.dataStore.data.map { it[BROKER_PORT] ?: "1883" }
    val sharedKey: Flow<String?> = context.dataStore.data.map { it[SHARED_KEY] }
    val topicPrefix: Flow<String?> = context.dataStore.data.map { it[TOPIC_PREFIX] }

    suspend fun saveBrokerConfig(url: String, port: String) {
        context.dataStore.edit {
            it[BROKER_URL] = url
            it[BROKER_PORT] = port
        }
    }

    suspend fun saveSharedKey(key: String) {
        context.dataStore.edit { it[SHARED_KEY] = key }
    }

    suspend fun saveTopicPrefix(prefix: String) {
        context.dataStore.edit { it[TOPIC_PREFIX] = prefix }
    }
}
