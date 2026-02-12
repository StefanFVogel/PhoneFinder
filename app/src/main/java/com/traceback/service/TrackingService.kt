package com.traceback.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.traceback.R
import com.traceback.TraceBackApp
import com.traceback.drive.DriveManager
import com.traceback.telegram.TelegramNotifier
import com.traceback.ui.MainActivity
import com.traceback.worker.PingWorker
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * TrackingService - Simplified version for Google Play compliance
 * 
 * Only monitors battery level and triggers Last Breath when thresholds are reached.
 * NO continuous GPS tracking - just battery monitoring.
 */
class TrackingService : Service() {
    
    companion object {
        private const val TAG = "TrackingService"
        private const val NOTIFICATION_ID = 1
        
        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            context.startForegroundService(intent)
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var driveManager: DriveManager
    private lateinit var telegramNotifier: TelegramNotifier
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Track which thresholds have already triggered to avoid duplicate alerts
    private val triggeredThresholds = mutableSetOf<Int>()
    
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percentage = (level * 100 / scale.toFloat()).toInt()
            
            checkBatteryThresholds(percentage)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        driveManager = DriveManager(this)
        telegramNotifier = TelegramNotifier(TraceBackApp.instance.securePrefs)
        
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        
        Log.i(TAG, "TrackingService created (battery monitor only)")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Schedule PingWorker for periodic pings
        PingWorker.schedule(this)
        
        Log.i(TAG, "TrackingService started")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        unregisterReceiver(batteryReceiver)
        
        Log.i(TAG, "TrackingService destroyed")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun checkBatteryThresholds(currentPercentage: Int) {
        val prefs = TraceBackApp.instance.securePrefs
        val thresholds = prefs.lastBreathThresholds
        
        if (thresholds.isEmpty()) {
            Log.d(TAG, "No thresholds configured")
            return
        }
        
        // Check each threshold
        for (threshold in thresholds.sortedDescending()) {
            if (currentPercentage <= threshold && !triggeredThresholds.contains(threshold)) {
                Log.w(TAG, "Battery at $currentPercentage%, threshold $threshold% reached!")
                triggeredThresholds.add(threshold)
                triggerLastBreath("Akku kritisch: $currentPercentage% (Schwelle: $threshold%)")
                break // Only trigger one at a time
            }
        }
        
        // Reset triggers when battery is charged above highest configured threshold + 5%
        val highestThreshold = thresholds.maxOrNull() ?: 15
        if (currentPercentage > highestThreshold + 5) {
            if (triggeredThresholds.isNotEmpty()) {
                Log.i(TAG, "Battery charged above ${highestThreshold + 5}%, resetting triggers")
                triggeredThresholds.clear()
            }
        }
    }
    
    private fun triggerLastBreath(reason: String) {
        serviceScope.launch {
            try {
                val location = getCurrentLocation()
                val wifiList = scanWifiNetworks()
                val prefs = TraceBackApp.instance.securePrefs
                
                val message = buildLastBreathMessage(reason, location, wifiList)
                
                var driveSuccess = false
                var telegramSuccess = false
                var smsSuccess = false
                
                // 1. Google Drive
                if (location != null && driveManager.isReady()) {
                    val kmlContent = generateLastBreathKml(location, reason)
                    driveSuccess = driveManager.uploadLastBreathKml(kmlContent)
                    Log.i(TAG, "Last Breath → Drive: ${if (driveSuccess) "✓" else "✗"}")
                }
                
                // 2. Telegram
                if (!prefs.telegramBotToken.isNullOrBlank() && !prefs.telegramChatId.isNullOrBlank()) {
                    telegramSuccess = telegramNotifier.sendEmergency(message)
                    Log.i(TAG, "Last Breath → Telegram: ${if (telegramSuccess) "✓" else "✗"}")
                }
                
                // 3. SMS
                if (!prefs.emergencySmsNumber.isNullOrBlank()) {
                    smsSuccess = sendEmergencySms(message)
                    Log.i(TAG, "Last Breath → SMS: ${if (smsSuccess) "✓" else "✗"}")
                }
                
                // Show notification to user
                showLocationSentNotification(reason, location, driveSuccess || telegramSuccess || smsSuccess)
                
                Log.i(TAG, "Last Breath complete: $reason (Drive=$driveSuccess, Telegram=$telegramSuccess, SMS=$smsSuccess)")
            } catch (e: Exception) {
                Log.e(TAG, "Last Breath failed", e)
            }
        }
    }
    
    private suspend fun getCurrentLocation(): Location? = withContext(Dispatchers.IO) {
        if (ActivityCompat.checkSelfPermission(this@TrackingService, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return@withContext null
        }
        
        return@withContext try {
            suspendCoroutine { continuation ->
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
    
    private fun generateLastBreathKml(location: Location, reason: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
<name>TraceBack Last Breath</name>
<description>$reason - $dateStr</description>
<Style id="lastBreathStyle">
    <IconStyle>
        <color>ff0000ff</color>
        <scale>1.5</scale>
        <Icon><href>http://maps.google.com/mapfiles/kml/paddle/red-stars.png</href></Icon>
    </IconStyle>
</Style>
<Placemark>
<name>🚨 Last Breath</name>
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
    
    private fun buildLastBreathMessage(reason: String, location: Location?, wifiList: List<String>): String {
        return buildString {
            appendLine("🚨 TraceBack Last Breath")
            appendLine("Grund: $reason")
            appendLine()
            if (location != null) {
                appendLine("📍 Letzter Standort:")
                appendLine("Lat: ${location.latitude}")
                appendLine("Lon: ${location.longitude}")
                appendLine()
                appendLine("🗺️ https://maps.google.com/maps?q=${location.latitude},${location.longitude}")
            } else {
                appendLine("⚠️ Kein Standort verfügbar")
            }
            if (wifiList.isNotEmpty()) {
                appendLine()
                appendLine("📶 Sichtbare WLANs:")
                wifiList.take(5).forEach { appendLine("• $it") }
            }
        }
    }
    
    private fun scanWifiNetworks(): List<String> {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.scanResults
                .sortedByDescending { it.level }
                .map { it.SSID }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun sendEmergencySms(message: String): Boolean {
        val number = TraceBackApp.instance.securePrefs.emergencySmsNumber ?: return false
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SMS permission not granted")
            return false
        }
        
        return try {
            val smsManager = android.telephony.SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            Log.i(TAG, "Emergency SMS sent to $number")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS: ${e.message}", e)
            false
        }
    }
    
    private fun showLocationSentNotification(reason: String, location: Location?, success: Boolean) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val title = if (success) "📍 Last Breath gesendet" else "⚠️ Last Breath fehlgeschlagen"
        val message = if (location != null) {
            "$reason\nStandort: ${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}"
        } else {
            "$reason\nKein Standort verfügbar"
        }
        
        val notification = NotificationCompat.Builder(this, TraceBackApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(3, notification)
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, TraceBackApp.CHANNEL_TRACKING)
            .setContentTitle("TraceBack aktiv")
            .setContentText("Überwacht Akkustand für Last Breath")
            .setSmallIcon(R.drawable.ic_tracking)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
