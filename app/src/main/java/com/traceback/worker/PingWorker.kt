package com.traceback.worker

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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
        
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val request = PeriodicWorkRequestBuilder<PingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            
            Log.i(TAG, "PingWorker scheduled")
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
        
        // 2. Get current location and upload ping
        val driveManager = DriveManager(applicationContext)
        if (!driveManager.isReady()) {
            Log.w(TAG, "Drive not ready")
            return Result.success()
        }
        
        val location = getCurrentLocation()
        val success = uploadPing(driveManager, location)
        
        if (success) {
            prefs.lastSyncTimestamp = System.currentTimeMillis()
            Log.i(TAG, "Ping uploaded successfully")
        } else {
            Log.e(TAG, "Ping upload failed")
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
    
    private suspend fun getCurrentLocation(): Location? {
        if (ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        
        return try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get location", e)
            null
        }
    }
    
    private suspend fun uploadPing(driveManager: DriveManager, location: Location?): Boolean {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val kmlContent = if (location != null) {
            """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
<name>TraceBack Ping</name>
<description>Letzter Ping - $dateStr</description>
<Style id="pingStyle">
    <IconStyle>
        <color>ff00ff00</color>
        <scale>1.0</scale>
        <Icon><href>http://maps.google.com/mapfiles/kml/paddle/grn-circle.png</href></Icon>
    </IconStyle>
</Style>
<Placemark>
<name>📍 Ping</name>
<description>$dateStr\nGenauigkeit: ${location.accuracy}m</description>
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
<description>Ping ohne Standort - $dateStr</description>
</Document>
</kml>"""
        }
        
        return driveManager.uploadPingKml(kmlContent)
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
