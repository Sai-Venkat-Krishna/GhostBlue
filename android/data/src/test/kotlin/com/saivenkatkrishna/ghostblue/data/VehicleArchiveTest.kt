package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ChargingLocationType
import com.saivenkatkrishna.ghostblue.data.model.TripRecord
import com.saivenkatkrishna.ghostblue.data.vehicle.archiveTrips
import com.saivenkatkrishna.ghostblue.data.vehicle.updateStatus
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus.PlugType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Port of `VehicleArchiveTests`. */
@RunWith(RobolectricTestRunner::class)
class VehicleArchiveTest {
    private lateinit var db: GhostBlueDatabase

    @Before fun setUp() {
        db = testDatabase()
    }

    @After fun tearDown() = db.close()

    private fun makeTrip(
        startDate: Instant = Instant.ofEpochSecond(1_700_000_000),
        odometer: Double = 12_345.0,
        distance: Double = 42.5,
    ) = EVTripDetail(distance, odometer, 220, 7_400, 1_100, 700, 5_900, 100, startDate, 3_600, 38.0, 72.0)

    @Test fun tripRecordPreservesEveryTripField() {
        val trip = makeTrip()
        val record = TripRecord("TESTVIN", trip)
        assertEquals("TESTVIN", record.vin)
        assertEquals(trip, record.tripDetail)
    }

    @Test fun tripKeyDeduplicatesTheSameVehicleAndTrip() {
        val trip = makeTrip()
        assertEquals(TripRecord.key("TESTVIN", trip), TripRecord.key("TESTVIN", trip))
        assertNotEquals(TripRecord.key("TESTVIN", trip), TripRecord.key("OTHERVIN", trip))
        assertNotEquals(TripRecord.key("TESTVIN", trip), TripRecord.key("TESTVIN", makeTrip(odometer = trip.odometer + 1)))
        // Swift's "\(vin)-\(Int(startDate.timeIntervalSince1970))-\(odometer)".
        assertEquals("TESTVIN-1700000000-12345.0", TripRecord.key("TESTVIN", trip))
    }

    @Test fun tripUserMetadataPersistsWithoutChangingAPITrip() = runTest {
        val record = TripRecord("TESTVIN", makeTrip()).apply {
            customName = "Morning commute"
            isFavorite = true
            startLocationName = "Home"
            endLocationName = "Office"
            startLatitude = 37.3349
            startLongitude = -122.0090
            endLatitude = 37.7749
            endLongitude = -122.4194
        }
        db.trips().insert(record)

        val saved = db.trips().forVin("TESTVIN").single()
        assertEquals("Morning commute", saved.customName)
        assertTrue(saved.isFavorite)
        assertEquals("Home", saved.startLocationName)
        assertEquals("Office", saved.endLocationName)
        assertEquals(37.3349, saved.startLatitude)
        assertEquals(-122.0090, saved.startLongitude)
        assertEquals(37.7749, saved.endLatitude)
        assertEquals(-122.4194, saved.endLongitude)
        assertEquals(42.5, saved.tripDetail.distance, 0.0)
    }

    @Test fun archiveKeepsOldTripsAndDeduplicatesTheRollingAPIWindow() = runTest {
        val vehicle = quietVehicle("TESTVIN", model = "Test EV").also { db.vehicles().insert(it) }
        val firstTrip = makeTrip()
        val laterTrip = makeTrip(firstTrip.startDate.plusSeconds(3_600), firstTrip.odometer + 28, 28.0)
        vehicle.archiveTrips(listOf(firstTrip, firstTrip), db)
        vehicle.archiveTrips(listOf(firstTrip, laterTrip), db)

        val records = db.trips().forVin("TESTVIN")
        assertEquals("Overlapping API windows must never duplicate saved trips", 2, records.size)
        assertEquals(2, records.map { it.tripKey }.toSet().size)
    }

