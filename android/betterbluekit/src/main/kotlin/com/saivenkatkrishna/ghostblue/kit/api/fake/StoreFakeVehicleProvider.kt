package com.saivenkatkrishna.ghostblue.kit.api.fake

import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus.EVStatus
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus.PlugType
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor

/** Failures the test car can simulate, set per vehicle. */
data class FakeDebugConfiguration(
    val shouldFailCredentialValidation: Boolean = false,
    val shouldFailLogin: Boolean = false,
    val shouldFailVehicleFetch: Boolean = false,
    val shouldFailStatusFetch: Boolean = false,
    val shouldFailPinValidation: Boolean = false,
    val shouldFailLock: Boolean = false,
    val shouldFailUnlock: Boolean = false,
    val shouldFailStartClimate: Boolean = false,
    val shouldFailStopClimate: Boolean = false,
    val shouldFailStartCharge: Boolean = false,
    val shouldFailStopCharge: Boolean = false,
    val customCredentialErrorMessage: String = "Invalid credentials",
    val customPinErrorMessage: String = "Invalid PIN",
) {
    fun shouldFailCommand(command: VehicleCommand): Boolean = when (command) {
        VehicleCommand.Lock -> shouldFailLock
        VehicleCommand.Unlock -> shouldFailUnlock
        is VehicleCommand.StartClimate -> shouldFailStartClimate
        VehicleCommand.StopClimate -> shouldFailStopClimate
        VehicleCommand.StartCharge -> shouldFailStartCharge
        VehicleCommand.StopCharge -> shouldFailStopCharge
        is VehicleCommand.SetTargetSOC -> false
    }
}

/** A test car's server-side state. */
data class FakeVehicleRecord(
    val vehicle: Vehicle,
    val evStatus: EVStatus? = null,
    val gasRange: VehicleStatus.FuelRange? = null,
    val location: VehicleStatus.Location? = null,
    val lockStatus: VehicleStatus.LockStatus? = null,
    val climateStatus: VehicleStatus.ClimateStatus? = null,
    val battery12V: Int? = null,
    val doorOpen: VehicleStatus.DoorStatus? = null,
    val trunkOpen: Boolean? = null,
    val hoodOpen: Boolean? = null,
    val tirePressureWarning: VehicleStatus.TirePressureWarning? = null,
    val syncDate: Instant? = null,
    val lastUpdated: Instant? = null,
    val debugConfiguration: FakeDebugConfiguration? = null,
    /** The car's usable capacity; the simulation assumes an IONIQ 5's 77.4 kWh when unknown. */
    val batteryCapacityKWh: Double? = null,
    /**
     * Set by the app while it confirms a charging stop, during which it shows
     * `charging = true` at 0 kW. When the app and the fake server share one
     * record (as on iOS), the server must not echo that placeholder back.
     */
    val pendingChargingStopDate: Instant? = null,
) {
    val accountId: UUID get() = vehicle.accountId
    val vin: String get() = vehicle.vin
}

/** Storage for test cars: in memory now, the app's database later. */
interface FakeVehicleStore {
    suspend fun records(accountId: UUID): List<FakeVehicleRecord>
    suspend fun record(vin: String, accountId: UUID): FakeVehicleRecord?
    suspend fun save(record: FakeVehicleRecord)
}

/**
 * The test car's "server". Port of GhostBlue's `SwiftDataFakeVehicleProvider`,
 * including its charging simulation.
 */
