package com.traceback.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.traceback.TraceBackApp
import com.traceback.drive.DriveManager
import com.traceback.telegram.TelegramNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Unified Last Breath sender - used by both real triggers and test button.
 * Ensures consistent behavior between test and production.
 */
object LastBreathSender {
    
    private const val TAG = "LastBreathSender"
    
    data class Result(
        val driveSuccess: Boolean,
        val telegramSuccess: Boolean,
        val smsSuccess: Boolean,
        val location: Location?,
        val wifiNetworks: List<String>
    )
    
    /**
     * Send Last Breath to all configured channels.
     * @param context Application context
     * @param reason Reason for Last Breath (e.g. "Akku kritisch: 5%")
     * @param isTest If true, message is marked as test
     */
    suspend fun send(
        context: Context,
        reason: String,
        isTest: Boolean = false
    ): Result = withContext(Dispatchers.IO) {
        val prefs = TraceBackApp.instance.securePrefs
        val driveManager = DriveManager(context)
        val telegramNotifier = TelegramNotifier(prefs)
        
        // 1. Get location
        val location = getCurrentLocation(context)
        
        // 2. Scan WiFi networks
        val wifiNetworks = scanWifiNetworks(context)
        
        // 3. Build message
        val message = buildMessage(reason, location, wifiNetworks, isTest)
        
        var driveSuccess = false
        var telegramSuccess = false
        var smsSuccess = false
        
        // 4. Upload to Google Drive
        if (location != null && driveManager.isReady()) {
            val kmlContent = generateKml(location, reason, isTest)
            driveSuccess = driveManager.uploadLastBreathKml(kmlContent)
            Log.i(TAG, "Drive upload: ${if (driveSuccess) "✓" else "✗"}")
        }
        
        // 5. Send Telegram
        if (!prefs.telegramBotToken.isNullOrBlank() && !prefs.telegramChatId.isNullOrBlank()) {
            telegramSuccess = telegramNotifier.sendEmergency(message)
            Log.i(TAG, "Telegram send: ${if (telegramSuccess) "✓" else "✗"}")
        }
        
        // 6. Send SMS
        if (!prefs.emergencySmsNumber.isNullOrBlank()) {
            smsSuccess = sendSms(context, prefs.emergencySmsNumber!!, message)
            Log.i(TAG, "SMS send: ${if (smsSuccess) "✓" else "✗"}")
        }
        
        Log.i(TAG, "Last Breath complete: reason=$reason, test=$isTest, " +
                "drive=$driveSuccess, telegram=$telegramSuccess, sms=$smsSuccess")
        
        Result(driveSuccess, telegramSuccess, smsSuccess, location, wifiNetworks)
    }
    
    private suspend fun getCurrentLocation(context: Context): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No location permission")
            return null
        }
        
        return try {
            suspendCoroutine { continuation ->
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        continuation.resume(location)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Location request failed", e)
                        continuation.resume(null)
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get location", e)
            null
        }
    }
    
    private fun scanWifiNetworks(context: Context): List<String> {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val results = wifiManager.scanResults
            Log.i(TAG, "WiFi scan found ${results.size} networks")
            
            results
                .sortedByDescending { it.level }
                .mapNotNull { result ->
                    val ssid = result.SSID
                    if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") null
                    else "$ssid (${result.level} dBm)"
                }
                .distinct()
                .also { Log.i(TAG, "Visible networks: $it") }
        } catch (e: Exception) {
            Log.e(TAG, "WiFi scan failed", e)
            emptyList()
        }
    }
    
    private fun buildMessage(
        reason: String,
        location: Location?,
        wifiNetworks: List<String>,
        isTest: Boolean
    ): String {
        return buildString {
            if (isTest) {
                appendLine("🧪 TraceBack TEST - Last Breath")
            } else {
                appendLine("🚨 TraceBack Last Breath")
            }
            appendLine("Grund: $reason")
            appendLine()
            
            if (location != null) {
                appendLine("📍 Standort:")
                appendLine("Lat: ${location.latitude}")
                appendLine("Lon: ${location.longitude}")
                appendLine("Genauigkeit: ${location.accuracy.toInt()}m")
                appendLine()
                appendLine("🗺️ https://maps.google.com/maps?q=${location.latitude},${location.longitude}")
            } else {
                appendLine("⚠️ Kein GPS-Standort verfügbar")
            }
            
            if (wifiNetworks.isNotEmpty()) {
                appendLine()
                appendLine("📶 Sichtbare WLANs (${wifiNetworks.size}):")
                wifiNetworks.take(5).forEach { appendLine("• $it") }
                if (wifiNetworks.size > 5) {
                    appendLine("• ... und ${wifiNetworks.size - 5} weitere")
                }
            }
            
            appendLine()
            appendLine("Zeit: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        }
    }
    
    private fun generateKml(location: Location, reason: String, isTest: Boolean): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val title = if (isTest) "🧪 Last Breath TEST" else "🚨 Last Breath"
        
        return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
<name>TraceBack Last Breath${if (isTest) " (TEST)" else ""}</name>
<description>$reason - $dateStr</description>
<Style id="lastBreathStyle">
    <IconStyle>
        <color>${if (isTest) "ff00ff00" else "ff0000ff"}</color>
        <scale>1.5</scale>
        <Icon><href>http://maps.google.com/mapfiles/kml/paddle/${if (isTest) "grn" else "red"}-stars.png</href></Icon>
    </IconStyle>
</Style>
<Placemark>
<name>$title</name>
<description>$reason
Genauigkeit: ${location.accuracy}m
Zeit: $dateStr</description>
<styleUrl>#lastBreathStyle</styleUrl>
<TimeStamp><when>$timestamp</when></TimeStamp>
<Point>
<coordinates>${location.longitude},${location.latitude},${location.altitude}</coordinates>
</Point>
</Placemark>
</Document>
</kml>"""
    }
    
    private fun sendSms(context: Context, number: String, message: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SMS permission not granted")
            return false
        }
        
        return try {
            val smsManager = android.telephony.SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed", e)
            false
        }
    }
}
