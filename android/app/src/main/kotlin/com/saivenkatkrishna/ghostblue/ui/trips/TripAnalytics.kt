package com.saivenkatkrishna.ghostblue.ui.trips

import com.saivenkatkrishna.ghostblue.data.model.TripRecord
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.ui.components.Format
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** Kilometres per mile, as the iOS Trips screen writes it. */
const val KM_PER_MILE = 1.60934

enum class TripDateFilter(val title: String, val dayCount: Int?) {
    ALL("All", null),
    SEVEN_DAYS("7 days", 7),
    THIRTY_DAYS("30 days", 30),
    NINETY_DAYS("90 days", 90),
}

enum class TripTrendPeriod(val title: String, val pluralTitle: String) {
    DAILY("Day", "Days"),
    WEEKLY("Week", "Weeks"),
    MONTHLY("Month", "Months"),
}

enum class TripSortOption(val title: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    LONGEST("Longest distance"),
    MOST_EFFICIENT("Best efficiency"),
    MOST_ENERGY("Most energy"),
}

data class TripEfficiencyTrendPoint(val periodStart: Instant, val distanceMiles: Double, val energyKWh: Double) {
    val efficiencyMilesPerKWh: Double get() = if (energyKWh > 0) distanceMiles / energyKWh else 0.0
}

data class TripEfficiencyExtremes(val best: EVTripDetail, val worst: EVTripDetail)

/** Port of iOS's `TripAnalytics`: the Trips screen's filters, energy and efficiency. */
object TripAnalytics {
    /** Trips at most [filter]'s days old, and none from the future. */
    fun filtered(
        trips: List<EVTripDetail>,
        filter: TripDateFilter,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<EVTripDetail> {
        val dayCount = filter.dayCount ?: return trips
        val cutoff = now.atZone(zone).minusDays(dayCount.toLong()).toInstant()
        return trips.filter { it.startDate >= cutoff && it.startDate <= now }
    }

    /** The reported total, else the sum of the positive parts. */
    fun energyKWh(trip: EVTripDetail): Double {
        val reported = max(0, trip.totalEnergyUsed) / 1000.0
        if (reported != 0.0) return reported
        return listOf(trip.drivetrainEnergy, trip.climateEnergy, trip.accessoriesEnergy, trip.batteryCareEnergy)
            .sumOf { max(0, it) / 1000.0 }
    }

    fun efficiencyMilesPerKWh(trip: EVTripDetail): Double? {
        val energy = energyKWh(trip)
        if (energy <= 0 || trip.distance <= 0) return null
        return trip.distance / energy
    }

    fun trendPoints(
        trips: List<EVTripDetail>,
        period: TripTrendPeriod,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<TripEfficiencyTrendPoint> {
        val aggregates = mutableMapOf<Instant, Pair<Double, Double>>()
        for (trip in trips) {
            val energy = energyKWh(trip)
            if (energy <= 0 || trip.distance <= 0) continue
            val start = periodStart(trip.startDate, period, zone, locale)
            val (distance, total) = aggregates[start] ?: (0.0 to 0.0)
            aggregates[start] = (distance + trip.distance) to (total + energy)
        }
        return aggregates.map { (start, value) -> TripEfficiencyTrendPoint(start, value.first, value.second) }
            .sortedBy { it.periodStart }
    }

    fun extremes(trips: List<EVTripDetail>): TripEfficiencyExtremes? {
        val eligible = trips.mapNotNull { trip -> efficiencyMilesPerKWh(trip)?.let { trip to it } }
        // Like Swift's max(by:) and min(by:), ties go to the first.
        val best = eligible.maxByOrNull { it.second } ?: return null
        val worst = eligible.minByOrNull { it.second } ?: return null
        return TripEfficiencyExtremes(best.first, worst.first)
    }

    fun electricityCost(trips: List<EVTripDetail>, ratePerKWh: Double?): Double? {
        if (ratePerKWh == null || ratePerKWh <= 0) return null
        val energy = trips.sumOf(::energyKWh)
        if (energy <= 0) return null
        return energy * ratePerKWh
    }

    fun percentageChange(points: List<TripEfficiencyTrendPoint>): Double? {
        if (points.size < 2) return null
        val previous = points[points.size - 2].efficiencyMilesPerKWh
        val latest = points.last().efficiencyMilesPerKWh
        if (previous <= 0) return null
        return (latest - previous) / previous * 100
    }

    /** The start of the day, the locale's week, or the month containing [date]. */
    fun periodStart(date: Instant, period: TripTrendPeriod, zone: ZoneId, locale: Locale = Locale.getDefault()): Instant {
        val day = date.atZone(zone).truncatedTo(ChronoUnit.DAYS)
        return when (period) {
            TripTrendPeriod.DAILY -> day
            TripTrendPeriod.WEEKLY ->
                day.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek))
            TripTrendPeriod.MONTHLY -> day.withDayOfMonth(1)
        }.toLocalDate().atStartOfDay(zone).toInstant()
    }
}

data class TripTotalEnergyPoint(val index: Int, val trip: EVTripDetail, val energyKWh: Double)

