package com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa

import com.saivenkatkrishna.ghostblue.kit.api.array
import com.saivenkatkrishna.ghostblue.kit.api.asDouble
import com.saivenkatkrishna.ghostblue.kit.api.bool
import com.saivenkatkrishna.ghostblue.kit.api.extractDouble
import com.saivenkatkrishna.ghostblue.kit.api.extractInt
import com.saivenkatkrishna.ghostblue.kit.api.int
import com.saivenkatkrishna.ghostblue.kit.api.numberDouble
import com.saivenkatkrishna.ghostblue.kit.api.numberInt
import com.saivenkatkrishna.ghostblue.kit.api.obj
import com.saivenkatkrishna.ghostblue.kit.api.objects
import com.saivenkatkrishna.ghostblue.kit.api.parseJSONObject
import com.saivenkatkrishna.ghostblue.kit.api.string
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.json.JsonObject

// MARK: - Responses

internal fun HyundaiUSAAPIClient.parseLoginResponse(data: ByteArray): AuthToken {
    val json = parseJSONObject(data)
    val accessToken = json?.string("access_token")
    val refreshToken = json?.string("refresh_token")
    val expiresIn = json?.string("expires_in")?.toIntOrNull()
    if (accessToken == null || refreshToken == null || expiresIn == null) {
        throw APIError.logError("Invalid login response: ${data.decodeToString()}", apiName = apiName)
    }
    BBLogger.info(BBLogCategory.AUTH, "HyundaiUSA: Login successful")
    return AuthToken(accessToken, refreshToken, Instant.now().plusSeconds(expiresIn.toLong()))
}

fun HyundaiUSAAPIClient.parseVehiclesResponse(data: ByteArray): List<Vehicle> {
    val vehicles = parseJSONObject(data)?.objects("enrolledVehicleDetails")
        ?: throw APIError.logError("Invalid vehicles response", apiName = apiName)
    return vehicles.mapNotNull { entry ->
        val details = entry.obj("vehicleDetails") ?: return@mapNotNull null
        val vin = details.string("vin") ?: return@mapNotNull null
        val regId = details.string("regid") ?: return@mapNotNull null
        val nickname = details.string("nickName") ?: return@mapNotNull null
        val evStatus = details.string("evStatus") ?: return@mapNotNull null
        val generation = details.string("vehicleGeneration") ?: return@mapNotNull null
        Vehicle(
            vin = vin,
            regId = regId,
            model = nickname,
            accountId = accountId,
            fuelType = when (evStatus) {
                "E" -> FuelType.ELECTRIC
                "P" -> FuelType.PHEV
                else -> FuelType.GAS
            },
            generation = generation.toIntOrNull() ?: 1,
            odometer = Distance(details.numberDouble("odometer") ?: 0.0, Distance.Units.MILES),
        )
    }
}

fun HyundaiUSAAPIClient.parseVehicleStatusResponse(data: ByteArray, vehicle: Vehicle): VehicleStatus {
    val statusData = parseJSONObject(data)?.obj("vehicleStatus")
        ?: throw APIError.logError("Invalid status response", apiName = apiName)
    val airTemp = statusData.obj("airTemp") ?: JsonObject(emptyMap())
    return VehicleStatus(
        vin = vehicle.vin,
        gasRange = parseGasRange(statusData, vehicle),
        evStatus = parseEVStatus(statusData, vehicle),
        location = parseLocation(statusData),
        lockStatus = VehicleStatus.LockStatus.fromLocked(statusData.bool("doorLock")),
        climateStatus = VehicleStatus.ClimateStatus(
            defrostOn = statusData.bool("defrost") ?: false,
            airControlOn = statusData.bool("airCtrlOn") ?: false,
            steeringWheelHeatingOn = (statusData.numberInt("steerWheelHeat") ?: 0) != 0,
            temperature = Temperature.fromApi(airTemp.numberInt("unit"), airTemp.string("value")),
        ),
        odometer = vehicle.odometer,
        syncDate = statusData.string("dateTime")?.let(::parseInternetDateTime),
        battery12V = statusData.obj("battery")?.numberInt("batSoc"),
        doorOpen = parseDoorOpen(statusData),
        trunkOpen = statusData.bool("trunkOpen"),
        hoodOpen = statusData.bool("hoodOpen"),
        tirePressureWarning = parseTirePressure(statusData),
    )
}

