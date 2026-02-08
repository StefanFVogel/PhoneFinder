package com.traceback.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Triggers Last Breath on device shutdown.
 * This is a best-effort attempt - Android may kill the app before completion.
 */
class ShutdownReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ShutdownReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SHUTDOWN) {
            Log.w(TAG, "Shutdown detected, triggering Last Breath")
            
            // Get reference to running service
            // Note: This is tricky - the service might already be stopping
            // We use a blocking call here since we have limited time
            runBlocking {
                try {
                    // Try to trigger via Intent to the service
                    val serviceIntent = Intent(context, TrackingService::class.java).apply {
                        action = "com.traceback.LAST_BREATH"
                        putExtra("reason", "Gerät wird heruntergefahren")
                    }
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to trigger Last Breath", e)
                }
            }
        }
    }
}