    @Test fun chargeSessionMeaningfulRulesAndPercentGain() {
        val start = Instant.ofEpochSecond(1_700_000_000)
        val session = ChargeSession("TESTVIN", start, 50.0, isDCFast = false)
        assertTrue(session.isActive)
        assertTrue(session.isMeaningful)

        session.endDate = start.plusSeconds(120)
        session.lastSampleDate = start.plusSeconds(120)
        assertFalse(session.isActive)
        assertFalse(session.isMeaningful)

        session.endPercentage = 53.0
        assertEquals(3.0, session.percentAdded, 0.0)
        assertTrue(session.isMeaningful)

        session.endPercentage = 45.0
        assertEquals("A stale status must never create negative added charge", 0.0, session.percentAdded, 0.0)
    }

    @Test fun manualChargeSessionCanBeCreatedAndItsDatesEdited() = runTest {
        val originalStart = Instant.ofEpochSecond(1_700_000_000)
        val originalEnd = originalStart.plusSeconds(3_600)
        val session = ChargeSession("TESTVIN", originalStart, 20.0, isDCFast = false).apply {
            pendingStopDate = originalEnd
            pendingStopObservedAt = originalEnd
            pendingStopPercentage = 45.0
            applyUserEdits(
                startDate = originalStart, endDate = originalEnd, startPercentage = 20.0, endPercentage = 45.0,
                energyAddedKWh = 18.4, maxChargeSpeedKW = 11.2, isDCFast = false, locationType = ChargingLocationType.HOME,
                electricityRatePerKWh = 0.128, actualCost = 2.36,
            )
        }
        db.chargeSessions().insert(session)

        var saved = db.chargeSessions().all().single()
        assertEquals(originalStart, saved.startDate)
        assertEquals(originalEnd, saved.endDate)
        assertEquals(originalEnd, saved.lastSampleDate)
        assertEquals(20.0, saved.startPercentage, 0.0)
        assertEquals(45.0, saved.endPercentage, 0.0)
        assertEquals(18.4, saved.energyAddedKWh, 0.0)
        assertEquals(11.2, saved.maxChargeSpeedKW, 0.0)
        assertEquals(ChargingLocationType.HOME, saved.locationType)
        assertEquals(0.128, saved.electricityRatePerKWh)
        assertEquals(2.36, saved.actualCost)
        assertNull(saved.pendingStopDate)
        assertFalse(saved.isActive)

        val correctedStart = originalStart.minusSeconds(900)
        val correctedEnd = originalEnd.plusSeconds(1_200)
        saved.applyUserEdits(
            startDate = correctedStart, endDate = correctedEnd, startPercentage = 18.0, endPercentage = 48.0,
            energyAddedKWh = 21.0, maxChargeSpeedKW = 10.8, isDCFast = false, locationType = ChargingLocationType.HOME,
            electricityRatePerKWh = 0.128, actualCost = null,
        )
        db.chargeSessions().update(saved)

        saved = db.chargeSessions().all().single()
        assertEquals(correctedStart, saved.startDate)
        assertEquals(correctedEnd, saved.endDate)
        assertEquals(correctedEnd, saved.lastSampleDate)
        assertEquals(30.0, saved.percentAdded, 0.0)
        assertNull(saved.actualCost)
    }