internal fun HyundaiUSAAPIClient.parseCommandResponse(data: ByteArray) {
    val json = parseJSONObject(data) ?: return
    if (json.string("isBlueLinkServicePinValid") == "invalid") {
        val remaining = json.string("remainingAttemptCount") ?: "unknown"
        throw APIError.invalidPin("Invalid PIN, $remaining attempts remaining", apiName = apiName)
    }
}

fun HyundaiUSAAPIClient.parseEVTripDetailsResponse(data: ByteArray): List<EVTripDetail> {
    val trips = parseJSONObject(data)?.objects("tripdetails")
        ?: throw APIError("Failed to parse trip details", apiName = apiName)
    return trips.mapNotNull { trip ->
        val distance = trip.int("distance") ?: return@mapNotNull null
        val odometer = trip.obj("odometer")?.get("value").asDouble() ?: return@mapNotNull null
        val accessories = trip.int("accessories") ?: return@mapNotNull null
        val totalUsed = trip.int("totalused") ?: return@mapNotNull null
        val regen = trip.int("regen") ?: return@mapNotNull null
        val climate = trip.int("climate") ?: return@mapNotNull null
        val drivetrain = trip.int("drivetrain") ?: return@mapNotNull null
        val batteryCare = trip.int("batterycare") ?: return@mapNotNull null
        val startDateText = trip.string("startdate") ?: return@mapNotNull null
        val duration = trip.obj("duration")?.int("value") ?: return@mapNotNull null
        val avgSpeed = trip.obj("avgspeed")?.get("value").asDouble() ?: return@mapNotNull null
        val maxSpeed = trip.obj("maxspeed")?.get("value").asDouble() ?: return@mapNotNull null
        val startDate = parseTripDate(startDateText) ?: return@mapNotNull null
        EVTripDetail(
            distance = distance.toDouble(),
            odometer = odometer,
            accessoriesEnergy = accessories,
            totalEnergyUsed = totalUsed,
            regenEnergy = regen,
            climateEnergy = climate,
            drivetrainEnergy = drivetrain,
            batteryCareEnergy = batteryCare,
            startDate = startDate,
            durationSeconds = duration,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
        )
    }
}

// MARK: - Status helpers

private fun parseEVStatus(statusData: JsonObject, vehicle: Vehicle): VehicleStatus.EVStatus? {
    if (!vehicle.fuelType.hasElectricCapability) return null
    val ev = statusData.obj("evStatus") ?: return null
    val ranges = fuelRanges(statusData)
    val evRange = if (ranges.size == 1) ranges.values.first() else ranges[FuelType.ELECTRIC] ?: return null

    val chargeTimeMinutes = ev.obj("remainTime2")?.obj("atc")?.numberInt("value") ?: 0
    var targetSocAC: Double? = null
    var targetSocDC: Double? = null
    for (target in ev.obj("reservChargeInfos")?.objects("targetSOClist").orEmpty()) {
        val plugType = target.int("plugType") ?: continue
        val soc = target["targetSOClevel"].asDouble() ?: continue
        if (plugType == 1) targetSocAC = soc else if (plugType == 0) targetSocDC = soc
    }
    return VehicleStatus.EVStatus(
        charging = ev.bool("batteryCharge") ?: false,
        chargeSpeed = maxOf(ev.numberDouble("batteryStndChrgPower") ?: 0.0, ev.numberDouble("batteryFstChrgPower") ?: 0.0),
        evRange = VehicleStatus.FuelRange(evRange, ev.numberDouble("batteryStatus") ?: 0.0),
        plugType = VehicleStatus.PlugType.fromBatteryPlugin(ev.numberInt("batteryPlugin") ?: 0),
        chargeTimeSeconds = 60L * chargeTimeMinutes,
        targetSocAC = targetSocAC,
        targetSocDC = targetSocDC,
    )
}

