package com.traceback

/**
 * Pure-logic battery threshold checking, extracted for testability.
 * Mirror of app/src/main/.../util/BatteryThresholdChecker.kt (no Android deps).
 */
object BatteryThresholdChecker {

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

    fun shouldResetTriggers(currentPercentage: Int, thresholds: Set<Int>): Boolean {
        val highestThreshold = thresholds.maxOrNull() ?: 15
        val resetMargin = if (highestThreshold >= 15) 10 else 5
        return currentPercentage > highestThreshold + resetMargin
    }

    fun shouldShowChargingAlert(
        currentPercentage: Int,
        isCharging: Boolean,
        chargingAlertEnabled: Boolean,
        alreadyAlerted: Boolean
    ): Boolean {
        return chargingAlertEnabled && isCharging && currentPercentage >= 80 && !alreadyAlerted
    }

    fun shouldResetChargingAlert(currentPercentage: Int): Boolean {
        return currentPercentage < 75
    }
}
