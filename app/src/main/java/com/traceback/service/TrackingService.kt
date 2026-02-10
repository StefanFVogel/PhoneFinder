package com.traceback.service

import android.Manifest
import android.app.Notification
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
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.traceback.R
import com.traceback.TraceBackApp
import com.traceback.drive.DriveManager
import com.traceback.kml.KmlGenerator
import com.traceback.telegram.TelegramNotifier
import com.traceback.tracking.SmartTrackingManager
import com.traceback.ui.MainActivity
import com.traceback.worker.SyncWorker
import kotlinx.coroutines.*

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
    private lateinit var locationCallback: LocationCallback
    private lateinit var kmlGenerator: KmlGenerator
    private lateinit var driveManager: DriveManager
    private lateinit var telegramNotifier: TelegramNotifier
    private lateinit var smartTrackingManager: SmartTrackingManager
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var lastLocation: Location? = null
    private var isMoving = false
    private var lastSyncTime = 0L
    private var lastStationaryLogTime = 0L
    
    private var lastBreathTriggered = false
    
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percentage = (level * 100 / scale.toFloat()).toInt()
            
            val threshold = TraceBackApp.instance.securePrefs.lastBreathThreshold
            
            if (percentage <= threshold && !lastBreathTriggered) {
                Log.w(TAG, "Battery critical ($percentage% <= $threshold%), triggering Last Breath")
                lastBreathTriggered = true
                triggerLastBreath("Akku kritisch: $percentage%")
            } else if (percentage > threshold + 5) {
                // Reset trigger when battery charged above threshold + buffer
                lastBreathTriggered = false
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        kmlGenerator = KmlGenerator(this)
        driveManager = DriveManager(this)
        telegramNotifier = TelegramNotifier(TraceBackApp.instance.securePrefs)
        smartTrackingManager = SmartTrackingManager(this)
        
        setupLocationCallback()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        
        // Start activity recognition
        smartTrackingManager.activityManager.startMonitoring()
        
        Log.i(TAG, "TrackingService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startLocationUpdates()
        
        // Schedule periodic sync via WorkManager
        SyncWorker.schedule(this)
        
        Log.i(TAG, "TrackingService started")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        unregisterReceiver(batteryReceiver)
        smartTrackingManager.activityManager.stopMonitoring()
        
        // Cancel periodic sync when tracking is stopped
        SyncWorker.cancel(this)
        
        Log.i(TAG, "TrackingService destroyed")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    processLocation(location)
                }
            }
        }
    }
    
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted")
            return
        }
        
        val prefs = TraceBackApp.instance.securePrefs
        val distanceMeters = prefs.trackingDistanceMeters.toFloat()
        
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
            .setMinUpdateDistanceMeters(distanceMeters)
            .setWaitForAccurateLocation(true)
            .build()
        
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        Log.i(TAG, "Location updates started with distance: ${distanceMeters}m")
    }
    
    private fun processLocation(location: Location) {
        val prefs = TraceBackApp.instance.securePrefs
        
        // Master switch check
        if (!prefs.trackingEnabled) {
            Log.d(TAG, "Tracking disabled, ignoring location")
            return
        }
        
        // Evaluate smart tracking mode
        serviceScope.launch {
            val mode = smartTrackingManager.evaluateTrackingMode()
            
            when (mode) {
                SmartTrackingManager.TrackingMode.OFF -> {
                    Log.d(TAG, "Smart tracking OFF, ignoring location")
                    return@launch
                }
                
                SmartTrackingManager.TrackingMode.STATIONARY -> {
                    // Only log once per day in stationary mode
                    val oneDayMs = 24 * 60 * 60 * 1000L
                    if (System.currentTimeMillis() - lastStationaryLogTime > oneDayMs) {
                        kmlGenerator.addPoint(location, isStopPoint = true)
                        lastStationaryLogTime = System.currentTimeMillis()
                        Log.i(TAG, "Logged daily stationary point")
                    }
                    
                    // Check for drift
                    val drift = smartTrackingManager.checkDrift(location)
                    if (drift != null && drift > 500) {
                        sendDriftAlert(drift, location)
                    }
                    return@launch
                }
                
                SmartTrackingManager.TrackingMode.LEARNING -> {
                    // Set learning start location and update learning
                    smartTrackingManager.setLearningStartLocation(location)
                    smartTrackingManager.updateLearning(location)
                    // Still track during learning
                }
                
                SmartTrackingManager.TrackingMode.ACTIVE -> {
                    // Normal active tracking
                }
            }
            
            // Active tracking logic
            val lastLoc = lastLocation
            if (lastLoc != null) {
                val distance = location.distanceTo(lastLoc)
                val timeDelta = location.time - lastLoc.time
                val speed = if (timeDelta > 0) distance / (timeDelta / 1000f) else 0f
                
                // Check if transitioning from moving to still
                if (isMoving && !smartTrackingManager.activityManager.isMoving()) {
                    // Log stop point
                    logStopPoint(location)
                    isMoving = false
                }
                
                // Log if actually moving
                if (speed > 1f || distance > prefs.trackingDistanceMeters) {
                    isMoving = true
                    kmlGenerator.addPoint(location)
                    Log.d(TAG, "Logged moving point: ${location.latitude}, ${location.longitude}")
                }
            } else {
                // First location - always log initial position
                kmlGenerator.addPoint(location, isStopPoint = true)
                Log.i(TAG, "Logged initial position: ${location.latitude}, ${location.longitude}")
            }
            
            lastLocation = location
        }
    }
    
    private suspend fun sendDriftAlert(drift: Float, location: Location) {
        val message = buildString {
            appendLine("⚠️ TraceBack Drift-Warnung")
            appendLine()
            appendLine("Das Gerät hat sich ${drift.toInt()}m von der erwarteten Position bewegt,")
            appendLine("obwohl es mit einem stationären Netzwerk verbunden ist.")
            appendLine()
            appendLine("📍 Aktuelle Position:")
            appendLine("https://maps.google.com/?q=${location.latitude},${location.longitude}")
        }
        
        telegramNotifier.sendEmergency(message)
        Log.w(TAG, "Drift alert sent: ${drift}m")
    }
    
    private fun logStopPoint(location: Location) {
        // Request high-precision fix for stop point
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { preciseLocation ->
                    val loc = preciseLocation ?: location
                    kmlGenerator.addPoint(loc, isStopPoint = true)
                    Log.i(TAG, "Logged stop point: ${loc.latitude}, ${loc.longitude}")
                }
        }
    }
    
    fun triggerLastBreath(reason: String) {
        serviceScope.launch {
            try {
                val location = lastLocation
                val wifiList = scanWifiNetworks()
                
                val message = buildLastBreathMessage(reason, location, wifiList)
                
                // Try Telegram first
                val telegramSuccess = telegramNotifier.sendEmergency(message)
                
                // Fallback to SMS if Telegram fails
                if (!telegramSuccess) {
                    sendEmergencySms(message)
                }
                
                Log.i(TAG, "Last Breath sent: $reason")
            } catch (e: Exception) {
                Log.e(TAG, "Last Breath failed", e)
            }
        }
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
                appendLine("Genauigkeit: ${location.accuracy}m")
                appendLine("https://maps.google.com/?q=${location.latitude},${location.longitude}")
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
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.scanResults
            .sortedByDescending { it.level }
            .map { it.SSID }
            .filter { it.isNotBlank() }
    }
    
    private fun sendEmergencySms(message: String) {
        val number = TraceBackApp.instance.securePrefs.emergencySmsNumber ?: return
        try {
            val smsManager = android.telephony.SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            Log.i(TAG, "Emergency SMS sent to $number")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, TraceBackApp.CHANNEL_TRACKING)
            .setContentTitle("TraceBack aktiv")
            .setContentText("Standort wird aufgezeichnet")
            .setSmallIcon(R.drawable.ic_tracking)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
