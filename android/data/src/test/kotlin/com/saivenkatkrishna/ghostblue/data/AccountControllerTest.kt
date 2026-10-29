package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.account.AccountController
import com.saivenkatkrishna.ghostblue.data.account.HTTPLogSinkManager
import com.saivenkatkrishna.ghostblue.data.account.SwiftAuthToken
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset
import com.saivenkatkrishna.ghostblue.data.model.CommandActivityState
import com.saivenkatkrishna.ghostblue.kit.api.APIClient
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLog
import com.saivenkatkrishna.ghostblue.kit.model.HTTPRequestType
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Login, token reuse, re-authentication and vehicle sync: the run-time half of iOS's `BBAccount`. */
@RunWith(RobolectricTestRunner::class)
class AccountControllerTest {
    private lateinit var db: GhostBlueDatabase
    private lateinit var account: BBAccount
    private val client = ScriptedClient()

    @Before fun setUp() {
        db = testDatabase()
        account = BBAccount("driver@example.com", "secret", "1234", Brand.HYUNDAI, Region.USA)
    }

    @After fun tearDown() = db.close()

    private suspend fun TestScope.controller(): AccountController {
        if (db.accounts().byId(account.id) == null) db.accounts().insert(account)
        return AccountController(account.id, db, backgroundScope, clientFactory = { client })
    }

    private fun apiVehicle(vin: String, model: String = "IONIQ 5") =
        Vehicle(vin, "reg-$vin", model, account.id, FuelType.ELECTRIC, 3, Distance(100.0, Distance.Units.MILES))

    @Test fun loginStoresTokenInSwiftFormatAndAValidOneIsReused() = runTest {
        controller().initialize()
        assertEquals(1, client.logins)
        val stored = db.accounts().byId(account.id)!!
        assertNotNull(stored.deviceId)
        assertEquals("refresh-1", stored.refreshToken)
        // Seconds since 2001, as a default Swift JSONEncoder writes dates.
        assertTrue(stored.serializedAuthToken!!.contains("\"expiresAt\":"))
        assertEquals("access-1", SwiftAuthToken.decode(stored.serializedAuthToken!!)?.accessToken)

        // A new process reads the stored token instead of logging in again.
        controller().initialize()
        assertEquals(1, client.logins)
    }

    @Test fun anExpiredStoredTokenLogsInAgain() = runTest {
        account.serializedAuthToken = SwiftAuthToken.encode(AuthToken("old", "old", Instant.now().minusSeconds(60)))
        controller().initialize()
        assertEquals(1, client.logins)
    }

    @Test fun swiftEncodedTokenFromAnIPhoneBackupIsRead() {
        // Written by iOS's BBAccount: JSONEncoder() with the default date strategy.
        val token = SwiftAuthToken.decode("""{"accessToken":"a","refreshToken":"r","expiresAt":815000000.5}""")!!
        assertEquals(Instant.parse("2026-10-29T20:53:20.500Z"), token.expiresAt)
        assertEquals(token, SwiftAuthToken.decode(SwiftAuthToken.encode(token)))
        assertEquals(
            """{"accessToken":"a","refreshToken":"r","expiresAt":815000000.5}""",
            SwiftAuthToken.encode(token),
        )
        assertNull(SwiftAuthToken.decode("not a token"))
    }

    @Test fun aSessionErrorLogsInAgainAndRetriesOnce() = runTest {
        val controller = controller()
        client.vehicles = listOf(apiVehicle("VIN1"))
        controller.loadVehicles()
        client.statusFailures += APIError.invalidVehicleSession()

        val status = controller.fetchVehicleStatus("VIN1")

        assertEquals("VIN1", status.vin)
        assertEquals(2, client.logins)
        assertEquals(2, client.statusReads)
    }

    @Test fun aTransientErrorKeepsTheTokenAndIsNotRetried() = runTest {
        val controller = controller()
        client.vehicles = listOf(apiVehicle("VIN1"))
        controller.loadVehicles()
        client.statusFailures += APIError("Network error: offline")

        val error = runCatching { controller.fetchVehicleStatus("VIN1") }.exceptionOrNull()

        assertTrue(error is APIError)
        assertEquals(1, client.logins)
        assertEquals(1, client.statusReads)
        assertNotNull(db.accounts().byId(account.id)!!.serializedAuthToken)
    }