class StoreFakeVehicleProvider(
    private val store: FakeVehicleStore,
    private val clock: () -> Instant = Instant::now,
) : FakeVehicleProvider {

    override suspend fun fakeVehicles(username: String, accountId: UUID): List<Vehicle> =
        store.records(accountId).map { it.vehicle }

    override suspend fun vehicleStatus(vin: String, accountId: UUID): VehicleStatus {
        val record = store.record(vin, accountId)
            ?: throw APIError.logError("Fake vehicle not found: $vin", apiName = "FakeAPI")
        val now = clock()
        // The fetch counts as a fresh read from the car.
        val updated = advanceSimulatedCharge(record, now).copy(lastUpdated = now, syncDate = now)
        store.save(updated)
        return updated.toStatus(now)
    }

    override suspend fun executeCommand(command: VehicleCommand, vin: String, accountId: UUID) {
        val record = store.record(vin, accountId)
            ?: throw APIError.logError("Fake vehicle not found for command: $vin", apiName = "FakeAPI")
        val now = clock()
        BBLogger.info(BBLogCategory.FAKE_API, "FakeAPI: ${command.name} for fake vehicle '$vin'")
        val updated = when (command) {
            VehicleCommand.Lock -> record.copy(lockStatus = VehicleStatus.LockStatus.LOCKED)
            VehicleCommand.Unlock -> record.copy(lockStatus = VehicleStatus.LockStatus.UNLOCKED)
            is VehicleCommand.StartClimate -> record.copy(
                climateStatus = VehicleStatus.ClimateStatus(
                    defrostOn = command.options.defrost,
                    airControlOn = command.options.climate,
                    steeringWheelHeatingOn = command.options.steeringWheel != 0,
                    temperature = command.options.temperature,
                ),
            )
            VehicleCommand.StopClimate -> record.climateStatus?.let { current ->
                record.copy(climateStatus = VehicleStatus.ClimateStatus(false, false, false, current.temperature))
            } ?: record
            VehicleCommand.StartCharge -> record.evStatus?.let { ev ->
                val plugType = if (ev.plugType != PlugType.UNPLUGGED) ev.plugType else PlugType.AC_CHARGER
                val speed = simulatedSpeedKW(plugType)
                val target = (if (plugType == PlugType.DC_CHARGER) ev.targetSocDC else ev.targetSocAC) ?: 100.0
                val remaining = max(0.0, target - ev.evRange.percentage)
                val minutes = ceil(remaining / 100 * record.capacity / speed * 60).toLong()
                record.copy(
                    evStatus = ev.copy(
                        charging = true,
                        chargeSpeed = speed,
                        plugType = plugType,
                        chargeTimeSeconds = minutes * 60,
                    ),
                    // Simulated progress is measured from now.
                    syncDate = now,
                )
            } ?: record
            VehicleCommand.StopCharge -> record.evStatus?.let { ev ->
                record.copy(evStatus = ev.copy(charging = false, chargeSpeed = 0.0, chargeTimeSeconds = 0))
            } ?: record
            is VehicleCommand.SetTargetSOC -> record.evStatus?.let { ev ->
                record.copy(
                    evStatus = ev.copy(
                        targetSocAC = command.acLevel.toDouble(),
                        targetSocDC = command.dcLevel.toDouble(),
                    ),
                )
            } ?: record
        }
        store.save(updated.copy(lastUpdated = now))
    }

    override suspend fun shouldFailCredentialValidation(accountId: UUID) =
        configs(accountId).any { it.shouldFailCredentialValidation }

    override suspend fun shouldFailLogin(accountId: UUID) = configs(accountId).any { it.shouldFailLogin }

    override suspend fun shouldFailVehicleFetch(accountId: UUID) = configs(accountId).any { it.shouldFailVehicleFetch }

    override suspend fun shouldFailStatusFetch(vin: String, accountId: UUID) =
        config(vin, accountId)?.shouldFailStatusFetch ?: false

    override suspend fun shouldFailPinValidation(vin: String, accountId: UUID) =
        config(vin, accountId)?.shouldFailPinValidation ?: false

    override suspend fun shouldFailCommand(command: VehicleCommand, vin: String, accountId: UUID) =
        config(vin, accountId)?.shouldFailCommand(command) ?: false

    override suspend fun customCredentialErrorMessage(accountId: UUID) =
        configs(accountId).firstOrNull()?.customCredentialErrorMessage ?: "Invalid credentials"

    override suspend fun customPinErrorMessage(vin: String, accountId: UUID) =
        config(vin, accountId)?.customPinErrorMessage ?: "Invalid PIN"

    private suspend fun configs(accountId: UUID) = store.records(accountId).mapNotNull { it.debugConfiguration }

    private suspend fun config(vin: String, accountId: UUID) = store.record(vin, accountId)?.debugConfiguration

    companion object {
        fun simulatedSpeedKW(plugType: PlugType): Double = if (plugType == PlugType.DC_CHARGER) 120.0 else 10.9

        /**
         * What the server reports: while the app confirms a stop it shows
         * `charging = true` at 0 kW, but the server already reported the stop.
         */
        internal fun serverEVStatus(record: FakeVehicleRecord): EVStatus? {
            val ev = record.evStatus ?: return null
            return if (record.pendingChargingStopDate != null && ev.charging && ev.chargeSpeed == 0.0) {
                ev.copy(charging = false)
            } else {
                ev
            }
        }

        /**
         * Moves a charging car on to [now]: charge and range rise with the
         * charger's power until the plug's target, where charging stops and
         * the car stays plugged in.
         */
        fun advanceSimulatedCharge(record: FakeVehicleRecord, now: Instant): FakeVehicleRecord {
            var ev = serverEVStatus(record) ?: return record
            val last = record.syncDate ?: record.lastUpdated
            if (ev.charging && ev.pluggedIn && last != null) {
                val elapsed = min(max(0.0, Duration.between(last, now).toMillis() / 1000.0), 6.0 * 3600)
                val capacity = record.capacity
                val speed = if (ev.chargeSpeed > 0) ev.chargeSpeed else simulatedSpeedKW(ev.plugType)
                val target = ev.currentTargetSOC ?: 100.0
                val current = ev.evRange.percentage
                val gained = speed * elapsed / 3600 / capacity * 100
                // Never lower the charge of a car already at or above its target.
                val next = if (current >= target) current else min(target, current + gained)
                val rangePerPercent = if (current > 0) ev.evRange.range.length / current else 3.0
                val range = ev.evRange.copy(
                    percentage = next,
                    range = ev.evRange.range.copy(length = swiftRounded(rangePerPercent * next)),
                )
                ev = if (next >= target) {
                    ev.copy(charging = false, chargeSpeed = 0.0, evRange = range, chargeTimeSeconds = 0)
                } else {
                    val minutes = ceil((target - next) / 100 * capacity / speed * 60).toLong()
                    ev.copy(charging = true, chargeSpeed = speed, evRange = range, chargeTimeSeconds = minutes * 60)
                }
            }
            return record.copy(evStatus = ev)
        }

        internal val FakeVehicleRecord.capacity: Double get() = batteryCapacityKWh ?: 77.4

        /** Swift's `.rounded()`: halves away from zero. */
        private fun swiftRounded(value: Double): Double =
            if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)
    }
}

private fun FakeVehicleRecord.toStatus(now: Instant) = VehicleStatus(
    vin = vin,
    lastUpdated = lastUpdated ?: now,
    syncDate = syncDate ?: now,
    gasRange = gasRange,
    evStatus = evStatus,
    location = location ?: VehicleStatus.Location(0.0, 0.0),
    lockStatus = lockStatus ?: VehicleStatus.LockStatus.UNKNOWN,
    climateStatus = climateStatus ?: VehicleStatus.ClimateStatus(
        defrostOn = false,
        airControlOn = false,
        steeringWheelHeatingOn = false,
        temperature = Temperature(70.0, Temperature.Units.FAHRENHEIT),
    ),
    odometer = vehicle.odometer,
    battery12V = battery12V,
    doorOpen = doorOpen,
    trunkOpen = trunkOpen,
    hoodOpen = hoodOpen,
    tirePressureWarning = tirePressureWarning,
)
