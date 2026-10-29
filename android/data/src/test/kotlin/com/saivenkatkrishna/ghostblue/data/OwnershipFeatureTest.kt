package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.charging.SDGESmartChargingSchedule
import com.saivenkatkrishna.ghostblue.data.charging.SmartChargingPreference.CHARGE
import com.saivenkatkrishna.ghostblue.data.charging.SmartChargingPreference.PAUSE
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ChargingLocationType
import com.saivenkatkrishna.ghostblue.data.model.CommandActivityState
import com.saivenkatkrishna.ghostblue.data.model.MaintenanceReminder
import com.saivenkatkrishna.ghostblue.data.model.RemoteCommandActivity
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomation
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomationKind
import com.saivenkatkrishna.ghostblue.data.vehicle.effectiveElectricityRate
import com.saivenkatkrishna.ghostblue.data.vehicle.evaluateMaintenanceReminders
import com.saivenkatkrishna.ghostblue.data.vehicle.inferredChargingLocationType
import com.saivenkatkrishna.ghostblue.data.vehicle.isSmartChargingControllableAC
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus.PlugType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Port of `OwnershipFeatureTests` (the trip CSV redaction belongs to phase 3's exports). */
@RunWith(RobolectricTestRunner::class)
class OwnershipFeatureTest {
    private lateinit var db: GhostBlueDatabase

    @Before fun setUp() {
        db = testDatabase()
    }

    @After fun tearDown() = db.close()

    private fun preference(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        SDGESmartChargingSchedule.preference(sanDiego(year, month, day, hour, minute))

    @Test fun sdgeWeekdayChargingWindowsCoverEveryBoundary() {
        // Monday, August 3, 2026 is a normal weekday.
        assertEquals(CHARGE, preference(2026, 8, 3, 0))
        assertEquals(CHARGE, preference(2026, 8, 3, 5, 59))
        assertEquals(PAUSE, preference(2026, 8, 3, 6))
        assertEquals(PAUSE, preference(2026, 8, 3, 9, 59))
        assertEquals(CHARGE, preference(2026, 8, 3, 10))
        assertEquals(CHARGE, preference(2026, 8, 3, 13, 59))
        assertEquals(PAUSE, preference(2026, 8, 3, 14))
    }

    @Test fun sdgeWeekendAndHolidayRemainContinuousUntilTwoPM() {
        // Saturday and Thanksgiving (Thursday) both use the weekend window.
        assertEquals(CHARGE, preference(2026, 8, 8, 8))
        assertEquals(PAUSE, preference(2026, 8, 8, 14))
        assertTrue(SDGESmartChargingSchedule.isHoliday(sanDiego(2026, 11, 26, 8)))
        assertEquals(CHARGE, preference(2026, 11, 26, 8))
    }

    @Test fun sdgeSundayHolidayObservesMondayButSaturdayDoesNotShiftFriday() {
        // July 4, 2027 is a Sunday, so Monday July 5 is the legal holiday.
        assertTrue(SDGESmartChargingSchedule.isHoliday(sanDiego(2027, 7, 5, 8)))
        // July 4, 2026 is a Saturday; Friday July 3 is a normal weekday.
        assertFalse(SDGESmartChargingSchedule.isHoliday(sanDiego(2026, 7, 3, 8)))
        assertEquals(PAUSE, preference(2026, 7, 3, 8))
    }

    @Test fun remoteCommandActivityPersistsSuccessAndFailureDetails() = runTest {
        val succeeded = RemoteCommandActivity("VIN-1", "Ioniq", "Lock", "Wear OS").apply { finish(CommandActivityState.SUCCEEDED) }
        val failed = RemoteCommandActivity("VIN-1", "Ioniq", "Start climate", "Widget or Tile")
            .apply { finish(CommandActivityState.FAILED, IllegalStateException("Vehicle did not respond")) }
        db.remoteCommands().insert(succeeded)
        db.remoteCommands().insert(failed)

        val saved = db.remoteCommands().all()
        assertEquals(2, saved.size)
        assertEquals(CommandActivityState.SUCCEEDED, saved.first { it.commandName == "Lock" }.state)
        assertEquals("Vehicle did not respond", saved.first { it.commandName == "Start climate" }.errorMessage)
    }

    @Test fun chargingSessionUsesHomeClassificationAndOffPeakSnapshotRate() {
        val vehicle = quietVehicle("VIN-CHARGE", model = "Ioniq 5", odometer = 12_000.0).apply {
            homeLatitude = 37.3349
            homeLongitude = -122.0090
            homePrivacyRadiusMeters = 300.0
            electricityRatePerKWh = 0.30
            offPeakElectricityRatePerKWh = 0.10
            offPeakStartHour = 22
            offPeakEndHour = 6
        }
        val sampleDate = LocalDateTime.of(2026, 1, 2, 1, 0).atZone(ZoneId.systemDefault()).toInstant()
        assertEquals(ChargingLocationType.HOME, vehicle.inferredChargingLocationType(37.3349, -122.0090, isDCFast = false))
        assertEquals(0.10, vehicle.effectiveElectricityRate(sampleDate))

        val session = ChargeSession(vehicle.vin, sampleDate, 40.0, isDCFast = false).apply {
            locationType = vehicle.inferredChargingLocationType(37.3349, -122.0090, isDCFast = false)
            electricityRatePerKWh = vehicle.effectiveElectricityRate(sampleDate)
        }
        assertEquals(ChargingLocationType.HOME, session.locationType)
        assertEquals(0.10, session.electricityRatePerKWh)
    }

    @Test fun smartChargingTariffUsesBothWeekdayWindows() {
        val vehicle = quietVehicle("VIN-SDGE", model = "Ioniq 5").apply {
            electricityRatePerKWh = 0.50
            offPeakElectricityRatePerKWh = 0.13
            smartChargingEnabled = true
        }
        assertEquals(0.13, vehicle.effectiveElectricityRate(sanDiego(2026, 8, 3, 5)))
        assertEquals(0.50, vehicle.effectiveElectricityRate(sanDiego(2026, 8, 3, 8)))
        assertEquals(0.13, vehicle.effectiveElectricityRate(sanDiego(2026, 8, 3, 11)))
    }

    @Test fun smartChargingToleratesPausedHomeACReportedAsDC() {
        val vehicle = quietVehicle("VIN-HOME-AC", model = "Ioniq 5").apply {
            smartChargingHomeOnly = true
            homeLatitude = 32.7157
            homeLongitude = -117.1611
            location = VehicleStatus.Location(32.7157, -117.1611)
        }
        val pausedHomeACReportedAsDC = evStatus(charging = false, percentage = 50.0, rangeMiles = 200.0, plugType = PlugType.DC_CHARGER)
        assertTrue(vehicle.isSmartChargingControllableAC(pausedHomeACReportedAsDC))

        val genuineFastDC = evStatus(charging = true, percentage = 50.0, chargeSpeed = 150.0, rangeMiles = 200.0, plugType = PlugType.DC_CHARGER)
        assertFalse(vehicle.isSmartChargingControllableAC(genuineFastDC))

        vehicle.location = VehicleStatus.Location(33.7157, -118.1611)
        assertFalse(vehicle.isSmartChargingControllableAC(pausedHomeACReportedAsDC))
    }

    @Test fun maintenanceAndAutomationRecordsRemainVehicleScoped() = runTest {
        db.maintenanceReminders().insert(MaintenanceReminder("VIN-A", "Tire rotation", dueOdometer = 20_000.0))
        db.maintenanceReminders().insert(MaintenanceReminder("VIN-B", "Annual inspection", dueDate = Instant.now()))
        db.automations().insert(VehicleAutomation("VIN-A", "Charge soon", VehicleAutomationKind.LOW_BATTERY, threshold = 25))

        assertEquals(listOf("Tire rotation"), db.maintenanceReminders().forVin("VIN-A").map { it.title })
        val automation = db.automations().forVin("VIN-A").single()
        assertEquals(25, automation.threshold)
        assertEquals(VehicleAutomationKind.LOW_BATTERY, automation.kind)
    }

    @Test fun odometerMaintenanceReminderIsMarkedWhenDue() = runTest {
        val vehicle = quietVehicle("VIN-DUE", model = "Test Vehicle", fuelType = FuelType.GAS, odometer = 20_500.0, generation = 1)
        db.vehicles().insert(vehicle)
        db.maintenanceReminders().insert(MaintenanceReminder(vehicle.vin, "Tire rotation", dueOdometer = 20_000.0))

        vehicle.evaluateMaintenanceReminders(db)

        assertNotNull(db.maintenanceReminders().forVin(vehicle.vin).single().lastNotifiedAt)
    }
}