    @Test fun vehicleSyncKeepsOwnerSettingsAppendsNewCarsAndRemovesMissingOnes() = runTest {
        val controller = controller()
        client.vehicles = listOf(apiVehicle("VIN1"), apiVehicle("VIN2"))
        controller.loadVehicles()
        db.vehicles().byVin("VIN1")!!.also {
            it.customName = "Blue"
            db.vehicles().update(it)
        }
        db.climatePresets().insert(ClimatePreset("Warm", "sun.max", ClimateOptions(), vehicleId = db.vehicles().byVin("VIN2")!!.id))

        client.vehicles = listOf(apiVehicle("VIN1", model = "IONIQ 5 XRT"), apiVehicle("VIN3"))
        controller.loadVehicles()

        val vehicles = controller.vehicles()
        assertEquals(listOf("VIN1", "VIN3"), vehicles.map { it.vin })
        assertEquals("Blue", vehicles[0].customName)
        assertEquals("IONIQ 5 XRT", vehicles[0].model)
        assertTrue(vehicles[1].sortOrder > vehicles[0].sortOrder)
        assertTrue("A removed car's presets go with it", db.climatePresets().all().isEmpty())
    }

    @Test fun removingAnAccountRemovesItsVehicles() = runTest {
        val controller = controller()
        client.vehicles = listOf(apiVehicle("VIN1"))
        controller.loadVehicles()
        AccountController.removeAccount(db.accounts().byId(account.id)!!, db)
        assertTrue(db.vehicles().all().isEmpty())
    }

    @Test fun aPendingMFAChallengeIsRethrownWithoutAnotherLogin() = runTest {
        client.loginFailure = APIError.requiresMFA(xid = "x", otpKey = "k")
        val controller = controller()
        val first = runCatching { controller.initialize() }.exceptionOrNull() as APIError
        val second = runCatching { controller.initialize() }.exceptionOrNull()
        assertEquals(APIError.ErrorType.REQUIRES_MFA, first.errorType)
        assertSame(first, second)
        assertEquals(1, client.logins)
    }

    @Test fun commandsAreLoggedWithTheirOutcome() = runTest {
        val controller = controller()
        client.vehicles = listOf(apiVehicle("VIN1"))
        controller.loadVehicles()
        controller.lockVehicle("VIN1")
        client.commandFailure = APIError.serverError()
        runCatching { controller.unlockVehicle("VIN1") }

        val log = db.remoteCommands().all()
        assertEquals(listOf("Lock", "Unlock"), log.map { it.commandName })
        assertEquals(listOf(CommandActivityState.SUCCEEDED, CommandActivityState.FAILED), log.map { it.state })
        assertEquals("Server temporarily unavailable", log[1].errorMessage)
        assertEquals("Phone", log[0].sourceName)
    }

    @Test fun climateStartsWithTheSelectedPreset() = runTest {
        val controller = controller()
        client.vehicles = listOf(apiVehicle("VIN1"))
        controller.loadVehicles()
        val vehicleId = db.vehicles().byVin("VIN1")!!.id
        val warm = ClimateOptions(defrost = true)
        db.climatePresets().insert(ClimatePreset("Cool", "snowflake", ClimateOptions(), vehicleId = vehicleId))
        db.climatePresets().insert(ClimatePreset("Warm", "sun.max", warm, isSelected = true, vehicleId = vehicleId))

        controller.startClimate("VIN1")

        assertEquals(VehicleCommand.StartClimate(warm), client.commands.single())
    }

    @Test fun httpLogsAreKeptToTheNewestFiftyOnceOverAHundred() = runTest {
        val logs = HTTPLogSinkManager(db, backgroundScope)
        repeat(101) { index ->
            logs.store(
                HTTPLog(
                    Instant.ofEpochSecond(1_790_000_000L + index), account.id, HTTPRequestType.LOGIN, "POST", "https://x",
                    emptyMap(), null, 200, emptyMap(), null, null, duration = 0.1,
                ),
            )
        }
        val kept = db.httpLogs().all()
        assertEquals(51, kept.size)
        assertEquals(Instant.ofEpochSecond(1_790_000_100), kept.first().timestamp)
    }
}

/** A client that answers from fields the test sets. */
private class ScriptedClient : APIClient {
    var logins = 0
    var statusReads = 0
    var vehicles = emptyList<Vehicle>()
    var loginFailure: APIError? = null
    val statusFailures = ArrayDeque<APIError>()
    var commandFailure: APIError? = null
    val commands = mutableListOf<VehicleCommand>()

    override suspend fun login(): AuthToken {
        logins++
        loginFailure?.let { throw it }
        return AuthToken("access-$logins", "refresh-$logins", Instant.now().plusSeconds(3600))
    }

    override suspend fun fetchVehicles(authToken: AuthToken) = vehicles

    override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean): VehicleStatus {
        statusReads++
        statusFailures.removeFirstOrNull()?.let { throw it }
        return vehicleStatus(vehicle.vin, evStatus(charging = false, percentage = 60.0), syncDate = Instant.now())
    }

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {
        commandFailure?.let { throw it }
        commands += command
    }
}
