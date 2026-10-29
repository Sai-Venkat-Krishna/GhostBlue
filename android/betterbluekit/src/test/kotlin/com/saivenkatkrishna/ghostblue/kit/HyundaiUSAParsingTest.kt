package com.saivenkatkrishna.ghostblue.kit

import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.HyundaiUSAAPIClient
import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.commandBody
import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.commandURL
import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.parseCommandResponse
import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.parseEVTripDetailsResponse
import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.parseLoginResponse
import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.parseVehicleStatusResponse
import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.parseVehiclesResponse
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus.LockStatus
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus.PlugType
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach

/**
 * Hand-checked expectations for the Hyundai USA parsers. The same fixtures
 * were also run through Swift BetterBlueKit's `bbcli parse` and matched field
 * for field (see android/README.md).
 */
class HyundaiUSAParsingTest {
    private val client = HyundaiUSAAPIClient(configuration(), zoneId = ZoneId.of("America/Los_Angeles"))

    @BeforeEach
    fun quiet() = silenceKitLogging()

    @Test
    fun `vehicles keep complete entries and skip the rest`() {
        val vehicles = client.parseVehiclesResponse(fixture("vehicles.json"))
        assertEquals(listOf("IONIQ 5", "Tucson PHEV", "Sonata"), vehicles.map { it.model })

        val ioniq = vehicles[0]
        assertEquals("KM8KRDAF5PU000001", ioniq.vin)
        assertEquals("H00000000000000001", ioniq.regId)
        assertEquals(FuelType.ELECTRIC, ioniq.fuelType)
        assertEquals(3, ioniq.generation)
        assertEquals(Distance(8500.5, Distance.Units.MILES), ioniq.odometer)
        assertEquals(testAccountId, ioniq.accountId)

        assertEquals(FuelType.PHEV, vehicles[1].fuelType)
        assertEquals(12000.0, vehicles[1].odometer.length)
        // Unknown evStatus codes are gas; a bad generation falls back to 1.
        assertEquals(FuelType.GAS, vehicles[2].fuelType)
        assertEquals(1, vehicles[2].generation)
        assertEquals(0.0, vehicles[2].odometer.length)
    }

    @Test
    fun `charging EV status`() {
        val status = client.parseVehicleStatusResponse(fixture("status-ev.json"), vehicle(FuelType.ELECTRIC))
        assertEquals(Instant.parse("2025-07-06T14:22:10Z"), status.syncDate)
        assertEquals(LockStatus.LOCKED, status.lockStatus)
        assertEquals(Temperature(72.0, Temperature.Units.FAHRENHEIT), status.climateStatus.temperature)
        assertEquals(false, status.climateStatus.airControlOn)
        assertEquals(87, status.battery12V)
        assertEquals(true, status.doorOpen?.frontRight)
        assertEquals("FR", status.doorOpen?.openDoorsDescription)
        assertEquals("FR", status.tirePressureWarning?.warningDescription)
        assertEquals(32.7157, status.location.latitude)
        assertEquals(-117.1611, status.location.longitude)
        assertEquals(Distance(8500.0, Distance.Units.MILES), status.odometer)
        assertNull(status.gasRange)

        val ev = requireNotNull(status.evStatus)
        assertTrue(ev.charging)
        assertEquals(10.9, ev.chargeSpeed)
        assertEquals(63.0, ev.evRange.percentage)
        // Unit code 3 isn't kilometres (1), so it's miles.
        assertEquals(Distance(182.0, Distance.Units.MILES), ev.evRange.range)
        assertEquals(PlugType.AC_CHARGER, ev.plugType)
        assertEquals(95L * 60, ev.chargeTimeSeconds)
        assertEquals(90.0, ev.targetSocAC)
        assertEquals(80.0, ev.targetSocDC)
        assertEquals(90.0, ev.currentTargetSOC)
    }

    @Test
    fun `gas cars ignore the EV block`() {
        val status = client.parseVehicleStatusResponse(fixture("status-ev.json"), vehicle(FuelType.GAS))
        assertNull(status.evStatus)
        assertNull(status.gasRange)
    }

    @Test
    fun `loosely typed values are read as Swift reads them`() {
        val status = client.parseVehicleStatusResponse(fixture("status-ev-loose.json"), vehicle(FuelType.ELECTRIC))
        // Fractional seconds aren't accepted by ISO8601DateFormatter's defaults.
        assertNull(status.syncDate)
        assertEquals(LockStatus.UNLOCKED, status.lockStatus) // doorLock: 0
        assertTrue(status.climateStatus.airControlOn) // airCtrlOn: 1
        assertTrue(status.climateStatus.steeringWheelHeatingOn) // "2"
        assertEquals(Temperature(82.0, Temperature.Units.FAHRENHEIT), status.climateStatus.temperature) // "HI"
        assertEquals(79, status.battery12V)
        assertEquals(32.7157, status.location.latitude)

        val ev = requireNotNull(status.evStatus)
        assertEquals(54.5, ev.evRange.percentage)
        assertEquals(Distance(301.5, Distance.Units.KILOMETERS), ev.evRange.range)
        assertEquals(PlugType.DC_CHARGER, ev.plugType)
        assertEquals(148.2, ev.chargeSpeed)
        assertEquals(35L * 60, ev.chargeTimeSeconds)
        // Targets need a numeric plugType and level: "1" and "80" are skipped.
        assertNull(ev.targetSocAC)
        assertEquals(70.0, ev.targetSocDC)
    }

