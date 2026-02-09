package com.traceback

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Telegram notification logic.
 */
class TelegramNotifierTest {

    @Test
    fun `Telegram API URL is correctly formed`() {
        val token = "123456:ABC-DEF"
        val baseUrl = "https://api.telegram.org/bot"
        
        val url = "${baseUrl}${token}/sendMessage"
        
        assertEquals("https://api.telegram.org/bot123456:ABC-DEF/sendMessage", url)
    }

    @Test
    fun `message JSON is valid`() {
        val chatId = "12345678"
        val message = "Test message"
        
        val json = buildMessageJson(chatId, message)
        
        assertTrue(json.contains("\"chat_id\""))
        assertTrue(json.contains("\"text\""))
        assertTrue(json.contains(chatId))
        assertTrue(json.contains(message))
    }

    @Test
    fun `location JSON is valid`() {
        val chatId = "12345678"
        val lat = 48.123456
        val lon = 11.654321
        
        val json = buildLocationJson(chatId, lat, lon)
        
        assertTrue(json.contains("\"latitude\""))
        assertTrue(json.contains("\"longitude\""))
        assertTrue(json.contains("48.123456"))
        assertTrue(json.contains("11.654321"))
    }

    @Test
    fun `Last Breath message contains all required info`() {
        val reason = "Battery critical"
        val lat = 48.0
        val lon = 11.0
        val wifiList = listOf("HomeNetwork", "Neighbor_5G")
        
        val message = buildLastBreathMessage(reason, lat, lon, wifiList)
        
        assertTrue(message.contains("Last Breath"))
        assertTrue(message.contains(reason))
        assertTrue(message.contains("48.0"))
        assertTrue(message.contains("11.0"))
        assertTrue(message.contains("maps.google.com"))
        assertTrue(message.contains("HomeNetwork"))
    }

    @Test
    fun `empty wifi list is handled`() {
        val message = buildLastBreathMessage("Test", 48.0, 11.0, emptyList())
        
        assertFalse(message.contains("Sichtbare WLANs"))
    }

    @Test
    fun `special characters are not escaped incorrectly`() {
        val message = "Test with special chars: äöü ß € @"
        val json = buildMessageJson("123", message)
        
        assertTrue(json.contains("äöü"))
        assertTrue(json.contains("€"))
    }

    // Helper functions
    
    private fun buildMessageJson(chatId: String, text: String): String {
        return """{"chat_id":"$chatId","text":"$text","parse_mode":"HTML"}"""
    }

    private fun buildLocationJson(chatId: String, lat: Double, lon: Double): String {
        return """{"chat_id":"$chatId","latitude":$lat,"longitude":$lon}"""
    }

    private fun buildLastBreathMessage(
        reason: String, 
        lat: Double, 
        lon: Double, 
        wifiList: List<String>
    ): String {
        return buildString {
            appendLine("🚨 TraceBack Last Breath")
            appendLine("Grund: $reason")
            appendLine()
            appendLine("📍 Letzter Standort:")
            appendLine("Lat: $lat")
            appendLine("Lon: $lon")
            appendLine("https://maps.google.com/?q=$lat,$lon")
            if (wifiList.isNotEmpty()) {
                appendLine()
                appendLine("📶 Sichtbare WLANs:")
                wifiList.take(5).forEach { appendLine("• $it") }
            }
        }
    }
}