/** Ranges by fuel from `evStatus.drvDistance`; later entries win, as in Swift's reduce. */
private fun fuelRanges(statusData: JsonObject): Map<FuelType, Distance> {
    val ev = statusData.obj("evStatus") ?: return emptyMap()
    val ranges = linkedMapOf<FuelType, Distance>()
    for (distance in ev.objects("drvDistance").orEmpty()) {
        val type = distance.numberInt("type") ?: 0
        val byFuel = distance.obj("rangeByFuel") ?: JsonObject(emptyMap())
        byFuel.obj("evModeRange")?.let { ranges[FuelType.ELECTRIC] = it.toDistance() }
        byFuel.obj("gasModeRange")?.let { ranges[FuelType.GAS] = it.toDistance() }
        val total = byFuel.obj("totalAvailableRange")
        if (total != null && ranges.isEmpty()) ranges[FuelType.fromNumber(type)] = total.toDistance()
    }
    return ranges
}

private fun JsonObject.toDistance() = Distance(
    numberDouble("value") ?: 0.0,
    Distance.Units.fromCode(numberInt("unit") ?: 2),
)

private fun parseGasRange(statusData: JsonObject, vehicle: Vehicle): VehicleStatus.FuelRange? {
    // Pure EVs have no gas range; their dte/fuelLevel fields mean something else.
    if (vehicle.fuelType == FuelType.ELECTRIC) return null
    val percentage = statusData.numberDouble("fuelLevel") ?: return null
    fuelRanges(statusData)[FuelType.GAS]?.let { return VehicleStatus.FuelRange(it, percentage) }
    val dte = statusData.obj("dte") ?: return null
    val range = dte["value"].extractDouble() ?: return null
    val units = Distance.Units.fromCode(dte["unit"].extractInt() ?: 2)
    return VehicleStatus.FuelRange(Distance(range, units), percentage)
}

private fun parseLocation(statusData: JsonObject): VehicleStatus.Location {
    val coord = statusData.obj("vehicleLocation")?.obj("coord")
    return VehicleStatus.Location(
        latitude = coord?.numberDouble("lat") ?: 0.0,
        longitude = coord?.numberDouble("lon") ?: 0.0,
    )
}

private fun parseDoorOpen(statusData: JsonObject): VehicleStatus.DoorStatus? {
    val doors = statusData.obj("doorOpen") ?: return null
    return VehicleStatus.DoorStatus(
        frontLeft = (doors.numberInt("frontLeft") ?: 0) != 0,
        frontRight = (doors.numberInt("frontRight") ?: 0) != 0,
        backLeft = (doors.numberInt("backLeft") ?: 0) != 0,
        backRight = (doors.numberInt("backRight") ?: 0) != 0,
    )
}

private fun parseTirePressure(statusData: JsonObject): VehicleStatus.TirePressureWarning? {
    val tires = statusData.obj("tirePressureLamp") ?: return null
    fun lamp(key: String) = (tires.numberInt(key) ?: 0) != 0
    return VehicleStatus.TirePressureWarning(
        frontLeft = lamp("tirePressureWarningLampFrontLeft"),
        frontRight = lamp("tirePressureWarningLampFrontRight"),
        rearLeft = lamp("tirePressureWarningLampRearLeft"),
        rearRight = lamp("tirePressureWarningLampRearRight"),
        all = lamp("tirePressureWarningLampAll"),
    )
}

// MARK: - Dates

private val internetDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

/** ISO 8601 with a zone and no fractional seconds, as `ISO8601DateFormatter()` accepts. */
internal fun parseInternetDateTime(text: String): Instant? = try {
    OffsetDateTime.parse(text, internetDateTime).toInstant()
} catch (_: DateTimeParseException) {
    null
}

private val tripDateFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("yyyy-MM-dd HH:mm:ss")
    .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
    .toFormatter(Locale.US)

/** Trip start times are the phone's local time ("2025-07-05 08:12:33.0"), kept to the millisecond. */
internal fun HyundaiUSAAPIClient.parseTripDate(text: String): Instant? = try {
    LocalDateTime.parse(text, tripDateFormatter).atZone(zoneId).toInstant().truncatedTo(ChronoUnit.MILLIS)
} catch (_: DateTimeParseException) {
    null
}