data class TripEnergyPoint(val tripIndex: Int, val category: TripEnergyCategory, val energyKWh: Double)

enum class TripEnergyCategory(val title: String) {
    DRIVETRAIN("Drivetrain"),
    CLIMATE("Climate"),
    ACCESSORIES("Accessories"),
    BATTERY_CARE("Battery Care"),
}

/** Port of iOS's `TripChartData`: the newest trips, oldest first, for the energy chart. */
object TripChartData {
    const val MAXIMUM_TRIP_COUNT = 12

    /** [trips] newest first in; the newest [limit], oldest first, with their positions. */
    fun indexedTrips(trips: List<EVTripDetail>, limit: Int = MAXIMUM_TRIP_COUNT): List<Pair<Int, EVTripDetail>> =
        trips.take(max(0, limit)).reversed().mapIndexed { index, trip -> index to trip }

    fun totalPoints(trips: List<EVTripDetail>): List<TripTotalEnergyPoint> =
        indexedTrips(trips).map { (index, trip) -> TripTotalEnergyPoint(index, trip, TripAnalytics.energyKWh(trip)) }

    fun breakdownPoints(trips: List<EVTripDetail>): List<TripEnergyPoint> =
        indexedTrips(trips).flatMap { (index, trip) ->
            listOf(
                TripEnergyCategory.DRIVETRAIN to trip.drivetrainEnergy,
                TripEnergyCategory.CLIMATE to trip.climateEnergy,
                TripEnergyCategory.ACCESSORIES to trip.accessoriesEnergy,
                TripEnergyCategory.BATTERY_CARE to trip.batteryCareEnergy,
            ).mapNotNull { (category, wattHours) ->
                val kWh = max(0, wattHours) / 1000.0
                if (kWh > 0) TripEnergyPoint(index, category, kWh) else null
            }
        }

    /** At most [maximumLabelCount] evenly spaced labels, always including the last. */
    fun axisIndices(count: Int, maximumLabelCount: Int = 6): List<Int> {
        if (count <= 0) return emptyList()
        if (count <= maximumLabelCount) return (0 until count).toList()
        val stride = max(1, ceil((count - 1).toDouble() / (maximumLabelCount - 1)).toInt())
        val indices = (0 until count step stride).toMutableList()
        if (indices.last() != count - 1) indices += count - 1
        return indices
    }

    fun yAxisMaximum(values: List<Double>): Double = max(1.0, (values.maxOrNull() ?: 0.0) * 1.15)

    fun breakdownYAxisMaximum(points: List<TripEnergyPoint>): Double =
        yAxisMaximum(points.groupBy { it.tripIndex }.values.map { group -> group.sumOf { it.energyKWh } })
}

/** Port of iOS's `TripCSVExporter`: plain values, quoted only when needed, LF lines. */
object TripCSVExporter {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun csv(
        trips: List<EVTripDetail>,
        vin: String,
        records: Map<String, TripRecord>,
        ratePerKWh: Double?,
        redactPrivateLocations: Boolean = true,
    ): String {
        val header = listOf(
            "Date", "Name", "Favorite", "Distance (mi)", "Distance (km)", "Energy (kWh)", "Efficiency (mi/kWh)",
            "Efficiency (km/kWh)", "Duration (min)", "Average speed (mph)", "Maximum speed (mph)", "Start",
            "Destination", "Estimated cost",
        )
        val rows = trips.map { trip ->
            val record = records[TripRecord.key(vin, trip)]
            val energy = TripAnalytics.energyKWh(trip)
            val efficiency = TripAnalytics.efficiencyMilesPerKWh(trip) ?: 0.0
            val cost = ratePerKWh?.let { energy * it }
            listOf(
                dateFormatter.format(trip.startDate),
                record?.customName ?: "",
                if (record?.isFavorite == true) "Yes" else "No",
                decimal(trip.distance, 2),
                decimal(trip.distance * KM_PER_MILE, 2),
                decimal(energy, 3),
                decimal(efficiency, 2),
                decimal(efficiency * KM_PER_MILE, 2),
                decimal(trip.durationSeconds / 60.0, 1),
                decimal(trip.avgSpeed, 1),
                decimal(trip.maxSpeed, 1),
                location(record?.startLocationName, redactPrivateLocations),
                location(record?.endLocationName, redactPrivateLocations),
                cost?.let { decimal(it, 2) } ?: "",
            )
        }
        return (listOf(header) + rows).joinToString("\n") { row -> row.joinToString(",", transform = ::escape) } + "\n"
    }

    fun filename(vehicleName: String): String = "GhostBlue-$vehicleName.csv"

    private fun location(name: String?, redact: Boolean): String = when {
        name == null -> ""
        redact -> "Private location"
        else -> name
    }

    /** As Swift's `String(format:locale: en_US_POSIX)`. */
    private fun decimal(value: Double, digits: Int): String = Format.printf(value, digits)

    private fun escape(value: String): String {
        if (!value.contains(',') && !value.contains('"') && !value.contains('\n')) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
