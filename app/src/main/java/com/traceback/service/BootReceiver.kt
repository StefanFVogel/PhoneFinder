package com.traceback.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.traceback.TraceBackApp

/**
 * Starts TrackingService after device boot.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.i(TAG, "Boot completed, checking if tracking should start")
            
            // Only start if tracking was enabled
            val prefs = TraceBackApp.instance.securePrefs
            if (prefs.trackingEnabled) {
                Log.i(TAG, "Starting TrackingService")
                TrackingService.start(context)
            } else {
                Log.i(TAG, "Tracking not enabled, skipping")
            }
        }
    }
}
