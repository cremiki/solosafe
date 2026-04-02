package com.solosafe.app.sensor

/**
 * Soglie per ogni preset ambientale (da CLAUDE.md).
 * Format: fallG, immobilityPreAlarmSec, immobilityAlarmSec, fallConfirmSec
 */
data class PresetThresholds(
    val fallThresholdG: Float,
    val immobilityPreAlarmSec: Int,
    val immobilityAlarmSec: Int,
    val fallConfirmSec: Int,
    val fallEnabled: Boolean = true,
) {
    companion object {
        fun forPreset(preset: String): PresetThresholds = when (preset.uppercase()) {
            "OFFICE"       -> PresetThresholds(2.0f, 60, 90, 30)
            "WAREHOUSE"    -> PresetThresholds(2.8f, 90, 120, 25)
            "CONSTRUCTION" -> PresetThresholds(3.5f, 120, 150, 20)
            "INDUSTRY"     -> PresetThresholds(4.0f, 150, 180, 20)
            "VEHICLE"      -> PresetThresholds(0f, 180, 210, 30, fallEnabled = false)
            "ALTITUDE"     -> PresetThresholds(2.0f, 60, 90, 10)
            else           -> PresetThresholds(2.8f, 90, 120, 25) // default WAREHOUSE
        }
    }
}
