package com.traceback

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.traceback.util.SecurePrefs

class TraceBackApp : Application() {
    
    companion object {
        const val CHANNEL_TRACKING = "traceback_tracking"
        const val CHANNEL_ALERTS = "traceback_alerts"
        const val CHANNEL_SERVICE = "traceback_service"
        
        lateinit var instance: TraceBackApp
            private set
    }
    
    lateinit var securePrefs: SecurePrefs
        private set
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        securePrefs = SecurePrefs(this)
        createNotificationChannels()
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            // Tracking channel (low priority, persistent)
            val trackingChannel = NotificationChannel(
                CHANNEL_TRACKING,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt an, dass TraceBack im Hintergrund läuft"
                setShowBadge(false)
            }
            
            // Alerts channel (high priority)
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Warnungen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Wichtige Warnungen wie Sync-Fehler oder Drift-Detection"
            }
            
            // Service channel (for foreground service)
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Ping-Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt den Status des Ping-Services an"
                setShowBadge(false)
            }
            
            manager.createNotificationChannel(trackingChannel)
            manager.createNotificationChannel(alertsChannel)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
