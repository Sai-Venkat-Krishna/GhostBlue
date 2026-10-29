package com.saivenkatkrishna.ghostblue.kit

import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeAPIClient
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeDebugConfiguration
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeVehicleSeeds
import com.saivenkatkrishna.ghostblue.kit.api.fake.InMemoryFakeVehicleStore
import com.saivenkatkrishna.ghostblue.kit.api.fake.StoreFakeVehicleProvider
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus.LockStatus
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus.PlugType
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach

/** The test car: seeds, commands, failure switches and the charging simulation. */
class FakeAPIClientTest {
    private var now = Instant.parse("2026-10-28T02:00:00Z")
    private lateinit var store: InMemoryFakeVehicleStore
    private lateinit var client: FakeAPIClient

    @BeforeEach
    fun setUp() {
        silenceKitLogging()
        store = InMemoryFakeVehicleStore(FakeVehicleSeeds.defaultRecords(testAccountId, now, Random(7)))
        client = FakeAPIClient(
            configuration(brand = Brand.FAKE),
            StoreFakeVehicleProvider(store) { now },
        ) { now }
    }

    private suspend fun vehicles(): List<Vehicle> = client.fetchVehicles(client.login())
    private suspend fun ioniq() = vehicles()[0]
    private suspend fun ev6() = vehicles()[2]
    private suspend fun status(vehicle: Vehicle) = client.fetchVehicleStatus(vehicle, client.login())
    private suspend fun send(vehicle: Vehicle, command: VehicleCommand) =
        client.sendCommand(vehicle, command, client.login())

    @Test
    fun `starter cars match the iOS test account`() = runTest {
        val vehicles = vehicles()
        assertEquals(listOf("Hyundai IONIQ 5", "Hyundai Sonata", "Kia EV6"), vehicles.map { it.model })
        assertEquals("FAKE-Hyundai IONIQ 5-200", vehicles[0].vin)
        assertEquals("REG201", vehicles[1].regId)

        val ioniq = status(vehicles[0])
        assertEquals(LockStatus.LOCKED, ioniq.lockStatus)
        assertEquals(92.0, ioniq.evStatus?.evRange?.percentage)
        assertEquals(276.0, ioniq.evStatus?.evRange?.range?.length)
        assertEquals(PlugType.AC_CHARGER, ioniq.evStatus?.plugType)
        assertEquals(Temperature(68.0, Temperature.Units.FAHRENHEIT), ioniq.climateStatus.temperature)
        assertTrue(ioniq.location.latitude in 37.7649..37.7849)

        val sonata = status(vehicles[1])
        assertNull(sonata.evStatus)
        assertEquals(75.0, sonata.gasRange?.percentage)
        assertEquals(300.0, sonata.gasRange?.range?.length)
    }

    @Test
    fun `a charging car gains charge between fetches`() = runTest {
        val ioniq = ioniq()
        now = now.plusSeconds(5 * 60)
        val ev = requireNotNull(status(ioniq).evStatus)
        // 45 kW for 5 minutes into 77.4 kWh is 4.845 %.
        assertEquals(92.0 + 45.0 * 300 / 3600 / 77.4 * 100, ev.evRange.percentage, 1e-9)
        assertEquals(291.0, ev.evRange.range.length)
        assertTrue(ev.charging)
        assertEquals(240L, ev.chargeTimeSeconds) // ceil(3.26 min) = 4 min

        now = now.plusSeconds(30 * 60)
        val full = requireNotNull(status(ioniq).evStatus)
        assertEquals(100.0, full.evRange.percentage)
        assertEquals(300.0, full.evRange.range.length)
        assertFalse(full.charging)
        assertEquals(0.0, full.chargeSpeed)
        assertTrue(full.pluggedIn)
    }

    @Test
    fun `a lower target stops charging without lowering the charge`() = runTest {
        val ioniq = ioniq()
        send(ioniq, VehicleCommand.SetTargetSOC(acLevel = 80, dcLevel = 80))
        now = now.plusSeconds(600)
        val ev = requireNotNull(status(ioniq).evStatus)
        assertEquals(92.0, ev.evRange.percentage)
        assertFalse(ev.charging)
        assertEquals(80.0, ev.targetSocAC)
    }

