package com.saivenkatkrishna.ghostblue.kit

import com.saivenkatkrishna.ghostblue.kit.api.HTTPMethod
import com.saivenkatkrishna.ghostblue.kit.api.createBetterBlueKitAPIClient
import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.HyundaiUSAAPIClient
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLog
import com.saivenkatkrishna.ghostblue.kit.model.HTTPRequestType
import com.saivenkatkrishna.ghostblue.kit.model.KitJson
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.kit.model.isTestAccount
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeEach

class ModelsTest {
    @BeforeEach
    fun quiet() = silenceKitLogging()

    @Test
    fun `units convert and format`() {
        assertEquals("161 km", Distance.Units.MILES.format(100.0, Distance.Units.KILOMETERS))
        assertEquals("62 mi", Distance.Units.KILOMETERS.format(100.0, Distance.Units.MILES))
        assertEquals("22°C", Temperature.Units.FAHRENHEIT.format(72.0, Temperature.Units.CELSIUS))
        assertEquals("72°F", Temperature.Units.FAHRENHEIT.format(72.0, Temperature.Units.FAHRENHEIT))
        assertEquals(Distance.Units.KILOMETERS, Distance.Units.fromCode(1))
        assertEquals(Distance.Units.MILES, Distance.Units.fromCode(3))
        assertEquals(62.0..82.0, Temperature.Units.FAHRENHEIT.hvacRange)
    }

    @Test
    fun `API temperatures`() {
        assertEquals(Temperature(72.0, Temperature.Units.FAHRENHEIT), Temperature.fromApi(1, "72"))
        assertEquals(Temperature(82.0, Temperature.Units.CELSIUS), Temperature.fromApi(0, "HI"))
        assertEquals(Temperature(62.0, Temperature.Units.CELSIUS), Temperature.fromApi(null, null))
        assertEquals(Temperature(62.0, Temperature.Units.CELSIUS), Temperature.fromApi(null, "LO"))
    }

    @Test
    fun `climate heat and seat codes`() {
        assertEquals(0, ClimateOptions().heatValue)
        assertEquals(2, ClimateOptions(rearDefrost = true).heatValue)
        assertEquals(3, ClimateOptions(steeringWheel = 1).heatValue)
        assertEquals(4, ClimateOptions(steeringWheel = 2, rearDefrost = true).heatValue)
        val seats = ClimateOptions(frontLeftSeat = 1, rearLeftSeat = 3, rearLeftVentilation = true).seatHeaterVentInfo()
        assertEquals(6, seats["drvSeatHeatState"])
        assertEquals(5, seats["rlSeatHeatState"])
        assertEquals(0, seats["astSeatHeatState"])
        assertEquals(Temperature(72.0, Temperature.Units.FAHRENHEIT), ClimateOptions().temperature)
    }

    @Test
    fun `climate options from before ventilation keep their missing fields`() {
        // A preset saved by an app version that predates ventilation and rear defrost.
        val old = """{"climate":true,"defrost":false,"duration":10,"frontLeftSeat":2,"frontRightSeat":0,""" +
            """"rearLeftSeat":0,"rearRightSeat":0,"steeringWheel":1,"temperature":{"units":"fahrenheit","value":70.0}}"""
        val decoded = KitJson.decodeFromString(ClimateOptions.serializer(), old)
        assertNull(decoded.rearDefrost)
        assertNull(decoded.frontLeftVentilation)
        assertFalse(decoded.rearDefrostEnabled)
        assertEquals(Json.parseToJsonElement(old), Json.parseToJsonElement(KitJson.encodeToString(ClimateOptions.serializer(), decoded)))
        // A new value has them false, and writes them.
        assertEquals(false, ClimateOptions().rearDefrost)
        assertTrue("rearDefrost" in Json.parseToJsonElement(KitJson.encodeToString(ClimateOptions.serializer(), ClimateOptions())).jsonObject)
    }

    @Test
    fun `charge target follows the plug`() {
        val range = VehicleStatus.FuelRange(Distance(200.0, Distance.Units.MILES), 60.0)
        fun ev(plug: VehicleStatus.PlugType) = VehicleStatus.EVStatus(
            charging = false, chargeSpeed = 0.0, evRange = range, plugType = plug,
            targetSocAC = 90.0, targetSocDC = 80.0,
        )
        assertEquals(90.0, ev(VehicleStatus.PlugType.AC_CHARGER).currentTargetSOC)
        assertEquals(80.0, ev(VehicleStatus.PlugType.DC_CHARGER).currentTargetSOC)
        assertNull(ev(VehicleStatus.PlugType.UNPLUGGED).currentTargetSOC)
        assertFalse(ev(VehicleStatus.PlugType.UNPLUGGED).pluggedIn)
        assertEquals(VehicleStatus.PlugType.DC_CHARGER, VehicleStatus.PlugType.fromBatteryPlugin(5))
        assertEquals(VehicleStatus.LockStatus.UNKNOWN, VehicleStatus.LockStatus.UNKNOWN.toggled())
        assertEquals(VehicleStatus.LockStatus.LOCKED, VehicleStatus.LockStatus.UNLOCKED.toggled())
    }

