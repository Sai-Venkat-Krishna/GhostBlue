import BetterBlueKit
import SwiftData
import UniformTypeIdentifiers
import XCTest
@testable import GhostBlue

/// Moving charging history from one install (a locally built app) to another
/// (a TestFlight build): export a backup file from one store, import it into
/// a separate, empty store.
@MainActor
final class BackupTransferTests: XCTestCase {
    private let vin = "TRANSFERVIN000001"
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BackupTransferTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func container() throws -> ModelContainer {
        try ModelContainer(
            for: Schema([
                BBAccount.self, BBVehicle.self, BBHTTPLog.self, ClimatePreset.self,
                ChargeSession.self, TripRecord.self, RemoteCommandActivity.self,
                MaintenanceReminder.self, VehicleAutomation.self
            ]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
    }

    /// Whole seconds: backups store ISO 8601 dates without fractions.
    private func day(_ offset: Int, hour: Int) -> Date {
        Date(timeIntervalSince1970: 1_790_000_000 + TimeInterval(offset * 86_400 + hour * 3_600))
    }

    @discardableResult
    private func seedLocalBuild(_ context: ModelContext) -> BBVehicle {
        let account = BBAccount(username: "driver@example.com", password: "secret", pin: "1234",
                                brand: .hyundai, region: .usa)
        context.insert(account)
        let car = BBVehicle(from: Vehicle(vin: vin, regId: "reg", model: "IONIQ 5", accountId: account.id,
                                          fuelType: .electric, generation: 3, odometer: .init(length: 9_000, units: .miles)))
        car.batteryCapacityKWh = 84
        car.offPeakElectricityRatePerKWh = 0.12
        context.insert(car)
        car.account = account

        // Overnight home AC charge with a tariff snapshot and power samples.
        let home = ChargeSession(vin: vin, startDate: day(0, hour: 0), startPercentage: 31, isDCFast: false)
        home.applyUserEdits(
            startDate: day(0, hour: 0), endDate: day(0, hour: 6), startPercentage: 31, endPercentage: 80,
            energyAddedKWh: 41.2, maxChargeSpeedKW: 10.9, isDCFast: false, locationType: .home,
            electricityRatePerKWh: 0.12, actualCost: nil, manualEnergyKWh: nil,
            tariff: ChargingTariff(standardRate: 0.3, offPeakRate: 0.12, peakRate: 0.6, usesSDGE: true,
                                   offPeakStartHour: 0, offPeakEndHour: 6, timeZoneIdentifier: "America/Los_Angeles")
        )
        home.speedSamples = [
            ChargeSpeedSample(date: day(0, hour: 1), speedKW: 10.9, batteryPercentage: 40),
            ChargeSpeedSample(date: day(0, hour: 3), speedKW: 10.8, batteryPercentage: 58)
        ]
        context.insert(home)

        // Public DC session with a charger reading and a receipt.
        let fast = ChargeSession(vin: vin, startDate: day(2, hour: 13), startPercentage: 18, isDCFast: true)
        fast.applyUserEdits(
            startDate: day(2, hour: 13), endDate: day(2, hour: 14), startPercentage: 18, endPercentage: 72,
            energyAddedKWh: 0, maxChargeSpeedKW: 178, isDCFast: true, locationType: .publicDC,
            electricityRatePerKWh: 0.48, actualCost: 21.37, manualEnergyKWh: 46.1
        )
        context.insert(fast)
        try? context.save()
        return car
    }

    private func sessions(_ context: ModelContext) throws -> [ChargeSession] {
        try context.fetch(FetchDescriptor<ChargeSession>(sortBy: [SortDescriptor(\.startDate)]))
    }

    // MARK: - Local build → TestFlight build

    func testChargingHistoryMovesToAnotherInstallWithEveryDetail() throws {
        let localBuild = try container()
        let sourceCar = seedLocalBuild(localBuild.mainContext)

        let file = try BackupTransfer.writeBackup(from: localBuild.mainContext, deviceName: "Mac", to: directory)
        XCTAssertEqual(file.pathExtension, "ghostbluebackup")

        let testFlightBuild = try container()
        let summary = try BackupTransfer.importArchive(try BackupTransfer.readArchive(at: file),
                                                       into: testFlightBuild.mainContext)
        XCTAssertEqual(summary.chargeSessionsAdded, 2)
        XCTAssertEqual(summary.accountsAdded, 1)
        XCTAssertEqual(summary.vehiclesAdded, 1)

        let original = try sessions(localBuild.mainContext)
        let imported = try sessions(testFlightBuild.mainContext)
        XCTAssertEqual(imported.count, original.count)
        for (a, b) in zip(original, imported) {
            XCTAssertEqual(b.id, a.id)
            XCTAssertEqual(b.vin, a.vin)
            XCTAssertEqual(b.startDate, a.startDate)
            XCTAssertEqual(b.endDate, a.endDate)
            XCTAssertEqual(b.startPercentage, a.startPercentage)
            XCTAssertEqual(b.endPercentage, a.endPercentage)
            XCTAssertEqual(b.energyAddedKWh, a.energyAddedKWh)
            XCTAssertEqual(b.maxChargeSpeedKW, a.maxChargeSpeedKW)
            XCTAssertEqual(b.isDCFast, a.isDCFast)
            XCTAssertEqual(b.locationType, a.locationType)
            XCTAssertEqual(b.electricityRatePerKWh, a.electricityRatePerKWh)
            XCTAssertEqual(b.actualCost, a.actualCost)
            XCTAssertEqual(b.manualEnergyKWh, a.manualEnergyKWh)
            XCTAssertEqual(b.tariff, a.tariff)
            XCTAssertEqual(b.speedSamples, a.speedSamples)
        }

        // The imported car resolves the same energy and cost, so the
        // TestFlight build's Energy tab shows the same totals.
        let importedCar = try XCTUnwrap(testFlightBuild.mainContext.fetch(FetchDescriptor<BBVehicle>()).first)
        XCTAssertEqual(importedCar.batteryCapacityKWh, 84)
        for (a, b) in zip(original, imported) {
            XCTAssertEqual(importedCar.energyDelivered(for: b), sourceCar.energyDelivered(for: a), accuracy: 0.0001)
            XCTAssertEqual(try XCTUnwrap(importedCar.chargingCost(for: b)),
                           try XCTUnwrap(sourceCar.chargingCost(for: a)), accuracy: 0.0001)
        }
    }

    func testImportingAgainOrIntoAnInstallThatHasTheSessionAddsNothingAndOverwritesNothing() throws {
        let localBuild = try container()
        seedLocalBuild(localBuild.mainContext)
        let file = try BackupTransfer.writeBackup(from: localBuild.mainContext, deviceName: "Mac", to: directory)

        // The TestFlight build already logged the DC session itself, with a
        // receipt the user entered there.
        let testFlightBuild = try container()
        let existing = ChargeSession(vin: vin, startDate: day(2, hour: 13), startPercentage: 18, isDCFast: true)
        existing.actualCost = 19.99
        testFlightBuild.mainContext.insert(existing)
        try testFlightBuild.mainContext.save()

        let archive = try BackupTransfer.readArchive(at: file)
        let first = try BackupTransfer.importArchive(archive, into: testFlightBuild.mainContext)
        XCTAssertEqual(first.chargeSessionsAdded, 1)
        XCTAssertEqual(first.chargeSessionsSkipped, 1)
        let dc = try XCTUnwrap(sessions(testFlightBuild.mainContext).first { $0.isDCFast })
        XCTAssertEqual(dc.actualCost, 19.99, "An existing session is never overwritten")

        let second = try BackupTransfer.importArchive(archive, into: testFlightBuild.mainContext)
        XCTAssertEqual(second.totalAdded, 0)
        XCTAssertEqual(try sessions(testFlightBuild.mainContext).count, 2)
    }

    /// Backups store whole-second dates. Records stamped past the half-second
    /// used to round to a different second than the file and import twice.
    func testRecordsWithFractionalSecondsAreNotDuplicatedOnReimport() throws {
        let localBuild = try container()
        seedLocalBuild(localBuild.mainContext)
        let manual = ChargeSession(vin: vin, startDate: Date(timeIntervalSince1970: 1_790_500_000.6),
                                   startPercentage: 40, isDCFast: false)
        localBuild.mainContext.insert(manual)
        let command = RemoteCommandActivity(vin: vin, vehicleName: "Car", commandName: "Lock", sourceName: "iPhone")
        command.startedAt = Date(timeIntervalSince1970: 1_790_500_100.7)
        localBuild.mainContext.insert(command)
        try localBuild.mainContext.save()
        let file = try BackupTransfer.writeBackup(from: localBuild.mainContext, deviceName: "Mac", to: directory)
        let archive = try BackupTransfer.readArchive(at: file)

        // Importing an install's own backup adds nothing.
        XCTAssertEqual(try BackupTransfer.importArchive(archive, into: localBuild.mainContext).totalAdded, 0)

        // Nor does an install that logged the same session itself, under a
        // different record id, at the same fractional instant.
        let testFlightBuild = try container()
        testFlightBuild.mainContext.insert(ChargeSession(vin: vin, startDate: Date(timeIntervalSince1970: 1_790_500_000.6),
                                                         startPercentage: 40, isDCFast: false))
        try testFlightBuild.mainContext.save()
        let summary = try BackupTransfer.importArchive(archive, into: testFlightBuild.mainContext)
        XCTAssertEqual(summary.chargeSessionsAdded, 2)
        XCTAssertEqual(summary.chargeSessionsSkipped, 1)
        XCTAssertEqual(try BackupTransfer.importArchive(archive, into: testFlightBuild.mainContext).totalAdded, 0)
    }

    // MARK: - File handling

    func testBackupFileTypeIsDeclaredSoOtherAppsOfferGhostBlue() throws {
        XCTAssertEqual(UTType(filenameExtension: "ghostbluebackup"), .ghostBlueBackup)
        XCTAssertTrue(UTType.ghostBlueBackup.conforms(to: .json))

        let exported = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "UTExportedTypeDeclarations") as? [[String: Any]])
        XCTAssertTrue(exported.contains { $0["UTTypeIdentifier"] as? String == UTType.ghostBlueBackup.identifier })
        let documents = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "CFBundleDocumentTypes") as? [[String: Any]])
        XCTAssertTrue(documents.contains {
            ($0["LSItemContentTypes"] as? [String])?.contains(UTType.ghostBlueBackup.identifier) == true
        })
    }

    func testPlainJSONBackupsFromBeforeTheFileTypeStillImport() throws {
        let localBuild = try container()
        seedLocalBuild(localBuild.mainContext)
        let legacy = directory.appendingPathComponent("GhostBlue-Backup-2026-10-01.json")
        try BackupArchive.export(from: localBuild.mainContext, deviceName: "iPhone").encoded().write(to: legacy)

        let archive = try BackupTransfer.readArchive(at: legacy)
        XCTAssertEqual(archive.chargeSessions.count, 2)
        XCTAssertTrue(BackupTransfer.importableTypes.contains(.json))
    }

    func testBackupFromANewerAppVersionIsRefused() throws {
        let localBuild = try container()
        var archive = BackupArchive.export(from: localBuild.mainContext, deviceName: "Mac")
        archive.version = BackupArchive.currentVersion + 1
        let file = directory.appendingPathComponent("future.ghostbluebackup")
        try archive.encoded().write(to: file)

        XCTAssertThrowsError(try BackupTransfer.readArchive(at: file)) { error in
            XCTAssertTrue(error is BackupTransferError)
        }
    }

    func testStaleInboxBackupsArePurgedButFreshOnesAndOtherFilesStay() throws {
        let now = Date()
        let stale = directory.appendingPathComponent("old.ghostbluebackup")
        let fresh = directory.appendingPathComponent("new.ghostbluebackup")
        let other = directory.appendingPathComponent("photo.png")
        for file in [stale, fresh, other] { try Data("{}".utf8).write(to: file) }
        try FileManager.default.setAttributes([.modificationDate: now.addingTimeInterval(-7_200)], ofItemAtPath: stale.path)
        try FileManager.default.setAttributes([.modificationDate: now.addingTimeInterval(-7_200)], ofItemAtPath: other.path)

        BackupTransfer.purgeStaleInboxCopies(in: directory, now: now)

        XCTAssertFalse(FileManager.default.fileExists(atPath: stale.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: fresh.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: other.path))
    }

    func testOnlyBackupFilesAreQueuedForImport() {
        let coordinator = BackupImportCoordinator()
        XCTAssertFalse(coordinator.handle(URL(string: "ghostblue://vehicle/\(vin)")!))
        XCTAssertFalse(coordinator.handle(directory.appendingPathComponent("photo.png")))
        XCTAssertNil(coordinator.pendingFile)

        let file = directory.appendingPathComponent("GhostBlue-Backup.ghostbluebackup")
        XCTAssertTrue(coordinator.handle(file))
        XCTAssertEqual(coordinator.pendingFile?.url, file)
    }
}
