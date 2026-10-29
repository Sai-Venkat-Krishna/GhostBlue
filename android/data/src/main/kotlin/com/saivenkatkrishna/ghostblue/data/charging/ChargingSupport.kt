package com.saivenkatkrishna.ghostblue.data.charging

import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargingLocationType
import com.saivenkatkrishna.ghostblue.data.model.secondsBetween
import com.saivenkatkrishna.ghostblue.data.vehicle.isAtConfiguredHome
import com.saivenkatkrishna.ghostblue.data.vehicle.isSmartChargingControllableAC
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** How often widgets and background work refresh a car. All values in seconds. */
object VehicleRefreshPolicy {
    /** At most every 10 minutes while plugged in, never more than once a minute. */
    fun interval(requested: Double, pluggedIn: Boolean): Double = max(60.0, if (pluggedIn) min(requested, 600.0) else requested)

    fun shouldRefresh(lastFetch: Instant?, now: Instant = Instant.now(), interval: Double): Boolean {
        if (lastFetch == null) return true
        return secondsBetween(lastFetch, now) >= interval
    }

    /**
     * How old data may be and still skip a fetch when a timeline reloads
     * every [reloadInterval]. Shorter than the interval: the fetch is stamped
     * after the network call, so an on-time reload sees data slightly younger
     * than the interval and would otherwise skip.
     */
    fun freshnessWindow(reloadInterval: Double): Double = min(reloadInterval * 0.75, 30.0 * 60)

    /** Runs [action] every [interval] seconds until cancelled; cancellation ends the loop. */
    suspend fun run(interval: () -> Double, action: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            delay((max(0.01, interval()) * 1000).toLong())
            action()
        }
    }
}

enum class ChargingDisplayState(val rawValue: String, val title: String) {
    CHARGING("charging", "Charging"),
    CONFIRMING_STOP("confirmingStop", "Waiting for confirmation"),
    SCHEDULED_PAUSE("scheduledPause", "Scheduled pause"),
    COMPLETED("completed", "Completed"),
    PLUGGED_IN("pluggedIn", "Plugged in · not charging"),
    UNPLUGGED("unplugged", "Unplugged"),
    OUTDATED("outdated", "Status outdated"),
    UNKNOWN("unknown", "Charging status unavailable"),
}

enum class SmartChargingOverride(val rawValue: String) {
    CHARGE_NOW("chargeNow"),
    SKIP_WINDOW("skipWindow");

    val title: String get() = if (this == CHARGE_NOW) "Charge now" else "Skip this window"
    val preference: SmartChargingPreference
        get() = if (this == CHARGE_NOW) SmartChargingPreference.CHARGE else SmartChargingPreference.PAUSE

    companion object {
        fun fromRawValue(raw: String): SmartChargingOverride? = entries.firstOrNull { it.rawValue == raw }
    }
}

val BBVehicle.vehicleSampleDate: Instant? get() = syncDate ?: lastUpdated

/** The owner's "charge now" or "skip" until the next schedule boundary; null once it has expired. */
fun BBVehicle.activeChargingOverride(at: Instant = Instant.now()): SmartChargingOverride? {
    val raw = smartChargingOverrideRaw ?: return null
    val expiry = smartChargingOverrideExpiresAt ?: return null
    if (at >= expiry) return null
    return SmartChargingOverride.fromRawValue(raw)
}

fun BBVehicle.chargingPreference(at: Instant = Instant.now()): SmartChargingPreference =
    activeChargingOverride(at)?.preference ?: SDGESmartChargingSchedule.preference(at)

fun BBVehicle.setChargingOverride(override: SmartChargingOverride?, at: Instant = Instant.now()) {
    smartChargingOverrideRaw = override?.rawValue
    smartChargingOverrideExpiresAt =
        if (override == null) null else SDGESmartChargingSchedule.transitions(at, count = 1).firstOrNull()?.date
}

fun BBVehicle.chargingDisplayState(at: Instant = Instant.now()): ChargingDisplayState {
    val evStatus = evStatus ?: return ChargingDisplayState.UNKNOWN
    val sampleDate = vehicleSampleDate
    if (sampleDate != null &&
        secondsBetween(sampleDate, at) > (if (evStatus.pluggedIn || evStatus.charging) 600.0 else 1800.0)
    ) {
        return ChargingDisplayState.OUTDATED
    }
    if (pendingChargingStopDate != null) return ChargingDisplayState.CONFIRMING_STOP
    if (evStatus.charging) return ChargingDisplayState.CHARGING
    if (!evStatus.pluggedIn) return ChargingDisplayState.UNPLUGGED
    val target = evStatus.currentTargetSOC
    if (target != null && evStatus.evRange.percentage >= target - 1) return ChargingDisplayState.COMPLETED
    if (smartChargingEnabled && (!smartChargingHomeOnly || isAtConfiguredHome) &&
        isSmartChargingControllableAC(evStatus) && chargingPreference(at) == SmartChargingPreference.PAUSE
    ) {
        return ChargingDisplayState.SCHEDULED_PAUSE
    }
    return ChargingDisplayState.PLUGGED_IN
}

/** A warning when no schedule check has been confirmed since the last boundary. */
fun BBVehicle.smartChargingWarning(at: Instant = Instant.now()): String? {
    if (!smartChargingEnabled || evStatus?.pluggedIn != true || !(!smartChargingHomeOnly || isAtConfiguredHome)) {
        return null
    }
    val lastConfirmed = smartChargingLastConfirmedAt
        ?: return "No successful schedule check recorded. Run a check and verify your Shortcuts automations."
    val boundary = SDGESmartChargingSchedule.mostRecentTransition(at) ?: return null
    if (secondsBetween(boundary, at) <= 300 || lastConfirmed >= boundary) return null
    return "No confirmed check since ${SDGESmartChargingSchedule.formattedTime(boundary)}. " +
        "A scheduled run may have been missed."
}

/** A home tariff snapshot for a new session; null for DC and public charging. */
fun BBVehicle.chargingTariff(
    locationType: ChargingLocationType,
    isDCFast: Boolean,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): ChargingTariff? {
    if (isDCFast || locationType == ChargingLocationType.PUBLIC_DC) return null
    if (!(locationType == ChargingLocationType.HOME ||
            (locationType == ChargingLocationType.AUTOMATIC && smartChargingEnabled))
    ) {
        return null
    }
    val standard = electricityRatePerKWh ?: return null
    if (!standard.isFinite() || standard < 0) return null
    return ChargingTariff(
        standardRate = standard,
        offPeakRate = offPeakElectricityRatePerKWh,
        peakRate = peakElectricityRatePerKWh,
        usesSDGE = smartChargingEnabled,
        offPeakStartHour = offPeakStartHour,
        offPeakEndHour = offPeakEndHour,
        timeZoneIdentifier = if (smartChargingEnabled) SDGESmartChargingSchedule.timeZone.id else deviceZone.id,
    )
}
