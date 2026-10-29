package com.saivenkatkrishna.ghostblue.data.charging

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

/**
 * Rates and time zone snapshotted with a session, so later tariff edits
 * cannot rewrite its bill. Energy is spread over the session by elapsed
 * time; a meter reading or receipt remains authoritative.
 */
@Serializable
data class ChargingTariff(
    val standardRate: Double,
    val offPeakRate: Double? = null,
    val peakRate: Double? = null,
    val usesSDGE: Boolean,
    val offPeakStartHour: Int,
    val offPeakEndHour: Int,
    val timeZoneIdentifier: String,
) {
    private val zone: ZoneId
        get() = runCatching { ZoneId.of(timeZoneIdentifier) }.getOrDefault(SDGESmartChargingSchedule.timeZone)

    fun rate(at: Instant): Double {
        val zone = zone
        val hour = at.atZone(zone).hour
        if (usesSDGE) {
            if (SDGESmartChargingSchedule.preference(at, zone) == SmartChargingPreference.CHARGE) {
                return offPeakRate ?: standardRate
            }
            if (hour in 16 until 21) return peakRate ?: standardRate
        } else {
            val offPeak = if (offPeakStartHour <= offPeakEndHour) hour in offPeakStartHour until offPeakEndHour
            else hour >= offPeakStartHour || hour < offPeakEndHour
            if (offPeak) return offPeakRate ?: standardRate
        }
        return standardRate
    }

    fun cost(energyKWh: Double, from: Instant, to: Instant): Double {
        if (!energyKWh.isFinite() || energyKWh < 0) return 0.0
        val duration = seconds(from, to)
        if (duration <= 0) return energyKWh * rate(from)
        val zone = zone
        var cursor = from
        var weightedRate = 0.0
        while (cursor < to) {
            // Absolute hour intervals, so skipped and repeated DST hours count once.
            val hourEnd = cursor.atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant()
            val next = minOf(to, maxOf(hourEnd, cursor.plusSeconds(1)))
            weightedRate += rate(cursor) * seconds(cursor, next) / duration
            cursor = next
        }
        return energyKWh * weightedRate
    }

    private fun seconds(from: Instant, to: Instant) =
        java.time.Duration.between(from, to).toNanos() / 1_000_000_000.0
}
