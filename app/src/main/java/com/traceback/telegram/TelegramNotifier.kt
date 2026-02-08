package com.traceback.telegram

import android.util.Log
import com.traceback.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Telegram Bot API client for emergency notifications.
 * Uses OkHttp for reliable, fast delivery.
 */
class TelegramNotifier(private val prefs: SecurePrefs) {
    
    companion object {
        private const val TAG = "TelegramNotifier"
        private const val BASE_URL = "https://api.telegram.org/bot"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    /**
     * Send emergency message via Telegram Bot API.
     * @return true if message was sent successfully
     */
    suspend fun sendEmergency(message: String): Boolean = withContext(Dispatchers.IO) {
        val token = prefs.telegramBotToken
        val chatId = prefs.telegramChatId
        
        if (token.isNullOrBlank() || chatId.isNullOrBlank()) {
            Log.w(TAG, "Telegram not configured")
            return@withContext false
        }
        
        try {
            val url = "${BASE_URL}${token}/sendMessage"
            
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", message)
                put("parse_mode", "HTML")
                put("disable_web_page_preview", false)
            }
            
            val body = json.toString().toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Message sent successfully")
                    return@withContext true
                } else {
                    Log.e(TAG, "Telegram API error: ${response.code} - ${response.body?.string()}")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Telegram message", e)
            return@withContext false
        }
    }
    
    /**
     * Send location as a map pin.
     */
    suspend fun sendLocation(latitude: Double, longitude: Double): Boolean = withContext(Dispatchers.IO) {
        val token = prefs.telegramBotToken
        val chatId = prefs.telegramChatId
        
        if (token.isNullOrBlank() || chatId.isNullOrBlank()) {
            return@withContext false
        }
        
        try {
            val url = "${BASE_URL}${token}/sendLocation"
            
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("latitude", latitude)
                put("longitude", longitude)
            }
            
            val body = json.toString().toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()
            
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send location", e)
            return@withContext false
        }
    }
    
    /**
     * Test connection with a simple getMe call.
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val token = prefs.telegramBotToken ?: return@withContext false
        
        try {
            val url = "${BASE_URL}${token}/getMe"
            val request = Request.Builder().url(url).get().build()
            
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            return@withContext false
        }
    }
}
