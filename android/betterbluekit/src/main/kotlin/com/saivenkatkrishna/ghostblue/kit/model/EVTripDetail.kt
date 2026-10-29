package com.saivenkatkrishna.ghostblue.kit.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable

/** One EV trip. Energy values are watt-hours; distance is in miles. */
@Serializable
data class EVTripDetail(
    val distance: Double,
    val odometer: Double,
    val accessoriesEnergy: Int,
    val totalEnergyUsed: Int,
    val regenEnergy: Int,
    val climateEnergy: Int,
    val drivetrainEnergy: Int,
    val batteryCareEnergy: Int,
    @Serializable(with = Iso8601InstantSerializer::class) val startDate: Instant,
    val durationSeconds: Int,
    val avgSpeed: Double,
    val maxSpeed: Double,
) {
    /** Same text as Swift's `"\(startDate.timeIntervalSince1970)-\(odometer)"`, so trip keys match. */
    val id: String
        get() {
            val seconds = startDate.epochSecond + startDate.nano / 1_000_000_000.0
            return "${swiftDescription(seconds)}-${swiftDescription(odometer)}"
        }

    /** Miles per kWh. */
    val efficiency: Double
        get() = if (totalEnergyUsed > 0) distance / (totalEnergyUsed / 1000.0) else 0.0

    val duration: Duration get() = durationSeconds.seconds

    /** "1 hr, 5 min", like Swift's abbreviated hours-and-minutes duration style. */
    val formattedDuration: String
        get() {
            val minutes = BigDecimal(durationSeconds).divide(BigDecimal(60), 0, RoundingMode.HALF_EVEN).toLong()
            val hours = minutes / 60
            val remainder = minutes % 60
            return when {
                hours == 0L -> "$remainder min"
                remainder == 0L -> "$hours hr"
                else -> "$hours hr, $remainder min"
            }
        }
}
