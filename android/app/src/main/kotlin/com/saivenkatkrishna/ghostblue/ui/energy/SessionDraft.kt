package com.saivenkatkrishna.ghostblue.ui.energy

import com.saivenkatkrishna.ghostblue.data.charging.ChargingTariff
import com.saivenkatkrishna.ghostblue.data.charging.chargingTariff
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ChargingLocationType
import com.saivenkatkrishna.ghostblue.data.vehicle.chargingRate
import com.saivenkatkrishna.ghostblue.data.vehicle.effectiveBatteryCapacityKWh
import com.saivenkatkrishna.ghostblue.ui.components.Format
import java.time.Instant
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The charging-session editor's form, with iOS's rules for defaults,
 * energy, tariff, cost and validation (`ChargeSessionEditor`). Numbers are
 * kept as the text the owner typed.
 */
data class SessionDraft(
    val start: Instant,
    val end: Instant,
    val completed: Boolean,
    val startPercentage: Int,
    val endPercentage: Int,
    val energyText: String,
    val peakText: String,
    val isDCFast: Boolean,
    val locationType: ChargingLocationType,
    val rateText: String,
    val actualCostText: String,
    val useTariff: Boolean,
    /** The tariff saved on the session being edited, if any. */
    val savedTariff: ChargingTariff? = null,
    /** True when editing a session that is still in progress. */
    val wasActive: Boolean = false,
) {
    private val energy: Double? get() = Format.parseDecimal(energyText)
    private val peak: Double? get() = Format.parseDecimal(peakText)
    private val rate: Double? get() = Format.parseDecimal(rateText)
    private val actualCost: Double? get() = Format.parseDecimal(actualCostText)

    /** The first problem with the form, or null when it can be saved. */
    val validationMessage: String?
        get() = when {
            completed && end <= start -> "End time must be later than start time."
            energyText.isNotBlank() && (energy == null || energy!! < 0) -> "Energy must be zero or greater."
            peakText.isNotBlank() && (peak == null || peak!! < 0) -> "Peak speed must be zero or greater."
            rateText.isNotBlank() && (rate == null || rate!! <= 0) -> "Rate must be greater than zero."
            actualCostText.isNotBlank() && (actualCost == null || actualCost!! < 0) -> "Actual cost must be zero or greater."
            else -> null
        }

    /** The meter reading; else capacity × charge added; else the session's plausible sampled energy. */
    fun energyKWh(vehicle: BBVehicle, existing: ChargeSession?): Double {
        energy?.let { if (it >= 0) return it }
        val capacity = vehicle.effectiveBatteryCapacityKWh
        if (capacity != null && capacity > 0 && endPercentage > startPercentage) {
            return capacity * (endPercentage - startPercentage) / 100
        }
        val session = existing ?: return 0.0
        val added = max(0, endPercentage - startPercentage)
        return min(max(0.0, session.energyAddedKWh), (capacity ?: ChargeSession.MAX_PLAUSIBLE_CAPACITY_KWH) * (added + 1) / 100)
    }

    /** Whether the time-of-use switch applies to this kind of charging. */
    fun tariffApplies(vehicle: BBVehicle): Boolean =
        !isDCFast && (locationType == ChargingLocationType.HOME ||
            (locationType == ChargingLocationType.AUTOMATIC && vehicle.smartChargingEnabled))

    /** The switch is offered when the car has a tariff or the session has one saved. */
    fun offersTariff(vehicle: BBVehicle): Boolean =
        savedTariff != null || vehicle.chargingTariff(locationType, isDCFast) != null

    fun tariff(vehicle: BBVehicle): ChargingTariff? {
        if (!useTariff || !tariffApplies(vehicle)) return null
        return savedTariff ?: vehicle.chargingTariff(locationType, isDCFast)
    }

    /** The receipt; else the tariff across the session; else rate × energy; else nothing. */
    fun cost(vehicle: BBVehicle, existing: ChargeSession?, now: Instant = Instant.now()): Double? {
        actualCost?.let { if (it >= 0) return it }
        val energy = energyKWh(vehicle, existing)
        tariff(vehicle)?.let { tariff ->
            val to = if (completed) end else existing?.lastSampleDate ?: now
            return tariff.cost(energy, start, to)
        }
        val rate = rate ?: return null
        if (rate <= 0 || energy <= 0) return null
        return rate * energy
    }

    /** A changed start, location or charger type puts the default rate back. */
    fun withDefaultRate(vehicle: BBVehicle): SessionDraft =
        copy(rateText = vehicle.chargingRate(start, locationType, isDCFast)?.let(::rateString) ?: "")

    /** Writes the form into [session], as iOS's `applyUserEdits` call. */
    fun applyTo(session: ChargeSession, vehicle: BBVehicle) {
        session.applyUserEdits(
            startDate = start,
            endDate = if (completed) end else null,
            startPercentage = startPercentage.toDouble(),
            endPercentage = endPercentage.toDouble(),
            energyAddedKWh = session.energyAddedKWh,
            maxChargeSpeedKW = peak ?: 0.0,
            isDCFast = isDCFast,
            locationType = locationType,
            electricityRatePerKWh = rate?.takeIf { it > 0 },
            actualCost = actualCost?.takeIf { it >= 0 },
            manualEnergyKWh = energy?.takeIf { it >= 0 },
            tariff = tariff(vehicle),
        )
    }

    /** A new session's row, ready for [applyTo]. */
    fun newSession(vehicle: BBVehicle): ChargeSession =
        ChargeSession(vehicle.vin, start, startPercentage.toDouble(), isDCFast)

    companion object {
        fun rateString(rate: Double): String = String.format(Locale.US, "%.3f", rate)

        private fun costString(cost: Double): String = String.format(Locale.US, "%.2f", cost)

        /** A completed hour from 20% to 30%, priced as the car's default for its location. */
        fun forNewSession(vehicle: BBVehicle, now: Instant = Instant.now()): SessionDraft {
            val start = now.minusSeconds(3600)
            val location = if (vehicle.smartChargingEnabled) ChargingLocationType.HOME else ChargingLocationType.AUTOMATIC
            return SessionDraft(
                start = start,
                end = now,
                completed = true,
                startPercentage = 20,
                endPercentage = 30,
                energyText = "",
                peakText = "",
                isDCFast = false,
                locationType = location,
                rateText = vehicle.chargingRate(start, location, false)?.let(::rateString) ?: "",
                actualCostText = "",
                useTariff = vehicle.chargingTariff(location, false) != null,
            )
        }

        fun forSession(session: ChargeSession, vehicle: BBVehicle): SessionDraft {
            val rate = vehicle.chargingRate(session)
                ?: vehicle.chargingRate(session.startDate, session.locationType, session.isDCFast)
            return SessionDraft(
                start = session.startDate,
                end = session.endDate ?: maxOf(session.lastSampleDate, session.startDate.plusSeconds(60)),
                completed = session.endDate != null,
                startPercentage = session.startPercentage.toInt(),
                endPercentage = session.endPercentage.toInt(),
                energyText = session.manualEnergyKWh?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                peakText = session.maxChargeSpeedKW.takeIf { it > 0 }?.let(Format::upToOneDecimal) ?: "",
                isDCFast = session.isDCFast,
                locationType = session.locationType,
                rateText = rate?.let(::rateString) ?: "",
                actualCostText = session.actualCost?.let(::costString) ?: "",
                useTariff = session.tariff != null,
                savedTariff = session.tariff,
                wasActive = session.isActive,
            )
        }
    }
}
