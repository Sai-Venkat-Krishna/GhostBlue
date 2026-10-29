package com.saivenkatkrishna.ghostblue.kit.api.fake

import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Test cars kept in memory, for the CLI and tests (the app will keep them in its database). */
class InMemoryFakeVehicleStore(records: List<FakeVehicleRecord> = emptyList()) : FakeVehicleStore {
    private val mutex = Mutex()
    private val records = linkedMapOf<Pair<UUID, String>, FakeVehicleRecord>()

    init {
        records.forEach { this.records[it.accountId to it.vin] = it }
    }

    override suspend fun records(accountId: UUID): List<FakeVehicleRecord> =
        mutex.withLock { records.values.filter { it.accountId == accountId } }

    override suspend fun record(vin: String, accountId: UUID): FakeVehicleRecord? =
        mutex.withLock { records[accountId to vin] }

    override suspend fun save(record: FakeVehicleRecord) {
        mutex.withLock { records[record.accountId to record.vin] = record }
    }
}

/** The starter test cars, matching GhostBlue's `FakeVehicleListView.createDefaultFakeVehicle`. */
object FakeVehicleSeeds {
    private data class Config(
        val model: String,
        val fuelType: FuelType,
        val batteryPercentage: Double,
        val fuelPercentage: Double,
        val isLocked: Boolean,
        val climateDefrost: Boolean,
        val climateAir: Boolean,
        val temperature: Double,
        val isCharging: Boolean,
        val isPluggedIn: Boolean,
        val chargeSpeed: Double,
        val odometer: Double,
    )

    private val configs = listOf(
        Config("Hyundai IONIQ 5", FuelType.ELECTRIC, 92.0, 0.0, true, true, true, 68.0, true, true, 45.0, 8500.0),
        Config("Hyundai Sonata", FuelType.GAS, 0.0, 75.0, false, false, false, 70.0, false, false, 0.0, 32000.0),
        Config("Kia EV6", FuelType.ELECTRIC, 88.0, 0.0, false, false, true, 70.0, false, true, 0.0, 12000.0),
    )

    /** Test car number [index] (the three models repeat) for [accountId]. */
    fun record(
        index: Int,
        accountId: UUID,
        now: Instant = Instant.now(),
        random: Random = Random.Default,
    ): FakeVehicleRecord {
        val config = configs[index % configs.size]
        val vehicleId = 200 + index
        val vehicle = Vehicle(
            vin = "FAKE-${config.model}-$vehicleId",
            regId = "REG$vehicleId",
            model = config.model,
            accountId = accountId,
            fuelType = config.fuelType,
            generation = 3,
            odometer = Distance(config.odometer, Distance.Units.MILES),
        )
        val evStatus = if (config.fuelType.hasElectricCapability) {
            VehicleStatus.EVStatus(
                charging = config.isCharging,
                chargeSpeed = config.chargeSpeed,
                evRange = VehicleStatus.FuelRange(
                    Distance(config.batteryPercentage * 3.0, Distance.Units.MILES),
                    config.batteryPercentage,
                ),
                plugType = if (config.isPluggedIn) VehicleStatus.PlugType.AC_CHARGER
                else VehicleStatus.PlugType.UNPLUGGED,
                chargeTimeSeconds = 0,
            )
        } else {
            null
        }
        val gasRange = if (config.fuelType.hasElectricCapability) null else VehicleStatus.FuelRange(
            Distance(config.fuelPercentage * 4.0, Distance.Units.MILES),
            config.fuelPercentage,
        )
        return FakeVehicleRecord(
            vehicle = vehicle,
            evStatus = evStatus,
            gasRange = gasRange,
            location = VehicleStatus.Location(
                latitude = 37.7749 + random.nextDouble(-0.01, 0.01),
                longitude = -122.4194 + random.nextDouble(-0.01, 0.01),
            ),
            lockStatus = if (config.isLocked) VehicleStatus.LockStatus.LOCKED else VehicleStatus.LockStatus.UNLOCKED,
            climateStatus = VehicleStatus.ClimateStatus(
                defrostOn = config.climateDefrost,
                airControlOn = config.climateAir,
                steeringWheelHeatingOn = false,
                temperature = Temperature(config.temperature, Temperature.Units.FAHRENHEIT),
            ),
            syncDate = now,
            lastUpdated = now,
        )
    }

    /** The three starter cars for [accountId]. */
    fun defaultRecords(accountId: UUID, now: Instant = Instant.now(), random: Random = Random.Default) =
        (0 until configs.size).map { record(it, accountId, now, random) }
}
