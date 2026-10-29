package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.account.CachedAPIClient
import com.saivenkatkrishna.ghostblue.data.charging.ChargingCSVExporter
import com.saivenkatkrishna.ghostblue.data.charging.ChargingDisplayState
import com.saivenkatkrishna.ghostblue.data.charging.ChargingTariff
import com.saivenkatkrishna.ghostblue.data.charging.SDGESmartChargingSchedule
import com.saivenkatkrishna.ghostblue.data.charging.SmartChargingOverride
import com.saivenkatkrishna.ghostblue.data.charging.SmartChargingPreference
import com.saivenkatkrishna.ghostblue.data.charging.VehicleRefreshPolicy
import com.saivenkatkrishna.ghostblue.data.charging.activeChargingOverride
import com.saivenkatkrishna.ghostblue.data.charging.chargingDisplayState
import com.saivenkatkrishna.ghostblue.data.charging.chargingPreference
import com.saivenkatkrishna.ghostblue.data.charging.setChargingOverride
import com.saivenkatkrishna.ghostblue.data.charging.smartChargingWarning
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.vehicle.chargingCost
import com.saivenkatkrishna.ghostblue.data.vehicle.energyDelivered
import com.saivenkatkrishna.ghostblue.data.vehicle.updateStatus
import com.saivenkatkrishna.ghostblue.kit.api.APIClient
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import java.util.UUID
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Port of `ChargingReliabilityTests`. */
@RunWith(RobolectricTestRunner::class)
class ChargingReliabilityTest {
    private val vin = "TEST-CHARGING"
    private lateinit var db: GhostBlueDatabase

    @Before fun setUp() {
        db = testDatabase()
    }

    @After fun tearDown() = db.close()

    private fun vehicle() = quietVehicle(vin)

    private fun date(month: Int, day: Int, hour: Int, minute: Int = 0) = sanDiego(2026, month, day, hour, minute)

    private fun status(charging: Boolean, at: Instant, percentage: Double = 30.0) = vehicleStatus(
        vin,
        evStatus(
            charging = charging, percentage = percentage, rangeMiles = 100.0,
            chargeTimeSeconds = 3600, targetSocAC = 80.0, targetSocDC = 90.0,
        ),
        syncDate = at,
        lastUpdated = at,
        location = VehicleStatus.Location(32.7, -117.1),
    )

    @Test fun transientStopKeepsPresentationAndHistoryInAgreement() = runTest {
        val car = vehicle().also { db.vehicles().insert(it) }
        val start = date(9, 14, 1)
        car.updateStatus(status(true, start), db)
        val candidate = start.plusSeconds(60)
        val accepted = car.updateStatus(status(false, candidate), db)
        assertEquals(true, accepted.evStatus?.charging)
        assertEquals(0.0, accepted.evStatus!!.chargeSpeed, 0.0)
        assertEquals(ChargingDisplayState.CONFIRMING_STOP, car.chargingDisplayState(candidate))
        assertTrue(db.chargeSessions().all().first().isActive)
        car.updateStatus(status(true, candidate.plusSeconds(60)), db)
        assertNull(car.pendingChargingStopDate)
        assertEquals(1, db.chargeSessions().all().size)
    }

    @Test fun replayedStopDoesNotBecomeConfirmedJustBecauseItWasFetchedAgain() = runTest {
        val car = vehicle().also { db.vehicles().insert(it) }
        val start = date(9, 14, 1)
        car.updateStatus(status(true, start), db)
        val stoppedAt = start.plusSeconds(60)
        val stopped = status(false, stoppedAt)
        car.updateStatus(stopped, db)
        car.updateStatus(stopped.copy(lastUpdated = stopped.lastUpdated.plusSeconds(120)), db)
        assertEquals(ChargingDisplayState.CONFIRMING_STOP, car.chargingDisplayState(stoppedAt))
        assertTrue(db.chargeSessions().all().first().isActive)
        car.updateStatus(status(false, stoppedAt.plusSeconds(60)), db)
        assertFalse(car.evStatus?.charging ?: true)
        assertNull(car.pendingChargingStopDate)
        assertEquals(stoppedAt, db.chargeSessions().all().first().endDate)
    }

