package com.saivenkatkrishna.ghostblue.data

import com.saivenkatkrishna.ghostblue.data.backup.BackupArchive
import com.saivenkatkrishna.ghostblue.data.backup.BackupNewerVersionException
import com.saivenkatkrishna.ghostblue.data.backup.BackupTransfer
import com.saivenkatkrishna.ghostblue.data.charging.ChargingTariff
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ChargeSpeedSample
import com.saivenkatkrishna.ghostblue.data.model.ChargingLocationType
import com.saivenkatkrishna.ghostblue.data.model.RemoteCommandActivity
import com.saivenkatkrishna.ghostblue.data.vehicle.chargingCost
import com.saivenkatkrishna.ghostblue.data.vehicle.energyDelivered
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Moving charging history from one install to another: export a backup
 * file from one store, import it into a separate, empty store. Port of
 * `BackupTransferTests`.
 */
@RunWith(RobolectricTestRunner::class)
class BackupTransferTest {
    private val vin = "TRANSFERVIN000001"
    private lateinit var directory: File
    private val databases = mutableListOf<GhostBlueDatabase>()

    private fun store() = testDatabase().also(databases::add)

    @Before fun setUp() {
        directory = Files.createTempDirectory("BackupTransferTest").toFile()
    }

    @After fun tearDown() {
        databases.forEach { it.close() }
        directory.deleteRecursively()
    }

    /** Whole seconds: backups store ISO 8601 dates without fractions. */
    private fun day(offset: Int, hour: Int) = Instant.ofEpochSecond(1_790_000_000L + offset * 86_400L + hour * 3_600L)

    private suspend fun seedLocalBuild(db: GhostBlueDatabase): BBVehicle {
        val account = BBAccount("driver@example.com", "secret", "1234", Brand.HYUNDAI, Region.USA)
        db.accounts().insert(account)
        val car = BBVehicle(Vehicle(vin, "reg", "IONIQ 5", account.id, FuelType.ELECTRIC, 3, Distance(9_000.0, Distance.Units.MILES)))
            .apply {
                batteryCapacityKWh = 84.0
                offPeakElectricityRatePerKWh = 0.12
                ownerAccountId = account.id
            }
        db.vehicles().insert(car)

        // Overnight home AC charge with a tariff snapshot and power samples.
        db.chargeSessions().insert(
            ChargeSession(vin, day(0, 0), 31.0, isDCFast = false).apply {
                applyUserEdits(
                    startDate = day(0, 0), endDate = day(0, 6), startPercentage = 31.0, endPercentage = 80.0,
                    energyAddedKWh = 41.2, maxChargeSpeedKW = 10.9, isDCFast = false, locationType = ChargingLocationType.HOME,
                    electricityRatePerKWh = 0.12, actualCost = null, manualEnergyKWh = null,
                    tariff = ChargingTariff(0.3, 0.12, 0.6, true, 0, 6, "America/Los_Angeles"),
                )
                speedSamples = listOf(ChargeSpeedSample(day(0, 1), 10.9, 40.0), ChargeSpeedSample(day(0, 3), 10.8, 58.0))
            },
        )
        // Public DC session with a charger reading and a receipt.
        db.chargeSessions().insert(
            ChargeSession(vin, day(2, 13), 18.0, isDCFast = true).apply {
                applyUserEdits(
                    startDate = day(2, 13), endDate = day(2, 14), startPercentage = 18.0, endPercentage = 72.0,
                    energyAddedKWh = 0.0, maxChargeSpeedKW = 178.0, isDCFast = true, locationType = ChargingLocationType.PUBLIC_DC,
                    electricityRatePerKWh = 0.48, actualCost = 21.37, manualEnergyKWh = 46.1,
                )
            },
        )
        return car
    }

    // Local build → another install

    @Test fun chargingHistoryMovesToAnotherInstallWithEveryDetail() = runTest {
        val localBuild = store()
        val sourceCar = seedLocalBuild(localBuild)

        val file = BackupTransfer.writeBackup(localBuild, "Pixel", directory)
        assertEquals("ghostbluebackup", file.extension)

        val otherInstall = store()
        val summary = BackupTransfer.importArchive(BackupTransfer.readArchive(file), otherInstall)
        assertEquals(2, summary.chargeSessionsAdded)
        assertEquals(1, summary.accountsAdded)
        assertEquals(1, summary.vehiclesAdded)

        val original = localBuild.chargeSessions().all()
        val imported = otherInstall.chargeSessions().all()
        assertEquals(original.size, imported.size)
        for ((a, b) in original.zip(imported)) {
            assertEquals(a.id, b.id)
            assertEquals(a.vin, b.vin)
            assertEquals(a.startDate, b.startDate)
            assertEquals(a.endDate, b.endDate)
            assertEquals(a.startPercentage, b.startPercentage, 0.0)
            assertEquals(a.endPercentage, b.endPercentage, 0.0)
            assertEquals(a.energyAddedKWh, b.energyAddedKWh, 0.0)
            assertEquals(a.maxChargeSpeedKW, b.maxChargeSpeedKW, 0.0)
            assertEquals(a.isDCFast, b.isDCFast)
            assertEquals(a.locationType, b.locationType)
            assertEquals(a.electricityRatePerKWh, b.electricityRatePerKWh)
            assertEquals(a.actualCost, b.actualCost)
            assertEquals(a.manualEnergyKWh, b.manualEnergyKWh)
            assertEquals(a.tariff, b.tariff)
            assertEquals(a.speedSamples, b.speedSamples)
        }

        // The imported car resolves the same energy and cost, so the Energy tab shows the same totals.
        val importedCar = otherInstall.vehicles().all().single()
        assertEquals(84.0, importedCar.batteryCapacityKWh)
        for ((a, b) in original.zip(imported)) {
            assertEquals(sourceCar.energyDelivered(a), importedCar.energyDelivered(b), 0.0001)
            assertEquals(sourceCar.chargingCost(a)!!, importedCar.chargingCost(b)!!, 0.0001)
        }
    }

