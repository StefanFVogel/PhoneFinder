package com.traceback.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.traceback.TraceBackApp
import com.traceback.service.TrackingService

/**
 * Starts tracking service on device boot if it was enabled before.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            
            Log.i(TAG, "Boot/Update completed, checking tracking state")
            
            // Only start if tracking was enabled before
            val prefs = TraceBackApp.instance.securePrefs
            if (prefs.trackingEnabled) {
                Log.i(TAG, "Starting tracking service (was enabled)")
                TrackingService.start(context)
            } else {
                Log.i(TAG, "Tracking was disabled, not starting service")
            }
        }
    }
}
