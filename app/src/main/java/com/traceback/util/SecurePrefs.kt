package com.traceback.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Verschlüsselte SharedPreferences für sensible Daten.
 * Bot-Token und Chat-ID werden niemals im Klartext gespeichert.
 */
class SecurePrefs(context: Context) {
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "traceback_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    companion object {
        private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
        private const val KEY_EMERGENCY_SMS_NUMBER = "emergency_sms_number"
        private const val KEY_TRACKING_DISTANCE_METERS = "tracking_distance_m"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_ts"
        private const val KEY_STATIONARY_ANCHORS = "stationary_anchors"
        private const val KEY_LAST_BREATH_THRESHOLD = "last_breath_threshold"
        private const val KEY_LAST_BREATH_THRESHOLDS = "last_breath_thresholds"
    }
    
    // Telegram Configuration
    var telegramBotToken: String?
        get() = prefs.getString(KEY_TELEGRAM_BOT_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TELEGRAM_BOT_TOKEN, value).apply()
    
    var telegramChatId: String?
        get() = prefs.getString(KEY_TELEGRAM_CHAT_ID, null)
        set(value) = prefs.edit().putString(KEY_TELEGRAM_CHAT_ID, value).apply()
    
    var emergencySmsNumber: String?
        get() = prefs.getString(KEY_EMERGENCY_SMS_NUMBER, null)
        set(value) = prefs.edit().putString(KEY_EMERGENCY_SMS_NUMBER, value).apply()
    
    // Tracking Settings
    var trackingDistanceMeters: Int
        get() = prefs.getInt(KEY_TRACKING_DISTANCE_METERS, 300)
        set(value) = prefs.edit().putInt(KEY_TRACKING_DISTANCE_METERS, value.coerceIn(50, 2000)).apply()
    
    var trackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TRACKING_ENABLED, value).apply()
    
    // Last Breath threshold (1-20%, default 5%) - DEPRECATED, use lastBreathThresholds
    var lastBreathThreshold: Int
        get() = prefs.getInt(KEY_LAST_BREATH_THRESHOLD, 5)
        set(value) = prefs.edit().putInt(KEY_LAST_BREATH_THRESHOLD, value.coerceIn(1, 20)).apply()
    
    // Last Breath thresholds as Set (15, 8, 4, 2)
    var lastBreathThresholds: Set<Int>
        get() {
            val stringSet = prefs.getStringSet(KEY_LAST_BREATH_THRESHOLDS, null)
            return stringSet?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(8, 4) // Default: 8% and 4%
        }
        set(value) {
            val stringSet = value.map { it.toString() }.toSet()
            prefs.edit().putStringSet(KEY_LAST_BREATH_THRESHOLDS, stringSet).apply()
        }
    
    // Sync Status
    var lastSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP, value).apply()
    
    // Stationary Anchors (JSON-encoded Set of SSID/MAC)
    var stationaryAnchors: Set<String>
        get() = prefs.getStringSet(KEY_STATIONARY_ANCHORS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_STATIONARY_ANCHORS, value).apply()
    
    fun addStationaryAnchor(anchor: String) {
        stationaryAnchors = stationaryAnchors + anchor
    }
    
    fun removeStationaryAnchor(anchor: String) {
        stationaryAnchors = stationaryAnchors - anchor
    }
    
    fun isConfiguredForEmergency(): Boolean {
        return !telegramBotToken.isNullOrBlank() && !telegramChatId.isNullOrBlank()
    }
}
