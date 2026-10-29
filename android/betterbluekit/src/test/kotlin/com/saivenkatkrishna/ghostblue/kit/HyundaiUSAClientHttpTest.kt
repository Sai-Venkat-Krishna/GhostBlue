package com.saivenkatkrishna.ghostblue.kit

import com.saivenkatkrishna.ghostblue.kit.api.hyundaiusa.HyundaiUSAAPIClient
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLog
import com.saivenkatkrishna.ghostblue.kit.model.HTTPRequestType
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/** The client's HTTP behaviour against a local server standing in for Hyundai's. */
class HyundaiUSAClientHttpTest {
    private val server = MockWebServer()
    private val logs = mutableListOf<HTTPLog>()
    private val token = AuthToken("access-123", "refresh-456", Instant.now().plusSeconds(1800))

    @BeforeEach
    fun start() {
        silenceKitLogging()
        server.start()
    }

    @AfterEach
    fun stop() = server.close()

    private fun client(redactPII: Boolean = true) = HyundaiUSAAPIClient(
        configuration(logSink = { logs += it }, redactPII = redactPII),
        httpClient = OkHttpClient(),
        baseUrlOverride = server.url("/").toString().trimEnd('/'),
    )

    private fun respond(code: Int = 200, body: String = "{}") {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    @Test
    fun `login posts credentials with Hyundai's headers`() = runTest {
        respond(body = fixture("login.json").decodeToString())
        val token = client().login()
        assertEquals("test-access-token", token.accessToken)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v2/ac/oauth/token", request.url.encodedPath)
        assertEquals("m66129Bb-em93-SPAHYN-bZ91-am4540zp19920", request.headers["client_id"])
        assertEquals("v558o935-6nne-423i-baa8", request.headers["clientSecret"])
        assertEquals("okhttp/3.12.0", request.headers["User-Agent"])
        assertEquals("application/json", request.headers["Content-Type"])
        // OkHttp asks for gzip itself so it can decode the reply.
        assertEquals("gzip", request.headers["Accept-Encoding"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("someone@example.com", body["username"]?.jsonPrimitive?.content)
        assertEquals("hunter2", body["password"]?.jsonPrimitive?.content)
    }

    @Test
    fun `status asks the car only when not cached`() = runTest {
        val client = client()
        respond(body = fixture("status-ev.json").decodeToString())
        respond(body = fixture("status-ev.json").decodeToString())
        val status = client.fetchVehicleStatus(vehicle(FuelType.ELECTRIC), token)
        client.fetchVehicleStatus(vehicle(FuelType.ELECTRIC), token, cached = false)
        assertEquals(63.0, status.evStatus?.evRange?.percentage)

        val cached = server.takeRequest()
        assertEquals("GET", cached.method)
        assertEquals("/ac/v2/rcs/rvs/vehicleStatus", cached.url.encodedPath)
        assertEquals("false", cached.headers["refresh"])
        assertEquals("access-123", cached.headers["accessToken"])
        assertEquals("1234", cached.headers["blueLinkServicePin"])
        assertEquals("someone@example.com", cached.headers["username"])
        assertEquals("KM8KRDAF5PU000001", cached.headers["vin"])
        assertEquals("KM8KRDAF5PU000001", cached.headers["APPCLOUD-VIN"])
        assertEquals("H00000000000000001", cached.headers["registrationId"])
        assertEquals("3", cached.headers["gen"])
        assertEquals("H", cached.headers["brandIndicator"])
        assertTrue(cached.headers["payloadGenerated"]!!.matches(Regex("""\d{14}""")))
        assertEquals("true", server.takeRequest().headers["refresh"])
    }

    @Test
    fun `vehicles and trips use the enrollment and trip endpoints`() = runTest {
        val client = client()
        respond(body = fixture("vehicles.json").decodeToString())
        respond(body = fixture("trips.json").decodeToString())
        assertEquals(3, client.fetchVehicles(token).size)
        assertEquals(2, client.fetchEVTripDetails(vehicle(FuelType.ELECTRIC), token).size)

        assertEquals("/ac/v2/enrollment/details/someone@example.com", server.takeRequest().url.encodedPath)
        val trips = server.takeRequest()
        assertEquals("/ac/v2/ts/alerts/maintenance/evTripDetails", trips.url.encodedPath)
        assertEquals("someone@example.com", trips.headers["userId"])
        assertEquals("access-123", trips.headers["access_token"])
    }

    @Test
    fun `commands post their body and surface a wrong PIN`() = runTest {
        val client = client()
        respond(body = """{"isBlueLinkServicePinValid":"valid"}""")
        respond(body = """{"isBlueLinkServicePinValid":"invalid","remainingAttemptCount":"1"}""")
        client.sendCommand(vehicle(FuelType.ELECTRIC), VehicleCommand.StartCharge, token)
        val error = assertFailsWith<APIError> {
            client.sendCommand(vehicle(FuelType.ELECTRIC), VehicleCommand.Lock, token)
        }
        assertEquals(APIError.ErrorType.INVALID_PIN, error.errorType)

        val start = server.takeRequest()
        assertEquals("/ac/v2/evc/charge/start", start.url.encodedPath)
        assertEquals("""{"chargeRatio":100}""", start.body!!.utf8())
        assertEquals("{}", server.takeRequest().body!!.utf8())
    }

    @Test
    fun `HTTP failures map to the same error types as Swift`() = runTest {
        val client = client()
        respond(401, """{"errorMessage":"expired"}""")
        respond(502, "bad gateway")
        respond(404, "")
        respond(500, "")

        val expired = assertFailsWith<APIError> { client.fetchVehicles(token) }
        assertEquals(APIError.ErrorType.INVALID_CREDENTIALS, expired.errorType)
        assertEquals(401, expired.code)
        assertEquals("""Authentication expired: {"errorMessage":"expired"}""", expired.message)

        val gateway = assertFailsWith<APIError> { client.fetchVehicles(token) }
        assertEquals(APIError.ErrorType.SERVER_ERROR, gateway.errorType)
        assertEquals("Server error (502): bad gateway", gateway.message)

        val notFound = assertFailsWith<APIError> { client.fetchVehicles(token) }
        assertEquals(APIError.ErrorType.GENERAL, notFound.errorType)
        assertEquals("HTTP 404: not found", notFound.message)
        assertEquals(404, notFound.code)

        assertEquals("HTTP 500: internal server error", assertFailsWith<APIError> { client.fetchVehicles(token) }.message)
    }

    @Test
    fun `no connection is a network error`() = runTest {
        val client = client()
        server.close()
        val error = assertFailsWith<APIError> { client.login() }
        assertTrue(error.message.startsWith("Network error: "), error.message)
        assertEquals(1, logs.size)
        assertEquals(null, logs[0].responseStatus)
        assertEquals("Error", logs[0].statusText)
    }

    @Test
    fun `gzip replies are decoded`() = runTest {
        val zipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(fixture("vehicles.json")) }
        }.toByteArray()
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Encoding", "gzip").body(Buffer().write(zipped)).build(),
        )
        assertEquals(3, client().fetchVehicles(token).size)
    }

