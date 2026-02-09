package com.traceback.activity

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Manages activity recognition (walking, driving, still, etc.)
 */
class ActivityRecognitionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ActivityRecognition"
        const val ACTION_ACTIVITY_TRANSITION = "com.traceback.ACTION_ACTIVITY_TRANSITION"
    }
    
    enum class UserActivity {
        STILL,
        WALKING,
        RUNNING,
        IN_VEHICLE,
        ON_BICYCLE,
        UNKNOWN
    }
    
    private val activityRecognitionClient: ActivityRecognitionClient = 
        ActivityRecognition.getClient(context)
    
    private val _currentActivity = MutableStateFlow(UserActivity.UNKNOWN)
    val currentActivity: StateFlow<UserActivity> = _currentActivity
    
    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_ACTIVITY_TRANSITION)
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
    
    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ActivityTransitionResult.hasResult(intent)) {
                val result = ActivityTransitionResult.extractResult(intent) ?: return
                for (event in result.transitionEvents) {
                    val activity = mapActivityType(event.activityType)
                    Log.i(TAG, "Activity transition: $activity (${event.transitionType})")
                    
                    if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                        _currentActivity.value = activity
                    }
                }
            }
        }
    }
    
    /**
     * Start monitoring activity transitions.
     */
    fun startMonitoring() {
        if (!hasPermission()) {
            Log.e(TAG, "Activity recognition permission not granted")
            return
        }
        
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.WALKING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.RUNNING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_BICYCLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        )
        
        val request = ActivityTransitionRequest(transitions)
        
        try {
            activityRecognitionClient.requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener {
                    Log.i(TAG, "Activity recognition started")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to start activity recognition", e)
                }
            
            // Register receiver
            val filter = IntentFilter(ACTION_ACTIVITY_TRANSITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(activityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(activityReceiver, filter)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception", e)
        }
    }
    
    /**
     * Stop monitoring activity transitions.
     */
    fun stopMonitoring() {
        try {
            activityRecognitionClient.removeActivityTransitionUpdates(pendingIntent)
            context.unregisterReceiver(activityReceiver)
            Log.i(TAG, "Activity recognition stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop activity recognition", e)
        }
    }
    
    /**
     * Check if user is currently moving.
     */
    fun isMoving(): Boolean {
        return when (_currentActivity.value) {
            UserActivity.WALKING, UserActivity.RUNNING, 
            UserActivity.IN_VEHICLE, UserActivity.ON_BICYCLE -> true
            else -> false
        }
    }
    
    /**
     * Check if user is in a vehicle.
     */
    fun isInVehicle(): Boolean {
        return _currentActivity.value == UserActivity.IN_VEHICLE
    }
    
    private fun mapActivityType(type: Int): UserActivity {
        return when (type) {
            DetectedActivity.STILL -> UserActivity.STILL
            DetectedActivity.WALKING -> UserActivity.WALKING
            DetectedActivity.RUNNING -> UserActivity.RUNNING
            DetectedActivity.IN_VEHICLE -> UserActivity.IN_VEHICLE
            DetectedActivity.ON_BICYCLE -> UserActivity.ON_BICYCLE
            else -> UserActivity.UNKNOWN
        }
    }
    
    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed before Android 10
        }
    }
}
