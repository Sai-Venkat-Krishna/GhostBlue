package com.saivenkatkrishna.ghostblue

import android.app.AlarmManager
import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.saivenkatkrishna.ghostblue.background.CommandWorker
import com.saivenkatkrishna.ghostblue.background.DueReminder
import com.saivenkatkrishna.ghostblue.background.ReminderScheduler
import com.saivenkatkrishna.ghostblue.background.RefreshWorker
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.background.SmartChargingScheduler
import com.saivenkatkrishna.ghostblue.background.SmartChargingWorker
import com.saivenkatkrishna.ghostblue.data.charging.SDGESmartChargingSchedule
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.MaintenanceReminder
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomation
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomationKind
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.notifications.chargingProgressText
import com.saivenkatkrishna.ghostblue.settings.AppSettings
import com.saivenkatkrishna.ghostblue.settings.WidgetRefreshInterval
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.widgets.CommandCenter
import com.saivenkatkrishna.ghostblue.widgets.CommandFeedback
import com.saivenkatkrishna.ghostblue.widgets.WidgetAccent
import com.saivenkatkrishna.ghostblue.widgets.WovenArt
import com.saivenkatkrishna.ghostblue.widgets.WovenContent
import com.saivenkatkrishna.ghostblue.widgets.rangeParts
import com.saivenkatkrishna.ghostblue.widgets.timeLeft
import com.saivenkatkrishna.ghostblue.widgets.WidgetState
import com.saivenkatkrishna.ghostblue.widgets.WidgetVehicle
import com.saivenkatkrishna.ghostblue.widgets.WidgetVehicles
import com.saivenkatkrishna.ghostblue.widgets.chargeTileLook
import com.saivenkatkrishna.ghostblue.widgets.climateTileLook
import com.saivenkatkrishna.ghostblue.widgets.dialColor
import com.saivenkatkrishna.ghostblue.widgets.lockTileLook
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app under test: an in-memory graph whose commands don't wait, and no scheduled work of its own. */
class TestGhostBlueApp : GhostBlueApplication() {
    override fun createGraph(): AppGraph {
        val prefs = getSharedPreferences("test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        return AppGraph(this, GhostBlueDatabase.inMemory(this), AppSettings(prefs), commandWaitSeconds = 0)
    }

    override fun startBackgroundWork() {}
}

/** Phase 4: widget and tile commands, background refresh, smart charging, reminders and what they show. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostBlueApp::class)
class BackgroundTest {
    private val context = ApplicationProvider.getApplicationContext<TestGhostBlueApp>()
    private val graph get() = context.graph
    private val now = Instant.parse("2026-10-28T18:00:00Z")

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun signedIn(): AppGraph = graph.signInTestAccount()

    private fun firstVehicle(): BBVehicle = runBlocking { graph.db.vehicles().all().first() }

    @Test fun aWidgetCommandRunsAndTheCarConfirms() = runTest {
        signedIn()
        val vin = firstVehicle().vin
        val worker = TestListenableWorkerBuilder<CommandWorker>(context)
            .setInputData(command(vin, RemoteAction.UNLOCK))
            .build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        assertEquals(VehicleStatus.LockStatus.UNLOCKED, graph.db.vehicles().byVin(vin)!!.lockStatus)
        assertTrue(graph.db.remoteCommands().all().any { it.commandName == "Unlock" })
        // The widgets and tiles show the result too, for when notifications aren't allowed.
        assertEquals("Unlocked", CommandFeedback.forControl(vin, RemoteAction.UNLOCK.control)?.text)
        val widget = WidgetVehicle.from(firstVehicle(), Distance.Units.MILES)
        assertEquals("Unlocked", widget.copy(commandText = CommandFeedback.latest(CommandFeedback.all.value, vin)?.text).statusLine)
        assertTrue(widget.statusLine.contains("Updated"))
    }

    private fun command(vin: String, action: RemoteAction, enqueuedAt: Long = System.currentTimeMillis()) = workDataOf(
        CommandWorker.KEY_VIN to vin,
        CommandWorker.KEY_ACTION to action.name,
        CommandWorker.KEY_ENQUEUED_AT to enqueuedAt,
    )

    @Test fun aCommandThatWaitedTooLongIsNotSent() = runTest {
        signedIn()
        val vin = firstVehicle().vin
        val worker = TestListenableWorkerBuilder<CommandWorker>(context)
            .setInputData(command(vin, RemoteAction.UNLOCK, enqueuedAt = System.currentTimeMillis() - 10 * 60_000))
            .build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Failure)
        assertEquals(VehicleStatus.LockStatus.LOCKED, graph.db.vehicles().byVin(vin)!!.lockStatus)
        assertTrue(graph.db.remoteCommands().all().none { it.commandName == "Unlock" })
        assertTrue(CommandFeedback.forControl(vin, RemoteAction.UNLOCK.control)!!.text.startsWith("Not sent"))
    }

    @Test fun lockAndClimateFromWidgetsDontReplaceEachOther() {
        assertNotEquals(CommandWorker.uniqueName("VIN", RemoteAction.LOCK), CommandWorker.uniqueName("VIN", RemoteAction.START_CLIMATE))
        assertEquals(CommandWorker.uniqueName("VIN", RemoteAction.LOCK), CommandWorker.uniqueName("VIN", RemoteAction.UNLOCK))
    }

    @Test fun aCommandForAnUnknownCarFails() = runTest {
        val worker = TestListenableWorkerBuilder<CommandWorker>(context)
            .setInputData(command("NOPE", RemoteAction.LOCK))
            .build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Failure)
    }

    @Test fun backgroundRefreshReadsStaleCarsAndKeepsFreshOnes() = runTest {
        signedIn()
        val vehicle = firstVehicle()
        graph.garage.editVehicle(vehicle.vin) { it.lastUpdated = Instant.now().minusSeconds(6 * 3600) }
        Thread.sleep(200)
        val worker = TestListenableWorkerBuilder<RefreshWorker>(context).build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        val refreshed = graph.db.vehicles().byVin(vehicle.vin)!!
        assertTrue(refreshed.lastUpdated!! > Instant.now().minusSeconds(60))
    }

    @Test fun refreshesComeEveryTenMinutesWhilePluggedIn() {
        val plugged = BBVehicle().apply {
            evStatus = VehicleStatus.EVStatus(false, 0.0, VehicleStatus.FuelRange(Distance(200.0, Distance.Units.MILES), 60.0), VehicleStatus.PlugType.AC_CHARGER)
        }
        val parked = BBVehicle()
        assertEquals(4 * 3600.0, RefreshWorker.nextDelaySeconds(listOf(parked), WidgetRefreshInterval.FOUR_HOURS), 0.0)
        assertEquals(600.0, RefreshWorker.nextDelaySeconds(listOf(parked, plugged), WidgetRefreshInterval.FOUR_HOURS), 0.0)
    }

    @Test fun theAppStartingDoesNotPushTheRefreshBack() {
        fun car(updatedSecondsAgo: Long?) = BBVehicle().apply { lastUpdated = updatedSecondsAgo?.let { now.minusSeconds(it) } }
        val interval = 4 * 3600.0
        // Counted from the stalest car's update, not from when the app last started.
        assertEquals(interval - 3600, RefreshWorker.dueInSeconds(listOf(car(600), car(3600)), interval, now), 0.0)
        // Overdue, or never read: soon, but not straight away.
        assertEquals(60.0, RefreshWorker.dueInSeconds(listOf(car(5 * 3600)), interval, now), 0.0)
        assertEquals(60.0, RefreshWorker.dueInSeconds(listOf(car(null)), interval, now), 0.0)
        assertEquals(interval, RefreshWorker.dueInSeconds(emptyList(), interval, now), 0.0)
    }

    @Test fun smartChargingIsScheduledJustAfterTheNextBoundary() = runTest {
        signedIn()
        assertNull(SmartChargingScheduler.nextRun(graph.db, now))
        val vin = firstVehicle().vin
        graph.garage.editVehicle(vin) { it.smartChargingEnabled = true }
        Thread.sleep(200)
        // 11 AM Pacific: the 10 AM boundary hasn't had its run yet, so it runs now.
        assertEquals(now, SmartChargingScheduler.nextRun(graph.db, now))
        val boundary = SDGESmartChargingSchedule.transitions(now, count = 1).first().date
        assertEquals(boundary.plusSeconds(60), SmartChargingScheduler.nextRun(graph.db, now, afterRun = true))
        graph.garage.editVehicle(vin) { it.smartChargingLastRunAt = now }
        Thread.sleep(200)
        assertEquals(boundary.plusSeconds(60), SmartChargingScheduler.nextRun(graph.db, now))

        SmartChargingScheduler.schedule(context)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertNotNull(alarms.nextScheduledAlarm)
    }

    @Test fun theSmartChargingWorkerRecordsEachRun() = runTest {
        signedIn()
        val vin = firstVehicle().vin
        graph.garage.editVehicle(vin) {
            it.smartChargingEnabled = true
            it.smartChargingHomeOnly = false
        }
        Thread.sleep(200)
        val worker = TestListenableWorkerBuilder<SmartChargingWorker>(context).build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        val vehicle = graph.db.vehicles().byVin(vin)!!
        assertNotNull(vehicle.smartChargingLastRunAt)
        assertNotNull(vehicle.smartChargingLastResult)
    }

    @Test fun preconditionRemindersRepeatDailyAndPostOnce() = runTest {
        signedIn()
        val vin = firstVehicle().vin
        val zone = ZoneOffset.UTC
        assertEquals(Instant.parse("2026-10-29T07:30:00Z"), ReminderScheduler.nextDaily(7, 30, now, zone))
        assertEquals(Instant.parse("2026-10-28T19:00:00Z"), ReminderScheduler.nextDaily(19, 0, now, zone))

        val automation = VehicleAutomation(vin, "Precondition", VehicleAutomationKind.PRECONDITION_REMINDER, hour = 17, minute = 45)
        graph.db.automations().insert(automation)
        // Fifteen minutes late (a dozing phone) still counts; it posts once.
        val due = ReminderScheduler.due(graph.db, now, zone)
        assertEquals(1, due.size)
        assertTrue(due.single() is DueReminder.Precondition)
        // Due and not yet posted: the alarm is now, not tomorrow, whatever reschedules it.
        assertEquals(now, ReminderScheduler.nextAlarm(graph.db, now, zone))
        automation.lastTriggeredAt = now
        graph.db.automations().update(automation)
        assertTrue(ReminderScheduler.due(graph.db, now, zone).isEmpty())
        assertEquals(Instant.parse("2026-10-29T17:45:00Z"), ReminderScheduler.upcoming(graph.db, now, zone).first().at)
        assertEquals(Instant.parse("2026-10-29T17:45:00Z"), ReminderScheduler.nextAlarm(graph.db, now, zone))
    }

    @Test fun datedMaintenanceIsDueAtNineOnItsDay() = runTest {
        signedIn()
        val vin = firstVehicle().vin
        val zone = ZoneOffset.UTC
        graph.db.maintenanceReminders().insert(MaintenanceReminder(vin, "Tire rotation", dueDate = Instant.parse("2026-11-02T00:00:00Z")))
        val next = ReminderScheduler.upcoming(graph.db, now, zone).single()
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), next.at)
        assertTrue(ReminderScheduler.due(graph.db, now, zone).isEmpty())
        assertEquals(1, ReminderScheduler.due(graph.db, Instant.parse("2026-11-02T09:00:30Z"), zone).size)
    }

    @Test fun quietHoursWrapPastMidnight() {
        val settings = graph.settings
        settings.setQuietHours(true, start = 22, end = 7)
        assertTrue(settings.isQuietHour(23))
        assertTrue(settings.isQuietHour(3))
        assertFalse(settings.isQuietHour(12))
        settings.setQuietHours(false)
        assertFalse(settings.isQuietHour(23))
    }

    @Test fun theChargingNotificationShowsChargeSpeedAndFinish() {
        val vehicle = BBVehicle().apply {
            model = "IONIQ 5"
            lastUpdated = now
            evStatus = VehicleStatus.EVStatus(
                charging = true,
                chargeSpeed = 11.0,
                evRange = VehicleStatus.FuelRange(Distance(150.0, Distance.Units.MILES), 50.0),
                plugType = VehicleStatus.PlugType.AC_CHARGER,
                chargeTimeSeconds = 3900,
                targetSocAC = 80.0,
            )
        }
        val text = chargingProgressText(vehicle, Distance.Units.MILES, now)!!
        assertEquals("Charging · IONIQ 5 · AC", text.title)
        assertTrue(text.text, text.text.startsWith("50% · 11.0 kW · 1 hr, 5 min to 80%"))
        assertEquals(80, text.target)
        vehicle.evStatus = vehicle.evStatus!!.copy(charging = false)
        assertNull(chargingProgressText(vehicle, Distance.Units.MILES, now))
    }

    @Test fun tilesShowTheCarAndSendTheOpposite() {
        val vehicle = BBVehicle().apply {
            model = "EV6"
            lockStatus = VehicleStatus.LockStatus.LOCKED
            climateStatus = VehicleStatus.ClimateStatus(false, true, false, com.saivenkatkrishna.ghostblue.kit.model.Temperature(70.0, com.saivenkatkrishna.ghostblue.kit.model.Temperature.Units.FAHRENHEIT))
            evStatus = VehicleStatus.EVStatus(false, 0.0, VehicleStatus.FuelRange(Distance(200.0, Distance.Units.MILES), 60.0), VehicleStatus.PlugType.UNPLUGGED)
        }
        assertEquals(RemoteAction.UNLOCK, lockTileLook(vehicle).action)
        assertTrue(lockTileLook(vehicle).active)
        assertEquals(RemoteAction.STOP_CLIMATE, climateTileLook(vehicle).action)
        assertNull(chargeTileLook(vehicle).action)
        assertNull(lockTileLook(null).action)
        assertTrue(RemoteAction.UNLOCK.needsDeviceUnlock)
        assertFalse(RemoteAction.LOCK.needsDeviceUnlock)
    }

    @Test fun widgetsFollowAPinnedCarThenTheSelectedOne() {
        val a = BBVehicle().apply { vin = "A" }
        val b = BBVehicle().apply { vin = "B" }
        val hidden = BBVehicle().apply { vin = "H"; isHidden = true }
        assertEquals("B", WidgetVehicles.resolve(listOf(a, b), pinned = "B", selected = "A")?.vin)
        assertEquals("A", WidgetVehicles.resolve(listOf(a, b), pinned = "gone", selected = "A")?.vin)
        assertEquals("A", WidgetVehicles.resolve(listOf(hidden, a), pinned = "H", selected = null)?.vin)
        assertEquals(dialColor(15, charging = false), dialColor(20, charging = false))
    }

    private fun chargingIoniq() = BBVehicle().apply {
        vin = "VIN1"
        model = "2025 IONIQ 5"
        lockStatus = VehicleStatus.LockStatus.LOCKED
        lastUpdated = now
        odometer = Distance(9789.0, Distance.Units.MILES)
        evStatus = VehicleStatus.EVStatus(
            charging = true,
            chargeSpeed = 6.9,
            evRange = VehicleStatus.FuelRange(Distance(253.0, Distance.Units.MILES), 87.0),
            plugType = VehicleStatus.PlugType.AC_CHARGER,
            chargeTimeSeconds = 4320,
            targetSocAC = 90.0,
        )
    }

    @Test fun theWovenWidgetShowsChargingAndBothLockButtons() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(340.dp, 380.dp))
        provideComposable { WovenContent(WidgetState(WidgetVehicle.from(chargingIoniq(), Distance.Units.MILES, now), image = null, rivian = false)) }
        onNode(hasText("253")).assertExists()
        onNode(hasText("RANGE")).assertExists()
        onNode(hasText("87%")).assertExists()
        onNode(hasText("Charging · AC · 6.9 kW")).assertExists()
        onNode(hasText("1h 12m left")).assertExists()
        onNode(hasText("Target 90%")).assertExists()
        // Both, as on iOS, whatever the car reports.
        onNode(hasTextEqualTo("Lock")).assertExists()
        onNode(hasTextEqualTo("Unlock")).assertExists()
    }

    @Test fun theWovenWidgetsRivianVersionFillsTheGoldButtonThatChangesTheState() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(340.dp, 380.dp))
        // Plugged in, so the charging row shows too: the pills must still fit Glance's ten children a column.
        val pluggedIn = chargingIoniq().apply { evStatus = evStatus!!.copy(charging = false) }
        provideComposable { WovenContent(WidgetState(WidgetVehicle.from(pluggedIn, Distance.Units.MILES, now), image = null, rivian = true)) }
        onNode(hasText("Plugged in · ${Format.time(now)}")).assertExists()
        onNode(hasText("Target 90%")).assertExists()
        onNode(hasText("87")).assertExists()
        onNode(hasTextEqualTo("Lock")).assertExists()
        onNode(hasTextEqualTo("Unlock")).assertExists()
    }

    @Test fun theWovenLetteringAndCaptionsReadAsOnIOS() {
        val vehicle = WidgetVehicle.from(chargingIoniq(), Distance.Units.MILES, now)
        assertEquals("IONIQ 5", WovenArt.wordmark(vehicle))
        assertEquals("IONIQ" to "5", WovenArt.parts("IONIQ 5"))
        assertEquals("KONA" to "", WovenArt.parts("KONA"))
        assertEquals("253" to "mi", rangeParts("253 mi"))
        assertEquals("--" to "", rangeParts("--"))
        assertEquals("1h 12m left", timeLeft(4320))
        assertEquals("12m left", timeLeft(720))
        assertEquals("12 min left", timeLeft(720, shortMinutes = false))
        assertNull(timeLeft(30))
    }

    @Test fun theCommandCenterWidgetShowsTheCarAndItsButtons() = runGlanceAppWidgetUnitTest {
        val vehicle = BBVehicle().apply {
            vin = "VIN1"
            model = "IONIQ 5"
            lockStatus = VehicleStatus.LockStatus.LOCKED
            lastUpdated = now
            evStatus = VehicleStatus.EVStatus(false, 0.0, VehicleStatus.FuelRange(Distance(250.0, Distance.Units.MILES), 84.0), VehicleStatus.PlugType.UNPLUGGED)
        }
        setContext(context)
        setAppWidgetSize(DpSize(260.dp, 120.dp))
        provideComposable { CommandCenter(WidgetState(WidgetVehicle.from(vehicle, Distance.Units.MILES, now), image = null, rivian = false)) }
        onNode(hasText("IONIQ 5")).assertExists()
        onNode(hasText("84%")).assertExists()
        onNode(hasContentDescriptionEqualTo("Unlock")).assertExists()
        onNode(hasContentDescriptionEqualTo("Start climate")).assertExists()
    }

    @Test fun withoutACarTheWidgetSaysSo() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(120.dp, 120.dp))
        provideComposable { CommandCenter(WidgetState(null, null, rivian = false)) }
        onNode(hasText("No Vehicle")).assertExists()
    }
}