    @Test
    fun `EV status uses Swift's stored property names`() {
        val ev = VehicleStatus.EVStatus(
            charging = true, chargeSpeed = 7.2,
            evRange = VehicleStatus.FuelRange(Distance(100.0, Distance.Units.MILES), 50.0),
            plugType = VehicleStatus.PlugType.AC_CHARGER, chargeTimeSeconds = 600,
        )
        val json = Json.parseToJsonElement(KitJson.encodeToString(ev)).jsonObject
        assertEquals("2", json["maybePlugType"].toString())
        assertEquals("600", json["maybeChargeTimeSeconds"].toString())
        assertFalse("targetSocAC" in json) // nil values are left out, as in Swift

        // Older data without the optional fields still decodes.
        val old = KitJson.decodeFromString<VehicleStatus.EVStatus>(
            """{"charging":false,"chargeSpeed":0,"evRange":{"range":{"length":1,"units":"miles"},"percentage":1}}""",
        )
        assertEquals(VehicleStatus.PlugType.UNPLUGGED, old.plugType)
        assertEquals(0L, old.chargeTimeSeconds)
    }

    @Test
    fun `tokens stop being valid five minutes early`() {
        assertTrue(AuthToken("a", "r", Instant.now().plusSeconds(600)).isValid)
        assertFalse(AuthToken("a", "r", Instant.now().plusSeconds(240)).isValid)
        assertFalse("secret-token" in AuthToken("secret-token", "r", Instant.now()).toString())
    }

    @Test
    fun `configurations never print credentials`() {
        val text = configuration(password = "p@ssw0rd!", pin = "9876").toString()
        assertFalse("p@ssw0rd!" in text)
        assertFalse("9876" in text)
    }

    @Test
    fun `error factories`() {
        assertEquals(401, APIError.invalidCredentials().code)
        assertEquals(APIError.ErrorType.INVALID_VEHICLE_SESSION, APIError.invalidVehicleSession().errorType)
        assertEquals(1005, APIError.invalidVehicleSession().code)
        assertEquals(502, APIError.serverError().code)
        assertEquals("Request In Progress", APIError.concurrentRequest().failureReason)
        val mfa = APIError.requiresMFA(xid = "x", otpKey = "k", hasEmail = true, rmTokenExpired = true)
        assertEquals(
            mapOf("xid" to "x", "otpKey" to "k", "hasEmail" to "true", "hasPhone" to "false", "rmTokenExpired" to "true"),
            mfa.userInfo,
        )
        assertEquals("Session expired - verification required", mfa.message)
    }

    @Test
    fun `brands, regions and the client factory`() {
        assertTrue(isTestAccount("TestAccount@BetterBlue.com", "betterblue"))
        assertFalse(isTestAccount("testaccount@betterblue.com", "nope"))
        assertEquals(listOf(Brand.HYUNDAI, Brand.KIA), Brand.availableBrands())
        assertEquals(Brand.entries, Brand.availableBrands("testaccount@betterblue.com", "betterblue"))
        assertEquals("https://api.telematics.hyundaiusa.com", Region.USA.apiBaseUrl(Brand.HYUNDAI))
        assertEquals("https://api.owners.kia.com", Region.USA.apiBaseUrl(Brand.KIA))
        assertEquals(Region.CANADA, Region.fromRawValue("CA"))
        assertEquals(FuelType.PHEV, FuelType.fromNumber(1))

        assertIs<HyundaiUSAAPIClient>(createBetterBlueKitAPIClient(configuration()))
        val kia = assertFailsWith<APIError> { createBetterBlueKitAPIClient(configuration(brand = Brand.KIA)) }
        assertEquals(APIError.ErrorType.REGION_NOT_SUPPORTED, kia.errorType)
        assertEquals("Kia is not yet supported in US", kia.message)
        assertFailsWith<APIError> { createBetterBlueKitAPIClient(configuration(brand = Brand.FAKE)) }
        assertEquals("POST", HTTPMethod.POST.name)
    }

    @Test
    fun `log status text`() {
        fun log(status: Int?, error: String? = null, apiError: String? = null) = HTTPLog(
            timestamp = Instant.EPOCH, accountId = testAccountId, requestType = HTTPRequestType.LOGIN,
            method = "POST", url = "https://example.com", requestHeaders = emptyMap(), requestBody = null,
            responseStatus = status, responseHeaders = emptyMap(), responseBody = null, error = error,
            apiError = apiError, duration = 1.234,
        )
        assertEquals("Pending", log(null).statusText)
        assertEquals("Error", log(null, error = "x").statusText)
        assertEquals("200", log(200).statusText)
        assertTrue(log(204).isSuccess)
        assertFalse(log(200, apiError = "API Error 1: x").isSuccess)
        assertEquals("1.23s", log(200).formattedDuration)
    }
}
