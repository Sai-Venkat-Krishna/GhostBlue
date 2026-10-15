import BetterBlueKit
import SwiftData
import XCTest
@testable import GhostBlue

@MainActor
final class BackupArchiveTests: XCTestCase {
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

    /// Populates a store with one of everything and returns the VIN used.
    @discardableResult
    private func seed(_ context: ModelContext, vin: String = "SEEDVIN0000000001") -> BBVehicle {
        let account = BBAccount(
            username: "driver@example.com", password: "secret", pin: "1234",
            brand: .hyundai, region: .usa
        )
        context.insert(account)

        let vehicle = BBVehicle(from: Vehicle(
            vin: vin, regId: "reg-1", model: "IONIQ 5", accountId: account.id,
            fuelType: .electric, generation: 3, odometer: .init(length: 1234, units: .miles)
        ))
        vehicle.customName = "Blue"
        vehicle.offPeakElectricityRatePerKWh = 0.12
        vehicle.account = account
        context.insert(vehicle)

        let preset = ClimatePreset(
            name: "Morning", iconName: "sun.max",
            climateOptions: ClimateOptions(), isSelected: true, vehicle: vehicle
        )
        context.insert(preset)

        let session = ChargeSession(
            vin: vin, startDate: Date(timeIntervalSince1970: 1_700_000_000),
            startPercentage: 20, isDCFast: false
        )
        session.endPercentage = 80
        session.energyAddedKWh = 42
        context.insert(session)

        let trip = TripRecord(vin: vin, trip: EVTripDetail(
            distance: 12, odometer: 1234, accessoriesEnergy: 1, totalEnergyUsed: 10,
            regenEnergy: 2, climateEnergy: 1, drivetrainEnergy: 6, batteryCareEnergy: 0,
            startDate: Date(timeIntervalSince1970: 1_700_100_000), durationSeconds: 900,
            avgSpeed: 30, maxSpeed: 55
        ))
        context.insert(trip)

        let activity = RemoteCommandActivity(
            vin: vin, vehicleName: "Blue", commandName: "Lock", sourceName: "iPhone"
        )
        context.insert(activity)

        let reminder = MaintenanceReminder(vin: vin, title: "Tire rotation")
        context.insert(reminder)

        let automation = VehicleAutomation(vin: vin, title: "Low battery", kind: .lowBattery)
        context.insert(automation)

        try? context.save()
        return vehicle
    }

    private func count<T: PersistentModel>(_: T.Type, in context: ModelContext) -> Int {
        (try? context.fetchCount(FetchDescriptor<T>())) ?? 0
    }

    func testRoundTripRecreatesEveryRecordInAnEmptyStore() throws {
        let source = try container()
        seed(source.mainContext)

        let data = try BackupArchive
            .export(from: source.mainContext, deviceName: "Source")
            .encoded()

        let destination = try container()
        let archive = try BackupArchive.decoded(from: data)
        let summary = try BackupArchive.merge(archive, into: destination.mainContext)

        XCTAssertEqual(summary.accountsAdded, 1)
        XCTAssertEqual(summary.vehiclesAdded, 1)
        XCTAssertEqual(summary.presetsAdded, 1)
        XCTAssertEqual(summary.chargeSessionsAdded, 1)
        XCTAssertEqual(summary.tripsAdded, 1)
        XCTAssertEqual(summary.remoteCommandsAdded, 1)
        XCTAssertEqual(summary.remindersAdded, 1)
        XCTAssertEqual(summary.automationsAdded, 1)
        XCTAssertTrue(summary.orphanedVehicles.isEmpty)

        let context = destination.mainContext
        XCTAssertEqual(count(BBVehicle.self, in: context), 1)
        XCTAssertEqual(count(ChargeSession.self, in: context), 1)
        XCTAssertEqual(count(TripRecord.self, in: context), 1)

        let restored = try XCTUnwrap(try context.fetch(FetchDescriptor<BBVehicle>()).first)
        XCTAssertEqual(restored.customName, "Blue")
        XCTAssertEqual(restored.offPeakElectricityRatePerKWh, 0.12)
        XCTAssertEqual(restored.account?.username, "driver@example.com")
        XCTAssertEqual(restored.safeClimatePresets.count, 1)
    }

