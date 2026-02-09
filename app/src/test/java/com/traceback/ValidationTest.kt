package com.traceback

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for input validation.
 */
class ValidationTest {

    @Test
    fun `Telegram bot token format is validated`() {
        val validToken = "123456789:ABCdefGHIjklMNOpqrsTUVwxyz"
        val invalidToken1 = "not-a-token"
        val invalidToken2 = ""
        val invalidToken3 = "123456789" // Missing second part
        
        assertTrue(isValidBotToken(validToken))
        assertFalse(isValidBotToken(invalidToken1))
        assertFalse(isValidBotToken(invalidToken2))
        assertFalse(isValidBotToken(invalidToken3))
    }

    @Test
    fun `Telegram chat ID format is validated`() {
        val validChatId1 = "123456789"
        val validChatId2 = "-1001234567890" // Group chat
        val invalidChatId1 = ""
        val invalidChatId2 = "abc123"
        
        assertTrue(isValidChatId(validChatId1))
        assertTrue(isValidChatId(validChatId2))
        assertFalse(isValidChatId(invalidChatId1))
        assertFalse(isValidChatId(invalidChatId2))
    }

    @Test
    fun `phone number format is validated`() {
        val validNumber1 = "+491701234567"
        val validNumber2 = "+1234567890"
        val invalidNumber1 = "01701234567" // Missing +
        val invalidNumber2 = ""
        val invalidNumber3 = "abc"
        
        assertTrue(isValidPhoneNumber(validNumber1))
        assertTrue(isValidPhoneNumber(validNumber2))
        assertFalse(isValidPhoneNumber(invalidNumber1))
        assertFalse(isValidPhoneNumber(invalidNumber2))
        assertFalse(isValidPhoneNumber(invalidNumber3))
    }

    @Test
    fun `tracking distance is within bounds`() {
        val minDistance = 50
        val maxDistance = 2000
        
        assertEquals(50, coerceDistance(30, minDistance, maxDistance))
        assertEquals(300, coerceDistance(300, minDistance, maxDistance))
        assertEquals(2000, coerceDistance(3000, minDistance, maxDistance))
    }

    @Test
    fun `SSID is valid anchor name`() {
        val validSSID1 = "HomeNetwork"
        val validSSID2 = "Office-WiFi_5G"
        val invalidSSID1 = "" // Empty
        val invalidSSID2 = "<unknown ssid>" // Android placeholder
        
        assertTrue(isValidSSID(validSSID1))
        assertTrue(isValidSSID(validSSID2))
        assertFalse(isValidSSID(invalidSSID1))
        assertFalse(isValidSSID(invalidSSID2))
    }

    @Test
    fun `latitude is within valid range`() {
        assertTrue(isValidLatitude(0.0))
        assertTrue(isValidLatitude(48.123))
        assertTrue(isValidLatitude(-33.9))
        assertTrue(isValidLatitude(90.0))
        assertTrue(isValidLatitude(-90.0))
        
        assertFalse(isValidLatitude(91.0))
        assertFalse(isValidLatitude(-91.0))
        assertFalse(isValidLatitude(Double.NaN))
    }

    @Test
    fun `longitude is within valid range`() {
        assertTrue(isValidLongitude(0.0))
        assertTrue(isValidLongitude(11.5))
        assertTrue(isValidLongitude(-122.4))
        assertTrue(isValidLongitude(180.0))
        assertTrue(isValidLongitude(-180.0))
        
        assertFalse(isValidLongitude(181.0))
        assertFalse(isValidLongitude(-181.0))
        assertFalse(isValidLongitude(Double.NaN))
    }

    @Test
    fun `accuracy threshold is reasonable`() {
        val goodAccuracy = 10.0f // 10 meters
        val okAccuracy = 50.0f // 50 meters
        val badAccuracy = 200.0f // 200 meters
        val threshold = 100.0f
        
        assertTrue(isAccuracyAcceptable(goodAccuracy, threshold))
        assertTrue(isAccuracyAcceptable(okAccuracy, threshold))
        assertFalse(isAccuracyAcceptable(badAccuracy, threshold))
    }

    // Validation helper functions

    private fun isValidBotToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return token.matches(Regex("^\\d+:[A-Za-z0-9_-]+$"))
    }

    private fun isValidChatId(chatId: String?): Boolean {
        if (chatId.isNullOrBlank()) return false
        return chatId.matches(Regex("^-?\\d+$"))
    }

    private fun isValidPhoneNumber(number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        return number.matches(Regex("^\\+\\d{10,15}$"))
    }

    private fun coerceDistance(value: Int, min: Int, max: Int): Int {
        return value.coerceIn(min, max)
    }

    private fun isValidSSID(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        if (ssid == "<unknown ssid>") return false
        return true
    }

    private fun isValidLatitude(lat: Double): Boolean {
        return !lat.isNaN() && lat >= -90.0 && lat <= 90.0
    }

    private fun isValidLongitude(lon: Double): Boolean {
        return !lon.isNaN() && lon >= -180.0 && lon <= 180.0
    }

    private fun isAccuracyAcceptable(accuracy: Float, threshold: Float): Boolean {
        return accuracy <= threshold
    }
}
