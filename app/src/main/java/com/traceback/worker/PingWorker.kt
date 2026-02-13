package com.traceback.worker

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.traceback.R
import com.traceback.TraceBackApp
import com.traceback.drive.DriveManager
import com.traceback.telegram.TelegramNotifier
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * PingWorker - Periodically checks permissions and uploads a ping.kml to Google Drive.
 * Runs every 15 minutes to ensure the app is still functional.
 */
class PingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PingWorker"
        private const val WORK_NAME = "traceback_ping"
        
        // Available intervals in minutes
        val INTERVALS = listOf(15, 60, 300, 1440) // 15min, 1h, 5h, 1 day
        
        fun getIntervalLabel(minutes: Int): String = when (minutes) {
            15 -> "15 Minuten"
            60 -> "1 Stunde"
            300 -> "5 Stunden"
            1440 -> "1 Tag"
            else -> "$minutes Minuten"
        }
        
        fun schedule(context: Context, intervalMinutes: Int = 60) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val request = PeriodicWorkRequestBuilder<PingWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.REPLACE, // Replace to update interval
                    request
                )
            
            Log.i(TAG, "PingWorker scheduled with interval: $intervalMinutes minutes")
        }
        
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "PingWorker cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "PingWorker started")
        
        val prefs = TraceBackApp.instance.securePrefs
        
        // Skip if not enabled
        if (!prefs.trackingEnabled) {
            Log.d(TAG, "Ping disabled, skipping")
            return Result.success()
        }
        
        // 1. Check permissions
        val permissionsOk = checkPermissions()
        if (!permissionsOk) {
            Log.w(TAG, "Permissions missing!")
            showNotification(
                "⚠️ Berechtigungen fehlen",
                "TraceBack kann nicht funktionieren. Bitte Berechtigungen prüfen."
            )
            return Result.success() // Don't retry, user needs to fix permissions
        }
        
        // 2. Get current location
        val location = getCurrentLocation()
        
        // 3. Scan WiFi networks
        val wifiNetworks = scanWifiNetworks()
        
        // 4. Send to ALL configured channels
        var anySuccess = false
        
        // Drive (KML + HTML)
        val driveManager = DriveManager(applicationContext)
        if (driveManager.isReady()) {
            val kmlSuccess = uploadPingToDrive(driveManager, location, wifiNetworks)
            val htmlSuccess = uploadPingHtml(driveManager, location, wifiNetworks)
            if (kmlSuccess || htmlSuccess) {
                Log.i(TAG, "Ping to Drive: KML=$kmlSuccess, HTML=$htmlSuccess")
                anySuccess = true
            } else {
                Log.w(TAG, "Ping to Drive: FAILED")
            }
        } else {
            Log.d(TAG, "Drive not configured, skipping")
        }
        
        // Telegram
        val telegramNotifier = TelegramNotifier(prefs)
        val telegramConfigured = !prefs.telegramBotToken.isNullOrBlank() && !prefs.telegramChatId.isNullOrBlank()
        if (telegramConfigured) {
            val telegramSuccess = sendPingToTelegram(telegramNotifier, location, wifiNetworks)
            if (telegramSuccess) {
                Log.i(TAG, "Ping to Telegram: OK")
                anySuccess = true
            } else {
                Log.w(TAG, "Ping to Telegram: FAILED")
            }
        } else {
            Log.d(TAG, "Telegram not configured, skipping")
        }
        
        if (anySuccess) {
            prefs.lastSyncTimestamp = System.currentTimeMillis()
            Log.i(TAG, "Ping completed (at least one channel succeeded)")
        } else if (!driveManager.isReady() && !telegramConfigured) {
            Log.w(TAG, "No channels configured for ping!")
        } else {
            Log.e(TAG, "All ping channels failed")
        }
        
        return Result.success()
    }
    
    private fun checkPermissions(): Boolean {
        val ctx = applicationContext
        
        // Check fine location
        val hasFineLocation = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        // Check background location (Android 10+)
        val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        
        // Check battery optimization
        val pm = ctx.getSystemService(PowerManager::class.java)
        val batteryOptDisabled = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        
        return hasFineLocation && hasBackgroundLocation && batteryOptDisabled
    }
    
    private fun scanWifiNetworks(): List<String> {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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
        } catch (e: Exception) {
            Log.e(TAG, "WiFi scan failed", e)
            emptyList()
        }
    }
    
    private suspend fun getCurrentLocation(): Location? {
        if (ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        
        return try {
            suspendCoroutine { continuation ->
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
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
    
    private suspend fun uploadPingToDrive(driveManager: DriveManager, location: Location?, wifiNetworks: List<String>): Boolean {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val wifiInfo = if (wifiNetworks.isNotEmpty()) "\nWLANs: ${wifiNetworks.take(3).joinToString(", ")}" else ""
        
        val kmlContent = if (location != null) {
            """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
<name>TraceBack Ping</name>
<description>Letzter Ping - $dateStr$wifiInfo</description>
<Style id="pingStyle">
    <IconStyle>
        <color>ff00ff00</color>
        <scale>1.0</scale>
        <Icon><href>http://maps.google.com/mapfiles/kml/paddle/grn-circle.png</href></Icon>
    </IconStyle>
</Style>
<Placemark>
<name>📍 Ping</name>
<description>$dateStr\nGenauigkeit: ${location.accuracy}m$wifiInfo</description>
<styleUrl>#pingStyle</styleUrl>
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
<name>TraceBack Ping</name>
<description>Ping ohne Standort - $dateStr$wifiInfo</description>
</Document>
</kml>"""
        }
        
        return driveManager.uploadPingKml(kmlContent)
    }
    
    private suspend fun uploadPingHtml(driveManager: DriveManager, location: Location?, wifiNetworks: List<String>): Boolean {
        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        
        val wifiHtml = if (wifiNetworks.isNotEmpty()) {
            """
        <div class="wifi">
            <h3>📶 Sichtbare WLANs (${wifiNetworks.size})</h3>
            <ul>
                ${wifiNetworks.take(10).joinToString("\n                ") { "<li>$it</li>" }}
                ${if (wifiNetworks.size > 10) "<li>... und ${wifiNetworks.size - 10} weitere</li>" else ""}
            </ul>
        </div>"""
        } else ""
        
        val htmlContent = if (location != null) {
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
    <title>TraceBack Ping - $dateStr</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; background: #f5f5f5; }
        .card { background: white; border-radius: 12px; padding: 20px; max-width: 500px; margin: 0 auto; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
        h1 { margin: 0 0 10px 0; font-size: 24px; }
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
        <h1>📡 TraceBack Ping</h1>
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
    <title>TraceBack Ping - $dateStr</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; background: #f5f5f5; }
        .card { background: white; border-radius: 12px; padding: 20px; max-width: 500px; margin: 0 auto; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
        h1 { margin: 0 0 10px 0; font-size: 24px; }
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
        <h1>📡 TraceBack Ping</h1>
        <div class="time">⚠️ $dateStr</div>
        <div class="warning">Standort konnte nicht ermittelt werden</div>$wifiHtml
    </div>
</body>
</html>"""
        }
        
        return driveManager.uploadHtml(htmlContent, "ping.html")
    }
    
    private suspend fun sendPingToTelegram(notifier: TelegramNotifier, location: Location?, wifiNetworks: List<String>): Boolean {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val message = buildString {
            appendLine("📡 TraceBack Ping")
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
                appendLine("⚠️ Standort konnte nicht ermittelt werden")
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
            appendLine("Zeit: $dateStr")
        }
        
        return notifier.sendEmergency(message)
    }
    
    /**
     * Convert bearing (0-360°) to compass direction
     */
    private fun bearingToDirection(bearing: Float): String {
        val directions = arrayOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
        val index = ((bearing + 22.5) / 45).toInt() % 8
        return directions[index]
    }
    
    private fun showNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        
        val notification = NotificationCompat.Builder(applicationContext, TraceBackApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(2, notification)
    }
}
