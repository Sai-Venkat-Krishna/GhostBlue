package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.backup.BackupArchive
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset
import com.saivenkatkrishna.ghostblue.data.model.MaintenanceReminder
import com.saivenkatkrishna.ghostblue.data.model.RemoteCommandActivity
import com.saivenkatkrishna.ghostblue.data.model.TripRecord
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomation
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomationKind
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Port of `BackupArchiveTests`. */
@RunWith(RobolectricTestRunner::class)
class BackupArchiveTest {
    private val databases = mutableListOf<GhostBlueDatabase>()

    private fun store() = testDatabase().also(databases::add)

    @After fun tearDown() = databases.forEach { it.close() }

    /** One of everything; returns the vehicle. */
    private suspend fun seed(db: GhostBlueDatabase, vin: String = "SEEDVIN0000000001"): BBVehicle {
        val account = BBAccount("driver@example.com", "secret", "1234", Brand.HYUNDAI, Region.USA)
        db.accounts().insert(account)
        val vehicle = BBVehicle(
            Vehicle(vin, "reg-1", "IONIQ 5", account.id, FuelType.ELECTRIC, 3, Distance(1234.0, Distance.Units.MILES)),
        ).apply {
            customName = "Blue"
            offPeakElectricityRatePerKWh = 0.12
            ownerAccountId = account.id
        }
        db.vehicles().insert(vehicle)
        db.climatePresets().insert(ClimatePreset("Morning", "sun.max", ClimateOptions(), isSelected = true, vehicleId = vehicle.id))
        db.chargeSessions().insert(
            ChargeSession(vin, Instant.ofEpochSecond(1_700_000_000), 20.0, isDCFast = false).apply {
                endPercentage = 80.0
                energyAddedKWh = 42.0
            },
        )
        db.trips().insert(
            TripRecord(
                vin,
                EVTripDetail(12.0, 1234.0, 1, 10, 2, 1, 6, 0, Instant.ofEpochSecond(1_700_100_000), 900, 30.0, 55.0),
            ),
        )
        db.remoteCommands().insert(RemoteCommandActivity(vin, "Blue", "Lock", "iPhone"))
        db.maintenanceReminders().insert(MaintenanceReminder(vin, "Tire rotation"))
        db.automations().insert(VehicleAutomation(vin, "Low battery", VehicleAutomationKind.LOW_BATTERY))
        return vehicle
    }

    @Test fun roundTripRecreatesEveryRecordInAnEmptyStore() = runTest {
        val source = store()
        seed(source)
        val data = BackupArchive.export(source, "Source").encoded()

        val destination = store()
        val summary = BackupArchive.merge(BackupArchive.decoded(data), destination)

        assertEquals(1, summary.accountsAdded)
        assertEquals(1, summary.vehiclesAdded)
        assertEquals(1, summary.presetsAdded)
        assertEquals(1, summary.chargeSessionsAdded)
        assertEquals(1, summary.tripsAdded)
        assertEquals(1, summary.remoteCommandsAdded)
        assertEquals(1, summary.remindersAdded)
        assertEquals(1, summary.automationsAdded)
        assertTrue(summary.orphanedVehicles.isEmpty())

        assertEquals(1, destination.vehicles().all().size)
        assertEquals(1, destination.chargeSessions().all().size)
        assertEquals(1, destination.trips().all().size)
        val restored = destination.vehicles().all().single()
        assertEquals("Blue", restored.customName)
        assertEquals(0.12, restored.offPeakElectricityRatePerKWh)
        assertEquals("driver@example.com", destination.accounts().byId(restored.ownerAccountId!!)?.username)
        assertEquals(1, destination.climatePresets().forVehicle(restored.id).size)
    }

    @Test fun mergingTheSameArchiveTwiceAddsNothingTheSecondTime() = runTest {
        val source = store()
        seed(source)
        val archive = BackupArchive.export(source, "Source")

        val destination = store()
        BackupArchive.merge(archive, destination)
        val second = BackupArchive.merge(archive, destination)

        assertEquals(0, second.totalAdded)
        assertEquals(1, second.accountsSkipped)
        assertEquals(1, second.chargeSessionsSkipped)
        assertEquals(1, second.tripsSkipped)
        assertEquals(1, destination.vehicles().all().size)
        assertEquals(1, destination.chargeSessions().all().size)
    }

    @Test fun mergeUnionsTwoStoresWithDifferentHistory() = runTest {
        val vin = "SEEDVIN0000000001"
        val source = store()
        seed(source, vin)
        source.chargeSessions().insert(ChargeSession(vin, Instant.ofEpochSecond(1_700_500_000), 30.0, isDCFast = true))

        val destination = store()
        seed(destination, vin)
        destination.chargeSessions().insert(ChargeSession(vin, Instant.ofEpochSecond(1_700_900_000), 50.0, isDCFast = false))

        val summary = BackupArchive.merge(BackupArchive.export(source, "Source"), destination)

        // The shared seed session is recognised; only the source-only one lands.
        assertEquals(1, summary.chargeSessionsAdded)
        assertEquals(1, summary.chargeSessionsSkipped)
        assertEquals(1, summary.accountsSkipped)
        assertEquals(0, summary.vehiclesAdded)
        assertEquals(3, destination.chargeSessions().all().size)
    }

    @Test fun existingVehicleKeepsItsOwnSettingsAndGainsOnlyMissingOnes() = runTest {
        val vin = "SEEDVIN0000000001"
        val source = store()
        val sourceVehicle = seed(source, vin)
        sourceVehicle.customName = "Source Name"
        sourceVehicle.abrpUserToken = "token-from-source"
        source.vehicles().update(sourceVehicle)

        val destination = store()
        val localVehicle = seed(destination, vin)
        localVehicle.customName = "Local Name"
        localVehicle.abrpUserToken = null
        destination.vehicles().update(localVehicle)

        val summary = BackupArchive.merge(BackupArchive.export(source, "Source"), destination)
        assertEquals(1, summary.vehiclesUpdated)
        assertEquals(0, summary.vehiclesAdded)

        val merged = destination.vehicles().all().single()
        assertEquals("a value already set here must survive", "Local Name", merged.customName)
        assertEquals("a blank must be filled from the backup", "token-from-source", merged.abrpUserToken)
    }

    @Test fun vehicleWithNoMatchingAccountIsReportedRatherThanDropped() = runTest {
        val source = store()
        seed(source)
        val archive = BackupArchive.export(source, "Source").copy(accounts = emptyList())

        val summary = BackupArchive.merge(archive, store())
        assertEquals(0, summary.vehiclesAdded)
        assertEquals(listOf("SEEDVIN0000000001"), summary.orphanedVehicles)
    }
}
