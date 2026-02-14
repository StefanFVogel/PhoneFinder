package com.traceback

import org.junit.Assert.*
import org.junit.Test

/**
 * Comprehensive tests for BatteryThresholdChecker logic.
 */
class BatteryThresholdCheckerTest {

    // --- findTriggeredThreshold ---

    @Test
    fun `no trigger when thresholds empty`() {
        val result = BatteryThresholdChecker.findTriggeredThreshold(5, emptySet(), emptySet())
        assertNull(result)
    }

    @Test
    fun `triggers highest matching threshold`() {
        val thresholds = setOf(20, 15, 10, 8, 5, 4, 3, 2)
        // 12% is below 20, 15 - triggers highest (20) first
        val result = BatteryThresholdChecker.findTriggeredThreshold(12, thresholds, emptySet())
        assertEquals(20, result)
    }

    @Test
    fun `does not re-trigger already triggered threshold`() {
        val thresholds = setOf(15, 8, 4, 2)
        val alreadyTriggered = setOf(15)
        // 12 is below 15 (already triggered) but above 8
        val result = BatteryThresholdChecker.findTriggeredThreshold(12, thresholds, alreadyTriggered)
        assertNull(result)
    }

    @Test
    fun `triggers next threshold when previous already triggered`() {
        val thresholds = setOf(15, 8, 4, 2)
        val alreadyTriggered = setOf(15)
        val result = BatteryThresholdChecker.findTriggeredThreshold(7, thresholds, alreadyTriggered)
        assertEquals(8, result)
    }

    @Test
    fun `rapid drop triggers highest unhandled threshold only`() {
        val thresholds = setOf(20, 15, 10, 8, 5, 4, 3, 2)
        // Battery drops to 1% - should trigger 20 (highest unhandled)
        val result = BatteryThresholdChecker.findTriggeredThreshold(1, thresholds, emptySet())
        assertEquals(20, result)
    }

    @Test
    fun `sequential drops trigger each threshold in order`() {
        val thresholds = setOf(15, 8, 4, 2)
        val triggered = mutableSetOf<Int>()

        val t1 = BatteryThresholdChecker.findTriggeredThreshold(14, thresholds, triggered)
        assertEquals(15, t1)
        triggered.add(t1!!)

        val t2 = BatteryThresholdChecker.findTriggeredThreshold(7, thresholds, triggered)
        assertEquals(8, t2)
        triggered.add(t2!!)

        val t3 = BatteryThresholdChecker.findTriggeredThreshold(3, thresholds, triggered)
        assertEquals(4, t3)
        triggered.add(t3!!)

        val t4 = BatteryThresholdChecker.findTriggeredThreshold(1, thresholds, triggered)
        assertEquals(2, t4)
    }

    @Test
    fun `no trigger when battery above all thresholds`() {
        val thresholds = setOf(20, 15, 8, 4, 2)
        val result = BatteryThresholdChecker.findTriggeredThreshold(50, thresholds, emptySet())
        assertNull(result)
    }

    @Test
    fun `triggers at exact threshold boundary`() {
        val thresholds = setOf(10, 5)
        val result = BatteryThresholdChecker.findTriggeredThreshold(10, thresholds, emptySet())
        assertEquals(10, result)
    }

    @Test
    fun `all thresholds already triggered returns null`() {
        val thresholds = setOf(15, 8, 4, 2)
        val allTriggered = setOf(15, 8, 4, 2)
        val result = BatteryThresholdChecker.findTriggeredThreshold(1, thresholds, allTriggered)
        assertNull(result)
    }

    // --- shouldResetTriggers ---

    @Test
    fun `reset at highest+10 when highest threshold is 20`() {
        val thresholds = setOf(20, 15, 8)
        assertTrue(BatteryThresholdChecker.shouldResetTriggers(31, thresholds))
        assertFalse(BatteryThresholdChecker.shouldResetTriggers(30, thresholds))
        assertFalse(BatteryThresholdChecker.shouldResetTriggers(25, thresholds))
    }

    @Test
    fun `reset at highest+10 when highest threshold is 15`() {
        val thresholds = setOf(15, 8, 4, 2)
        assertTrue(BatteryThresholdChecker.shouldResetTriggers(26, thresholds))
        assertFalse(BatteryThresholdChecker.shouldResetTriggers(24, thresholds))
    }

    @Test
    fun `reset at highest+5 when highest threshold below 15`() {
        val thresholds = setOf(8, 4, 2)
        assertTrue(BatteryThresholdChecker.shouldResetTriggers(14, thresholds))
        assertFalse(BatteryThresholdChecker.shouldResetTriggers(12, thresholds))
    }

    @Test
    fun `reset defaults to 15+10 when thresholds empty`() {
        assertTrue(BatteryThresholdChecker.shouldResetTriggers(26, emptySet()))
        assertFalse(BatteryThresholdChecker.shouldResetTriggers(24, emptySet()))
    }

    // --- shouldShowChargingAlert ---

    @Test
    fun `charging alert fires when all conditions met`() {
        assertTrue(BatteryThresholdChecker.shouldShowChargingAlert(82, true, true, false))
    }

    @Test
    fun `charging alert fires at exactly 80`() {
        assertTrue(BatteryThresholdChecker.shouldShowChargingAlert(80, true, true, false))
    }

    @Test
    fun `charging alert does not fire below 80`() {
        assertFalse(BatteryThresholdChecker.shouldShowChargingAlert(79, true, true, false))
    }

    @Test
    fun `charging alert does not fire when not charging`() {
        assertFalse(BatteryThresholdChecker.shouldShowChargingAlert(85, false, true, false))
    }

    @Test
    fun `charging alert does not fire when disabled`() {
        assertFalse(BatteryThresholdChecker.shouldShowChargingAlert(85, true, false, false))
    }

    @Test
    fun `charging alert does not re-fire when already alerted`() {
        assertFalse(BatteryThresholdChecker.shouldShowChargingAlert(85, true, true, true))
    }

    // --- shouldResetChargingAlert ---

    @Test
    fun `charging alert resets below 75`() {
        assertTrue(BatteryThresholdChecker.shouldResetChargingAlert(74))
    }

    @Test
    fun `charging alert does not reset at 75 or above`() {
        assertFalse(BatteryThresholdChecker.shouldResetChargingAlert(75))
        assertFalse(BatteryThresholdChecker.shouldResetChargingAlert(80))
    }
}
