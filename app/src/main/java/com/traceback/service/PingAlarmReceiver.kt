package com.traceback.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receives alarm broadcasts and triggers the PingService.
 */
class PingAlarmReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "PingAlarmReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Alarm received: ${intent.action}")
        
        // Forward to service with the same action
        val serviceIntent = Intent(context, PingService::class.java).apply {
            action = intent.action
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
