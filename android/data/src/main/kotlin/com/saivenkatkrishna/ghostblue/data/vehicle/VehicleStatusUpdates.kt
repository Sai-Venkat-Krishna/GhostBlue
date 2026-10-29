package com.saivenkatkrishna.ghostblue.data.vehicle

import com.saivenkatkrishna.ghostblue.data.charging.BatteryCapacityCatalog
import com.saivenkatkrishna.ghostblue.data.charging.ChargeStopPolicy
import com.saivenkatkrishna.ghostblue.data.charging.SDGESmartChargingSchedule
import com.saivenkatkrishna.ghostblue.data.charging.SmartChargingPreference
import com.saivenkatkrishna.ghostblue.data.charging.chargingTariff
import com.saivenkatkrishna.ghostblue.data.db.ChargeSessionDao
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ChargingLocationType
import com.saivenkatkrishna.ghostblue.data.model.secondsBetween
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus.EVStatus
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus.PlugType
import java.time.Instant
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The VIN of every car a status was applied to, on every refresh path:
 * the counterpart of iOS's `.bbVehicleStatusUpdated` notification.
 */
object VehicleStatusEvents {
    private val updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val statusUpdated: SharedFlow<String> = updates.asSharedFlow()

    internal fun post(vin: String) {
        updates.tryEmit(vin)
    }
}

/**
 * Applies a fetched status: the port of iOS's `BBVehicle.updateStatus(with:)`.
 * An older sample than the one shown is ignored. A first "not charging"
 * after charging is held as a pending stop (shown as charging at 0 kW) until
 * [ChargeStopPolicy] confirms it.
 *
 * With [db], the sample is also folded into the charge-session log, alert
 * rules are checked against stored reminders and automations, and this
 * vehicle's row is saved; pass a copy freshly read from [db] so the save
 * doesn't overwrite newer changes. Without it only this object changes.
 *
 * Returns the status to show.
 */
suspend fun BBVehicle.updateStatus(status: VehicleStatus, db: GhostBlueDatabase? = null): VehicleStatus {
    val incomingSync = status.syncDate
    val currentSync = syncDate
    if (incomingSync != null && currentSync != null && incomingSync < currentSync) {
        return statusForDisplay(status)
    }
    val wakeTicket = StatusWaitingManager.shared.ticket()
    var accepted = status
    val previousOdometer = odometer
    val commandedStop = ChargeStopPolicy.isCommandedStop(chargeStopRequestedAt, status.lastUpdated)
    chargeStopRequestedAt?.let { requestedAt ->
        if (secondsBetween(requestedAt, status.lastUpdated) > ChargeStopPolicy.COMMAND_WINDOW) chargeStopRequestedAt = null
    }
    val incomingEV = status.evStatus
    if (incomingEV != null) {
        val sampleDate = status.syncDate ?: status.lastUpdated
        if (!incomingEV.charging && evStatus?.charging == true) {
            if (pendingChargingStopDate != null && pendingChargingStopObservedAt == null) {
                // Candidate recorded before observation times existed.
                pendingChargingStopObservedAt = status.lastUpdated
            }
            if (ChargeStopPolicy.isConfirmed(
                    incomingEV,
                    sampleDate = sampleDate,
                    fetchedAt = status.lastUpdated,
                    candidateDate = pendingChargingStopDate,
                    candidateObservedAt = pendingChargingStopObservedAt,
                    commandedStop = commandedStop,
                )
            ) {
                pendingChargingStopDate = null
                pendingChargingStopObservedAt = null
                chargeStopRequestedAt = null
            } else {
                if (pendingChargingStopDate == null) {
                    pendingChargingStopDate = sampleDate
                    pendingChargingStopObservedAt = status.lastUpdated
                }
                // Keep the last confirmed state, without inventing power or an ETA.
                accepted = accepted.copy(
                    evStatus = EVStatus(
                        charging = true,
                        chargeSpeed = 0.0,
                        evRange = incomingEV.evRange,
                        plugType = if (incomingEV.pluggedIn) incomingEV.plugType
                        else evStatus?.plugType ?: PlugType.UNPLUGGED,
                        chargeTimeSeconds = 0,
                        targetSocAC = incomingEV.targetSocAC ?: evStatus?.targetSocAC,
                        targetSocDC = incomingEV.targetSocDC ?: evStatus?.targetSocDC,
                    ),
                )
            }
        } else {
            pendingChargingStopDate = null
            pendingChargingStopObservedAt = null
        }
    } else {
        accepted = accepted.copy(evStatus = evStatus)
    }
    // Ends command-verification sleeps that began before this status arrived.
    StatusWaitingManager.shared.wakeUp(id, issuedAt = wakeTicket)

    lastUpdated = status.lastUpdated
    syncDate = status.syncDate
    // PHEVs report both ranges; keep values the status leaves out.
    status.gasRange?.let { gasRange = it }
    accepted.evStatus?.let { evStatus = it }
    if (fuelType == FuelType.ELECTRIC && status.evStatus != null && status.gasRange == null) gasRange = null
    location = status.location
    lockStatus = status.lockStatus
    climateStatus = status.climateStatus
    status.odometer?.let { odometer = it }
    battery12V = status.battery12V
    doorOpen = status.doorOpen
    trunkOpen = status.trunkOpen
    hoodOpen = status.hoodOpen
    tirePressureWarning = status.tirePressureWarning

    if (db != null) recordChargingActivity(status, db.chargeSessions(), previousOdometer, commandedStop)
    evaluateSmartAlerts(accepted, db)
    db?.vehicles()?.update(this)
    VehicleStatusEvents.post(vin)
    return accepted
}