    func testMergingTheSameArchiveTwiceAddsNothingTheSecondTime() throws {
        let source = try container()
        seed(source.mainContext)
        let archive = BackupArchive.export(from: source.mainContext, deviceName: "Source")

        let destination = try container()
        _ = try BackupArchive.merge(archive, into: destination.mainContext)
        let second = try BackupArchive.merge(archive, into: destination.mainContext)

        XCTAssertEqual(second.totalAdded, 0)
        XCTAssertEqual(second.accountsSkipped, 1)
        XCTAssertEqual(second.chargeSessionsSkipped, 1)
        XCTAssertEqual(second.tripsSkipped, 1)
        XCTAssertEqual(count(BBVehicle.self, in: destination.mainContext), 1)
        XCTAssertEqual(count(ChargeSession.self, in: destination.mainContext), 1)
    }

    func testMergeUnionsTwoStoresWithDifferentHistory() throws {
        let vin = "SEEDVIN0000000001"

        let source = try container()
        let sourceVehicle = seed(source.mainContext, vin: vin)
        let onlyInSource = ChargeSession(
            vin: sourceVehicle.vin, startDate: Date(timeIntervalSince1970: 1_700_500_000),
            startPercentage: 30, isDCFast: true
        )
        source.mainContext.insert(onlyInSource)
        try source.mainContext.save()

        let destination = try container()
        seed(destination.mainContext, vin: vin)
        let onlyInDestination = ChargeSession(
            vin: vin, startDate: Date(timeIntervalSince1970: 1_700_900_000),
            startPercentage: 50, isDCFast: false
        )
        destination.mainContext.insert(onlyInDestination)
        try destination.mainContext.save()

        let archive = BackupArchive.export(from: source.mainContext, deviceName: "Source")
        let summary = try BackupArchive.merge(archive, into: destination.mainContext)

        // The shared seed session is recognised; only the source-only one lands.
        XCTAssertEqual(summary.chargeSessionsAdded, 1)
        XCTAssertEqual(summary.chargeSessionsSkipped, 1)
        XCTAssertEqual(summary.accountsSkipped, 1)
        XCTAssertEqual(summary.vehiclesAdded, 0)
        XCTAssertEqual(count(ChargeSession.self, in: destination.mainContext), 3)
    }

    func testExistingVehicleKeepsItsOwnSettingsAndGainsOnlyMissingOnes() throws {
        let vin = "SEEDVIN0000000001"

        let source = try container()
        let sourceVehicle = seed(source.mainContext, vin: vin)
        sourceVehicle.customName = "Source Name"
        sourceVehicle.abrpUserToken = "token-from-source"
        try source.mainContext.save()

        let destination = try container()
        let localVehicle = seed(destination.mainContext, vin: vin)
        localVehicle.customName = "Local Name"
        localVehicle.abrpUserToken = nil
        try destination.mainContext.save()

        let archive = BackupArchive.export(from: source.mainContext, deviceName: "Source")
        let summary = try BackupArchive.merge(archive, into: destination.mainContext)

        XCTAssertEqual(summary.vehiclesUpdated, 1)
        XCTAssertEqual(summary.vehiclesAdded, 0)

        let merged = try XCTUnwrap(try destination.mainContext.fetch(FetchDescriptor<BBVehicle>()).first)
        XCTAssertEqual(merged.customName, "Local Name", "a value already set here must survive")
        XCTAssertEqual(merged.abrpUserToken, "token-from-source", "a blank must be filled from the backup")
    }

    func testVehicleWithNoMatchingAccountIsReportedRatherThanDropped() throws {
        let source = try container()
        seed(source.mainContext)
        var archive = BackupArchive.export(from: source.mainContext, deviceName: "Source")
        archive.accounts = []

        let destination = try container()
        let summary = try BackupArchive.merge(archive, into: destination.mainContext)

        XCTAssertEqual(summary.vehiclesAdded, 0)
        XCTAssertEqual(summary.orphanedVehicles, ["SEEDVIN0000000001"])
    }
}
