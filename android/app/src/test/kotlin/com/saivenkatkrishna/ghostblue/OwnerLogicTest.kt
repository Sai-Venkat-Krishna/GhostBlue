package com.saivenkatkrishna.ghostblue

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset
import com.saivenkatkrishna.ghostblue.data.model.MaintenanceReminder
import com.saivenkatkrishna.ghostblue.data.model.RemoteCommandActivity
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.shortcuts.AppShortcuts
import com.saivenkatkrishna.ghostblue.shortcuts.ShortcutCommand
import com.saivenkatkrishna.ghostblue.shortcuts.ShortcutRequest
import com.saivenkatkrishna.ghostblue.ui.energy.ChargingMonth
import com.saivenkatkrishna.ghostblue.ui.settings.MaintenanceDraft
import com.saivenkatkrishna.ghostblue.ui.settings.MaintenancePreset
import com.saivenkatkrishna.ghostblue.ui.settings.remoteActivityRows
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rules behind the new screens: the month's charging, maintenance drafts, shortcut parsing and specs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OwnerLogicTest {
    private fun ev(vin: String = "VIN1") = BBVehicle().apply {
        this.vin = vin
        model = "IONIQ 5"
        odometer = Distance(8_500.0, Distance.Units.MILES)
        evStatus = VehicleStatus.EVStatus(false, 0.0, VehicleStatus.FuelRange(Distance(200.0, Distance.Units.MILES), 70.0), VehicleStatus.PlugType.AC_CHARGER)
    }

    private fun session(vin: String, start: Instant, kWh: Double, receipt: Double?) = ChargeSession(vin, start, 30.0, isDCFast = false).apply {
        endDate = start.plusSeconds(3_600)
        lastSampleDate = endDate!!
        endPercentage = 60.0
        manualEnergyKWh = kWh
        actualCost = receipt
    }

    @Test fun theMonthAddsThisMonthsSessionsAndProjectsToItsEnd() {
        val vehicle = ev().apply {
            monthlyChargingBudget = 40.0
            electricityRatePerKWh = 0.25
        }
        val now = Instant.parse("2026-11-06T12:00:00Z")
        val sessions = listOf(
            session("VIN1", Instant.parse("2026-11-02T03:00:00Z"), 10.0, receipt = 4.0),
            // No receipt: priced at the saved rate.
            session("VIN1", Instant.parse("2026-11-05T03:00:00Z"), 24.0, receipt = null),
            session("VIN1", Instant.parse("2026-10-30T03:00:00Z"), 50.0, receipt = 100.0),
            session("VIN2", Instant.parse("2026-11-03T03:00:00Z"), 50.0, receipt = 100.0),
        )
        val month = ChargingMonth.of(vehicle, sessions, now, ZoneOffset.UTC)
        assertEquals(10.0, month.cost, 1e-9)
        assertEquals(34.0, month.energyKWh, 1e-9)
        // Ten dollars in six days of a thirty-day month.
        assertEquals(50.0, month.projectedCost, 1e-9)
        assertEquals(0.25, month.budgetFraction!!, 1e-9)
        assertFalse(month.isOverBudget)
        assertTrue(month.isOnPaceToExceed)

        val over = ChargingMonth.of(vehicle.apply { monthlyChargingBudget = 8.0 }, sessions, now, ZoneOffset.UTC)
        assertTrue(over.isOverBudget)
        assertEquals(1.0, over.budgetFraction!!, 1e-9)
        assertNull(ChargingMonth.of(vehicle.apply { monthlyChargingBudget = null }, sessions, now, ZoneOffset.UTC).budgetFraction)
    }

    @Test fun aMaintenanceDraftNeedsATitleAndANumberForTheOdometer() {
        val draft = MaintenanceDraft.forNew(ev(), LocalDate.of(2026, 11, 6))
        assertEquals(LocalDate.of(2027, 5, 6), draft.dueDate)
        assertEquals("16000", draft.odometerText)
        assertTrue(draft.validationMessage!!.contains("Name"))
        assertNull(draft.copy(title = "Brake fluid").validationMessage)
        assertTrue(draft.copy(title = "Brakes", hasOdometer = true, odometerText = "lots").validationMessage!!.contains("odometer"))
        assertTrue(draft.copy(title = "Brakes", costText = "free").validationMessage!!.contains("cost"))
    }

    @Test fun editingTheDueDateOrOdometerMakesAReminderDueAgain() {
        val zone = ZoneId.of("America/Los_Angeles")
        val vehicle = ev()
        val reminder = MaintenanceReminder(vehicle.vin, "Inspection", dueDate = Instant.parse("2026-12-01T17:30:00Z")).apply {
            lastNotifiedAt = Instant.parse("2026-12-01T17:00:00Z")
        }
        val draft = MaintenanceDraft.forReminder(reminder, vehicle, zone)
        assertEquals(LocalDate.of(2026, 12, 1), draft.dueDate)

        // Only the notes change: the stored time and the notification stand.
        draft.copy(notes = "Bring the registration").applyTo(reminder, zone)
        assertEquals(Instant.parse("2026-12-01T17:30:00Z"), reminder.dueDate)
        assertEquals("Bring the registration", reminder.notes)
        assertTrue(reminder.lastNotifiedAt != null)

        // A new day is stored at noon there, and notifies again.
        draft.copy(dueDate = LocalDate.of(2027, 1, 15)).applyTo(reminder, zone)
        assertEquals(LocalDate.of(2027, 1, 15).atTime(LocalTime.NOON).atZone(zone).toInstant(), reminder.dueDate)
        assertNull(reminder.lastNotifiedAt)

        reminder.lastNotifiedAt = Instant.now()
        draft.copy(dueDate = LocalDate.of(2027, 1, 15), hasOdometer = true, odometerText = "20000", costText = "89.5").applyTo(reminder, zone)
        assertEquals(20_000.0, reminder.dueOdometer!!, 0.0)
        assertEquals(89.5, reminder.cost!!, 0.0)
        assertNull(reminder.lastNotifiedAt)
    }

    @Test fun quickAddUsesIosDistancesAndMonths() {
        val vehicle = ev()
        val now = Instant.parse("2026-11-06T18:00:00Z")
        val rotation = MaintenancePreset.TIRE_ROTATION.reminder(vehicle, now, ZoneOffset.UTC)
        assertEquals(16_000.0, rotation.dueOdometer!!, 0.0)
        assertNull(rotation.dueDate)
        val insurance = MaintenancePreset.INSURANCE_RENEWAL.reminder(vehicle, now, ZoneOffset.UTC)
        assertEquals(Instant.parse("2027-05-06T18:00:00Z"), insurance.dueDate)
        assertNull(insurance.dueOdometer)
        assertEquals("In 15,000 km", MaintenancePreset.CABIN_AIR_FILTER.subtitle(Distance.Units.KILOMETERS))
    }

    @Test fun theActivityFilterShowsOneCarOrAll() {
        val rows = listOf(
            RemoteCommandActivity("A", "Car A", "Lock", "Phone"),
            RemoteCommandActivity("B", "Car B", "Unlock", "Watch"),
        )
        assertEquals(listOf("A"), remoteActivityRows(rows, "A", allVehicles = false).map { it.vin })
        assertEquals(2, remoteActivityRows(rows, "A", allVehicles = true).size)
        assertEquals(2, remoteActivityRows(rows, null, allVehicles = false).size)
    }

    @Test fun commandsAreReadHoweverTheyreSpelled() {
        assertEquals(ShortcutCommand.START_CLIMATE, ShortcutCommand.parse("Start Climate"))
        assertEquals(ShortcutCommand.START_CHARGE, ShortcutCommand.parse("start-charging"))
        assertEquals(ShortcutCommand.SMART_CHARGING, ShortcutCommand.parse("reconcile_smart_charging"))
        assertEquals(ShortcutCommand.UNLOCK, ShortcutCommand.parse(" UNLOCK "))
        assertNull(ShortcutCommand.parse("open_trunk"))
        assertNull(ShortcutCommand.parse(""))

        val request = ShortcutRequest.from(
            Intent(ShortcutRequest.ACTION_COMMAND)
                .putExtra("command", "start_climate")
                .putExtra("vehicle", " Kia EV6 ")
                .putExtra("preset", "Winter"),
        )!!
        assertEquals(ShortcutRequest(ShortcutCommand.START_CLIMATE, "Kia EV6", "Winter"), request)
        // Assistant's App Actions parameter.
        assertEquals(ShortcutCommand.LOCK, ShortcutRequest.from(Intent().putExtra("feature", "lock"))!!.command)
        assertNull(ShortcutRequest.from(Intent()))
    }

    @Test fun dynamicShortcutsCoverClimateChargingAndTheOtherPresets() {
        val vehicle = ev()
        val selected = ClimatePreset().apply { name = "Default"; isSelected = true; sortOrder = 0 }
        val winter = ClimatePreset().apply { name = "Winter warm-up"; sortOrder = 1 }
        val specs = AppShortcuts.dynamicFor(vehicle, listOf(winter, selected))
        // Launchers show the first two dynamic shortcuts: climate on and off.
        assertEquals(listOf("climate_selected", "stop_climate", "start_charge", "stop_charge", "preset_${winter.id}"), specs.map { it.id })
        assertEquals("Start Default · IONIQ 5", specs.first().longLabel)
        assertEquals(selected.id.toString(), specs.first().presetId)
        val preset = specs.last()
        assertEquals("Start Winter warm-up · IONIQ 5", preset.longLabel)
        val intent = AppShortcuts.intent(ApplicationProvider.getApplicationContext(), preset)
        assertEquals("start_climate", intent.getStringExtra("command"))
        assertEquals("VIN1", intent.getStringExtra("vin"))
        assertEquals(winter.id.toString(), intent.getStringExtra("preset"))

        // A gas car has no charging shortcuts; no car, none at all.
        assertEquals(listOf("climate_selected", "stop_climate"), AppShortcuts.dynamicFor(ev().apply { evStatus = null }, emptyList()).map { it.id })
        assertTrue(AppShortcuts.dynamicFor(null, emptyList()).isEmpty())
    }
}