    @Test fun olderSampleCannotRollBackVehicleOrChargingState() = runTest {
        val car = vehicle()
        val latest = date(9, 14, 1)
        car.updateStatus(status(true, latest, percentage = 50.0))
        val accepted = car.updateStatus(status(false, latest.minusSeconds(60), percentage = 20.0))
        assertEquals(50.0, car.evStatus!!.evRange.percentage, 0.0)
        assertEquals(true, accepted.evStatus?.charging)
        assertEquals(latest, accepted.syncDate)
        assertNull(car.pendingChargingStopDate)
    }

    @Test fun missingEVFieldsDoNotSignalAStop() = runTest {
        val car = vehicle()
        val start = date(9, 14, 1)
        car.updateStatus(status(true, start))
        val accepted = car.updateStatus(status(false, start.plusSeconds(60)).copy(evStatus = null))
        assertEquals(true, accepted.evStatus?.charging)
        assertNull(car.pendingChargingStopDate)
    }

    @Test fun chargingLabelsSeparateScheduledPauseCompletionAndOutdatedData() = runTest {
        val car = vehicle()
        car.smartChargingEnabled = true
        car.smartChargingHomeOnly = false
        val morningPause = date(9, 14, 7)
        car.updateStatus(status(false, morningPause))
        assertEquals(ChargingDisplayState.SCHEDULED_PAUSE, car.chargingDisplayState(morningPause))
        assertEquals(ChargingDisplayState.OUTDATED, car.chargingDisplayState(morningPause.plusSeconds(601)))
        val midday = date(9, 14, 10)
        car.updateStatus(status(false, midday, percentage = 80.0))
        assertEquals(ChargingDisplayState.COMPLETED, car.chargingDisplayState(midday))
    }

    @Test fun measuredEnergyIncludingZeroOverridesBatteryEstimateAndPersists() = runTest {
        val car = vehicle()
        car.batteryCapacityKWh = 77.4
        val session = ChargeSession(car.vin, Instant.now(), 20.0, isDCFast = false).apply {
            endPercentage = 50.0
            manualEnergyKWh = 27.6
            electricityRatePerKWh = 0.2
            rateWasLocationAware = true
        }
        db.chargeSessions().insert(session)
        val saved = db.chargeSessions().byId(session.id)!!
        assertEquals(27.6, car.energyDelivered(saved), 0.0)
        assertEquals(5.52, car.chargingCost(saved)!!, 0.0001)
        saved.manualEnergyKWh = 0.0
        assertEquals(0.0, car.energyDelivered(saved), 0.0)
        saved.manualEnergyKWh = null
        assertEquals(23.22, car.energyDelivered(saved), 0.0001)
    }

    private val tariff = ChargingTariff(
        standardRate = 0.3, offPeakRate = 0.1, peakRate = 0.6, usesSDGE = true,
        offPeakStartHour = 0, offPeakEndHour = 6, timeZoneIdentifier = "America/Los_Angeles",
    )

    @Test fun tariffSplitsMiddayAndPeakBoundaries() {
        assertEquals(2.0, tariff.cost(10.0, date(9, 14, 9), date(9, 14, 11)), 0.001)
        assertEquals(2.0, tariff.cost(10.0, date(9, 14, 13), date(9, 14, 15)), 0.001)
        assertEquals(4.5, tariff.cost(10.0, date(9, 14, 15), date(9, 14, 17)), 0.001)
    }

    @Test fun tariffCrossesMidnightAndBothDSTChanges() {
        assertEquals(2.0, tariff.cost(10.0, date(9, 14, 23), date(9, 15, 1)), 0.001)
        for ((month, day) in listOf(3 to 8, 11 to 1)) {
            assertEquals(1.0, tariff.cost(10.0, date(month, day, 0), date(month, day, 7)), 0.001)
        }
    }

