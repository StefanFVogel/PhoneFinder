package com.traceback.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.traceback.kml.KmlGenerator
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Worker for data aging - compresses old daily logs to weekly/monthly summaries.
 */
class DataAgingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "DataAgingWorker"
        private const val WORK_NAME = "traceback_data_aging"
        
        private const val DAYS_TO_KEEP_DAILY = 7     // Keep daily logs for 7 days
        private const val WEEKS_TO_KEEP_WEEKLY = 4   // Keep weekly logs for 4 weeks
        
        /**
         * Schedule daily data aging check.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            
            val request = PeriodicWorkRequestBuilder<DataAgingWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.HOURS)  // Don't run immediately
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            
            Log.i(TAG, "Scheduled data aging worker")
        }
    }
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val kmlDir: File by lazy {
        File(applicationContext.filesDir, "kml").apply { mkdirs() }
    }
    
    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting data aging")
        
        return try {
            compressDailyToWeekly()
            cleanupOldFiles()
            
            Log.i(TAG, "Data aging completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Data aging failed", e)
            Result.retry()
        }
    }
    
    /**
     * Compress daily logs older than DAYS_TO_KEEP_DAILY into weekly summaries.
     */
    private fun compressDailyToWeekly() {
        val cutoffDate = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -DAYS_TO_KEEP_DAILY)
        }.time
        
        // Find all daily CSV files
        val dailyFiles = kmlDir.listFiles { file ->
            file.name.startsWith("track_") && file.name.endsWith(".csv")
        } ?: return
        
        // Group by week
        val filesByWeek = dailyFiles.groupBy { file ->
            val dateStr = file.name.removePrefix("track_").removeSuffix(".csv")
            try {
                val date = dateFormat.parse(dateStr) ?: return@groupBy null
                if (date.after(cutoffDate)) return@groupBy null  // Too recent
                
                val cal = Calendar.getInstance().apply { time = date }
                "${cal.get(Calendar.YEAR)}-W${cal.get(Calendar.WEEK_OF_YEAR)}"
            } catch (e: Exception) {
                null
            }
        }.filterKeys { it != null }
        
        // Create weekly summaries
        filesByWeek.forEach { (weekKey, files) ->
            if (weekKey == null || files.size < 2) return@forEach
            
            val weekFile = File(kmlDir, "week_$weekKey.csv")
            if (weekFile.exists()) return@forEach  // Already processed
            
            Log.i(TAG, "Compressing ${files.size} daily files into $weekKey")
            
            // Combine and reduce: keep stop points + hourly samples
            val allLines = files.flatMap { it.readLines() }
            val reduced = reduceToHourlySamples(allLines)
            
            weekFile.writeText(reduced.joinToString("\n"))
            
            // Delete processed daily files
            files.forEach { it.delete() }
        }
    }
    
    /**
     * Reduce data to stop points + one sample per hour.
     */
    private fun reduceToHourlySamples(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        var lastHour = -1L
        
        lines.sortedBy { 
            it.split(",").firstOrNull()?.toLongOrNull() ?: 0L 
        }.forEach { line ->
            val parts = line.split(",")
            if (parts.size < 6) return@forEach
            
            val timestamp = parts[0].toLongOrNull() ?: return@forEach
            val isStopPoint = parts[5].toBoolean()
            val hour = timestamp / 3600000
            
            if (isStopPoint || hour != lastHour) {
                result.add(line)
                lastHour = hour
            }
        }
        
        return result
    }
    
    /**
     * Clean up old weekly files.
     */
    private fun cleanupOldFiles() {
        val cutoffDate = Calendar.getInstance().apply {
            add(Calendar.WEEK_OF_YEAR, -WEEKS_TO_KEEP_WEEKLY)
        }.time
        
        kmlDir.listFiles { file ->
            file.name.startsWith("week_") && file.name.endsWith(".csv")
        }?.forEach { file ->
            val weekStr = file.name.removePrefix("week_").removeSuffix(".csv")
            try {
                val parts = weekStr.split("-W")
                if (parts.size == 2) {
                    val year = parts[0].toInt()
                    val week = parts[1].toInt()
                    
                    val fileDate = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.WEEK_OF_YEAR, week)
                    }.time
                    
                    if (fileDate.before(cutoffDate)) {
                        Log.i(TAG, "Deleting old weekly file: ${file.name}")
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing week file name: ${file.name}", e)
            }
        }
    }
}