/** The stored status, for when an older sample than the one shown arrives. */
fun BBVehicle.statusForDisplay(fallback: VehicleStatus): VehicleStatus = fallback.copy(
    lastUpdated = lastUpdated ?: fallback.lastUpdated,
    syncDate = syncDate,
    evStatus = evStatus,
    gasRange = gasRange,
    location = location ?: fallback.location,
    lockStatus = lockStatus ?: fallback.lockStatus,
    climateStatus = climateStatus ?: fallback.climateStatus,
    odometer = odometer,
    battery12V = battery12V,
    doorOpen = doorOpen,
    trunkOpen = trunkOpen,
    hoodOpen = hoodOpen,
    tirePressureWarning = tirePressureWarning,
)

// MARK: - Charge sessions

/**
 * Power readings describe at most this much of the gap between samples;
 * across longer gaps the slower reading is used, so a taper or an early
 * finish isn't over-counted. Seconds.
 */
private const val MAX_TRAPEZOID_GAP = 30.0 * 60

/**
 * Folds a status into the charge-session log: opens a session on the first
 * charging sample, integrates energy on later ones and closes the session
 * when charging stops.
 *
 * [previousOdometer] is the odometer before this status; a rise between two
 * charging samples means the car was driven in between. [commandedStop]
 * means GhostBlue sent a stop shortly before this status was fetched.
 */