    @Test fun historicalTariffSnapshotAndReceiptWinOverCurrentSettings() = runTest {
        val car = vehicle()
        val session = ChargeSession(car.vin, date(9, 14, 9), 20.0, isDCFast = false).apply {
            endDate = date(9, 14, 11)
            manualEnergyKWh = 10.0
            this.tariff = this@ChargingReliabilityTest.tariff
        }
        db.chargeSessions().insert(session)
        car.electricityRatePerKWh = 100.0
        val saved = db.chargeSessions().byId(session.id)!!
        assertEquals(2.0, car.chargingCost(saved)!!, 0.001)
        saved.actualCost = 1.25
        assertEquals(1.25, car.chargingCost(saved)!!, 0.0)
        assertEquals(tariff, saved.tariff)
    }

    @Test fun overridesExpireAtActualNextBoundaryIncludingHolidays() {
        val car = vehicle()
        val pause = date(9, 14, 7)
        car.setChargingOverride(SmartChargingOverride.CHARGE_NOW, pause)
        assertEquals(SmartChargingPreference.CHARGE, car.chargingPreference(pause))
        assertEquals(date(9, 14, 10), car.smartChargingOverrideExpiresAt)
        assertNull(car.activeChargingOverride(date(9, 14, 10)))
        car.setChargingOverride(SmartChargingOverride.SKIP_WINDOW, date(9, 7, 1)) // Labor Day
        assertEquals(date(9, 7, 14), car.smartChargingOverrideExpiresAt)
        car.setChargingOverride(null)
        assertNull(car.smartChargingOverrideExpiresAt)
    }

    @Test fun weekendPreviewOmitsWeekdayPauseAndResume() {
        val transitions = SDGESmartChargingSchedule.transitions(date(9, 12, 1), count = 3)
        assertEquals(listOf(date(9, 12, 14), date(9, 13, 0), date(9, 13, 14)), transitions.map { it.date })
    }

    @Test fun failedRetryDoesNotHideMissingConfirmedScheduleCheck() = runTest {
        val car = vehicle()
        val now = date(9, 14, 10, minute = 10)
        car.smartChargingEnabled = true
        car.smartChargingHomeOnly = false
        car.updateStatus(status(false, now))
        car.smartChargingLastConfirmedAt = date(9, 14, 5)
        car.smartChargingLastRunAt = now
        assertNotNull(car.smartChargingWarning(now))
        car.smartChargingLastConfirmedAt = now
        assertNull(car.smartChargingWarning(now))
    }

    @Test fun csvUsesMeasuredEnergyReceiptAndUnambiguousDates() {
        val car = vehicle()
        car.batteryCapacityKWh = 77.4
        val session = ChargeSession(car.vin, date(9, 14, 9), 20.0, isDCFast = true).apply {
            endDate = date(9, 14, 10)
            endPercentage = 50.0
            manualEnergyKWh = 27.6
            actualCost = 8.2
        }
        val csv = ChargingCSVExporter.csv(listOf(session), car)
        assertTrue(csv.contains("2026-09-14T16:00:00Z"))
        assertTrue(csv.contains("\"27.6\",\"Measured · manual\",\"8.2\",\"Receipt\""))
        assertFalse(csv.contains(car.vin))
        assertEquals(3, csv.split("\r\n").size)
    }

    @Test fun widgetFreshnessUsesSameChargingIntervalAsTimeline() {
        val interval = VehicleRefreshPolicy.interval(requested = 1800.0, pluggedIn = true)
        assertEquals(600.0, interval, 0.0)
        val now = Instant.now()
        assertTrue(VehicleRefreshPolicy.shouldRefresh(now.minusSeconds(601), now, interval))
        assertFalse(VehicleRefreshPolicy.shouldRefresh(now.minusSeconds(599), now, interval))
    }

    @Test fun cancelledRefreshLoopExitsWithoutExecutingAction() = runTest {
        var actions = 0
        val loop = launch { VehicleRefreshPolicy.run(interval = { 60.0 }) { actions++ } }
        loop.cancel()
        loop.join()
        assertEquals(0, actions)
    }
}

/** Port of `CachedStatusReliabilityTests`: request sharing must never hand back stale state. */
@OptIn(ExperimentalCoroutinesApi::class)
class CachedStatusReliabilityTest {
    private val car = Vehicle("MOCK", "mock", "Mock", UUID.randomUUID(), FuelType.ELECTRIC, 3, Distance(0.0, Distance.Units.MILES))
    private val token = AuthToken("test", "test", Instant.MAX)

