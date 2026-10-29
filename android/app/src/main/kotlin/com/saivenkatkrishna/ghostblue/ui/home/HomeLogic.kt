package com.saivenkatkrishna.ghostblue.ui.home

import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.vehicle.chargingRate
import com.saivenkatkrishna.ghostblue.data.vehicle.effectiveBatteryCapacityKWh
import com.saivenkatkrishna.ghostblue.data.vehicle.inferredChargingLocationType
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.ui.components.Format
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

enum class Severity { GOOD, INFO, WARNING, CRITICAL }

/** What the hero's pill says about the car, first match wins as on iOS. */
enum class VehicleState(val title: String) {
    WARNING("Check vehicle"),
    CHARGING("Charging"),
    CLIMATE("Climate on"),
    LOCKED("Locked"),
    IDLE("Vehicle"),
}

val BBVehicle.hasOpenPanel: Boolean get() = doorOpen?.anyOpen == true || hoodOpen == true || trunkOpen == true

val BBVehicle.state: VehicleState
    get() = when {
        hasOpenPanel || tirePressureWarning?.hasWarning == true -> VehicleState.WARNING
        evStatus?.charging == true -> VehicleState.CHARGING
        climateStatus?.airControlOn == true -> VehicleState.CLIMATE
        lockStatus == VehicleStatus.LockStatus.LOCKED -> VehicleState.LOCKED
        else -> VehicleState.IDLE
    }

val FuelType.title: String
    get() = when (this) {
        FuelType.ELECTRIC -> "Electric"
        FuelType.PHEV -> "Plug-in hybrid"
        FuelType.GAS -> "Gas"
    }

data class BriefingItem(val title: String, val detail: String, val severity: Severity)

/** The smart briefing, in iOS's order; "Everything looks good" when nothing applies. */
fun briefingItems(
    vehicle: BBVehicle,
    distanceUnit: Distance.Units = Distance.Units.MILES,
    now: Instant = Instant.now(),
): List<BriefingItem> {
    val items = mutableListOf<BriefingItem>()
    if (vehicle.hasOpenPanel) {
        val parts = listOfNotNull(
            vehicle.doorOpen?.takeIf { it.anyOpen }?.let { "Doors: ${it.openDoorsDescription}" },
            "Hood".takeIf { vehicle.hoodOpen == true },
            "Trunk".takeIf { vehicle.trunkOpen == true },
        )
        items += BriefingItem("Vehicle is open", parts.joinToString(" · "), Severity.WARNING)
    }
    vehicle.tirePressureWarning?.takeIf { it.hasWarning }?.let {
        items += BriefingItem("Check tire pressure", it.warningDescription, Severity.WARNING)
    }
    vehicle.battery12V?.takeIf { it < 40 }?.let {
        items += BriefingItem("12V battery is low", "$it%", if (it < 20) Severity.CRITICAL else Severity.WARNING)
    }
    if (vehicle.lockStatus == VehicleStatus.LockStatus.UNLOCKED) {
        items += BriefingItem("Vehicle is unlocked", "Lock it from the controls below.", Severity.INFO)
    }
    val ev = vehicle.evStatus
    if (ev != null) {
        val percentage = ev.evRange.percentage
        if (ev.charging) {
            val target = chargeTarget(ev)
            val finish = chargeFinishDate(vehicle, now)?.let { " · done around ${Format.finishTime(it, now)}" } ?: ""
            items += BriefingItem("Charging to $target%", "${percentage.toInt()}% now$finish", Severity.GOOD)
        } else if (percentage <= 20) {
            items += BriefingItem(
                "Battery is low",
                "${percentage.toInt()}% · ${Format.distance(ev.evRange.range, distanceUnit)} left",
                if (percentage <= 10) Severity.CRITICAL else Severity.WARNING,
            )
        }
    }
    vehicle.lastUpdated?.let { updated ->
        if (Duration.between(updated, now).toMinutes() >= 15) {
            items += BriefingItem("Status is ${Format.relative(updated, now).removeSuffix(" ago")} old", "Pull down to ask the car for a fresh reading.", Severity.INFO)
        }
    }
    if (items.isEmpty()) items += BriefingItem("Everything looks good", "Locked, closed and ready.", Severity.GOOD)
    return items
}

/** The charge limit, kept between the current level and 100. */
fun chargeTarget(ev: VehicleStatus.EVStatus): Int {
    val current = ev.evRange.percentage
    return (ev.currentTargetSOC ?: 100.0).coerceIn(min(current, 100.0), 100.0).toInt()
}

/** When the charge should finish: the reading plus the car's estimate, if over a minute and still ahead. */
fun chargeFinishDate(vehicle: BBVehicle, now: Instant = Instant.now()): Instant? {
    val ev = vehicle.evStatus ?: return null
    val updated = vehicle.lastUpdated ?: return null
    if (ev.chargeTimeSeconds <= 60) return null
    val finish = updated.plusSeconds(ev.chargeTimeSeconds)
    return finish.takeIf { it > now }
}

/** Home's charging card: what's left of the charge and roughly what it will cost. */
data class ChargingDetails(
    val target: Int,
    val remaining: String,
    val speed: String,
    val energyLeftKWh: Double?,
    val estimatedCost: Double?,
    val warnings: List<String>,
)

fun chargingDetails(vehicle: BBVehicle, now: Instant = Instant.now()): ChargingDetails? {
    val ev = vehicle.evStatus ?: return null
    val target = chargeTarget(ev)
    val capacity = vehicle.effectiveBatteryCapacityKWh
    val energyLeft = capacity?.let { max(0.0, it * (target - ev.evRange.percentage) / 100) }
    val isDC = ev.plugType == VehicleStatus.PlugType.DC_CHARGER
    val location = vehicle.location
    val locationType = vehicle.inferredChargingLocationType(location?.latitude, location?.longitude, isDC)
    val rate = vehicle.chargingRate(now, locationType, isDC)
    val warnings = mutableListOf<String>()
    if (ev.charging) {
        if (ev.chargeSpeed <= 0) warnings += "Charging may be paused. The car reports no power flowing."
        else if (!isDC && ev.chargeSpeed < 2) warnings += "Charging is slower than expected for an AC charger."
    }
    vehicle.lastUpdated?.let { if (Duration.between(it, now).seconds > 600) warnings += "This reading is more than 10 minutes old." }
    return ChargingDetails(
        target = target,
        remaining = if (ev.chargeTimeSeconds > 0) Format.duration(ev.chargeTimeSeconds.toDouble()) else "Calculating",
        speed = if (ev.chargeSpeed > 0) "${Format.oneDecimal(ev.chargeSpeed)} kW" else "--",
        energyLeftKWh = energyLeft,
        estimatedCost = if (energyLeft != null && rate != null && rate > 0) energyLeft * rate else null,
        warnings = warnings,
    )
}

/** "Both open", "Hood open", "Trunk open", "Closed". */
fun hoodTrunkText(vehicle: BBVehicle): String = when {
    vehicle.hoodOpen == true && vehicle.trunkOpen == true -> "Both open"
    vehicle.hoodOpen == true -> "Hood open"
    vehicle.trunkOpen == true -> "Trunk open"
    vehicle.hoodOpen == null && vehicle.trunkOpen == null -> "Unknown"
    else -> "Closed"
}
