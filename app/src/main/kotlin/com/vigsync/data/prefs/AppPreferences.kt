package com.vigsync.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {

    companion object {
        val BROKER_URL = stringPreferencesKey("broker_url")
        val BROKER_PORT = stringPreferencesKey("broker_port")
        val TOPIC_PREFIX = stringPreferencesKey("topic_prefix")
        val PAIRED_DEVICES = stringPreferencesKey("paired_devices")
        val BROKER_USERNAME = stringPreferencesKey("broker_username")
        val BROKER_PASSWORD = stringPreferencesKey("broker_password")
        val SHARED_KEY = stringPreferencesKey("shared_key")
        val USE_TLS = booleanPreferencesKey("use_tls")
        val MQTT_VERSION = intPreferencesKey("mqtt_version") // 3 for 3.1.1, 5 for v5
        
        // Sync Notification Controls
        val NOTIF_CALLS = booleanPreferencesKey("notif_calls")
        val NOTIF_SMS = booleanPreferencesKey("notif_sms")
        val NOTIF_OTHER = booleanPreferencesKey("notif_other")

        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val SERVICE_ENABLED = booleanPreferencesKey("service_enabled")

        // Sharing Toggles (Safeguarded)
        val SHARE_CALLS = booleanPreferencesKey("share_calls")
        val SHARE_SMS = booleanPreferencesKey("share_sms")
        val SHARE_NOTIFICATIONS = booleanPreferencesKey("share_notifications")

        // App-specific filtering
        val OBSERVED_APP_PACKAGES = stringSetPreferencesKey("observed_app_packages")
        val DISABLED_APP_PACKAGES = stringSetPreferencesKey("disabled_app_packages")

        // Keys for Migration
        private const val OLD_SECURE_SETTINGS = "secure_settings"
        private const val OLD_KEY_SHARED_KEY = "shared_key"
        private const val OLD_KEY_BROKER_PASSWORD = "broker_password"
        private const val KEY_MIGRATED = "prefs_migrated_v2"
    }

    suspend fun migrateIfNeeded() {
        context.dataStore.edit { prefs ->
            // 1. Ensure shared key exists (Generate if new installation)
            if (prefs[SHARED_KEY].isNullOrEmpty()) {
                val newKey = com.vigsync.core.crypto.EncryptionManager.generateRandomKey()
                prefs[SHARED_KEY] = newKey
            }
            
            // 2. Ensure topic prefix exists
            if (prefs[TOPIC_PREFIX].isNullOrEmpty()) {
                prefs[TOPIC_PREFIX] = "vigsync/${java.util.UUID.randomUUID()}"
            }

            if (prefs[booleanPreferencesKey(KEY_MIGRATED)] == true) return@edit

            try {
                // We use reflection to try to load the security library classes
                // If the library was removed from build.gradle.kts, this will fail gracefully
                val masterKeyClass = Class.forName("androidx.security.crypto.MasterKey")
                val builderClass = Class.forName("androidx.security.crypto.MasterKey\$Builder")
                val schemeClass = Class.forName("androidx.security.crypto.MasterKey\$KeyScheme")
                val sharedPrefsClass = Class.forName("androidx.security.crypto.EncryptedSharedPreferences")
                val keySchemeClass = Class.forName("androidx.security.crypto.EncryptedSharedPreferences\$PrefKeyEncryptionScheme")
                val valueSchemeClass = Class.forName("androidx.security.crypto.EncryptedSharedPreferences\$PrefValueEncryptionScheme")

                val builder = builderClass.getConstructor(Context::class.java).newInstance(context)
                val aes256gcm = schemeClass.getField("AES256_GCM").get(null)
                builderClass.getMethod("setKeyScheme", schemeClass).invoke(builder, aes256gcm)
                val masterKey = builderClass.getMethod("build").invoke(builder)

                val createMethod = sharedPrefsClass.getMethod(
                    "create",
                    Context::class.java,
                    String::class.java,
                    masterKeyClass,
                    keySchemeClass,
                    valueSchemeClass
                )

                val aes256siv = keySchemeClass.getField("AES256_SIV").get(null)
                val aes256gcmValue = valueSchemeClass.getField("AES256_GCM").get(null)

                val encryptedPrefs = createMethod.invoke(
                    null,
                    context,
                    OLD_SECURE_SETTINGS,
                    masterKey,
                    aes256siv,
                    aes256gcmValue
                ) as android.content.SharedPreferences

                val oldPass = encryptedPrefs.getString(OLD_KEY_BROKER_PASSWORD, null)
                val oldKey = encryptedPrefs.getString(OLD_KEY_SHARED_KEY, null)

                if (oldPass != null) prefs[BROKER_PASSWORD] = oldPass
                if (oldKey != null) prefs[SHARED_KEY] = oldKey
                
                prefs[booleanPreferencesKey(KEY_MIGRATED)] = true
            } catch (e: Exception) {
                // Migration failed or library not present
                prefs[booleanPreferencesKey(KEY_MIGRATED)] = true
            }
        }
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
    val serviceEnabled: Flow<Boolean> = context.dataStore.data.map { it[SERVICE_ENABLED] ?: false }

    suspend fun saveSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SYNC_ENABLED] = enabled }
    }

    suspend fun saveServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SERVICE_ENABLED] = enabled }
    }

    val shareCalls: Flow<Boolean> = context.dataStore.data.map { it[SHARE_CALLS] ?: false }
    val shareSms: Flow<Boolean> = context.dataStore.data.map { it[SHARE_SMS] ?: false }
    val shareNotifications: Flow<Boolean> = context.dataStore.data.map { it[SHARE_NOTIFICATIONS] ?: false }

    suspend fun saveSharingSettings(calls: Boolean, sms: Boolean, notifications: Boolean) {
        context.dataStore.edit {
            it[SHARE_CALLS] = calls
            it[SHARE_SMS] = sms
            it[SHARE_NOTIFICATIONS] = notifications
        }
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
