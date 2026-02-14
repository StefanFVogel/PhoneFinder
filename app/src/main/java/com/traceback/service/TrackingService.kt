package com.traceback.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.traceback.R
import com.traceback.TraceBackApp
import com.traceback.ui.MainActivity
import com.traceback.util.BatteryThresholdChecker
import kotlinx.coroutines.*

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
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Track which thresholds have already triggered to avoid duplicate alerts
    private val triggeredThresholds = mutableSetOf<Int>()
    private var chargingAlertTriggered = false
    
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percentage = (level * 100 / scale.toFloat()).toInt()

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            checkBatteryThresholds(percentage, isCharging)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        
        Log.i(TAG, "TrackingService created (battery monitor only)")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Note: PingService handles periodic pings now (started separately)
        
        Log.i(TAG, "TrackingService started (battery monitor)")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        unregisterReceiver(batteryReceiver)
        
        Log.i(TAG, "TrackingService destroyed")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun checkBatteryThresholds(currentPercentage: Int, isCharging: Boolean) {
        val prefs = TraceBackApp.instance.securePrefs

        // --- Charging alert (battery health) ---
        if (BatteryThresholdChecker.shouldShowChargingAlert(
                currentPercentage, isCharging, prefs.chargingAlertEnabled, chargingAlertTriggered
            )) {
            Log.i(TAG, "Battery at $currentPercentage% while charging - alert!")
            chargingAlertTriggered = true
            showChargingAlert(currentPercentage)
        }
        if (BatteryThresholdChecker.shouldResetChargingAlert(currentPercentage)) {
            chargingAlertTriggered = false
        }

        // --- Last Breath thresholds ---
        val thresholds = prefs.lastBreathThresholds

        if (thresholds.isEmpty()) {
            Log.d(TAG, "No thresholds configured")
            return
        }

        val triggered = BatteryThresholdChecker.findTriggeredThreshold(
            currentPercentage, thresholds, triggeredThresholds
        )
        if (triggered != null) {
            Log.w(TAG, "Battery at $currentPercentage%, threshold $triggered% reached!")
            triggeredThresholds.add(triggered)
            triggerLastBreath("Akku kritisch: $currentPercentage% (Schwelle: $triggered%)")
        }

        if (BatteryThresholdChecker.shouldResetTriggers(currentPercentage, thresholds)) {
            if (triggeredThresholds.isNotEmpty()) {
                Log.i(TAG, "Battery charged, resetting triggers")
                triggeredThresholds.clear()
            }
        }
    }
    
    private fun triggerLastBreath(reason: String) {
        serviceScope.launch {
            try {
                // Use unified LastBreathSender for consistent behavior
                val result = com.traceback.util.LastBreathSender.send(
                    context = this@TrackingService,
                    reason = reason,
                    isTest = false
                )
                
                // Show notification to user
                showLocationSentNotification(
                    reason, 
                    result.location, 
                    result.driveSuccess || result.telegramSuccess || result.smsSuccess
                )
                
                Log.i(TAG, "Last Breath complete: $reason (Drive=${result.driveSuccess}, Telegram=${result.telegramSuccess}, SMS=${result.smsSuccess}, WiFi=${result.wifiNetworks.size})")
            } catch (e: Exception) {
                Log.e(TAG, "Last Breath failed", e)
            }
        }
    }
    
    private fun showChargingAlert(percentage: Int) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(this, TraceBackApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentTitle("Akku bei $percentage% – Ladegerät trennen")
            .setContentText("Für optimale Akku-Lebensdauer bei 80% vom Ladegerät trennen.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(4, notification)
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