    @Test
    fun `missing pieces fall back to defaults`() {
        val minimal = client.parseVehicleStatusResponse(fixture("status-ev-minimal.json"), vehicle(FuelType.ELECTRIC))
        assertEquals(LockStatus.UNKNOWN, minimal.lockStatus)
        assertEquals(Temperature(62.0, Temperature.Units.CELSIUS), minimal.climateStatus.temperature)
        assertEquals(0.0, minimal.location.latitude)
        val ev = requireNotNull(minimal.evStatus)
        assertEquals(Distance(40.0, Distance.Units.MILES), ev.evRange.range)
        assertEquals(PlugType.UNPLUGGED, ev.plugType)
        assertNull(ev.currentTargetSOC)

        val noRange = client.parseVehicleStatusResponse(fixture("status-ev-no-range.json"), vehicle(FuelType.ELECTRIC))
        assertNull(noRange.evStatus)
        assertEquals(LockStatus.UNLOCKED, noRange.lockStatus)

        // With a single range of any fuel, that range is used, as in Swift.
        val gasOnly = client.parseVehicleStatusResponse(
            fixture("status-ev-gas-only-range.json"),
            vehicle(FuelType.ELECTRIC),
        )
        assertEquals(250.0, gasOnly.evStatus?.evRange?.range?.length)
    }

    @Test
    fun `gas range from dte`() {
        val status = client.parseVehicleStatusResponse(fixture("status-gas-dte.json"), vehicle(FuelType.GAS))
        assertEquals(Instant.parse("2025-07-06T16:00:00Z"), status.syncDate)
        assertEquals(48.0, status.gasRange?.percentage)
        assertEquals(Distance(310.0, Distance.Units.MILES), status.gasRange?.range)
        assertEquals(true, status.hoodOpen)
        assertNull(status.trunkOpen)

        val asEV = client.parseVehicleStatusResponse(fixture("status-gas-dte.json"), vehicle(FuelType.ELECTRIC))
        assertNull(asEV.gasRange)
    }

    @Test
    fun `plug-in hybrids have both ranges`() {
        val status = client.parseVehicleStatusResponse(fixture("status-phev.json"), vehicle(FuelType.PHEV))
        assertEquals(Distance(31.0, Distance.Units.MILES), status.evStatus?.evRange?.range)
        assertEquals(80.0, status.evStatus?.evRange?.percentage)
        // rangeByFuel's gas range wins over dte.
        assertEquals(Distance(380.0, Distance.Units.MILES), status.gasRange?.range)
        assertEquals(55.0, status.gasRange?.percentage)
    }

    @Test
    fun `a response without vehicleStatus is an error`() {
        val error = assertFailsWith<APIError> {
            client.parseVehicleStatusResponse(fixture("status-invalid.json"), vehicle(FuelType.ELECTRIC))
        }
        assertEquals("Invalid status response", error.message)
        assertEquals(APIError.ErrorType.GENERAL, error.errorType)
    }

    @Test
    fun `trips are read in the phone's time zone`() {
        val trips = client.parseEVTripDetailsResponse(fixture("trips.json"))
        // Fractional distances, dates without fractional seconds and missing
        // speeds are skipped, as in Swift.
        assertEquals(2, trips.size)

        val first = trips[0]
        assertEquals(28.0, first.distance)
        assertEquals(12345.6, first.odometer)
        assertEquals(8950, first.totalEnergyUsed)
        assertEquals(1140, first.regenEnergy)
        assertEquals(Instant.parse("2025-07-05T15:12:33Z"), first.startDate)
        assertEquals(2700, first.durationSeconds)
        assertEquals(37.4, first.avgSpeed)
        assertEquals(55.0, first.maxSpeed)
        assertEquals("45 min", first.formattedDuration)
        assertEquals(28.0 / 8.95, first.efficiency, 1e-9)
        assertEquals("1751728353.0-12345.6", first.id)

        assertEquals(Instant.parse("2025-07-05T00:45:02.123Z"), trips[1].startDate)
        assertEquals("1751676302.123-12500.0", trips[1].id)
        assertEquals("2 hr, 45 min", trips[1].formattedDuration)
    }

