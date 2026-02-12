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
        
        // 4. Upload to Google Drive (KML + HTML)
        if (driveManager.isReady()) {
            val kmlContent = generateKml(location, reason, wifiNetworks, isTest)
            val htmlContent = generateHtml(location, reason, wifiNetworks, isTest)
            val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            val timestamp = dateTimeFormat.format(Date())
            val kmlSuccess = driveManager.uploadLastBreathKml(kmlContent)
            val htmlSuccess = driveManager.uploadHtml(htmlContent, "last_breath_$timestamp.html")
            driveSuccess = kmlSuccess || htmlSuccess
            Log.i(TAG, "Drive upload: KML=${if (kmlSuccess) "✓" else "✗"}, HTML=${if (htmlSuccess) "✓" else "✗"}")
        }
        
        // 5. Send Telegram (full message with Maps link for preview)
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
                
                // Speed & bearing only if moving (> 2 km/h = 0.556 m/s)
                if (location.hasSpeed() && location.speed > 0.556f) {
                    val speedKmh = (location.speed * 3.6).toInt()
                    append("🚗 Geschwindigkeit: ${speedKmh} km/h")
                    if (location.hasBearing()) {
                        append(" • ${bearingToDirection(location.bearing)}")
                    }
                    appendLine()
                }
                
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
    
    /**
     * Convert bearing (0-360°) to compass direction
     */
    private fun bearingToDirection(bearing: Float): String {
        val directions = arrayOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
        val index = ((bearing + 22.5) / 45).toInt() % 8
        return directions[index]
    }
    
    private fun generateKml(location: Location?, reason: String, wifiNetworks: List<String>, isTest: Boolean): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val title = if (isTest) "🧪 Last Breath TEST" else "🚨 Last Breath"
        val wifiInfo = if (wifiNetworks.isNotEmpty()) "\nWLANs: ${wifiNetworks.take(3).joinToString(", ")}" else ""
        
        return if (location != null) {
            """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
<name>TraceBack Last Breath${if (isTest) " (TEST)" else ""}</name>
<description>$reason - $dateStr$wifiInfo</description>
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
Zeit: $dateStr$wifiInfo</description>
<styleUrl>#lastBreathStyle</styleUrl>
<TimeStamp><when>$timestamp</when></TimeStamp>
<Point>
<coordinates>${location.longitude},${location.latitude},${location.altitude}</coordinates>
</Point>
</Placemark>
</Document>
</kml>"""
        } else {
            """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
<name>TraceBack Last Breath${if (isTest) " (TEST)" else ""}</name>
<description>$reason - $dateStr (Kein GPS)$wifiInfo</description>
</Document>
</kml>"""
        }
    }
    
    private fun generateHtml(location: Location?, reason: String, wifiNetworks: List<String>, isTest: Boolean): String {
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val title = if (isTest) "🧪 Last Breath TEST" else "🚨 Last Breath"
        val headerColor = if (isTest) "#4caf50" else "#f44336"
        
        val wifiHtml = if (wifiNetworks.isNotEmpty()) {
            """
        <div class="wifi">
            <h3>📶 Sichtbare WLANs (${wifiNetworks.size})</h3>
            <ul>
                ${wifiNetworks.take(10).joinToString("\n") { "<li>$it</li>" }}
                ${if (wifiNetworks.size > 10) "<li>... und ${wifiNetworks.size - 10} weitere</li>" else ""}
            </ul>
        </div>"""
        } else ""
        
        return if (location != null) {
            val lat = location.latitude
            val lon = location.longitude
            val acc = location.accuracy.toInt()
            val googleMapsUrl = "https://www.google.com/maps?q=$lat,$lon"
            val osmUrl = "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon&zoom=15"
            
            // Speed & bearing only if moving (> 2 km/h)
            val speedHtml = if (location.hasSpeed() && location.speed > 0.556f) {
                val speedKmh = (location.speed * 3.6).toInt()
                val direction = if (location.hasBearing()) " • ${bearingToDirection(location.bearing)}" else ""
                """<div class="speed">🚗 Geschwindigkeit: ${speedKmh} km/h$direction</div>"""
            } else ""
            
            """<!DOCTYPE html>
<html lang="de">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>TraceBack Last Breath - $dateStr</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; background: #f5f5f5; }
        .card { background: white; border-radius: 12px; padding: 20px; max-width: 600px; margin: 0 auto; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
        h1 { margin: 0 0 5px 0; font-size: 24px; color: $headerColor; }
        .reason { background: ${if (isTest) "#e8f5e9" else "#ffebee"}; padding: 10px 15px; border-radius: 8px; margin: 10px 0; color: ${if (isTest) "#2e7d32" else "#c62828"}; }
        .time { color: #666; margin-bottom: 20px; }
        .coords { font-family: monospace; background: #f0f0f0; padding: 10px; border-radius: 8px; margin: 15px 0; }
        .accuracy { color: #888; font-size: 14px; }
        .speed { color: #1976d2; font-size: 14px; margin-top: 8px; }
        .map { width: 100%; height: 300px; border: none; border-radius: 8px; margin: 15px 0; }
        .links { display: flex; gap: 10px; flex-wrap: wrap; }
        .links a { flex: 1; text-align: center; padding: 12px; background: #4285f4; color: white; text-decoration: none; border-radius: 8px; min-width: 120px; }
        .links a:hover { background: #3367d6; }
        .links a.osm { background: #7ebc6f; }
        .links a.osm:hover { background: #6aa85c; }
        .wifi { margin-top: 20px; padding-top: 15px; border-top: 1px solid #eee; }
        .wifi h3 { margin: 0 0 10px 0; font-size: 16px; }
        .wifi ul { margin: 0; padding-left: 20px; }
        .wifi li { margin: 5px 0; font-family: monospace; font-size: 13px; }
    </style>
</head>
<body>
    <div class="card">
        <h1>$title</h1>
        <div class="reason">$reason</div>
        <div class="time">📍 $dateStr</div>
        <div class="coords">
            Lat: $lat<br>
            Lon: $lon
        </div>
        <div class="accuracy">📏 Genauigkeit: ${acc}m</div>
        $speedHtml
        <iframe class="map" src="https://www.openstreetmap.org/export/embed.html?bbox=${lon-0.01},${lat-0.01},${lon+0.01},${lat+0.01}&layer=mapnik&marker=$lat,$lon"></iframe>
        <div class="links">
            <a href="$googleMapsUrl" target="_blank">🗺️ Google Maps</a>
            <a href="$osmUrl" target="_blank" class="osm">🗺️ OpenStreetMap</a>
        </div>$wifiHtml
    </div>
</body>
</html>"""
        } else {
            """<!DOCTYPE html>
<html lang="de">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>TraceBack Last Breath - $dateStr</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; background: #f5f5f5; }
        .card { background: white; border-radius: 12px; padding: 20px; max-width: 600px; margin: 0 auto; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
        h1 { margin: 0 0 5px 0; font-size: 24px; color: $headerColor; }
        .reason { background: ${if (isTest) "#e8f5e9" else "#ffebee"}; padding: 10px 15px; border-radius: 8px; margin: 10px 0; color: ${if (isTest) "#2e7d32" else "#c62828"}; }
        .time { color: #666; margin-bottom: 20px; }
        .warning { background: #fff3cd; padding: 15px; border-radius: 8px; color: #856404; }
        .wifi { margin-top: 20px; padding-top: 15px; border-top: 1px solid #eee; }
        .wifi h3 { margin: 0 0 10px 0; font-size: 16px; }
        .wifi ul { margin: 0; padding-left: 20px; }
        .wifi li { margin: 5px 0; font-family: monospace; font-size: 13px; }
    </style>
</head>
<body>
    <div class="card">
        <h1>$title</h1>
        <div class="reason">$reason</div>
        <div class="time">⚠️ $dateStr</div>
        <div class="warning">Standort konnte nicht ermittelt werden</div>$wifiHtml
    </div>
</body>
</html>"""
        }
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
