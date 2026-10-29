package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.account.AccountController
import com.saivenkatkrishna.ghostblue.data.account.SmartChargingReconciliation.Action
import com.saivenkatkrishna.ghostblue.data.charging.ChargeStopPolicy
import com.saivenkatkrishna.ghostblue.data.charging.SmartChargingOverride
import com.saivenkatkrishna.ghostblue.data.charging.setChargingOverride
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.vehicle.updateStatus
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus.PlugType
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end checks of the SDG&E off-peak automation: the real
 * reconciliation against the Room-backed test car, so each decision sends
 * the actual command and is verified by polling. Port of
 * `SmartChargingAutomationTests`. Waits run on virtual time.
 */
@RunWith(RobolectricTestRunner::class)
class SmartChargingAutomationTest {
    private val vin = "SMARTCHARGE00001"
    private lateinit var db: GhostBlueDatabase

    @Before fun setUp() {
        db = testDatabase()
    }

    @After fun tearDown() = db.close()

    /** San Diego wall-clock time; 2026-10-19 is a Monday. */
    private fun sanDiego(month: Int, day: Int, hour: Int, minute: Int = 0) = sanDiego(2026, month, day, hour, minute)

    /** A test-account IONIQ 5 on a home AC charger with smart charging on. */
    private suspend fun TestScope.makeCar(
        charging: Boolean,
        percentage: Double = 62.0,
        plugType: PlugType = PlugType.AC_CHARGER,
    ): AccountController {
        val account = BBAccount("testaccount@betterblue.com", "betterblue", "1234", Brand.FAKE, Region.USA)
        db.accounts().insert(account)
        val car = BBVehicle(
            Vehicle(vin, "smart", "IONIQ 5", account.id, FuelType.ELECTRIC, 3, Distance(12_480.0, Distance.Units.MILES)),
        ).apply {
            ownerAccountId = account.id
            notifyChargeComplete = false
            notifyChargeInterrupted = false
            smartChargingEnabled = true
            smartChargingHomeOnly = true
            smartChargingTargetSOC = 80
            homeLatitude = 32.7157
            homeLongitude = -117.1611
            location = VehicleStatus.Location(32.7158, -117.1612)
            lockStatus = VehicleStatus.LockStatus.LOCKED
            climateStatus = VehicleStatus.ClimateStatus(false, false, false, Temperature(72.0, Temperature.Units.FAHRENHEIT))
            evStatus = evStatus(
                charging = charging, percentage = percentage, chargeSpeed = if (charging) 10.9 else 0.0, rangeMiles = 184.0,
                plugType = plugType, chargeTimeSeconds = if (charging) 3600 else 0, targetSocAC = 100.0, targetSocDC = 90.0,
            )
            syncDate = Instant.now()
            lastUpdated = Instant.now()
        }
        db.vehicles().insert(car)
        return AccountController(account.id, db, backgroundScope).also { it.initialize() }
    }

    private suspend fun car() = db.vehicles().byVin(vin)!!

    private suspend fun edit(change: (BBVehicle) -> Unit) = car().also(change).also { db.vehicles().update(it) }

    private suspend fun openSessions() = db.chargeSessions().allOpen()

    // Commands sent by the schedule

    @Test fun weekdayMorningPausesAChargingCarAndConfirmsOnFirstPoll() = runTest {
        val controller = makeCar(charging = true)
        val start = currentTime

        // The refresh inside the run opens the session the pause must close.
        val result = controller.reconcileSmartCharging(vin, at = sanDiego(10, 19, 7))

        assertEquals(Action.STOPPED, result.action)
        assertEquals("Charging paused and confirmed.", result.message)
        val car = car()
        assertEquals(false, car.evStatus?.charging)
        assertNull("The commanded stop must not wait for a second sample", car.pendingChargingStopDate)
        assertNotNull(car.smartChargingLastConfirmedAt)
        assertTrue("The pause must close the charging session", openSessions().isEmpty())
        // One 8 s settle delay and one verification poll, not two polls.
        assertTrue(currentTime - start < 14_000)
    }

    @Test fun middaySuperOffPeakStartsChargingBelowTarget() = runTest {
        val controller = makeCar(charging = false)
        val result = controller.reconcileSmartCharging(vin, at = sanDiego(10, 19, 11))
        assertEquals(Action.STARTED, result.action)
        assertEquals(true, car().evStatus?.charging)
        assertEquals("Starting the charge opens a session", 1, openSessions().size)
    }

    @Test fun chargeNowOverrideStartsChargingDuringAPause() = runTest {
        val controller = makeCar(charging = false)
        val pauseTime = sanDiego(10, 19, 7, 30)
        edit { it.setChargingOverride(SmartChargingOverride.CHARGE_NOW, pauseTime) }

        val result = controller.reconcileSmartCharging(vin, at = pauseTime)
        assertEquals(Action.STARTED, result.action)
        assertEquals(true, car().evStatus?.charging)
    }

