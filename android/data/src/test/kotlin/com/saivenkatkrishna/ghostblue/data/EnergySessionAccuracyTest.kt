package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.account.RoomFakeVehicleStore
import com.saivenkatkrishna.ghostblue.data.charging.BatteryCapacityCatalog
import com.saivenkatkrishna.ghostblue.data.charging.ChargeStopPolicy
import com.saivenkatkrishna.ghostblue.data.charging.ChargingDisplayState
import com.saivenkatkrishna.ghostblue.data.charging.VehicleRefreshPolicy
import com.saivenkatkrishna.ghostblue.data.charging.chargingDisplayState
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomation
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomationKind
import com.saivenkatkrishna.ghostblue.data.vehicle.effectiveBatteryCapacityKWh
import com.saivenkatkrishna.ghostblue.data.vehicle.energyDelivered
import com.saivenkatkrishna.ghostblue.data.vehicle.energySource
import com.saivenkatkrishna.ghostblue.data.vehicle.isUsingModelDefaultBatteryCapacity
import com.saivenkatkrishna.ghostblue.data.vehicle.updateStatus
import com.saivenkatkrishna.ghostblue.kit.api.fake.StoreFakeVehicleProvider
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus.PlugType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Session detection and energy accounting behind the Energy tab. Port of `EnergySessionAccuracyTests`. */
@RunWith(RobolectricTestRunner::class)
class EnergySessionAccuracyTest {
    private val vin = "TEST-ENERGY"
    private val t0 = Instant.ofEpochSecond(1_790_000_000)
    private lateinit var db: GhostBlueDatabase

    @Before fun setUp() {
        db = testDatabase()
    }

    @After fun tearDown() = db.close()

    private suspend fun vehicle(model: String = "IONIQ 5", fuelType: FuelType = FuelType.ELECTRIC): BBVehicle =
        quietVehicle(vin, model, fuelType, odometer = 1000.0).also { db.vehicles().insert(it) }

    private fun status(
        charging: Boolean,
        at: Instant,
        fetchedAt: Instant? = null,
        percentage: Double,
        plugType: PlugType = PlugType.AC_CHARGER,
        speed: Double? = null,
        odometer: Double = 1000.0,
    ) = vehicleStatus(
        vin,
        evStatus(
            charging = charging,
            percentage = percentage,
            chargeSpeed = speed ?: if (charging) 7.2 else 0.0,
            rangeMiles = percentage * 2.5,
            plugType = plugType,
            chargeTimeSeconds = if (charging) 3600 else 0,
            targetSocAC = 80.0,
            targetSocDC = 90.0,
        ),
        syncDate = at,
        lastUpdated = fetchedAt ?: at,
        location = VehicleStatus.Location(32.7, -117.1),
        odometer = Distance(odometer, Distance.Units.MILES),
    )

    private suspend fun sessions(): List<ChargeSession> = db.chargeSessions().all()

    // Stop detection

    @Test fun unplugEndsSessionOnFirstSample() = runTest {
        val car = vehicle()
        car.updateStatus(status(true, t0, percentage = 40.0), db)
        val unplugged = t0.plusSeconds(1800)
        val accepted = car.updateStatus(status(false, unplugged, percentage = 52.0, plugType = PlugType.UNPLUGGED), db)

        assertEquals(false, accepted.evStatus?.charging)
        assertEquals(false, accepted.evStatus?.pluggedIn)
        assertNull(car.pendingChargingStopDate)
        assertNotEquals(ChargingDisplayState.CONFIRMING_STOP, car.chargingDisplayState(unplugged))
        val session = sessions().first()
        assertEquals(unplugged, session.endDate)
        assertEquals(52.0, session.endPercentage, 0.0)
    }

    @Test fun reachingTargetEndsSessionOnFirstSample() = runTest {
        val car = vehicle()
        car.updateStatus(status(true, t0, percentage = 60.0), db)
        val done = t0.plusSeconds(3600)
        car.updateStatus(status(false, done, percentage = 80.0), db)

        assertNull(car.pendingChargingStopDate)
        assertEquals(ChargingDisplayState.COMPLETED, car.chargingDisplayState(done))
        assertEquals(done, sessions().first().endDate)
    }

