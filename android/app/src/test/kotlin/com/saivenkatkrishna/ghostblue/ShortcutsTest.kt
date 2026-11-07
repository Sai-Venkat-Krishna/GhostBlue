package com.saivenkatkrishna.ghostblue

import android.app.KeyguardManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.saivenkatkrishna.ghostblue.background.CommandWorker
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.shortcuts.ShortcutActivity
import com.saivenkatkrishna.ghostblue.shortcuts.ShortcutCommand
import com.saivenkatkrishna.ghostblue.shortcuts.ShortcutPlan
import com.saivenkatkrishna.ghostblue.shortcuts.ShortcutPlanner
import com.saivenkatkrishna.ghostblue.shortcuts.ShortcutRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Launcher shortcuts and the automation intent: which car and preset, who may send, and Unlock's phone check. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostBlueApp::class)
class ShortcutsTest {
    private val context = ApplicationProvider.getApplicationContext<TestGhostBlueApp>()
    private val graph get() = context.graph

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun cars(): List<BBVehicle> = runBlocking { graph.db.vehicles().all() }

    private fun plan(command: ShortcutCommand, vehicle: String? = null, preset: String? = null) = runBlocking {
        ShortcutPlanner.plan(ShortcutRequest(command, vehicle, preset), graph.db, graph.settings.selectedVin.value)
    }

    @Test fun withoutAnAccountTheresNothingToSendTo() {
        assertEquals(ShortcutPlan.Refused("Sign in to GhostBlue first."), plan(ShortcutCommand.LOCK))
    }

    @Test fun theNamedCarElseTheSelectedOne() {
        graph.signInTestAccount()
        val (ioniq, _, ev6) = cars()
        graph.settings.setSelectedVin(ev6.vin)
        assertEquals(ev6.vin, (plan(ShortcutCommand.LOCK) as ShortcutPlan.Command).vehicle.vin)
        assertEquals(ioniq.vin, (plan(ShortcutCommand.LOCK, vehicle = "hyundai ioniq 5") as ShortcutPlan.Command).vehicle.vin)
        assertEquals(ioniq.vin, (plan(ShortcutCommand.REFRESH, vehicle = ioniq.vin) as ShortcutPlan.Refresh).vehicle.vin)
        assertTrue(plan(ShortcutCommand.LOCK, vehicle = "Model T") is ShortcutPlan.Refused)
    }

    @Test fun chargingNeedsAnEvAndSmartChargingNeedsItOn() {
        graph.signInTestAccount()
        val sonata = cars()[1]
        assertTrue(plan(ShortcutCommand.START_CHARGE, vehicle = sonata.vin) is ShortcutPlan.Refused)
        assertEquals(ShortcutPlan.Refused("No vehicles have Smart Charging enabled in GhostBlue."), plan(ShortcutCommand.SMART_CHARGING))
        runBlocking { graph.db.vehicles().update(cars()[0].apply { smartChargingEnabled = true }) }
        assertEquals(1, (plan(ShortcutCommand.SMART_CHARGING) as ShortcutPlan.SmartCharging).vehicles.size)
    }

    private fun addPreset(vehicle: BBVehicle, name: String, temperature: Double): ClimatePreset = runBlocking {
        ClimatePreset().apply {
            this.name = name
            iconName = "snowflake"
            vehicleId = vehicle.id
            sortOrder = 5
            climateOptions = ClimateOptions(temperature = Temperature(temperature, Temperature.Units.FAHRENHEIT))
        }.also { graph.db.climatePresets().insert(it) }
    }

    @Test fun aPresetIsFoundByNameOnItsOwnCar() {
        graph.signInTestAccount()
        val (ioniq, _, ev6) = cars()
        val cool = addPreset(ioniq, "Cool Down", 64.0)
        val command = plan(ShortcutCommand.START_CLIMATE, vehicle = ioniq.vin, preset = "cool down") as ShortcutPlan.Command
        assertEquals(cool.id, command.preset!!.id)
        assertTrue(plan(ShortcutCommand.START_CLIMATE, vehicle = ev6.vin, preset = "Cool Down") is ShortcutPlan.Refused)
        // Without one, Start climate uses the car's selected preset, as iOS's Start Vehicle Climate.
        assertEquals(null, (plan(ShortcutCommand.START_CLIMATE, vehicle = ioniq.vin) as ShortcutPlan.Command).preset)
    }

