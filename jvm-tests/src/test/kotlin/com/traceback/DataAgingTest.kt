package com.traceback

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

/**
 * Unit tests for data aging (archival) logic.
 */
class DataAgingTest {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Test
    fun `daily files are named correctly`() {
        val date = dateFormat.parse("2026-02-08")!!
        val fileName = generateDailyFileName(date)
        
        assertEquals("track_2026-02-08.csv", fileName)
    }

    @Test
    fun `weekly archive includes correct date range`() {
        val startDate = dateFormat.parse("2026-02-03")!! // Monday
        val endDate = dateFormat.parse("2026-02-09")!! // Sunday
        
        val weekFiles = getWeekFiles(startDate, endDate)
        
        assertEquals(7, weekFiles.size)
        assertTrue(weekFiles.first().contains("2026-02-03"))
        assertTrue(weekFiles.last().contains("2026-02-09"))
    }

    @Test
    fun `hourly sampling reduces data correctly`() {
        // Simulate 60 points over 1 hour (1 per minute)
        val points = (0 until 60).map { minute ->
            val timestamp = 1707400800000L + (minute * 60_000) // Feb 8, 2024 14:00 + minutes
            TrackPoint(48.0 + minute * 0.0001, 11.0, timestamp, false)
        }
        
        val reduced = reduceToHourlySamples(points)
        
        // Should keep only 1 point per hour (first one)
        assertEquals(1, reduced.size)
    }

    @Test
    fun `stop points are always preserved`() {
        val points = listOf(
            TrackPoint(48.0, 11.0, 1707400800000L, false),
            TrackPoint(48.1, 11.1, 1707401400000L, true), // Stop point
            TrackPoint(48.2, 11.2, 1707402000000L, false),
            TrackPoint(48.3, 11.3, 1707402600000L, true)  // Stop point
        )
        
        val reduced = reduceToHourlySamples(points)
        
        // Both stop points should be preserved
        val stopCount = reduced.count { it.isStopPoint }
        assertEquals(2, stopCount)
    }

    @Test
    fun `old files are identified for deletion`() {
        val now = System.currentTimeMillis()
        val oldFile = FileInfo("track_old.csv", now - (31L * 24 * 60 * 60 * 1000)) // 31 days ago
        val recentFile = FileInfo("track_recent.csv", now - (7L * 24 * 60 * 60 * 1000)) // 7 days ago
        
        val filesToDelete = filterOldFiles(listOf(oldFile, recentFile), 30)
        
        assertEquals(1, filesToDelete.size)
        assertEquals("track_old.csv", filesToDelete.first().name)
    }

    @Test
    fun `CSV line parsing is correct`() {
        val line = "1707400800000,48.123456,11.654321,520.0,5.5,false"
        
        val point = parseCSVLine(line)
        
        assertEquals(1707400800000L, point.timestamp)
        assertEquals(48.123456, point.lat, 0.000001)
        assertEquals(11.654321, point.lon, 0.000001)
        assertFalse(point.isStopPoint)
    }

    @Test
    fun `CSV line generation is correct`() {
        val point = TrackPoint(48.123456, 11.654321, 1707400800000L, true)
        
        val line = toCSVLine(point)
        
        assertTrue(line.contains("48.123456"))
        assertTrue(line.contains("11.654321"))
        assertTrue(line.contains("1707400800000"))
        assertTrue(line.contains("true"))
    }

    // Data classes
    data class TrackPoint(
        val lat: Double,
        val lon: Double,
        val timestamp: Long,
        val isStopPoint: Boolean
    )

    data class FileInfo(val name: String, val createdTime: Long)

    // Helper functions
    
    private fun generateDailyFileName(date: Date): String {
        return "track_${dateFormat.format(date)}.csv"
    }

    private fun getWeekFiles(start: Date, end: Date): List<String> {
        val files = mutableListOf<String>()
        val cal = Calendar.getInstance().apply { time = start }
        
        while (!cal.time.after(end)) {
            files.add(generateDailyFileName(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        
        return files
    }

    private fun reduceToHourlySamples(points: List<TrackPoint>): List<TrackPoint> {
        val result = mutableListOf<TrackPoint>()
        var lastHour = -1L
        
        for (point in points.sortedBy { it.timestamp }) {
            val hour = point.timestamp / 3600000
            if (point.isStopPoint || hour != lastHour) {
                result.add(point)
                lastHour = hour
            }
        }
        
        return result
    }

    private fun filterOldFiles(files: List<FileInfo>, maxAgeDays: Int): List<FileInfo> {
        val cutoff = System.currentTimeMillis() - (maxAgeDays.toLong() * 24 * 60 * 60 * 1000)
        return files.filter { it.createdTime < cutoff }
    }

    private fun parseCSVLine(line: String): TrackPoint {
        val parts = line.split(",")
        return TrackPoint(
            lat = parts[1].toDouble(),
            lon = parts[2].toDouble(),
            timestamp = parts[0].toLong(),
            isStopPoint = parts[5].toBoolean()
        )
    }

    private fun toCSVLine(point: TrackPoint): String {
        return "${point.timestamp},${point.lat},${point.lon},0.0,0.0,${point.isStopPoint}"
    }
}
