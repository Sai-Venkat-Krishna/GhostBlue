package com.saivenkatkrishna.ghostblue.data.charging

import com.saivenkatkrishna.ghostblue.data.model.secondsBetween
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant

/**
 * Decides when a `charging = false` status ends a charge. BlueLink can blip
 * false mid-charge, so an ordinary stop waits for a second, newer snapshot.
 * Some stops need no second sample, and a cached snapshot nothing
 * contradicts for long enough is accepted, so a finished charge isn't shown
 * as charging until the next drive.
 */
object ChargeStopPolicy {
    /** Seconds. */
    const val CONFIRMATION_DELAY = 15.0 * 60

    /** Seconds after GhostBlue sends a stop command during which "not charging" is that command taking effect. */
    const val COMMAND_WINDOW = 15.0 * 60

    fun isCommandedStop(requestedAt: Instant?, fetchedAt: Instant): Boolean {
        if (requestedAt == null) return false
        val elapsed = secondsBetween(requestedAt, fetchedAt)
        return elapsed >= 0 && elapsed <= COMMAND_WINDOW
    }

    /** The cable is out, or the pack is at its target. A 0 % reading is a malformed payload, not evidence. */
    fun isDefinitive(status: VehicleStatus.EVStatus): Boolean {
        if (status.charging || status.evRange.percentage <= 0) return false
        if (!status.pluggedIn) return true
        val target = status.currentTargetSOC
        if (target != null && target > 0) return status.evRange.percentage >= target - 1
        return status.evRange.percentage >= 100
    }

    fun isConfirmed(
        status: VehicleStatus.EVStatus,
        sampleDate: Instant,
        fetchedAt: Instant,
        candidateDate: Instant?,
        candidateObservedAt: Instant?,
        commandedStop: Boolean = false,
    ): Boolean {
        if (isDefinitive(status)) return true
        if (commandedStop && !status.charging && status.evRange.percentage > 0) return true
        if (candidateDate == null) return false
        if (sampleDate > candidateDate) return true
        if (candidateObservedAt == null) return false
        return secondsBetween(candidateObservedAt, fetchedAt) >= CONFIRMATION_DELAY
    }
}

/**
 * Usable pack sizes for Hyundai, Kia and Genesis EVs and PHEVs, used when the
 * owner hasn't entered one. Where a model had several packs the common
 * long-range US pack is assumed, so it is shown as a model default.
 */
object BatteryCapacityCatalog {
    private val electric = listOf(
        listOf("ioniq5n") to 84.0,
        listOf("ioniq9") to 110.3,
        listOf("ioniq5", "ioniq6", "ev6", "gv60", "gv70") to 77.4,
        listOf("ev9") to 99.8,
        listOf("ev3", "ev4") to 81.4,
        listOf("g80") to 87.2,
        listOf("kona", "niro", "soul") to 64.8,
        listOf("ioniq") to 38.3,
    )

    fun defaultCapacityKWh(model: String, fuelType: FuelType): Double? {
        val compact = model.lowercase().replace("-", " ").replace("_", " ").replace(" ", "")
        return when (fuelType) {
            FuelType.GAS -> null
            FuelType.PHEV -> when {
                "niro" in compact -> 11.1
                listOf("tucson", "santafe", "sorento", "sportage").any { it in compact } -> 13.8
                else -> null
            }
            FuelType.ELECTRIC -> electric.firstOrNull { (keys, _) -> keys.any { it in compact } }?.second
        }
    }
}
