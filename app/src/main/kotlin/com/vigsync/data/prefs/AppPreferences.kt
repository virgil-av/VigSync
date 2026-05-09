package com.vigsync.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.vigsync.core.crypto.EncryptionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {

    companion object {
        // Server MQTT Configuration (For Raspberry Pi consumer)
        val BROKER_URL = stringPreferencesKey("server_broker_url")
        val BROKER_PORT = stringPreferencesKey("server_broker_port")
        val BROKER_USER = stringPreferencesKey("server_broker_user")
        val BROKER_PASS = stringPreferencesKey("server_broker_pass")
        val TOPIC_PREFIX = stringPreferencesKey("server_topic_prefix")
        val SHARED_KEY = stringPreferencesKey("shared_key")

        // Monitoring Notification Controls
        val NOTIF_CALLS = booleanPreferencesKey("notif_calls")
        val NOTIF_SMS = booleanPreferencesKey("notif_sms")
        val NOTIF_OTHER = booleanPreferencesKey("notif_other")

        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled") // Repurposed for local monitoring

        // App-specific filtering
        val OBSERVED_APP_PACKAGES = stringSetPreferencesKey("observed_app_packages")
        val DISABLED_APP_PACKAGES = stringSetPreferencesKey("disabled_app_packages")
    }

    suspend fun migrateIfNeeded() {
        // Generate a random key if none exists
        context.dataStore.edit { prefs ->
            if (prefs[SHARED_KEY].isNullOrEmpty()) {
                prefs[SHARED_KEY] = EncryptionManager.generateRandomKey()
            }
        }
    }

    // Server Config Accessors
    val brokerUrl: Flow<String> = context.dataStore.data.map { it[BROKER_URL] ?: "broker.hivemq.com" }
    val brokerPort: Flow<String> = context.dataStore.data.map { it[BROKER_PORT] ?: "1883" }
    val brokerUser: Flow<String> = context.dataStore.data.map { it[BROKER_USER] ?: "" }
    val brokerPass: Flow<String> = context.dataStore.data.map { it[BROKER_PASS] ?: "" }
    val topicPrefix: Flow<String> = context.dataStore.data.map { it[TOPIC_PREFIX] ?: "vigsync" }
    val sharedKey: Flow<String?> = context.dataStore.data.map { it[SHARED_KEY] }

    suspend fun saveServerConfig(url: String, port: String, user: String, pass: String, prefix: String) {
        context.dataStore.edit {
            it[BROKER_URL] = url
            it[BROKER_PORT] = port
            it[BROKER_USER] = user
            it[BROKER_PASS] = pass
            it[TOPIC_PREFIX] = prefix
        }
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

    val syncEnabled: Flow<Boolean> = context.dataStore.data.map { it[SYNC_ENABLED] ?: false }

    suspend fun saveSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SYNC_ENABLED] = enabled }
    }

    val observedAppPackages: Flow<Set<String>> = context.dataStore.data.map { it[OBSERVED_APP_PACKAGES] ?: emptySet() }
    val disabledAppPackages: Flow<Set<String>> = context.dataStore.data.map { it[DISABLED_APP_PACKAGES] ?: emptySet() }

    suspend fun addObservedPackage(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[OBSERVED_APP_PACKAGES] ?: emptySet()
            if (packageName !in current) {
                prefs[OBSERVED_APP_PACKAGES] = current + packageName
            }
        }
    }

    suspend fun toggleAppDisabled(packageName: String, disabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[DISABLED_APP_PACKAGES] ?: emptySet()
            if (disabled) {
                prefs[DISABLED_APP_PACKAGES] = current + packageName
            } else {
                prefs[DISABLED_APP_PACKAGES] = current - packageName
            }
        }
    }
}