    @Test
    fun `requests are logged with personal data redacted`() = runTest {
        respond(body = fixture("login.json").decodeToString())
        client().login()
        val log = logs.single()
        assertEquals(HTTPRequestType.LOGIN, log.requestType)
        assertEquals("POST", log.method)
        assertEquals(200, log.responseStatus)
        assertEquals(testAccountId, log.accountId)
        assertTrue(log.isSuccess)
        assertTrue("hunter2" !in log.requestBody.orEmpty())
        assertTrue("[REDACTED]" in log.requestBody.orEmpty())
        assertEquals("[REDACTED]", log.requestHeaders["clientSecret"])
        assertTrue("test-access-token" !in log.responseBody.orEmpty())
    }

    @Test
    fun `redaction can be turned off`() = runTest {
        respond(body = fixture("login.json").decodeToString())
        client(redactPII = false).login()
        assertTrue("hunter2" in logs.single().requestBody.orEmpty())
    }

    @Test
    fun `service errors inside a 200 reply are noted in the log`() = runTest {
        respond(body = """{"status":{"errorCode":1005,"errorMessage":"Invalid vehicle"}}""")
        assertFailsWith<APIError> { client().fetchVehicleStatus(vehicle(FuelType.ELECTRIC), token) }
        assertEquals("API Error 1005: Invalid vehicle", logs.single().apiError)
        assertEquals("200 (API Error)", logs.single().statusText)
    }
}
