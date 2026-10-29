package com.saivenkatkrishna.ghostblue

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.settings.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The main flows through the real screens, against the test car: signing
 * in, remote commands, each tab, settings, sessions and trips. Robolectric
 * runs them on the JVM, so they need no emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
class AppFlowsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun signingInWithTheTestAccountOpensTheGarage() {
        val graph = testGraph()
        compose.setApp(graph)
        compose.onNodeWithTag("connectButton").performClick()
        compose.onNodeWithTag("usernameField").performTextInput(TEST_USERNAME)
        compose.onNodeWithTag("passwordField").performTextInput(TEST_PASSWORD)
        // The test account always signs in to the test cars.
        compose.onNodeWithText("Using test account - fake data will be used").assertIsDisplayed()
        compose.onNodeWithTag("signInButton").performClick()

        compose.waitForTag("batteryPercent")
        compose.waitForText("Hyundai IONIQ 5")
        assertEquals(3, runBlocking { graph.db.vehicles().all() }.size)
    }

    @Test fun unlockAndLockFollowWhatTheCarReports() {
        val graph = testGraph().signInTestAccount()
        val vin = graph.garage.selectedVehicle.value!!.vin
        compose.setApp(graph)
        compose.waitForTag("unlockButton")
        compose.onNodeWithTag("unlockButton").performClick()
        compose.waitUntil(10_000) { runBlocking { graph.db.vehicles().byVin(vin) }?.lockStatus == VehicleStatus.LockStatus.UNLOCKED }
        compose.waitForText("Unlocked")

        compose.onNodeWithTag("lockButton").performClick()
        compose.waitUntil(10_000) { runBlocking { graph.db.vehicles().byVin(vin) }?.lockStatus == VehicleStatus.LockStatus.LOCKED }
        compose.waitForText("Locked")
        assertTrue(runBlocking { graph.db.remoteCommands().all() }.size >= 2)
    }

    @Test fun climateStopsAndStartsFromTheSplitButton() {
        val graph = testGraph().signInTestAccount()
        val vin = graph.garage.selectedVehicle.value!!.vin
        compose.setApp(graph)
        compose.waitForTag("climateButton")
        // The first test car starts with climate running.
        compose.onNodeWithTag("climateButton").performClick()
        compose.waitUntil(10_000) { runBlocking { graph.db.vehicles().byVin(vin) }?.climateStatus?.airControlOn == false }
        compose.waitForText("Climate off")
    }

    @Test fun eachTabShowsItsScreen() {
        val graph = testGraph().signInTestAccount()
        compose.setApp(graph)
        compose.waitForTag("homeContent")

        compose.onNodeWithTag("tab_LOCATION").performClick()
        compose.waitForTag("locationCard")
        compose.onNodeWithTag("vehicleMap").assertExists()

        compose.onNodeWithTag("tab_ENERGY").performClick()
        compose.waitForTag("chargingStatus")
        compose.onNodeWithTag("energyDelivered").assertExists()

        compose.onNodeWithTag("tab_TRIPS").performClick()
        // The test car makes up a week of trips.
        compose.waitForTag("tripsOverview")
        compose.onNodeWithTag("tripsList").performScrollToNode(hasTestTag("tripCard"))

        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.waitForTag("settingsList")
    }

    @Test fun kilometresChangeEveryDistance() {
        val graph = testGraph().signInTestAccount()
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("distance_1"))
        compose.onNodeWithTag("distance_1").performClick()
        assertEquals(Distance.Units.KILOMETERS, graph.settings.distanceUnit.value)

        compose.onNodeWithTag("tab_HOME").performClick()
        compose.waitForText(" km", substring = true)
    }

    @Test fun theRivianThemeSwapsInItsDashboard() {
        val graph = testGraph().signInTestAccount()
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("theme_1"))
        compose.onNodeWithTag("theme_1").performClick()
        assertEquals(AppTheme.RIVIAN, graph.settings.theme.value)

        compose.onNodeWithTag("tab_HOME").performClick()
        compose.waitForTag("rivianLock")
        compose.onNodeWithTag("tab_ENERGY").performClick()
        compose.waitForText("Energy")
    }

    @Test fun aManuallyAddedSessionAppearsInTheLog() {
        val graph = testGraph().signInTestAccount()
        val vin = graph.garage.selectedVehicle.value!!.vin
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_ENERGY").performClick()
        compose.waitForTag("addSession")
        compose.onNodeWithTag("addSession").performClick()
        compose.waitForTag("energyField")
        compose.onNodeWithTag("energyField").performTextInput("7.5")
        compose.onNodeWithTag("saveSession").performClick()

        compose.waitUntil(10_000) { runBlocking { graph.db.chargeSessions().forVin(vin) }.any { it.manualEnergyKWh == 7.5 } }
        compose.onNodeWithTag("energyList").performScrollToNode(hasTestTag("sessionCard"))
        compose.waitForText("7.5 kWh", substring = true)
    }

    @Test fun aTripCanBeRenamedAndCompared() {
        val graph = testGraph().signInTestAccount()
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_TRIPS").performClick()
        compose.waitForTag("tripsOverview")
        compose.onNodeWithTag("tripsList").performScrollToNode(hasTestTag("tripCard"))
        // A trip card's menu is a popup, which never reports idle under Robolectric; the rename it
        // starts goes through the same model call, and the card must show the new name.
        val trip = runBlocking { graph.db.trips().forVin(graph.garage.selectedVehicle.value!!.vin) }.first()
        graph.garage.renameTrip(trip, "  Commute  ")
        graph.garage.toggleFavorite(trip)
        compose.waitUntil(10_000) { runBlocking { graph.db.trips().all() }.any { it.customName == "Commute" && it.isFavorite } }
        // Favourites sort first, so the renamed trip leads the list.
        compose.onNodeWithTag("tripsList").performScrollToNode(hasText("Commute"))

        compose.onNodeWithTag("compareButton").performClick()
        compose.onAllNodes(hasTestTag("tripCard"))[0].performClick()
        compose.onAllNodes(hasTestTag("tripCard"))[1].performClick()
        compose.onNodeWithText("2 of 2 selected").assertExists()
        compose.onNodeWithTag("showComparison").performClick()
        compose.waitForTag("comparisonSheet")
        compose.onNodeWithText("Efficiency").assertExists()
    }

    @Test fun hidingTheLocationCoversTheMap() {
        val graph = testGraph().signInTestAccount()
        graph.settings.setHideVehicleLocation(true)
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_LOCATION").performClick()
        compose.waitForText("Vehicle Location Hidden")
        compose.onNodeWithText("Show location").performClick()
        compose.waitForTag("vehicleMap")
    }

    @Test fun backupsAreMadeAndMergedFromSettings() {
        val graph = testGraph().signInTestAccount()
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("backupRow"))
        compose.onNodeWithTag("backupRow").performClick()
        compose.waitForTag("exportBackup")
        compose.onNodeWithTag("importBackup").assertExists()
        compose.onNode(hasText("contains your saved credentials", substring = true)).assertExists()
    }

    @Test fun debugModeTurnsOnTheRequestLog() {
        val graph = testGraph().signInTestAccount()
        graph.settings.setDebugMode(true)
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("httpLogsRow"))
        compose.onNodeWithTag("httpLogsRow").performClick()
        compose.waitForTag("httpLogList")
        compose.onNodeWithTag("clearLogs").performClick()
        compose.waitForText("No HTTP Logs")
    }
}
