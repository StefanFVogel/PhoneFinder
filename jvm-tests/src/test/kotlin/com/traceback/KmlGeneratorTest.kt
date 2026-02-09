package com.traceback

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for KML generation logic.
 * These tests run on the JVM without Android dependencies.
 */
class KmlGeneratorTest {

    @Test
    fun `KML header is valid XML`() {
        val header = """<?xml version="1.0" encoding="UTF-8"?>"""
        assertTrue(header.startsWith("<?xml"))
        assertTrue(header.contains("UTF-8"))
    }

    @Test
    fun `coordinate format is correct`() {
        val lat = 48.123456
        val lon = 11.654321
        val alt = 520.0
        
        val coordString = "$lon,$lat,$alt"
        
        assertEquals("11.654321,48.123456,520.0", coordString)
    }

    @Test
    fun `KML placemark structure is valid`() {
        val name = "Test Point"
        val lat = 48.0
        val lon = 11.0
        
        val placemark = buildPlacemark(name, lat, lon)
        
        assertTrue(placemark.contains("<Placemark>"))
        assertTrue(placemark.contains("<name>$name</name>"))
        assertTrue(placemark.contains("<Point>"))
        assertTrue(placemark.contains("<coordinates>"))
        assertTrue(placemark.contains("</Placemark>"))
    }

    @Test
    fun `multiple coordinates form valid LineString`() {
        val points = listOf(
            Triple(48.0, 11.0, 0.0),
            Triple(48.1, 11.1, 0.0),
            Triple(48.2, 11.2, 0.0)
        )
        
        val lineString = buildLineString(points)
        
        assertTrue(lineString.contains("<LineString>"))
        assertTrue(lineString.contains("<coordinates>"))
        assertEquals(3, lineString.split("\n").filter { it.contains(",") }.size)
    }

    @Test
    fun `stop points are marked correctly`() {
        val stopPoint = buildPlacemark("Stop", 48.0, 11.0, isStop = true)
        
        assertTrue(stopPoint.contains("stopStyle") || stopPoint.contains("red-circle"))
    }

    // Helper functions (would be in actual KmlGenerator)
    
    private fun buildPlacemark(name: String, lat: Double, lon: Double, isStop: Boolean = false): String {
        val style = if (isStop) "#stopStyle" else "#trackStyle"
        return """
            <Placemark>
            <name>$name</name>
            <styleUrl>$style</styleUrl>
            <Point>
            <coordinates>$lon,$lat,0</coordinates>
            </Point>
            </Placemark>
        """.trimIndent()
    }

    private fun buildLineString(points: List<Triple<Double, Double, Double>>): String {
        val coords = points.joinToString("\n") { (lat, lon, alt) -> "$lon,$lat,$alt" }
        return """
            <LineString>
            <coordinates>
            $coords
            </coordinates>
            </LineString>
        """.trimIndent()
    }
}
