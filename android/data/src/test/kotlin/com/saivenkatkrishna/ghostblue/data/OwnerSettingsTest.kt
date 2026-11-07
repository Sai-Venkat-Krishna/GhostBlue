package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.account.RemoteCommandLog
import com.saivenkatkrishna.ghostblue.data.charging.chargingTariff
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ChargingLocationType
import com.saivenkatkrishna.ghostblue.data.model.CommandActivityState
import com.saivenkatkrishna.ghostblue.data.model.RemoteCommandActivity
import com.saivenkatkrishna.ghostblue.data.vehicle.chargingCost
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The owner's charging prices as the Charging Costs sheet now edits them
 * (on-peak rate, off-peak hours), priced the way iOS's `ChargingTariff`
 * does, and the command log's upkeep.
 */
@RunWith(RobolectricTestRunner::class)
class OwnerSettingsTest {
    private lateinit var db: GhostBlueDatabase

    @Before fun setUp() {
        db = testDatabase()
    }

    @After fun tearDown() = db.close()

    private fun homeSession(vin: String, from: Instant, to: Instant, kWh: Double) =
        ChargeSession(vin, from, 40.0, isDCFast = false).apply {
            endDate = to
            lastSampleDate = to
            endPercentage = 60.0
            manualEnergyKWh = kWh
            locationType = ChargingLocationType.HOME
        }

    @Test fun ownOffPeakHoursWrapPastMidnightAndIgnoreTheSdgePeakRate() {
        val zone = ZoneId.of("America/Chicago")
        val vehicle = quietVehicle("VIN-HOURS").apply {
            electricityRatePerKWh = 0.30
            offPeakElectricityRatePerKWh = 0.10
            peakElectricityRatePerKWh = 0.55
            offPeakStartHour = 21
            offPeakEndHour = 7
        }
        val tariff = vehicle.chargingTariff(ChargingLocationType.HOME, isDCFast = false, deviceZone = zone)!!
        assertEquals(21, tariff.offPeakStartHour)
        assertEquals(7, tariff.offPeakEndHour)
        // iOS only charges the on-peak rate on SDG&E's schedule.
        assertEquals(0.30, tariff.rate(LocalDateTime.of(2026, 11, 4, 17, 0).atZone(zone).toInstant()), 1e-9)
        assertEquals(0.10, tariff.rate(LocalDateTime.of(2026, 11, 4, 23, 0).atZone(zone).toInstant()), 1e-9)
        assertEquals(0.10, tariff.rate(LocalDateTime.of(2026, 11, 5, 6, 59).atZone(zone).toInstant()), 1e-9)

        // 20:00–22:00: half the energy before the window, half inside it.
        val session = homeSession(
            vehicle.vin,
            LocalDateTime.of(2026, 11, 4, 20, 0).atZone(zone).toInstant(),
            LocalDateTime.of(2026, 11, 4, 22, 0).atZone(zone).toInstant(),
            kWh = 10.0,
        ).apply { this.tariff = tariff }
        assertEquals(5 * 0.30 + 5 * 0.10, vehicle.chargingCost(session)!!, 1e-9)
    }

    @Test fun sdgeOnPeakRateAppliesFromFourToNine() {
        val vehicle = quietVehicle("VIN-PEAK").apply {
            electricityRatePerKWh = 0.30
            offPeakElectricityRatePerKWh = 0.12
            peakElectricityRatePerKWh = 0.62
            smartChargingEnabled = true
        }
        val tariff = vehicle.chargingTariff(ChargingLocationType.HOME, isDCFast = false)!!
        assertEquals("America/Los_Angeles", tariff.timeZoneIdentifier)
        // Wednesday 2026-11-04: 2–4 PM is neither super off-peak nor on-peak; 4–9 PM is on-peak.
        assertEquals(0.30, tariff.rate(sanDiego(2026, 11, 4, 15)), 1e-9)
        assertEquals(0.62, tariff.rate(sanDiego(2026, 11, 4, 16)), 1e-9)
        assertEquals(0.62, tariff.rate(sanDiego(2026, 11, 4, 20, 59)), 1e-9)
        assertEquals(0.30, tariff.rate(sanDiego(2026, 11, 4, 21)), 1e-9)
        assertEquals(0.12, tariff.rate(sanDiego(2026, 11, 4, 11)), 1e-9)

        val session = homeSession(vehicle.vin, sanDiego(2026, 11, 4, 15), sanDiego(2026, 11, 4, 17), kWh = 10.0)
            .apply { this.tariff = tariff }
        assertEquals(5 * 0.30 + 5 * 0.62, vehicle.chargingCost(session)!!, 1e-9)

        // Without an on-peak rate, those hours fall back to the other-charging rate.
        vehicle.peakElectricityRatePerKWh = null
        val noPeak = vehicle.chargingTariff(ChargingLocationType.HOME, isDCFast = false)!!
        assertEquals(0.30, noPeak.rate(sanDiego(2026, 11, 4, 17)), 1e-9)
        // A session keeps the tariff it started with.
        assertEquals(5 * 0.30 + 5 * 0.62, vehicle.chargingCost(session)!!, 1e-9)
    }

    @Test fun theCommandLogKeepsAYearAndAtMostFiveHundredRows() = runTest {
        val now = Instant.parse("2026-11-06T18:00:00Z")
        val commands = db.remoteCommands()
        repeat(RemoteCommandLog.MAX_ROWS + 10) { index ->
            commands.insert(
                RemoteCommandActivity("VIN1", "IONIQ 5", "Lock", "Phone").apply {
                    startedAt = now.minusSeconds(3_600L * (index + 1))
                    finish(CommandActivityState.SUCCEEDED, at = startedAt)
                },
            )
        }
        commands.insert(RemoteCommandActivity("VIN1", "IONIQ 5", "Unlock", "Phone").apply { startedAt = now.minusSeconds(400L * 86_400) })

        val removed = RemoteCommandLog.prune(db, now)
        assertEquals(11, removed)
        val left = commands.all()
        assertEquals(RemoteCommandLog.MAX_ROWS, left.size)
        assertTrue(left.none { it.commandName == "Unlock" })
        // The newest are kept.
        assertEquals(now.minusSeconds(3_600), left.maxOf { it.startedAt })
    }

    @Test fun aCommandLeftSendingIsClosedButOneInFlightIsNot() = runTest {
        val now = Instant.parse("2026-11-06T18:00:00Z")
        val commands = db.remoteCommands()
        val stale = RemoteCommandActivity("VIN1", "IONIQ 5", "Start climate", "Phone").apply { startedAt = now.minusSeconds(20 * 60) }
        val live = RemoteCommandActivity("VIN1", "IONIQ 5", "Lock", "Phone").apply { startedAt = now.minusSeconds(60) }
        commands.insert(stale)
        commands.insert(live)

        RemoteCommandLog.prune(db, now)
        val rows = commands.all().associateBy { it.id }
        assertEquals(CommandActivityState.FAILED, rows.getValue(stale.id).state)
        assertEquals(RemoteCommandLog.UNFINISHED_MESSAGE, rows.getValue(stale.id).errorMessage)
        assertEquals(now, rows.getValue(stale.id).completedAt)
        assertEquals(CommandActivityState.SENDING, rows.getValue(live.id).state)
        assertNull(rows.getValue(live.id).completedAt)
    }
}