    @Test fun chargeSessionPreservesExactStartEndAndUpdateTimes() = runTest {
        val vehicle = quietVehicle("TESTVIN", model = "Test EV").also { db.vehicles().insert(it) }
        val startedAt = epoch(1_700_000_000.125)
        val updatedAt = epoch(1_700_000_125.500)
        val endedAt = epoch(1_700_000_360.875)

        vehicle.updateStatus(makeVehicleStatus(charging = true, percentage = 20.0, syncDate = startedAt), db)
        vehicle.updateStatus(makeVehicleStatus(charging = true, percentage = 25.0, syncDate = updatedAt), db)

        var session = db.chargeSessions().all().single()
        assertEquals(startedAt, session.startDate)
        assertNull(session.endDate)
        assertEquals(updatedAt, session.lastSampleDate)

        vehicle.updateStatus(makeVehicleStatus(charging = false, percentage = 30.0, syncDate = endedAt), db)
        session = db.chargeSessions().all().single()
        assertNull("One non-charging response must remain an unconfirmed stop", session.endDate)
        assertEquals(updatedAt, session.lastSampleDate)

        vehicle.updateStatus(makeVehicleStatus(charging = false, percentage = 30.0, syncDate = endedAt.plusSeconds(60)), db)
        session = db.chargeSessions().all().single()
        assertEquals(startedAt, session.startDate)
        assertEquals(endedAt, session.endDate)
        assertEquals(endedAt, session.lastSampleDate)
    }

    @Test fun chargeSessionIgnoresStaleStopAndSurvivesTransientFalseStatus() = runTest {
        val vehicle = quietVehicle("TESTVIN", model = "Test EV").also { db.vehicles().insert(it) }
        val startedAt = Instant.ofEpochSecond(1_700_000_000)
        val chargingUpdate = startedAt.plusSeconds(120)
        vehicle.updateStatus(makeVehicleStatus(charging = true, percentage = 20.0, syncDate = startedAt), db)
        vehicle.updateStatus(makeVehicleStatus(charging = true, percentage = 22.0, syncDate = chargingUpdate), db)

        // A cached status older than the latest charging sample does nothing.
        vehicle.updateStatus(makeVehicleStatus(charging = false, percentage = 20.0, syncDate = startedAt.plusSeconds(60)), db)
        var session = db.chargeSessions().all().single()
        assertTrue(session.isActive)
        assertEquals(chargingUpdate, session.lastSampleDate)

        // A fresh false response becomes a candidate, but charging resuming
        // keeps the same session instead of closing and creating a second one.
        vehicle.updateStatus(makeVehicleStatus(charging = false, percentage = 23.0, syncDate = startedAt.plusSeconds(180)), db)
        session = db.chargeSessions().all().single()
        assertTrue(session.isActive)
        assertEquals(chargingUpdate, session.lastSampleDate)

        val resumedAt = startedAt.plusSeconds(240)
        vehicle.updateStatus(makeVehicleStatus(charging = true, percentage = 24.0, syncDate = resumedAt), db)
        val sessions = db.chargeSessions().all()
        session = sessions.single()
        assertTrue(session.isActive)
        assertEquals(resumedAt, session.lastSampleDate)
        assertEquals(24.0, session.endPercentage, 0.0)
    }

    /**
     * Non-charging samples stay plugged in by default: the ambiguous blip the
     * stop debounce exists for.
     */
    private fun makeVehicleStatus(charging: Boolean, percentage: Double, syncDate: Instant, pluggedIn: Boolean = true) =
        vehicleStatus(
            "TESTVIN",
            evStatus(
                charging = charging, percentage = percentage,
                plugType = if (charging || pluggedIn) PlugType.AC_CHARGER else PlugType.UNPLUGGED, chargeTimeSeconds = 3_600,
            ),
            syncDate = syncDate,
        )

    @Test fun chargeSpeedHistoryIsPersistedAndBounded() = runTest {
        val start = Instant.ofEpochSecond(1_700_000_000)
        val session = ChargeSession("TESTVIN", start, 20.0, isDCFast = true)
        for (index in 0 until 125) {
            session.appendSpeedSample(start.plusSeconds(index * 60L), index.toDouble(), 20 + index / 10.0)
        }
        db.chargeSessions().insert(session)

        val saved = db.chargeSessions().all().single()
        assertEquals(120, saved.speedSamples.size)
        assertEquals(5.0, saved.speedSamples.first().speedKW, 0.0)
        assertEquals(124.0, saved.speedSamples.last().speedKW, 0.0)
    }
}