    // Decisions that must not send a command

    @Test fun weekendAndHolidayMorningsAreChargeWindows() = runTest {
        val controller = makeCar(charging = true)
        for (date in listOf(sanDiego(10, 24, 7), sanDiego(11, 26, 7))) { // Saturday; Thanksgiving
            val result = controller.reconcileSmartCharging(vin, at = date)
            assertEquals("$date", Action.ALREADY_CORRECT, result.action)
            assertEquals("Charging is already running as requested.", result.message)
        }
    }

    @Test fun afternoonPauseLeavesAStoppedCarAlone() = runTest {
        val controller = makeCar(charging = false)
        val result = controller.reconcileSmartCharging(vin, at = sanDiego(10, 19, 15))
        assertEquals(Action.ALREADY_CORRECT, result.action)
        assertEquals("Charging is already paused as requested.", result.message)
        assertNotNull(car().smartChargingLastConfirmedAt)
    }

    @Test fun carAtTargetIsNotStartedInAChargeWindow() = runTest {
        val controller = makeCar(charging = false, percentage = 80.0)
        val result = controller.reconcileSmartCharging(vin, at = sanDiego(10, 19, 1))
        assertEquals(Action.ALREADY_CORRECT, result.action)
        assertEquals("Battery is already at the 80% target.", result.message)
        assertEquals(false, car().evStatus?.charging)
    }

    @Test fun skipsUnpluggedAwayAndDCFastCars() = runTest {
        val controller = makeCar(charging = false, plugType = PlugType.UNPLUGGED)
        var result = controller.reconcileSmartCharging(vin, at = sanDiego(10, 19, 11))
        assertEquals(Action.SKIPPED, result.action)
        assertEquals("Vehicle is not plugged in.", result.message)

        edit {
            it.evStatus = evStatus(charging = false, percentage = 62.0, rangeMiles = 184.0)
            it.location = VehicleStatus.Location(32.80, -117.25) // ~11 km from home
        }
        result = controller.reconcileSmartCharging(vin, at = sanDiego(10, 19, 11))
        assertEquals(Action.SKIPPED, result.action)
        assertEquals("Vehicle is outside the saved home area.", result.message)

        edit {
            it.location = VehicleStatus.Location(32.7158, -117.1612)
            it.evStatus = evStatus(
                charging = true, percentage = 62.0, chargeSpeed = 150.0, rangeMiles = 184.0,
                plugType = PlugType.DC_CHARGER, chargeTimeSeconds = 600,
            )
        }
        result = controller.reconcileSmartCharging(vin, at = sanDiego(10, 19, 7))
        assertEquals("A DC fast session is never paused", Action.SKIPPED, result.action)
        assertEquals(true, car().evStatus?.charging)
    }

    @Test fun concurrentRunsForOneCarShareOneReconciliation() = runTest {
        val controller = makeCar(charging = true)
        val date = sanDiego(10, 19, 7)
        val first = async { controller.reconcileSmartCharging(vin, at = date) }
        val second = async { controller.reconcileSmartCharging(vin, at = date) }
        assertEquals(first.await(), second.await())
        assertEquals(1, db.remoteCommands().all().count { it.commandName == "Stop charging" })
    }

    // Commanded stop policy

    @Test fun commandedStopConfirmsOnlyInsideItsWindow() = runTest {
        val car = quietVehicle("CMDSTOP").also { db.vehicles().insert(it) }
        val t0 = Instant.ofEpochSecond(1_790_000_000)
        fun status(charging: Boolean, at: Instant) = vehicleStatus(
            "CMDSTOP",
            evStatus(charging = charging, percentage = 50.0, chargeSpeed = if (charging) 7.0 else 0.0, targetSocAC = 80.0),
            syncDate = at,
            location = VehicleStatus.Location(0.0, 0.0),
        )

        car.updateStatus(status(true, t0), db)
        car.chargeStopRequestedAt = t0.plusSeconds(30)
        car.updateStatus(status(false, t0.plusSeconds(60)), db)
        assertNull(car.pendingChargingStopDate)
        assertNull(car.chargeStopRequestedAt)
        assertEquals(false, car.evStatus?.charging)
        assertTrue(openSessions().isEmpty())

        // A request older than the window is ordinary debounce again.
        car.updateStatus(status(true, t0.plusSeconds(3600)), db)
        car.chargeStopRequestedAt = t0.plusSeconds(3600)
        car.updateStatus(status(false, t0.plusSeconds(3600 + ChargeStopPolicy.COMMAND_WINDOW + 60)), db)
        assertNotNull(car.pendingChargingStopDate)
        assertNull("An expired request is cleared", car.chargeStopRequestedAt)
    }
}
