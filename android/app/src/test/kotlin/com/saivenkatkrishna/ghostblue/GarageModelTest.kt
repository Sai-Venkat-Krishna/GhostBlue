package com.saivenkatkrishna.ghostblue

import android.app.Application
import com.saivenkatkrishna.ghostblue.ui.CommandKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app's command buttons, with the car slow to confirm so a command is still waiting when the next tap comes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GarageModelTest {
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(10)
        }
    }

    @Test fun unlockWhileLockWaitsIsSentAndTheSameTapAgainStopsWaiting() {
        val graph = testGraph(commandWaitSeconds = 60).signInTestAccount()
        val garage = graph.garage
        val vin = garage.selectedVehicle.value!!.vin
        fun sent() = runBlocking { graph.db.remoteCommands().all() }.map { it.commandName }

        garage.unlock(vin)
        waitFor { garage.commandState(vin, CommandKind.LOCK).running && sent() == listOf("Unlock") }

        // The other action on the same control replaces the waiting one, rather than only cancelling it.
        garage.lock(vin)
        waitFor { sent() == listOf("Unlock", "Lock") }
        waitFor { garage.commandState(vin, CommandKind.LOCK).running }

        // The same action again stops waiting and sends nothing.
        garage.lock(vin)
        waitFor { !garage.commandState(vin, CommandKind.LOCK).running }
        Thread.sleep(200)
        assertEquals(listOf("Unlock", "Lock"), sent())
        assertFalse(garage.commandState(vin, CommandKind.LOCK).running)
    }
}
