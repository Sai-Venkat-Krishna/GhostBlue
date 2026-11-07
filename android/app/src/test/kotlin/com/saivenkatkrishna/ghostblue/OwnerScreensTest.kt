package com.saivenkatkrishna.ghostblue

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.saivenkatkrishna.ghostblue.data.model.CommandActivityState
import com.saivenkatkrishna.ghostblue.data.model.RemoteCommandActivity
import java.time.Instant
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

/**
 * The screens ported from iOS's ownership hub, through the real UI against
 * the test cars: charging prices and the budget, the remote activity log,
 * and maintenance reminders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
class OwnerScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun AppGraph.vehicle(vin: String) = runBlocking { db.vehicles().byVin(vin) }!!

    @Test fun thePeakRateOffPeakHoursAndBudgetAreSavedFromChargingCosts() {
        val graph = testGraph().signInTestAccount()
        val vin = graph.garage.selectedVehicle.value!!.vin
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_ENERGY").performClick()
        compose.waitForTag("costsButton")
        compose.onNodeWithTag("costsButton").performClick()
        compose.waitForTag("costSheet")
        compose.onNodeWithTag("chargingMonth").assertExists()

        compose.onNodeWithTag("peakRateField").performScrollTo().performTextInput("0.62")
        compose.waitUntil(5_000) { graph.vehicle(vin).peakElectricityRatePerKWh == 0.62 }

        compose.onNodeWithTag("offPeakStartLater").performScrollTo().performClick()
        compose.waitUntil(5_000) { graph.vehicle(vin).offPeakStartHour == 1 }

        compose.onNodeWithTag("budgetField").performScrollTo().performTextInput("50")
        compose.waitUntil(5_000) { graph.vehicle(vin).monthlyChargingBudget == 50.0 }
        compose.waitForTag("budgetProgress")

        // Cleared, it's off again.
        compose.onNodeWithTag("budgetField").performTextReplacement("")
        compose.waitUntil(5_000) { graph.vehicle(vin).monthlyChargingBudget == null }
    }

    private fun AppGraph.logCommand(vin: String, name: String, command: String, state: CommandActivityState, error: String? = null) =
        runBlocking {
            db.remoteCommands().insert(
                RemoteCommandActivity(vin, name, command, "Phone").apply {
                    startedAt = Instant.now().minusSeconds(120)
                    finish(state, error?.let(::IllegalStateException))
                },
            )
        }

    @Test fun remoteActivityListsEveryCommandAndClears() {
        val graph = testGraph().signInTestAccount()
        val cars = runBlocking { graph.db.vehicles().all() }
        graph.logCommand(cars[0].vin, cars[0].displayName, "Unlock", CommandActivityState.SUCCEEDED)
        graph.logCommand(cars[0].vin, cars[0].displayName, "Start climate", CommandActivityState.FAILED, "Vehicle did not respond")
        graph.logCommand(cars[2].vin, cars[2].displayName, "Lock", CommandActivityState.SUCCEEDED)
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("remoteActivityRow"))
        compose.onNodeWithTag("remoteActivityRow").performClick()
        compose.waitForTag("activityList")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("activityRow").fetchSemanticsNodes().size == 3 }
        compose.waitForText("Vehicle did not respond")

        compose.onNodeWithTag("clearActivity").performClick()
        compose.onNodeWithTag("confirmClearActivity").performClick()
        compose.waitForTag("noActivity")
        assertTrue(runBlocking { graph.db.remoteCommands().all() }.isEmpty())
    }

    @Test fun aCarsActivityShowsItsOwnCommandsWithAWayToAll() {
        val graph = testGraph().signInTestAccount()
        val cars = runBlocking { graph.db.vehicles().all() }
        graph.logCommand(cars[0].vin, cars[0].displayName, "Unlock", CommandActivityState.SUCCEEDED)
        graph.logCommand(cars[2].vin, cars[2].displayName, "Lock", CommandActivityState.SUCCEEDED)
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasText(cars[0].displayName))
        compose.onAllNodes(hasText(cars[0].displayName))[0].performClick()
        compose.waitForTag("vehicleActivityRow")
        compose.onNodeWithTag("vehicleActivityRow").performClick()
        compose.waitForTag("activityList")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("activityRow").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("allVehicles").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("activityRow").fetchSemanticsNodes().size == 2 }
    }

    private fun openMaintenance(graph: AppGraph, name: String) {
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasText(name))
        compose.onAllNodes(hasText(name))[0].performClick()
        compose.waitForTag("maintenanceRow")
        compose.onNodeWithTag("maintenanceRow").performClick()
        compose.waitForTag("maintenanceList")
    }

    @Test fun maintenanceRemindersAreAddedEditedCompletedAndDeleted() {
        val graph = testGraph().signInTestAccount()
        val car = runBlocking { graph.db.vehicles().all() }.first()
        openMaintenance(graph, car.displayName)
        compose.waitForTag("noReminders")

        // iOS's Quick add: 7,500 past the odometer.
        compose.onNodeWithTag("maintenanceList").performScrollToNode(hasTestTag("preset_TIRE_ROTATION"))
        compose.onNodeWithTag("preset_TIRE_ROTATION").performClick()
        compose.waitUntil(5_000) { runBlocking { graph.db.maintenanceReminders().forVin(car.vin) }.isNotEmpty() }
        val rotation = runBlocking { graph.db.maintenanceReminders().forVin(car.vin) }.single()
        assertEquals("Tire rotation", rotation.title)
        assertEquals(car.odometer.length + 7_500, rotation.dueOdometer!!, 0.01)
        assertNull(rotation.dueDate)

        // Opened, it's edited in place.
        compose.onNodeWithTag("maintenanceList").performScrollToNode(hasTestTag("reminderRow"))
        compose.onNodeWithTag("reminderRow").performClick()
        compose.waitForTag("reminderEditor")
        compose.onNodeWithTag("reminderTitle").performTextReplacement("Tire rotation and alignment")
        compose.onNodeWithTag("reminderCost").performScrollTo().performTextInput("45")
        compose.onNodeWithTag("saveReminder").performClick()
        compose.waitUntil(5_000) {
            runBlocking { graph.db.maintenanceReminders().byId(rotation.id) }?.let { it.title == "Tire rotation and alignment" && it.cost == 45.0 } == true
        }
        compose.waitForTag("serviceCost")

        compose.onNodeWithTag("reminderDone").performClick()
        compose.waitUntil(5_000) { runBlocking { graph.db.maintenanceReminders().byId(rotation.id) }?.isCompleted == true }

        // One of the owner's own, due in six months by default.
        compose.onNodeWithTag("addReminder").performClick()
        compose.waitForTag("reminderEditor")
        compose.onNodeWithTag("reminderTitle").performTextInput("Wiper blades")
        compose.onNodeWithTag("saveReminder").performClick()
        compose.waitUntil(5_000) { runBlocking { graph.db.maintenanceReminders().forVin(car.vin) }.size == 2 }
        val wipers = runBlocking { graph.db.maintenanceReminders().forVin(car.vin) }.first { it.title == "Wiper blades" }
        assertNotNull(wipers.dueDate)
        assertTrue(wipers.dueDate!! > Instant.now().plusSeconds(150L * 86_400))

        // Deleted from its editor.
        compose.onAllNodes(hasText("Wiper blades"))[0].performClick()
        compose.waitForTag("deleteReminder")
        compose.onNodeWithTag("deleteReminder").performScrollTo().performClick()
        compose.onNode(hasText("Delete") and !hasTestTag("deleteReminder")).performClick()
        compose.waitUntil(5_000) { runBlocking { graph.db.maintenanceReminders().forVin(car.vin) }.size == 1 }
    }
}
