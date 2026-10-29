package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.account.AccountController
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.vehicle.StatusWaitingManager
import com.saivenkatkrishna.ghostblue.data.vehicle.VehicleStatusEvents
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.APIError.ErrorType
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real-time reads wake the car's modem (12 V drain, Hyundai rate limits).
 * These pin what keeps them to what a command needs. Port of `WakeUpReductionTests`.
 */
@RunWith(RobolectricTestRunner::class)
class WakeUpReductionTest {
    private lateinit var db: GhostBlueDatabase

    @Before fun setUp() {
        db = testDatabase()
    }

    @After fun tearDown() = db.close()

    private fun error(type: ErrorType, code: Int? = null, message: String = "test") = APIError(message, code, "Test", type)

    // Re-login only for session errors

    @Test fun onlySessionErrorsTriggerAReLogin() {
        // The token is the problem: log in again.
        assertTrue(AccountController.shouldReauthenticate(error(ErrorType.INVALID_CREDENTIALS, 401)))
        assertTrue(AccountController.shouldReauthenticate(error(ErrorType.INVALID_VEHICLE_SESSION, 1005)))
        assertTrue(AccountController.shouldReauthenticate(error(ErrorType.GENERAL, 403)))
        assertTrue(
            "An unrecognised failure still re-authenticates so the app cannot get stuck",
            AccountController.shouldReauthenticate(error(ErrorType.GENERAL)),
        )

        // Transient or not fixable by a login: keep the token, no retry.
        for (transient in listOf(
            error(ErrorType.SERVER_ERROR, 502), // modem did not answer a real-time read
            error(ErrorType.GENERAL, 429), // rate limited
            error(ErrorType.GENERAL, 503),
            error(ErrorType.GENERAL, message = "Network error: The Internet connection appears to be offline."),
            error(ErrorType.GENERAL, -1009), // offline
            error(ErrorType.GENERAL, -1001), // timed out
            error(ErrorType.CONCURRENT_REQUEST, 502),
            error(ErrorType.INVALID_PIN),
            error(ErrorType.KIA_INVALID_REQUEST, 502),
            error(ErrorType.REGION_NOT_SUPPORTED),
            error(ErrorType.FAILED_RETRY_LOGIN, 502),
            error(ErrorType.REQUIRES_MFA),
        )) {
            assertFalse("${transient.errorType} ${transient.code}", AccountController.shouldReauthenticate(transient))
        }
    }

    // Status waiter ordering

    @Test fun aWakeUpIssuedBeforeASleepDoesNotCutItShort() = runTest {
        val manager = StatusWaitingManager()
        val vehicleId = UUID.randomUUID()
        // The wake from a poll's own status update, delivered late.
        val staleWake = manager.ticket()
        val start = currentTime

        val sleeper = launch { manager.wait(vehicleId, 1) }
        delay(150)
        val ended = manager.wakeUp(vehicleId, issuedAt = staleWake)
        sleeper.join()

        assertFalse(ended)
        assertTrue(currentTime - start >= 1000)
    }

    @Test fun aWakeUpIssuedDuringASleepEndsIt() = runTest {
        val manager = StatusWaitingManager()
        val vehicleId = UUID.randomUUID()
        val start = currentTime

        val sleeper = launch { manager.wait(vehicleId, 10) }
        delay(150)
        val ended = manager.wakeUp(vehicleId)
        sleeper.join()

        assertTrue(ended)
        assertTrue(currentTime - start < 2000)
    }

    // One read per stop

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun stoppingChargeReadsTheCarOnce() = runTest {
        val account = BBAccount("testaccount@betterblue.com", "betterblue", "1234", Brand.FAKE, Region.USA)
        db.accounts().insert(account)
        val vin = "WAKETEST00000001"
        db.vehicles().insert(
            BBVehicle(Vehicle(vin, "wake", "IONIQ 5", account.id, FuelType.ELECTRIC, 3, Distance(1_000.0, Distance.Units.MILES)))
                .apply {
                    ownerAccountId = account.id
                    notifyChargeComplete = false
                    notifyChargeInterrupted = false
                    lockStatus = VehicleStatus.LockStatus.LOCKED
                    climateStatus = climateOff
                    location = VehicleStatus.Location(32.7, -117.1)
                    evStatus = evStatus(
                        charging = true, percentage = 55.0, chargeSpeed = 10.9, chargeTimeSeconds = 3600,
                        targetSocAC = 80.0, targetSocDC = 90.0,
                    )
                    syncDate = Instant.now()
                    lastUpdated = Instant.now()
                },
        )
        val controller = AccountController(account.id, db, backgroundScope).also { it.initialize() }
        var reads = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            VehicleStatusEvents.statusUpdated.collect { if (it == vin) reads++ }
        }

        // As the Home screen's charge control does it: no read straight after
        // the command (it would only see the old state), then the verification poll.
        controller.stopCharge(vin)
        controller.waitForStatusChange(vin, condition = { it.evStatus?.charging == false })

        assertEquals(false, db.vehicles().byVin(vin)!!.evStatus?.charging)
        // Previously a read straight after the command saw "charging", then the
        // verification poll ran. The commanded stop now confirms on the poll.
        assertEquals(1, reads)
    }
}