suspend fun BBVehicle.recordChargingActivity(
    status: VehicleStatus,
    sessions: ChargeSessionDao,
    previousOdometer: Distance? = null,
    commandedStop: Boolean = false,
) {
    val evStatus = status.evStatus ?: return
    val sampleDate = status.syncDate ?: status.lastUpdated
    val percentage = evStatus.evRange.percentage
    val session = sessions.openSession(vin)

    if (evStatus.charging) {
        if (session == null) {
            startChargeSession(sampleDate, percentage, evStatus, sessions)
            return
        }
        // Ignore replays. A charging sample older than an unconfirmed stop is
        // stale too; an equal or newer one clears the transient stop.
        if (sampleDate <= session.lastSampleDate) return
        session.pendingStopDate?.let { pendingStopDate ->
            if (sampleDate < pendingStopDate) return
            session.pendingStopDate = null
            session.pendingStopObservedAt = null
            session.pendingStopPercentage = null
        }

        // A long polling gap alone is one plug-in (an overnight charge seen
        // only at plug-in and at breakfast). Only evidence the car was used
        // in between closes the session.
        if (vehicleWasUsed(previousOdometer, status.odometer, session.endPercentage, percentage)) {
            session.endDate = session.lastSampleDate
            sessions.update(session)
            startChargeSession(sampleDate, percentage, evStatus, sessions)
            return
        }

        val gap = secondsBetween(session.lastSampleDate, sampleDate)
        val previousSpeed = session.speedSamples.lastOrNull()?.speedKW ?: evStatus.chargeSpeed
        val currentSpeed = max(0.0, evStatus.chargeSpeed)
        val intervalPower = if (gap <= MAX_TRAPEZOID_GAP) (max(0.0, previousSpeed) + currentSpeed) / 2
        else min(max(0.0, previousSpeed), currentSpeed)
        if (intervalPower > 0) session.energyAddedKWh += intervalPower * gap / 3600
        session.appendSpeedSample(sampleDate, evStatus.chargeSpeed, percentage)
        session.maxChargeSpeedKW = max(session.maxChargeSpeedKW, evStatus.chargeSpeed)
        session.isDCFast = session.isDCFast || evStatus.plugType == PlugType.DC_CHARGER
        session.endPercentage = percentage
        session.lastSampleDate = sampleDate
        sessions.update(session)
    } else if (session != null) {
        // Never let an older or replayed status close a newer session.
        if (sampleDate <= session.lastSampleDate) return

        if (ChargeStopPolicy.isConfirmed(
                evStatus,
                sampleDate = sampleDate,
                fetchedAt = status.lastUpdated,
                candidateDate = session.pendingStopDate,
                candidateObservedAt = session.pendingStopObservedAt,
                commandedStop = commandedStop,
            )
        ) {
            // A confirmed candidate dates the stop; a definitive first sample dates it itself.
            val stopDate = session.pendingStopDate ?: sampleDate
            session.endPercentage = maxOf(session.endPercentage, session.pendingStopPercentage ?: percentage, percentage)
            session.endDate = stopDate
            session.lastSampleDate = stopDate
            session.pendingStopDate = null
            session.pendingStopObservedAt = null
            session.pendingStopPercentage = null
        } else if (session.pendingStopDate != null) {
            // The same snapshot again isn't independent evidence until it has
            // stood for the confirmation delay.
            if (session.pendingStopObservedAt == null) session.pendingStopObservedAt = status.lastUpdated
        } else {
            // Stored, so the debounce holds across the app, widgets and automations.
            session.pendingStopDate = sampleDate
            session.pendingStopObservedAt = status.lastUpdated
            session.pendingStopPercentage = percentage
            session.endPercentage = max(session.endPercentage, percentage)
        }
        sessions.update(session)
    }
}

private suspend fun BBVehicle.startChargeSession(
    date: Instant,
    percentage: Double,
    evStatus: EVStatus,
    sessions: ChargeSessionDao,
) {
    val isDC = evStatus.plugType == PlugType.DC_CHARGER
    val session = ChargeSession(
        vin = vin,
        startDate = date,
        startPercentage = percentage,
        isDCFast = isDC,
        latitude = location?.latitude,
        longitude = location?.longitude,
    )
    session.locationType = inferredChargingLocationType(location?.latitude, location?.longitude, isDC)
    session.electricityRatePerKWh = chargingRate(date, session.locationType, session.isDCFast)
    session.rateWasLocationAware = true
    session.tariff = chargingTariff(session.locationType, session.isDCFast)
    session.maxChargeSpeedKW = evStatus.chargeSpeed
    session.appendSpeedSample(date, evStatus.chargeSpeed, percentage)
    sessions.insert(session)
}

/**
 * True when two samples cannot belong to one plug-in: the odometer advanced,
 * or the pack lost more charge than climate or a reporting wobble explains.
 */
fun vehicleWasUsed(
    previousOdometer: Distance?,
    currentOdometer: Distance?,
    sessionPercentage: Double,
    currentPercentage: Double,
): Boolean {
    if (currentPercentage < sessionPercentage - 2) return true
    if (previousOdometer == null || currentOdometer == null || previousOdometer.length <= 0 ||
        previousOdometer.units != currentOdometer.units
    ) {
        return false
    }
    return currentOdometer.length - previousOdometer.length >= 1
}

// MARK: - Capacity, location and price

/** The owner's capacity, else the model default. */
val BBVehicle.effectiveBatteryCapacityKWh: Double?
    get() = batteryCapacityKWh?.takeIf { it > 0 } ?: BatteryCapacityCatalog.defaultCapacityKWh(model, fuelType)

val BBVehicle.isUsingModelDefaultBatteryCapacity: Boolean
    get() = (batteryCapacityKWh ?: 0.0) <= 0 && effectiveBatteryCapacityKWh != null

fun BBVehicle.energySource(session: ChargeSession): String =
    session.energySource(effectiveBatteryCapacityKWh, capacityIsModelDefault = isUsingModelDefaultBatteryCapacity)

