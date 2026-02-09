package com.traceback.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.traceback.TraceBackApp
import com.traceback.drive.DriveManager
import com.traceback.kml.KmlGenerator
import java.util.concurrent.TimeUnit

/**
 * Periodic worker for syncing KML to Google Drive.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "traceback_sync"
        
        /**
         * Schedule periodic sync (every hour).
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            
            Log.i(TAG, "Scheduled periodic sync")
        }
        
        /**
         * Cancel periodic sync.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic sync")
        }
        
        /**
         * Run sync immediately.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "Triggered immediate sync")
        }
    }
    
    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting sync work")
        
        return try {
            val kmlGenerator = KmlGenerator(applicationContext)
            val driveManager = DriveManager(applicationContext)
            
            if (!driveManager.isReady()) {
                Log.w(TAG, "Drive not ready, skipping sync")
                return Result.retry()
            }
            
            val kmlContent = kmlGenerator.generateDailyKml()
            val success = driveManager.uploadKml(kmlContent)
            
            if (success) {
                TraceBackApp.instance.securePrefs.lastSyncTimestamp = System.currentTimeMillis()
                Log.i(TAG, "Sync completed successfully")
                Result.success()
            } else {
                Log.e(TAG, "Sync failed")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync error", e)
            Result.retry()
        }
    }
}
