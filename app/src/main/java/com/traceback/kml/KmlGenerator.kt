package com.traceback.kml

import android.content.Context
import android.location.Location
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Generates KML files from location data.
 * Compatible with Google Maps/Earth.
 */
class KmlGenerator(private val context: Context) {
    
    companion object {
        private const val KML_DIR = "kml"
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    
    private val kmlDir: File by lazy {
        File(context.filesDir, KML_DIR).apply { mkdirs() }
    }
    
    data class TrackPoint(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val timestamp: Long,
        val accuracy: Float,
        val isStopPoint: Boolean = false
    )
    
    private val todayPoints = mutableListOf<TrackPoint>()
    
    fun addPoint(location: Location, isStopPoint: Boolean = false) {
        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            timestamp = location.time,
            accuracy = location.accuracy,
            isStopPoint = isStopPoint
        )
        todayPoints.add(point)
        
        // Persist to daily file
        appendToTodayFile(point)
    }
    
    fun generateDailyKml(): String {
        val today = dateFormat.format(Date())
        return generateKml(todayPoints, "TraceBack $today")
    }
    
    fun generateKml(points: List<TrackPoint>, name: String): String {
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
            appendLine("<Document>")
            appendLine("<name>$name</name>")
            
            // Styles
            appendLine("""
                <Style id="trackStyle">
                    <LineStyle>
                        <color>ff0000ff</color>
                        <width>3</width>
                    </LineStyle>
                </Style>
                <Style id="stopStyle">
                    <IconStyle>
                        <Icon><href>http://maps.google.com/mapfiles/kml/paddle/red-circle.png</href></Icon>
                    </IconStyle>
                </Style>
            """.trimIndent())
            
            // Track as LineString
            if (points.size >= 2) {
                appendLine("<Placemark>")
                appendLine("<name>Route</name>")
                appendLine("<styleUrl>#trackStyle</styleUrl>")
                appendLine("<LineString>")
                appendLine("<tessellate>1</tessellate>")
                appendLine("<altitudeMode>clampToGround</altitudeMode>")
                appendLine("<coordinates>")
                
                points.forEach { point ->
                    appendLine("${point.longitude},${point.latitude},${point.altitude}")
                }
                
                appendLine("</coordinates>")
                appendLine("</LineString>")
                appendLine("</Placemark>")
            }
            
            // Stop points as Placemarks
            points.filter { it.isStopPoint }.forEach { point ->
                appendLine("<Placemark>")
                appendLine("<name>Stopp ${timeFormat.format(Date(point.timestamp))}</name>")
                appendLine("<styleUrl>#stopStyle</styleUrl>")
                appendLine("<TimeStamp><when>${timeFormat.format(Date(point.timestamp))}</when></TimeStamp>")
                appendLine("<Point>")
                appendLine("<coordinates>${point.longitude},${point.latitude},${point.altitude}</coordinates>")
                appendLine("</Point>")
                appendLine("</Placemark>")
            }
            
            appendLine("</Document>")
            appendLine("</kml>")
        }
    }
    
    private fun appendToTodayFile(point: TrackPoint) {
        val today = dateFormat.format(Date())
        val file = File(kmlDir, "track_$today.csv")
        
        val line = "${point.timestamp},${point.latitude},${point.longitude},${point.altitude},${point.accuracy},${point.isStopPoint}\n"
        file.appendText(line)
    }
    
    fun loadTodayPoints(): List<TrackPoint> {
        val today = dateFormat.format(Date())
        val file = File(kmlDir, "track_$today.csv")
        
        if (!file.exists()) return emptyList()
        
        return file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    val parts = line.split(",")
                    TrackPoint(
                        timestamp = parts[0].toLong(),
                        latitude = parts[1].toDouble(),
                        longitude = parts[2].toDouble(),
                        altitude = parts[3].toDouble(),
                        accuracy = parts[4].toFloat(),
                        isStopPoint = parts[5].toBoolean()
                    )
                } catch (e: Exception) {
                    null
                }
            }
    }
    
    /**
     * Data Aging: Compress daily logs to weekly summary.
     * Keeps only stop points and hourly samples.
     */
    fun archiveWeek(weekFiles: List<File>): String {
        val allPoints = weekFiles.flatMap { file ->
            file.readLines().mapNotNull { line ->
                try {
                    val parts = line.split(",")
                    TrackPoint(
                        timestamp = parts[0].toLong(),
                        latitude = parts[1].toDouble(),
                        longitude = parts[2].toDouble(),
                        altitude = parts[3].toDouble(),
                        accuracy = parts[4].toFloat(),
                        isStopPoint = parts[5].toBoolean()
                    )
                } catch (e: Exception) { null }
            }
        }
        
        // Keep stop points + hourly samples
        val reduced = mutableListOf<TrackPoint>()
        var lastHour = -1L
        
        allPoints.sortedBy { it.timestamp }.forEach { point ->
            val hour = point.timestamp / 3600000
            if (point.isStopPoint || hour != lastHour) {
                reduced.add(point)
                lastHour = hour
            }
        }
        
        return generateKml(reduced, "Woche ${dateFormat.format(Date(allPoints.first().timestamp))}")
    }
}