    @Test fun unchallengedCachedStopConfirmsAfterDelay() = runTest {
        val car = vehicle()
        car.updateStatus(status(true, t0, percentage = 40.0), db)
        val stopAt = t0.plusSeconds(600)
        // BlueLink keeps serving the same snapshot: same sync date, later fetches.
        car.updateStatus(status(false, stopAt, fetchedAt = stopAt, percentage = 45.0), db)
        car.updateStatus(status(false, stopAt, fetchedAt = stopAt.plusSeconds(300), percentage = 45.0), db)
        assertNotNull(car.pendingChargingStopDate)
        assertTrue(sessions().first().isActive)

        car.updateStatus(
            status(false, stopAt, fetchedAt = stopAt.plusSeconds(ChargeStopPolicy.CONFIRMATION_DELAY + 1), percentage = 45.0),
            db,
        )
        assertNull(car.pendingChargingStopDate)
        assertEquals(false, car.evStatus?.charging)
        assertEquals("The stop is dated by the snapshot, not by when it was confirmed", stopAt, sessions().first().endDate)
    }

    @Test fun malformedZeroPercentStopIsNotDefinitive() {
        val zero = evStatus(charging = false, percentage = 0.0, rangeMiles = 0.0, plugType = PlugType.UNPLUGGED)
        assertFalse(ChargeStopPolicy.isDefinitive(zero))
    }

    // Session boundaries

    @Test fun longPollingGapKeepsOneSessionAndItsEnergy() = runTest {
        val car = vehicle()
        car.updateStatus(status(true, t0, percentage = 30.0), db)
        car.updateStatus(status(true, t0.plusSeconds(8 * 3600), percentage = 70.0), db)

        val all = sessions()
        assertEquals(1, all.size)
        assertEquals(40.0, all[0].percentAdded, 0.0)
        assertEquals(77.4 * 0.4, car.energyDelivered(all[0]), 0.001)
    }

    @Test fun drivingBetweenChargingSamplesStartsNewSession() = runTest {
        val car = vehicle()
        car.updateStatus(status(true, t0, percentage = 30.0, odometer = 1000.0), db)
        car.updateStatus(status(true, t0.plusSeconds(3 * 3600), percentage = 55.0, odometer = 1042.0), db)

        val all = sessions()
        assertEquals(2, all.size)
        assertEquals(t0, all[0].endDate)
        assertNull(all[1].endDate)
        assertEquals(55.0, all[1].startPercentage, 0.0)
    }

    @Test fun chargeLostBetweenSamplesStartsNewSession() = runTest {
        val car = vehicle()
        car.updateStatus(status(true, t0, percentage = 60.0), db)
        car.updateStatus(status(true, t0.plusSeconds(5 * 3600), percentage = 41.0), db)
        assertEquals(2, sessions().size)
    }

    // Energy

    @Test fun heldChargerReadingCannotExceedChargeAdded() {
        val session = ChargeSession(vin, t0, 92.0, isDCFast = false)
        session.endPercentage = 92.0
        session.energyAddedKWh = 110.36
        assertEquals(1.2, session.resolvedEnergyKWh(null), 0.001)
        assertEquals(0.774, session.resolvedEnergyKWh(77.4), 0.001)
    }

    @Test fun powerIsIntegratedAsTrapezoidAndConservativelyAcrossLongGaps() = runTest {
        val car = vehicle(model = "Unknown EV")
        car.updateStatus(status(true, t0, percentage = 40.0, speed = 0.0), db)
        car.updateStatus(status(true, t0.plusSeconds(600), percentage = 41.0, speed = 10.0), db)
        assertEquals(10.0 / 2 * 600 / 3600, sessions().first().energyAddedKWh, 0.0001)

        car.updateStatus(status(true, t0.plusSeconds(600 + 7200), percentage = 60.0, speed = 4.0), db)
        assertEquals(0.8333 + 4 * 2, sessions().first().energyAddedKWh, 0.001)
    }

    @Test fun modelDefaultCapacityIsUsedOnlyWithoutOwnerValue() = runTest {
        val car = vehicle()
        assertEquals(77.4, car.effectiveBatteryCapacityKWh)
        assertTrue(car.isUsingModelDefaultBatteryCapacity)
        car.batteryCapacityKWh = 84.0
        assertEquals(84.0, car.effectiveBatteryCapacityKWh)
        assertFalse(car.isUsingModelDefaultBatteryCapacity)

        val session = ChargeSession(vin, t0, 20.0, isDCFast = false)
        session.endPercentage = 70.0
        assertEquals("Estimated from battery · 84 kWh", car.energySource(session))
        car.batteryCapacityKWh = null
        assertEquals("Estimated from battery · 77.4 kWh model default", car.energySource(session))
    }

