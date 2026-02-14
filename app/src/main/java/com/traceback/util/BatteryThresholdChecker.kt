package com.traceback.util

/**
 * Pure-logic battery threshold checking, extracted for testability.
 * No Android dependencies.
 */
object BatteryThresholdChecker {

    /**
     * Determine which threshold (if any) should trigger for the current battery level.
     * Returns the highest matching threshold that hasn't been triggered yet, or null.
     */
    fun findTriggeredThreshold(
        currentPercentage: Int,
        thresholds: Set<Int>,
        alreadyTriggered: Set<Int>
    ): Int? {
        if (thresholds.isEmpty()) return null

        for (threshold in thresholds.sortedDescending()) {
            if (currentPercentage <= threshold && threshold !in alreadyTriggered) {
                return threshold
            }
        }
        return null
    }

    /**
     * Determine if triggered thresholds should be reset (battery charged sufficiently).
     * Uses higher margin (10%) for thresholds >= 15% to avoid frequent re-triggering.
     */
    fun shouldResetTriggers(currentPercentage: Int, thresholds: Set<Int>): Boolean {
        val highestThreshold = thresholds.maxOrNull() ?: 15
        val resetMargin = if (highestThreshold >= 15) 10 else 5
        return currentPercentage > highestThreshold + resetMargin
    }

    /**
     * Determine if charging alert (80%) should fire.
     */
    fun shouldShowChargingAlert(
        currentPercentage: Int,
        isCharging: Boolean,
        chargingAlertEnabled: Boolean,
        alreadyAlerted: Boolean
    ): Boolean {
        return chargingAlertEnabled && isCharging && currentPercentage >= 80 && !alreadyAlerted
    }

    /**
     * Determine if charging alert state should be reset (hysteresis at 75%).
     */
    fun shouldResetChargingAlert(currentPercentage: Int): Boolean {
        return currentPercentage < 75
    }
}