    @Test
    fun `start and stop charge`() = runTest {
        val ev6 = ev6()
        send(ev6, VehicleCommand.StartCharge)
        val started = requireNotNull(status(ev6).evStatus)
        assertTrue(started.charging)
        assertEquals(10.9, started.chargeSpeed)
        assertEquals(52L * 60, started.chargeTimeSeconds) // 12 % of 77.4 kWh at 10.9 kW

        now = now.plusSeconds(1800)
        assertEquals(88.0 + 10.9 * 1800 / 3600 / 77.4 * 100, status(ev6).evStatus!!.evRange.percentage, 1e-9)

        send(ev6, VehicleCommand.StopCharge)
        val stopped = requireNotNull(status(ev6).evStatus)
        assertFalse(stopped.charging)
        assertEquals(0.0, stopped.chargeSpeed)
    }

    @Test
    fun `the server doesn't echo the app's pending-stop placeholder`() = runTest {
        val ioniq = ioniq()
        val record = store.record(ioniq.vin, testAccountId)!!
        store.save(
            record.copy(
                evStatus = record.evStatus!!.copy(charging = true, chargeSpeed = 0.0),
                pendingChargingStopDate = now,
            ),
        )
        now = now.plusSeconds(600)
        val ev = requireNotNull(status(ioniq).evStatus)
        assertFalse(ev.charging)
        assertEquals(92.0, ev.evRange.percentage)
    }

    @Test
    fun `lock and climate commands`() = runTest {
        val ioniq = ioniq()
        send(ioniq, VehicleCommand.Unlock)
        assertEquals(LockStatus.UNLOCKED, status(ioniq).lockStatus)
        send(ioniq, VehicleCommand.Lock)
        assertEquals(LockStatus.LOCKED, status(ioniq).lockStatus)

        val options = ClimateOptions(temperature = Temperature(75.0, Temperature.Units.FAHRENHEIT), steeringWheel = 2)
        send(ioniq, VehicleCommand.StartClimate(options))
        val on = status(ioniq).climateStatus
        assertTrue(on.airControlOn)
        assertTrue(on.steeringWheelHeatingOn)
        assertEquals(75.0, on.temperature.value)

        send(ioniq, VehicleCommand.StopClimate)
        val off = status(ioniq).climateStatus
        assertFalse(off.airControlOn)
        assertFalse(off.defrostOn)
        assertEquals(75.0, off.temperature.value)
    }

    @Test
    fun `failure switches`() = runTest {
        val ioniq = ioniq()
        val record = store.record(ioniq.vin, testAccountId)!!
        store.save(
            record.copy(
                debugConfiguration = FakeDebugConfiguration(
                    shouldFailStatusFetch = true,
                    shouldFailLock = true,
                    customPinErrorMessage = "Wrong PIN",
                ),
            ),
        )
        val token = client.login()
        assertEquals(500, assertFailsWith<APIError> { client.fetchVehicleStatus(ioniq, token) }.code)
        assertEquals(
            "Debug: Simulated lock failure",
            assertFailsWith<APIError> { client.sendCommand(ioniq, VehicleCommand.Lock, token) }.message,
        )
        client.sendCommand(ioniq, VehicleCommand.Unlock, token)

        store.save(
            store.record(ioniq.vin, testAccountId)!!.copy(
                debugConfiguration = FakeDebugConfiguration(
                    shouldFailPinValidation = true,
                    shouldFailCredentialValidation = true,
                    customCredentialErrorMessage = "Nope",
                    customPinErrorMessage = "Wrong PIN",
                ),
            ),
        )
        val pin = assertFailsWith<APIError> { client.sendCommand(ioniq, VehicleCommand.Unlock, token) }
        assertEquals(APIError.ErrorType.INVALID_PIN, pin.errorType)
        assertEquals("Wrong PIN", pin.message)
        val login = assertFailsWith<APIError> { client.login() }
        assertEquals(APIError.ErrorType.INVALID_CREDENTIALS, login.errorType)
        assertEquals("Nope", login.message)
    }

    @Test
    fun `synthetic trips for electric cars only`() = runTest {
        val token = client.login()
        val vehicles = vehicles()
        val trips = requireNotNull(client.fetchEVTripDetails(vehicles[0], token))
        assertEquals(7, trips.size)
        assertEquals(now.minusSeconds(24 * 3600), trips[0].startDate)
        assertEquals(12_345.0 + trips.drop(1).sumOf { it.distance }, trips[0].odometer, 1e-9)
        assertEquals(358, trips[0].accessoriesEnergy)
        assertNull(client.fetchEVTripDetails(vehicles[1], token))
    }

    @Test
    fun `unknown cars are an error`() = runTest {
        val ghost = ioniq().copy(vin = "NOPE")
        assertFailsWith<APIError> { status(ghost) }
    }
}