    @Test
    fun `login reads the token and its lifetime`() {
        val before = Instant.now()
        val token = client.parseLoginResponse(fixture("login.json"))
        assertEquals("test-access-token", token.accessToken)
        assertEquals("test-refresh-token", token.refreshToken)
        assertTrue(token.expiresAt >= before.plusSeconds(1799) && token.expiresAt <= Instant.now().plusSeconds(1799))
        assertTrue(token.isValid)

        // expires_in must be a string, as Hyundai sends it.
        assertFailsWith<APIError> {
            client.parseLoginResponse("""{"access_token":"a","refresh_token":"r","expires_in":1799}""".toByteArray())
        }
    }

    @Test
    fun `an invalid PIN reply becomes an invalid PIN error`() {
        val error = assertFailsWith<APIError> {
            client.parseCommandResponse(
                """{"isBlueLinkServicePinValid":"invalid","remainingAttemptCount":"2"}""".toByteArray(),
            )
        }
        assertEquals(APIError.ErrorType.INVALID_PIN, error.errorType)
        assertEquals("Invalid PIN, 2 attempts remaining", error.message)

        client.parseCommandResponse("""{"isBlueLinkServicePinValid":"valid"}""".toByteArray())
        client.parseCommandResponse("not json".toByteArray())
    }

    @Test
    fun `command endpoints`() {
        val ev = vehicle(FuelType.ELECTRIC)
        val gas = vehicle(FuelType.GAS)
        val base = "https://api.telematics.hyundaiusa.com"
        assertEquals("$base/ac/v2/rcs/rdo/off", client.commandURL(VehicleCommand.Lock, ev))
        assertEquals("$base/ac/v2/rcs/rdo/on", client.commandURL(VehicleCommand.Unlock, ev))
        assertEquals("$base/ac/v2/evc/fatc/start", client.commandURL(VehicleCommand.StartClimate(ClimateOptions()), ev))
        assertEquals("$base/ac/v2/rcs/rsc/start", client.commandURL(VehicleCommand.StartClimate(ClimateOptions()), gas))
        assertEquals("$base/ac/v2/evc/fatc/stop", client.commandURL(VehicleCommand.StopClimate, ev))
        assertEquals("$base/ac/v2/rcs/rsc/stop", client.commandURL(VehicleCommand.StopClimate, gas))
        assertEquals("$base/ac/v2/evc/charge/start", client.commandURL(VehicleCommand.StartCharge, ev))
        assertEquals("$base/ac/v2/evc/charge/stop", client.commandURL(VehicleCommand.StopCharge, ev))
        assertEquals("$base/ac/v2/evc/charge/targetsoc/set", client.commandURL(VehicleCommand.SetTargetSOC(80, 90), ev))
    }

    @Test
    fun `climate bodies by fuel type and generation`() {
        val options = ClimateOptions(
            frontLeftSeat = 3,
            frontRightSeat = 2,
            frontRightVentilation = true,
            steeringWheel = 1,
            rearDefrost = true,
            duration = 15,
        )
        val seats = """{"drvSeatHeatState":8,"astSeatHeatState":4,"rlSeatHeatState":0,"rrSeatHeatState":0}"""
        assertJson(
            """{"airCtrl":1,"airTemp":{"value":"72","unit":1},"defrost":false,"heating1":4,
               "igniOnDuration":15,"seatHeaterVentInfo":$seats}""",
            client.commandBody(VehicleCommand.StartClimate(options), vehicle(FuelType.ELECTRIC, generation = 3)),
        )
        assertJson(
            """{"airCtrl":1,"airTemp":{"value":"72","unit":1},"defrost":false,"heating1":4}""",
            client.commandBody(VehicleCommand.StartClimate(options), vehicle(FuelType.ELECTRIC, generation = 2)),
        )
        assertJson(
            """{"Ims":0,"airCtrl":1,"airTemp":{"unit":1,"value":72},"defrost":false,"heating1":4,
               "igniOnDuration":15,"seatHeaterVentInfo":$seats,"username":"someone@example.com",
               "vin":"KM8KRDAF5PU000001"}""",
            client.commandBody(VehicleCommand.StartClimate(options), vehicle(FuelType.GAS)),
        )
    }

    @Test
    fun `charge bodies`() {
        val ev = vehicle(FuelType.ELECTRIC)
        assertJson("""{"chargeRatio":100}""", client.commandBody(VehicleCommand.StartCharge, ev))
        assertJson(
            """{"targetSOClist":[{"targetSOClevel":90,"plugType":1},{"targetSOClevel":80,"plugType":0}]}""",
            client.commandBody(VehicleCommand.SetTargetSOC(acLevel = 90, dcLevel = 80), ev),
        )
        assertJson("{}", client.commandBody(VehicleCommand.Lock, ev))
        assertJson("{}", client.commandBody(VehicleCommand.StopCharge, ev))
    }

    private fun assertJson(expected: String, actual: JsonObject) {
        assertEquals(Json.parseToJsonElement(expected), actual)
    }
}
