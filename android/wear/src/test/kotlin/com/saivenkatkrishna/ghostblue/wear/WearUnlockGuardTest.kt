package com.saivenkatkrishna.ghostblue.wear

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.wear.tile.VehicleTileService
import com.saivenkatkrishna.ghostblue.wear.tile.tileContent
import com.saivenkatkrishna.ghostblue.wear.ui.WearApp
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A watch with no screen lock: the tile's Unlock asks in the app instead of unlocking the car in one tap. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestWearApp::class, qualifiers = "w227dp-h227dp-round-watch-xhdpi")
class WearUnlockGuardTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<TestWearApp>()

    /** A watch graph whose screen lock is [secure]. */
    private fun watch(secure: Boolean): WearGraph {
        val prefs = context.getSharedPreferences("wear-guard-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val graph = WearGraph(context, GhostBlueDatabase.inMemory(context), WearSettings(prefs), commandWaitSeconds = 0, isDeviceSecure = { secure })
        val error = runBlocking { graph.model.signIn("testaccount@betterblue.com", "betterblue", "", Brand.FAKE, Region.USA) }
        check(error == null) { "Sign-in failed: $error" }
        return graph
    }

    private fun lockedCar() = BBVehicle().apply {
        vin = "VIN1"
        model = "IONIQ 5"
        lockStatus = VehicleStatus.LockStatus.LOCKED
    }

    @Test fun theTileOffersUnlockInTheAppWithoutAScreenLock() {
        assertTrue(tileContent(lockedCar(), Distance.Units.MILES, null, deviceSecure = false).confirmUnlockInApp)
        assertFalse(tileContent(lockedCar(), Distance.Units.MILES, null, deviceSecure = true).confirmUnlockInApp)
        // Locking needs no check.
        val unlocked = lockedCar().apply { lockStatus = VehicleStatus.LockStatus.UNLOCKED }
        assertFalse(tileContent(unlocked, Distance.Units.MILES, null, deviceSecure = false).confirmUnlockInApp)
    }

    @Test fun aTileUnlockFromAWatchWithNoScreenLockIsRefused() {
        val graph = watch(secure = false)
        val vin = runBlocking { graph.db.vehicles().all() }.first().vin
        assertFalse(VehicleTileService.handleClick(graph, RemoteAction.UNLOCK.name, vin))
        assertNull(graph.model.commands.value[vin to WatchControl.LOCK])
        assertTrue(VehicleTileService.handleClick(graph, RemoteAction.LOCK.name, vin))
    }

    @Test fun aTileUnlockFromALockedWatchGoes() {
        val graph = watch(secure = true)
        val vin = runBlocking { graph.db.vehicles().all() }.first().vin
        assertTrue(VehicleTileService.handleClick(graph, RemoteAction.UNLOCK.name, vin))
        assertEquals(RemoteAction.UNLOCK, graph.model.commands.value[vin to WatchControl.LOCK]?.action)
    }

    @Test fun theAppAsksBeforeUnlockingForTheTile() {
        val graph = watch(secure = false)
        val vin = runBlocking { graph.db.vehicles().all() }.first().vin
        graph.unlockToConfirm.value = vin
        compose.setContent { WearApp(graph, remoteImages = false) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("confirmUnlock")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("confirmUnlock").performClick()
        compose.waitUntil(10_000) { runBlocking { graph.db.vehicles().byVin(vin) }?.lockStatus == VehicleStatus.LockStatus.UNLOCKED }
        assertNull(graph.unlockToConfirm.value)
    }
}
