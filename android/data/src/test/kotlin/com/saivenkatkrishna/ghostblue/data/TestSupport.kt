package com.saivenkatkrishna.ghostblue.data

import androidx.test.core.app.ApplicationProvider
import com.saivenkatkrishna.ghostblue.data.charging.SDGESmartChargingSchedule
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.UUID

/** An empty in-memory database; needs Robolectric. */
fun testDatabase(): GhostBlueDatabase = GhostBlueDatabase.inMemory(ApplicationProvider.getApplicationContext())

/** San Diego wall-clock time, as the iOS tests build dates with `SDGESmartChargingSchedule.calendar()`. */
fun sanDiego(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
    ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute), SDGESmartChargingSchedule.timeZone).toInstant()

fun epoch(seconds: Long): Instant = Instant.ofEpochSecond(seconds)

fun epoch(seconds: Double): Instant {
    val whole = Math.floor(seconds).toLong()
    return Instant.ofEpochSecond(whole, Math.round((seconds - whole) * 1_000_000_000))
}

fun Instant.plusSeconds(seconds: Double): Instant = plusNanos(Math.round(seconds * 1_000_000_000))

val climateOff = VehicleStatus.ClimateStatus(false, false, false, Temperature(70.0, Temperature.Units.FAHRENHEIT))

fun evStatus(
    charging: Boolean,
    percentage: Double,
    chargeSpeed: Double = if (charging) 7.2 else 0.0,
    rangeMiles: Double = 150.0,
    plugType: VehicleStatus.PlugType = VehicleStatus.PlugType.AC_CHARGER,
    chargeTimeSeconds: Long = 0,
    targetSocAC: Double? = null,
    targetSocDC: Double? = null,
) = VehicleStatus.EVStatus(
    charging = charging,
    chargeSpeed = chargeSpeed,
    evRange = VehicleStatus.FuelRange(Distance(rangeMiles, Distance.Units.MILES), percentage),
    plugType = plugType,
    chargeTimeSeconds = chargeTimeSeconds,
    targetSocAC = targetSocAC,
    targetSocDC = targetSocDC,
)

fun vehicleStatus(
    vin: String,
    evStatus: VehicleStatus.EVStatus?,
    syncDate: Instant?,
    lastUpdated: Instant = syncDate ?: Instant.now(),
    location: VehicleStatus.Location = VehicleStatus.Location(37.3349, -122.0090),
    odometer: Distance? = null,
    lockStatus: VehicleStatus.LockStatus = VehicleStatus.LockStatus.LOCKED,
) = VehicleStatus(
    vin = vin,
    lastUpdated = lastUpdated,
    syncDate = syncDate,
    evStatus = evStatus,
    location = location,
    lockStatus = lockStatus,
    climateStatus = climateOff,
    odometer = odometer,
)

/** A car with every alert off, as the iOS tests make them. */
fun quietVehicle(
    vin: String,
    model: String = "IONIQ 5",
    fuelType: FuelType = FuelType.ELECTRIC,
    accountId: UUID = UUID.randomUUID(),
    odometer: Double = 0.0,
    generation: Int = 3,
) = BBVehicle(
    Vehicle(
        vin = vin,
        regId = "test",
        model = model,
        accountId = accountId,
        fuelType = fuelType,
        generation = generation,
        odometer = Distance(odometer, Distance.Units.MILES),
    ),
).apply {
    notifyChargeComplete = false
    notifyChargeInterrupted = false
    notifyOpenPanels = false
    notifyLowEVBattery = false
    notifyLow12VBattery = false
    notifyTirePressure = false
}
