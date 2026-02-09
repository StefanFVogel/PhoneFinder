package com.traceback

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Unit tests for tracking logic (distance, speed, movement detection).
 */
class TrackingLogicTest {

    @Test
    fun `Haversine distance calculation is accurate`() {
        // Munich to Nuremberg: ~150km
        val lat1 = 48.1351
        val lon1 = 11.5820
        val lat2 = 49.4521
        val lon2 = 11.0767
        
        val distance = haversineDistance(lat1, lon1, lat2, lon2)
        
        // Should be approximately 150km (150,000m)
        assertTrue(distance > 140_000 && distance < 160_000)
    }

    @Test
    fun `zero distance for same point`() {
        val lat = 48.0
        val lon = 11.0
        
        val distance = haversineDistance(lat, lon, lat, lon)
        
        assertEquals(0.0, distance, 0.001)
    }

    @Test
    fun `speed calculation is correct`() {
        val distance = 300.0 // meters
        val timeMs = 60_000L // 1 minute
        
        val speed = calculateSpeed(distance, timeMs)
        
        // 300m in 60s = 5 m/s
        assertEquals(5.0, speed, 0.01)
    }

    @Test
    fun `movement detection threshold works`() {
        val movingSpeed = 5.0 // m/s (18 km/h)
        val stationarySpeed = 0.5 // m/s (1.8 km/h)
        val threshold = 1.0 // m/s
        
        assertTrue(isMoving(movingSpeed, threshold))
        assertFalse(isMoving(stationarySpeed, threshold))
    }

    @Test
    fun `distance threshold filtering works`() {
        val distanceThreshold = 300 // meters
        
        assertTrue(shouldLogPoint(350.0, distanceThreshold))
        assertFalse(shouldLogPoint(250.0, distanceThreshold))
    }

    @Test
    fun `stationary anchor detection works`() {
        val stationaryAnchors = setOf("HomeWiFi", "OfficeWiFi")
        val currentSSID = "HomeWiFi"
        val unknownSSID = "CoffeeShop"
        
        assertTrue(isConnectedToStationaryAnchor(currentSSID, stationaryAnchors))
        assertFalse(isConnectedToStationaryAnchor(unknownSSID, stationaryAnchors))
    }

    @Test
    fun `drift detection triggers above threshold`() {
        val driftThreshold = 500.0 // meters
        
        assertTrue(isDriftDetected(600.0, driftThreshold))
        assertFalse(isDriftDetected(400.0, driftThreshold))
    }

    @Test
    fun `battery threshold triggers Last Breath`() {
        val threshold = 2
        
        assertTrue(shouldTriggerLastBreath(1, threshold))
        assertTrue(shouldTriggerLastBreath(2, threshold))
        assertFalse(shouldTriggerLastBreath(3, threshold))
        assertFalse(shouldTriggerLastBreath(50, threshold))
    }

    // Helper functions (pure logic, no Android dependencies)

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2).pow(2) + 
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * 
                sin(dLon / 2).pow(2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return R * c
    }

    private fun calculateSpeed(distanceMeters: Double, timeMs: Long): Double {
        if (timeMs <= 0) return 0.0
        return distanceMeters / (timeMs / 1000.0)
    }

    private fun isMoving(speed: Double, threshold: Double): Boolean {
        return speed > threshold
    }

    private fun shouldLogPoint(distance: Double, threshold: Int): Boolean {
        return distance >= threshold
    }

    private fun isConnectedToStationaryAnchor(ssid: String, anchors: Set<String>): Boolean {
        return anchors.contains(ssid)
    }

    private fun isDriftDetected(drift: Double, threshold: Double): Boolean {
        return drift > threshold
    }

    private fun shouldTriggerLastBreath(batteryPercent: Int, threshold: Int): Boolean {
        return batteryPercent <= threshold
    }
}