    @Test fun importingAgainOrIntoAnInstallThatHasTheSessionAddsNothingAndOverwritesNothing() = runTest {
        val localBuild = store()
        seedLocalBuild(localBuild)
        val file = BackupTransfer.writeBackup(localBuild, "Pixel", directory)

        // The other install already logged the DC session itself, with a receipt entered there.
        val otherInstall = store()
        otherInstall.chargeSessions().insert(ChargeSession(vin, day(2, 13), 18.0, isDCFast = true).apply { actualCost = 19.99 })

        val archive = BackupTransfer.readArchive(file)
        val first = BackupTransfer.importArchive(archive, otherInstall)
        assertEquals(1, first.chargeSessionsAdded)
        assertEquals(1, first.chargeSessionsSkipped)
        assertEquals("An existing session is never overwritten", 19.99, otherInstall.chargeSessions().all().first { it.isDCFast }.actualCost)

        assertEquals(0, BackupTransfer.importArchive(archive, otherInstall).totalAdded)
        assertEquals(2, otherInstall.chargeSessions().all().size)
    }

    /** Backups store whole-second dates; records stamped past the half-second must not import twice. */
    @Test fun recordsWithFractionalSecondsAreNotDuplicatedOnReimport() = runTest {
        val localBuild = store()
        seedLocalBuild(localBuild)
        localBuild.chargeSessions().insert(ChargeSession(vin, epoch(1_790_500_000.6), 40.0, isDCFast = false))
        localBuild.remoteCommands().insert(
            RemoteCommandActivity(vin, "Car", "Lock", "iPhone").apply { startedAt = epoch(1_790_500_100.7) },
        )
        val archive = BackupTransfer.readArchive(BackupTransfer.writeBackup(localBuild, "Pixel", directory))

        // Importing an install's own backup adds nothing.
        assertEquals(0, BackupTransfer.importArchive(archive, localBuild).totalAdded)

        // Nor does an install that logged the same session itself, under a
        // different record id, at the same fractional instant.
        val otherInstall = store()
        otherInstall.chargeSessions().insert(ChargeSession(vin, epoch(1_790_500_000.6), 40.0, isDCFast = false))
        val summary = BackupTransfer.importArchive(archive, otherInstall)
        assertEquals(2, summary.chargeSessionsAdded)
        assertEquals(1, summary.chargeSessionsSkipped)
        assertEquals(0, BackupTransfer.importArchive(archive, otherInstall).totalAdded)
    }

    // File handling

    @Test fun plainJSONBackupsFromBeforeTheFileTypeStillImport() = runTest {
        val localBuild = store()
        seedLocalBuild(localBuild)
        val legacy = File(directory, "GhostBlue-Backup-2026-10-01.json")
        legacy.writeBytes(BackupArchive.export(localBuild, "iPhone").encoded())

        assertEquals(2, BackupTransfer.readArchive(legacy).chargeSessions.size)
        assertTrue(BackupTransfer.isBackupFile(legacy.name))
    }

    @Test fun backupFromANewerAppVersionIsRefused() = runTest {
        val archive = BackupArchive.export(store(), "Pixel").copy(version = BackupArchive.CURRENT_VERSION + 1)
        val file = File(directory, "future.ghostbluebackup").apply { writeBytes(archive.encoded()) }

        val error = runCatching { BackupTransfer.readArchive(file) }.exceptionOrNull()
        assertTrue(error is BackupNewerVersionException)
    }

    @Test fun onlyBackupFilesAreRecognised() {
        assertTrue(BackupTransfer.isBackupFile("GhostBlue-Backup.ghostbluebackup"))
        assertTrue(BackupTransfer.isBackupFile("OLD.JSON"))
        assertFalse(BackupTransfer.isBackupFile("photo.png"))
        assertFalse(BackupTransfer.isBackupFile("ghostbluebackup"))
        assertEquals("GhostBlue-Backup-2026-10-28.ghostbluebackup", BackupTransfer.filename(java.time.LocalDate.of(2026, 10, 28)))
    }
}