    private fun TestScope.client(mock: ControlledStatusClient) = CachedAPIClient(mock, backgroundScope)

    @Test fun freshReadNeverJoinsCachedReadAndLateCachedResponseCannotOverwriteIt() = runTest {
        val mock = ControlledStatusClient()
        val client = client(mock)
        val cached = async { client.fetchVehicleStatus(car, token, cached = true) }
        runCurrent()
        val fresh = async { client.fetchVehicleStatus(car, token, cached = false) }
        runCurrent()
        assertEquals(listOf(true, false), mock.calls)
        mock.finish(1, charging = true)
        assertEquals(true, fresh.await().evStatus?.charging)
        mock.finish(0, charging = false)
        cached.await()
        val subsequent = client.fetchVehicleStatus(car, token, cached = true)
        assertEquals(true, subsequent.evStatus?.charging)
        assertEquals(2, mock.calls.size)
    }

    @Test fun identicalFreshReadsCoalesce() = runTest {
        val mock = ControlledStatusClient()
        val client = client(mock)
        val first = async { client.fetchVehicleStatus(car, token, cached = false) }
        runCurrent()
        val second = async { client.fetchVehicleStatus(car, token, cached = false) }
        repeat(20) { yield() }
        runCurrent()
        assertEquals(1, mock.calls.size)
        mock.finish(0, charging = true)
        first.await()
        second.await()
    }

    @Test fun postCommandVerificationDoesNotJoinPreCommandRead() = runTest {
        val mock = ControlledStatusClient()
        val client = client(mock)
        val old = async { client.fetchVehicleStatus(car, token, cached = false) }
        runCurrent()
        client.sendCommand(car, VehicleCommand.StartCharge, token)
        val verification = async { client.fetchVehicleStatus(car, token, cached = false) }
        runCurrent()
        assertEquals(2, mock.calls.size)
        mock.finish(0, charging = false)
        old.await()
        mock.finish(1, charging = true)
        assertEquals(true, verification.await().evStatus?.charging)
    }
}

/** One failed shared request must fail only its own callers. */
class CachedRequestFailureTest {
    private val car = Vehicle("MOCK", "mock", "Mock", UUID.randomUUID(), FuelType.ELECTRIC, 3, Distance(0.0, Distance.Units.MILES))

    @Test fun aFailedRequestLeavesTheClientWorking() = runTest {
        // A plain Job: without its own supervisor, the client's failure would cancel it.
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        var calls = 0
        val client = CachedAPIClient(
            object : APIClient {
                override suspend fun login(): AuthToken {
                    if (calls++ == 0) throw APIError("Network error: offline")
                    return AuthToken("ok", "ok", Instant.MAX)
                }

                override suspend fun fetchVehicles(authToken: AuthToken) = listOf(car)

                override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean) =
                    throw UnsupportedOperationException()

                override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {}
            },
            scope,
        )
        assertTrue(runCatching { client.login() }.exceptionOrNull() is APIError)
        assertEquals("ok", client.login().accessToken)
        assertTrue(scope.isActive)
        scope.cancel()
    }
}

/** A client whose status reads finish only when the test says so. */
private class ControlledStatusClient : APIClient {
    val calls = mutableListOf<Boolean>()
    private val pending = mutableMapOf<Int, CompletableDeferred<VehicleStatus>>()

    override suspend fun login() = AuthToken("mock", "mock", Instant.MAX)

    override suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle> = emptyList()

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {}

    override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean): VehicleStatus {
        val result = CompletableDeferred<VehicleStatus>()
        pending[calls.size] = result
        calls += cached
        return result.await()
    }

    fun finish(index: Int, charging: Boolean) {
        pending.remove(index)?.complete(
            vehicleStatus(
                "MOCK",
                evStatus(charging = charging, percentage = 30.0, chargeSpeed = if (charging) 7.0 else 0.0, rangeMiles = 100.0, chargeTimeSeconds = 60),
                syncDate = null,
                location = VehicleStatus.Location(0.0, 0.0),
            ),
        )
    }
}