    @Test fun aPresetCommandStartsClimateWithThatPreset() = runTest {
        graph.signInTestAccount()
        val ev6 = cars()[2]
        val cool = addPreset(ev6, "Cool Down", 64.0)
        val worker = TestListenableWorkerBuilder<CommandWorker>(context)
            .setInputData(
                workDataOf(
                    CommandWorker.KEY_VIN to ev6.vin,
                    CommandWorker.KEY_ACTION to RemoteAction.START_CLIMATE.name,
                    CommandWorker.KEY_ENQUEUED_AT to System.currentTimeMillis(),
                    CommandWorker.KEY_PRESET_ID to cool.id.toString(),
                ),
            )
            .build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        val status = graph.db.vehicles().byVin(ev6.vin)!!.climateStatus!!
        assertTrue(status.airControlOn)
        assertEquals(64.0, status.temperature.value, 0.01)
        assertTrue(graph.db.remoteCommands().all().any { it.commandName == "Start climate" })
    }

    private fun launch(intent: Intent): ActivityController<ShortcutActivity> =
        Robolectric.buildActivity(ShortcutActivity::class.java, intent).create().start().resume().visible()
            .windowFocusChanged(true).also(::settle)

    /** The plan reads Room off the main thread; let it finish. */
    private fun settle(controller: ActivityController<ShortcutActivity>) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (controller.get().isFinishing) return
            Thread.sleep(10)
        }
    }

    private fun commandWork(vin: String, control: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("command-$vin-$control").get()

    private fun shortcutIntent(command: String) = Intent(ShortcutRequest.ACTION_SHORTCUT)
        .setClassName(context, ShortcutActivity::class.java.name)
        .putExtra(ShortcutRequest.EXTRA_COMMAND, command)

    private fun automationIntent(command: String) = Intent(ShortcutRequest.ACTION_COMMAND)
        .setClassName(context, "com.saivenkatkrishna.ghostblue.shortcuts.AutomationCommandActivity")
        .putExtra(ShortcutRequest.EXTRA_COMMAND, command)

    @Test fun aLauncherShortcutQueuesTheCommandLikeAWidget() {
        graph.signInTestAccount()
        val vin = graph.garage.selectedVehicle.value!!.vin
        val activity = launch(shortcutIntent("lock")).get()
        assertTrue(activity.isFinishing)
        assertEquals(1, commandWork(vin, "lock").size)
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Locking"))
    }

    @Test fun otherAppsMustBeAllowedFirst() {
        graph.signInTestAccount()
        val vin = graph.garage.selectedVehicle.value!!.vin
        launch(automationIntent("lock"))
        assertEquals("Allow Assistant and automation apps in GhostBlue › Settings first.", ShadowToast.getTextOfLatestToast())
        assertTrue(commandWork(vin, "lock").isEmpty())

        graph.settings.setAllowAutomation(true)
        launch(automationIntent("lock"))
        assertEquals(1, commandWork(vin, "lock").size)
    }

    @Test fun unlockWaitsForThePhoneToBeUnlocked() {
        graph.signInTestAccount()
        val vin = graph.garage.selectedVehicle.value!!.vin
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        val controller = launch(shortcutIntent("unlock"))
        assertFalse("waiting for the PIN", controller.get().isFinishing)
        assertTrue(commandWork(vin, "lock").isEmpty())

        // The owner unlocks the phone: the command goes.
        keyguard.setKeyguardLocked(false)
        settle(controller)
        assertTrue(controller.get().isFinishing)
        assertEquals(1, commandWork(vin, "lock").size)
    }

    @Test fun unlockIsDroppedIfThePhoneStaysLocked() {
        graph.signInTestAccount()
        val vin = graph.garage.selectedVehicle.value!!.vin
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        val controller = launch(shortcutIntent("unlock"))
        // Dismissing the PIN prompt cancels.
        keyguard.setKeyguardLocked(true)
        settle(controller)
        assertTrue(controller.get().isFinishing)
        assertEquals(ShortcutActivity.UNLOCK_NEEDS_PHONE, ShadowToast.getTextOfLatestToast())
        assertTrue(commandWork(vin, "lock").isEmpty())
    }
}
