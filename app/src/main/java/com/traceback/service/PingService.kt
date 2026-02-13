package com.traceback.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.traceback.R
import com.traceback.TraceBackApp
import com.traceback.drive.DriveManager
import com.traceback.telegram.TelegramNotifier
import com.traceback.ui.MainActivity
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground Service for reliable ping scheduling.
 * Uses AlarmManager for exact timing instead of WorkManager.
 */
class PingService : Service() {
    
    companion object {
        private const val TAG = "PingService"
        private const val NOTIFICATION_ID = 100
        private const val ACTION_PING = "com.traceback.ACTION_PING"
        private const val REQUEST_CODE_ALARM = 1001
        
        // Available intervals in minutes
        val INTERVALS = listOf(15, 60, 300, 1440)
        
        fun getIntervalLabel(minutes: Int): String = when (minutes) {
            15 -> "15 Minuten"
            60 -> "1 Stunde"
            300 -> "5 Stunden"
            1440 -> "1 Tag"
            else -> "$minutes Minuten"
        }
        
        fun start(context: Context) {
            val intent = Intent(context, PingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, PingService::class.java))
            cancelAlarm(context)
        }
        
        private fun cancelAlarm(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val intent = Intent(context, PingAlarmReceiver::class.java).apply {
                action = ACTION_PING
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE_ALARM, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PingService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "PingService onStartCommand, action=${intent?.action}")
        
        val prefs = TraceBackApp.instance.securePrefs
        if (!prefs.trackingEnabled) {
            Log.i(TAG, "Tracking disabled, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Start foreground immediately
        if (!isRunning) {
            startForegroundWithNotification()
            isRunning = true
        }
        
        // Handle ping action from alarm
        if (intent?.action == ACTION_PING) {
            Log.i(TAG, "Ping alarm triggered")
            serviceScope.launch {
                executePing()
            }
        }
        
        // Schedule next alarm
        scheduleNextAlarm()
        
        return START_STICKY
    }
    
    private fun startForegroundWithNotification() {
        val prefs = TraceBackApp.instance.securePrefs
        val intervalLabel = getIntervalLabel(prefs.pingIntervalMinutes)
        
        val notification = createNotification(
            "TraceBack aktiv",
            "Ping alle $intervalLabel"
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun createNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, TraceBackApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun scheduleNextAlarm() {
        val prefs = TraceBackApp.instance.securePrefs
        val intervalMs = prefs.pingIntervalMinutes * 60 * 1000L
        
        val alarmManager = getSystemService(AlarmManager::class.java)
        val intent = Intent(this, PingAlarmReceiver::class.java).apply {
            action = ACTION_PING
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, REQUEST_CODE_ALARM, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val triggerTime = SystemClock.elapsedRealtime() + intervalMs
        
        // Use setExactAndAllowWhileIdle for reliable timing even in Doze
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
        
        Log.i(TAG, "Next ping scheduled in ${prefs.pingIntervalMinutes} minutes")
    }
    
    private suspend fun executePing() {
        val prefs = TraceBackApp.instance.securePrefs
        
        // Update notification
        val notification = createNotification(
            "TraceBack",
            "Sende Ping..."
        )
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        // Check permissions
        if (!checkPermissions()) {
            Log.w(TAG, "Permissions missing!")
            updateNotificationError("⚠️ Berechtigungen fehlen")
            return
        }
        
        // Get location
        val location = getCurrentLocation()
        
        // Scan WiFi
        val wifiNetworks = scanWifiNetworks()
        
        // Send to channels
        var anySuccess = false
        
        // Drive
        val driveManager = DriveManager(this)
        if (driveManager.isReady()) {
            val kmlSuccess = uploadPingToDrive(driveManager, location, wifiNetworks)
            val htmlSuccess = uploadPingHtml(driveManager, location, wifiNetworks)
            if (kmlSuccess || htmlSuccess) {
                anySuccess = true
                Log.i(TAG, "Drive ping: OK")
            }
        }
        
        // Telegram
        val telegramNotifier = TelegramNotifier(prefs)
        if (!prefs.telegramBotToken.isNullOrBlank() && !prefs.telegramChatId.isNullOrBlank()) {
            val telegramSuccess = sendPingToTelegram(telegramNotifier, location, wifiNetworks)
            if (telegramSuccess) {
                anySuccess = true
                Log.i(TAG, "Telegram ping: OK")
            }
        }
        
        if (anySuccess) {
            prefs.lastSyncTimestamp = System.currentTimeMillis()
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            updateNotificationSuccess("Letzter Ping: $timeStr")
        } else {
            updateNotificationError("Ping fehlgeschlagen")
        }
    }
    
    private fun checkPermissions(): Boolean {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        
        val pm = getSystemService(PowerManager::class.java)
        val batteryOptDisabled = pm.isIgnoringBatteryOptimizations(packageName)
        
        return hasFineLocation && hasBackgroundLocation && batteryOptDisabled
    }
    
    private suspend fun getCurrentLocation(): Location? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(
                this@PingService, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {
            return@withContext null
        }
        
        return@withContext try {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this@PingService)
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        continuation.resume(location) {}
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Location request failed", e)
                        continuation.resume(null) {}
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get location", e)
            null
        }
    }
    
    private fun scanWifiNetworks(): List<String> {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val results = wifiManager.scanResults
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
        <div class="coords">Lat: $lat<br>Lon: $lon</div>
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
    
    private fun bearingToDirection(bearing: Float): String {
        val directions = arrayOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
        val index = ((bearing + 22.5) / 45).toInt() % 8
        return directions[index]
    }
    
    private fun updateNotificationSuccess(text: String) {
        val prefs = TraceBackApp.instance.securePrefs
        val intervalLabel = getIntervalLabel(prefs.pingIntervalMinutes)
        val notification = createNotification(
            "TraceBack aktiv",
            "$text • Ping alle $intervalLabel"
        )
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun updateNotificationError(text: String) {
        val notification = createNotification("TraceBack", text)
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        isRunning = false
        Log.i(TAG, "PingService destroyed")
    }
}
