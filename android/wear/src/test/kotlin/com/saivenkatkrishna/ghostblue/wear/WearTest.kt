package com.saivenkatkrishna.ghostblue.wear

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.wear.complication.ComplicationContent
import com.saivenkatkrishna.ghostblue.wear.complication.VehicleComplicationService
import com.saivenkatkrishna.ghostblue.wear.tile.VehicleTileService
import com.saivenkatkrishna.ghostblue.wear.tile.tileContent
import com.saivenkatkrishna.ghostblue.wear.ui.WearApp
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The watch app under test: an in-memory database, its own settings, commands that don't wait, no phone sync. */
class TestWearApp : WearApplication() {
    override fun createGraph(): WearGraph {
        val prefs = getSharedPreferences("wear-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        return WearGraph(this, GhostBlueDatabase.inMemory(this), WearSettings(prefs), commandWaitSeconds = 0)
    }

    override fun startGraph() {}
}

/** The watch: signing in, the car's controls, settings, the tile and the complications. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestWearApp::class, qualifiers = "w227dp-h227dp-round-watch-xhdpi")
class WearTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<TestWearApp>()
    private val graph get() = context.graph

    private fun signIn() {
        val error = runBlocking { graph.model.signIn("testaccount@betterblue.com", "betterblue", "", Brand.FAKE, Region.USA) }
        check(error == null) { "Sign-in failed: $error" }
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun theTestAccountSignsInFromTheWatch() {
        compose.setContent { WearApp(graph, remoteImages = false) }
        waitForTag("emptyState")
        compose.onNodeWithTag("signInButton").performClick()
        waitForTag("testAccountButton")
        compose.onNodeWithTag("testAccountButton").performClick()
        waitForTag("vehiclePage")
        assertEquals(3, runBlocking { graph.db.vehicles().all() }.size)
        assertNotNull(graph.settings.selectedVin.value)
    }

    @Test fun lockingFromTheWatchFollowsTheCar() {
        signIn()
        val vin = runBlocking { graph.db.vehicles().all().first() }.vin
        compose.setContent { WearApp(graph, remoteImages = false) }
        waitForTag("lockControl")
        // The first test car starts locked, so the button unlocks.
        compose.onNodeWithTag("lockControl").performClick()
        compose.waitUntil(10_000) { runBlocking { graph.db.vehicles().byVin(vin) }?.lockStatus == VehicleStatus.LockStatus.UNLOCKED }
    }

    @Test fun aCommandIsRunningAsSoonAsItsSent() {
        signIn()
        val vin = runBlocking { graph.db.vehicles().all().first() }.vin
        // The tile reads the commands straight after sending, to draw the progress.
        graph.model.send(vin, WatchControl.LOCK, RemoteAction.UNLOCK)
        assertEquals(true, graph.model.commands.value[vin to WatchControl.LOCK]?.running)
    }

    @Test fun settingsSwitchToKilometres() {
        signIn()
        compose.setContent { WearApp(graph, remoteImages = false) }
        waitForTag("settingsButton")
        compose.onNodeWithTag("settingsButton").performClick()
        waitForTag("kilometres")
        compose.onNodeWithTag("kilometres").performClick()
        assertEquals(Distance.Units.KILOMETERS, graph.settings.distanceUnit.value)
    }

    private fun vehicle(locked: Boolean, climate: Boolean) = BBVehicle().apply {
        vin = "VIN1"
        model = "IONIQ 5"
        lockStatus = if (locked) VehicleStatus.LockStatus.LOCKED else VehicleStatus.LockStatus.UNLOCKED
        climateStatus = VehicleStatus.ClimateStatus(false, climate, false, com.saivenkatkrishna.ghostblue.kit.model.Temperature(70.0, com.saivenkatkrishna.ghostblue.kit.model.Temperature.Units.FAHRENHEIT))
        evStatus = VehicleStatus.EVStatus(false, 0.0, VehicleStatus.FuelRange(Distance(252.0, Distance.Units.MILES), 84.0), VehicleStatus.PlugType.UNPLUGGED)
    }

    @Test fun theTileShowsTheCarAndTheOppositeCommands() {
        val content = tileContent(vehicle(locked = true, climate = true), Distance.Units.MILES, command = null)
        assertEquals("IONIQ 5", content.title)
        assertEquals("84%", content.percent)
        assertEquals("252 mi · Locked", content.detail)
        assertEquals(RemoteAction.UNLOCK, content.lockAction)
        assertEquals(RemoteAction.STOP_CLIMATE, content.climateAction)
        assertNull(tileContent(null, Distance.Units.MILES, null).lockAction)

        val device = DeviceParametersBuilders.DeviceParameters.Builder()
            .setScreenWidthDp(227).setScreenHeightDp(227).setScreenDensity(2f)
            .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
            .build()
        assertNotNull(VehicleTileService.layout(context, device, content))
    }

    @Test fun complicationsShowBatteryAndRange() {
        val content = ComplicationContent.from(vehicle(locked = true, climate = false), Distance.Units.MILES)
        val ranged = VehicleComplicationService.data(context, ComplicationType.RANGED_VALUE, content) as RangedValueComplicationData
        assertEquals(84f, ranged.value)
        assertTrue(VehicleComplicationService.data(context, ComplicationType.SHORT_TEXT, content) is ShortTextComplicationData)
        assertTrue(VehicleComplicationService.data(context, ComplicationType.LONG_TEXT, content) is LongTextComplicationData)
        assertEquals("IONIQ 5 · 84% · 252 mi", content.longText)
        assertNull(VehicleComplicationService.data(context, ComplicationType.SMALL_IMAGE, content))
    }
}