fun BBVehicle.inferredChargingLocationType(latitude: Double?, longitude: Double?, isDCFast: Boolean): ChargingLocationType {
    val homeLatitude = homeLatitude
    val homeLongitude = homeLongitude
    if (latitude == null || longitude == null || homeLatitude == null || homeLongitude == null) {
        return if (isDCFast) ChargingLocationType.PUBLIC_DC else ChargingLocationType.AUTOMATIC
    }
    val earthRadius = 6_371_000.0
    val latitudeDelta = (latitude - homeLatitude) * Math.PI / 180
    val longitudeDelta = (longitude - homeLongitude) * Math.PI / 180
    val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
        cos(homeLatitude * Math.PI / 180) * cos(latitude * Math.PI / 180) *
        sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
    val distance = earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    if (distance <= homePrivacyRadiusMeters) return ChargingLocationType.HOME
    return if (isDCFast) ChargingLocationType.PUBLIC_DC else ChargingLocationType.PUBLIC_AC
}

/** The home price at [at]: the smart-charging tariff, else the owner's off-peak window in [deviceZone]. */
fun BBVehicle.effectiveElectricityRate(at: Instant, deviceZone: ZoneId = ZoneId.systemDefault()): Double? {
    if (smartChargingEnabled) {
        chargingTariff(ChargingLocationType.HOME, isDCFast = false, deviceZone = deviceZone)?.let { return it.rate(at) }
    }
    val baseRate = electricityRatePerKWh
    val offPeak = offPeakElectricityRatePerKWh ?: return baseRate
    if (smartChargingEnabled) {
        return if (SDGESmartChargingSchedule.preference(at) == SmartChargingPreference.CHARGE) offPeak else baseRate
    }
    val hour = at.atZone(deviceZone).hour
    val isOffPeak = if (offPeakStartHour <= offPeakEndHour) hour >= offPeakStartHour && hour < offPeakEndHour
    else hour >= offPeakStartHour || hour < offPeakEndHour
    return if (isOffPeak) offPeak else baseRate
}

/** The default rate for a new session. DC charging never inherits a home time-of-use rate. */
fun BBVehicle.chargingRate(
    at: Instant,
    locationType: ChargingLocationType,
    isDCFast: Boolean,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): Double? {
    if (isDCFast || locationType == ChargingLocationType.PUBLIC_DC) return dcFastChargingRatePerKWh ?: electricityRatePerKWh
    if (locationType == ChargingLocationType.HOME || (locationType == ChargingLocationType.AUTOMATIC && smartChargingEnabled)) {
        return effectiveElectricityRate(at, deviceZone)
    }
    return electricityRatePerKWh
}

/** The session's own rate; older sessions are re-priced by their classification. */
fun BBVehicle.chargingRate(session: ChargeSession): Double? {
    val rate = session.electricityRatePerKWh
    if (session.rateWasLocationAware && rate != null) return rate
    return chargingRate(session.startDate, session.locationType, session.isDCFast) ?: rate
}

fun BBVehicle.energyDelivered(session: ChargeSession): Double = session.resolvedEnergyKWh(effectiveBatteryCapacityKWh)

/** The receipt, else the tariff snapshot, else energy × rate; null without a price. */
fun BBVehicle.chargingCost(session: ChargeSession): Double? {
    session.actualCost?.let { if (it >= 0) return it }
    session.tariff?.let { tariff ->
        return tariff.cost(energyDelivered(session), session.startDate, session.endDate ?: session.lastSampleDate)
    }
    val rate = chargingRate(session)
    if (rate == null || rate <= 0) return null
    return energyDelivered(session) * rate
}

val BBVehicle.isAtConfiguredHome: Boolean
    get() {
        val location = location ?: return false
        return inferredChargingLocationType(location.latitude, location.longitude, isDCFast = false) ==
            ChargingLocationType.HOME
    }

/**
 * Hyundai can report DC while a scheduled home AC session is paused. That
 * report is relaxed only for home-only control, inside the geofence, below
 * DC fast-charging power. Real DC sessions are never touched.
 */
fun BBVehicle.isSmartChargingControllableAC(status: EVStatus): Boolean = when (status.plugType) {
    PlugType.AC_CHARGER -> true
    PlugType.DC_CHARGER -> smartChargingHomeOnly && isAtConfiguredHome && status.chargeSpeed < 25
    PlugType.UNPLUGGED -> false
}
