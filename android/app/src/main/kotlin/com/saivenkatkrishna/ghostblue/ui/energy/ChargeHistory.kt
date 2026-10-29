package com.saivenkatkrishna.ghostblue.ui.energy

import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.vehicle.chargingCost
import com.saivenkatkrishna.ghostblue.data.vehicle.energyDelivered
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.max

/** The Energy tab's period, as iOS's `ChargeHistoryRange`. */
enum class ChargeHistoryRange(val dayCount: Int, val title: String) {
    WEEK(7, "Week"),
    MONTH(30, "Month"),
    QUARTER(90, "3 months");

    /** Three months of daily bars are unreadably thin, so that range is summed per week. */
    val bucketsByWeek: Boolean get() = this == QUARTER

    fun start(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Instant = start(dayCount, now, zone)

    companion object {
        /** The first calendar day in the range: "Week" is today and the six days before it. */
        fun start(days: Int, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Instant =
            now.atZone(zone).toLocalDate().minusDays((max(1, days) - 1).toLong()).atStartOfDay(zone).toInstant()
    }
}

enum class ChargePowerFilter(val title: String) {
    ALL("All"),
    UNDER_TWO_KW("< 2 kW"),
    TWO_KW_AND_ABOVE("2 kW+");

    fun includes(session: ChargeSession): Boolean = when (this) {
        ALL -> true
        UNDER_TWO_KW -> session.maxChargeSpeedKW > 0 && session.maxChargeSpeedKW < 2
        TWO_KW_AND_ABOVE -> session.maxChargeSpeedKW >= 2
    }
}

/** One bar of the energy trend: a day, or a week for [ChargeHistoryRange.QUARTER]. */
data class ChargeEnergyBucket(val start: LocalDate, val energyKWh: Double, val sessionCount: Int)

/**
 * Everything the Energy tab computes from the session log for one car,
 * period and power filter: the port of `ChargeHistoryView`'s computed
 * properties. Energy and cost come from the data layer.
 */
class ChargeHistorySummary(
    allSessions: List<ChargeSession>,
    private val vehicle: BBVehicle,
    val range: ChargeHistoryRange,
    val powerFilter: ChargePowerFilter,
    private val now: Instant = Instant.now(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
) {
    /** Meaningful sessions for the car, newest first. */
    val sessions: List<ChargeSession> =
        allSessions.filter { it.vin == vehicle.vin && it.isMeaningful }.sortedByDescending { it.startDate }

    private val powerFiltered = sessions.filter(powerFilter::includes)

    /** The sessions the period shows. */
    val displayed: List<ChargeSession> = range.start(now, zone).let { start -> powerFiltered.filter { it.startDate >= start } }

    private val previous: List<ChargeSession> = run {
        val end = range.start(now, zone)
        val start = end.atZone(zone).minusDays(range.dayCount.toLong()).toInstant()
        powerFiltered.filter { it.startDate >= start && it.startDate < end }
    }

    fun energy(session: ChargeSession): Double = vehicle.energyDelivered(session)

    fun cost(session: ChargeSession): Double? = vehicle.chargingCost(session)

    private fun energyInLastDays(days: Int): Double {
        val start = ChargeHistoryRange.start(days, now, zone)
        return powerFiltered.filter { it.startDate >= start }.sumOf(::energy)
    }

    val weekEnergyKWh: Double get() = energyInLastDays(7)
    val monthEnergyKWh: Double get() = energyInLastDays(30)
    val lifetimeEnergyKWh: Double get() = powerFiltered.sumOf(::energy)

    val totalEnergyKWh: Double = displayed.sumOf(::energy)
    val totalPercentAdded: Double get() = displayed.sumOf { it.percentAdded }
    val dcSessionCount: Int get() = displayed.count { it.isDCFast }

    /** Sum of the sessions that have a price; null when none has. */
    val totalCost: Double? = displayed.mapNotNull(::cost).takeIf { it.isNotEmpty() }?.sum()

    /** Percent change from the period before; null when it had no energy. */
    val energyChange: Double?
        get() {
            val previousEnergy = previous.sumOf(::energy)
            if (previousEnergy <= 0) return null
            return (totalEnergyKWh - previousEnergy) / previousEnergy * 100
        }

    val averageSessionEnergyKWh: Double get() = totalEnergyKWh / max(1, displayed.size)

    val averagePeakKW: Double?
        get() = displayed.map { it.maxChargeSpeedKW }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()

    /** Divides by every shown session, priced or not, as iOS does. */
    val averageCost: Double? get() = totalCost?.let { it / max(1, displayed.size) }

    private fun bucketStart(date: LocalDate): LocalDate =
        if (range.bucketsByWeek) date.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek)) else date

    /**
     * Every bucket from the start of the period through today, empty ones
     * included, so the selected period stays on screen when only one day
     * has a session (iOS fixes the chart's domain for the same reason).
     */
    val buckets: List<ChargeEnergyBucket> = run {
        val grouped = displayed.groupBy { bucketStart(it.startDate.atZone(zone).toLocalDate()) }
        val first = bucketStart(range.start(now, zone).atZone(zone).toLocalDate())
        val last = bucketStart(now.atZone(zone).toLocalDate())
        generateSequence(first) { if (range.bucketsByWeek) it.plusWeeks(1) else it.plusDays(1) }
            .takeWhile { !it.isAfter(last) }
            .map { day ->
                val inBucket = grouped[day].orEmpty()
                ChargeEnergyBucket(day, inBucket.sumOf(::energy), inBucket.size)
            }
            .toList()
    }

    val hasTrendEnergy: Boolean get() = buckets.any { it.energyKWh >= 0.05 }

    /** True when sessions exist for the car at all, before the period and filter. */
    val hasAnySessions: Boolean get() = sessions.isNotEmpty()
}