    @Test fun batteryCatalog() {
        fun capacity(model: String, fuel: FuelType = FuelType.ELECTRIC) = BatteryCapacityCatalog.defaultCapacityKWh(model, fuel)
        assertEquals(77.4, capacity("IONIQ 5"))
        assertEquals(77.4, capacity("Hyundai IONIQ 5"))
        assertEquals(84.0, capacity("IONIQ 5 N"))
        assertEquals(77.4, capacity("IONIQ 6"))
        assertEquals(38.3, capacity("Ioniq Electric"))
        assertEquals(99.8, capacity("EV9"))
        assertEquals(64.8, capacity("Kona Electric"))
        assertEquals(13.8, capacity("TUCSON", FuelType.PHEV))
        assertNull(capacity("TUCSON", FuelType.GAS))
        assertNull(capacity("Mystery EV"))
    }

    // Automations

    @Test fun unpluggedAutomationFiresOnTheUnplugSample() = runTest {
        val car = vehicle()
        val rule = VehicleAutomation(vin, "Unplugged", VehicleAutomationKind.UNPLUGGED)
        db.automations().insert(rule)
        car.updateStatus(status(true, t0, percentage = 50.0), db)
        car.updateStatus(status(false, t0.plusSeconds(900), percentage = 58.0, plugType = PlugType.UNPLUGGED), db)
        assertNotNull(
            "Unplugging must not wait for a second stop sample",
            db.automations().all().single().lastTriggeredAt,
        )
    }

    // Widget refresh

    @Test fun onTimeWidgetReloadFetchesEvenThoughFetchWasStampedLate() {
        val interval = VehicleRefreshPolicy.interval(requested = 4.0 * 3600, pluggedIn = false)
        val window = VehicleRefreshPolicy.freshnessWindow(interval)
        // The previous fetch finished 20 s after the timeline was built, so an
        // on-time reload sees data 20 s younger than the interval.
        val now = Instant.now()
        val lastFetch = now.minusSeconds((interval - 20).toLong())
        assertTrue(VehicleRefreshPolicy.shouldRefresh(lastFetch, now, window))
        assertEquals(450.0, VehicleRefreshPolicy.freshnessWindow(600.0), 0.0)
        assertFalse(VehicleRefreshPolicy.shouldRefresh(now.minusSeconds(60), now, window))
    }

    // Fake vehicle

    @Test fun fakeVehicleChargesTowardTargetAndStops() = runTest {
        val car = vehicle()
        val start = Instant.now().minusSeconds(3600)
        car.evStatus = evStatus(
            charging = true, percentage = 40.0, chargeSpeed = 10.9, rangeMiles = 100.0,
            chargeTimeSeconds = 3600, targetSocAC = 80.0, targetSocDC = 90.0,
        )
        car.syncDate = start
        car.lastUpdated = start
        db.vehicles().update(car)
        val provider = StoreFakeVehicleProvider(RoomFakeVehicleStore(db))

        val charging = provider.vehicleStatus(vin, car.accountId).evStatus!!
        assertTrue(charging.charging)
        assertEquals(40 + 10.9 / 77.4 * 100, charging.evRange.percentage, 0.2)

        db.vehicles().byVin(vin)!!.also {
            it.syncDate = Instant.now().minusSeconds(6 * 3600)
            db.vehicles().update(it)
        }
        val finished = provider.vehicleStatus(vin, car.accountId).evStatus!!
        assertEquals(false, finished.charging)
        assertEquals(80.0, finished.evRange.percentage, 0.0)
        assertEquals(true, finished.pluggedIn)
    }

    @Test fun fakeVehicleReportsStopInsteadOfEchoingPendingPlaceholder() = runTest {
        val car = vehicle()
        car.updateStatus(status(true, Instant.now().minusSeconds(120), percentage = 50.0), db)
        car.updateStatus(status(false, Instant.now().minusSeconds(60), percentage = 51.0), db)
        assertEquals("Placeholder while the stop is confirmed", true, car.evStatus?.charging)

        val provider = StoreFakeVehicleProvider(RoomFakeVehicleStore(db))
        assertEquals(false, provider.vehicleStatus(vin, car.accountId).evStatus?.charging)
    }
}
