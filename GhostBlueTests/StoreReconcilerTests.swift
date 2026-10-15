import BetterBlueKit
import SwiftData
import XCTest
@testable import GhostBlue

@MainActor
final class StoreReconcilerTests: XCTestCase {
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

    private func account(_ context: ModelContext, username: String = "driver@example.com") -> BBAccount {
        let account = BBAccount(
            username: username, password: "secret", pin: "1234", brand: .hyundai, region: .usa
        )
        context.insert(account)
        return account
    }

    private func vehicle(
        _ context: ModelContext, vin: String, account: BBAccount, lastUpdated: Date? = nil
    ) -> BBVehicle {
        let vehicle = BBVehicle(from: Vehicle(
            vin: vin, regId: "reg", model: "IONIQ 5", accountId: account.id,
            fuelType: .electric, generation: 3, odometer: .init(length: 0, units: .miles)
        ))
        vehicle.lastUpdated = lastUpdated
        vehicle.account = account
        context.insert(vehicle)
        return vehicle
    }

    private func count<T: PersistentModel>(_: T.Type, in context: ModelContext) -> Int {
        (try? context.fetchCount(FetchDescriptor<T>())) ?? 0
    }

    func testSameVINFromTwoDevicesCollapsesToOneVehicle() throws {
        let store = try container()
        let context = store.mainContext
        let owner = account(context)

        let older = vehicle(context, vin: "VIN000000000001", account: owner,
                            lastUpdated: Date(timeIntervalSince1970: 1_700_000_000))
        older.customName = "From iPhone"

        let newer = vehicle(context, vin: "VIN000000000001", account: owner,
                            lastUpdated: Date(timeIntervalSince1970: 1_700_900_000))
        newer.abrpUserToken = "token-from-ipad"
        try context.save()

        XCTAssertEqual(count(BBVehicle.self, in: context), 2)

        let summary = StoreReconciler.reconcile(in: context)

        XCTAssertEqual(summary.vehiclesMerged, 1)
        XCTAssertEqual(count(BBVehicle.self, in: context), 1)

        let survivor = try XCTUnwrap(try context.fetch(FetchDescriptor<BBVehicle>()).first)
        XCTAssertEqual(survivor.lastUpdated, Date(timeIntervalSince1970: 1_700_900_000),
                       "the fresher status should win")
        XCTAssertEqual(survivor.abrpUserToken, "token-from-ipad")
        XCTAssertEqual(survivor.customName, "From iPhone",
                       "a setting only the dropped copy had must be adopted")
    }

    func testPresetsFollowTheSurvivingVehicle() throws {
        let store = try container()
        let context = store.mainContext
        let owner = account(context)

        let keep = vehicle(context, vin: "VIN000000000002", account: owner,
                           lastUpdated: Date(timeIntervalSince1970: 1_700_900_000))
        context.insert(ClimatePreset(name: "Morning", iconName: "sun.max",
                                     climateOptions: ClimateOptions(), isSelected: true, vehicle: keep))

        let drop = vehicle(context, vin: "VIN000000000002", account: owner,
                           lastUpdated: Date(timeIntervalSince1970: 1_700_000_000))
        context.insert(ClimatePreset(name: "Evening", iconName: "moon",
                                     climateOptions: ClimateOptions(), isSelected: false, vehicle: drop))
        context.insert(ClimatePreset(name: "Morning", iconName: "sun.max",
                                     climateOptions: ClimateOptions(), isSelected: false, vehicle: drop))
        try context.save()

        StoreReconciler.reconcile(in: context)

        let survivor = try XCTUnwrap(try context.fetch(FetchDescriptor<BBVehicle>()).first)
        let names = survivor.safeClimatePresets.map(\.name).sorted()
        XCTAssertEqual(names, ["Evening", "Morning"],
                       "the unique preset moves across and the duplicate name does not double up")
        XCTAssertEqual(count(ClimatePreset.self, in: context), 2)
    }

    func testDuplicateChargeSessionsKeepTheFinishedCopy() throws {
        let store = try container()
        let context = store.mainContext
        let start = Date(timeIntervalSince1970: 1_700_300_000)

        let open = ChargeSession(vin: "VIN3", startDate: start, startPercentage: 20, isDCFast: false)
        context.insert(open)

        let finished = ChargeSession(vin: "VIN3", startDate: start, startPercentage: 20, isDCFast: false)
        finished.endDate = start.addingTimeInterval(3600)
        finished.energyAddedKWh = 30
        context.insert(finished)
        try context.save()

        let summary = StoreReconciler.reconcile(in: context)

        XCTAssertEqual(summary.chargeSessionsMerged, 1)
        let survivor = try XCTUnwrap(try context.fetch(FetchDescriptor<ChargeSession>()).first)
        XCTAssertNotNil(survivor.endDate)
        XCTAssertEqual(survivor.energyAddedKWh, 30)
    }

    func testDuplicateAccountsCollapseAndKeepTheirVehicles() throws {
        let store = try container()
        let context = store.mainContext

        let first = account(context)
        first.dateCreated = Date(timeIntervalSince1970: 1_700_000_000)
        _ = vehicle(context, vin: "VIN-A", account: first)

        let second = account(context)
        second.dateCreated = Date(timeIntervalSince1970: 1_700_900_000)
        _ = vehicle(context, vin: "VIN-B", account: second)
        try context.save()

        let summary = StoreReconciler.reconcile(in: context)

        XCTAssertEqual(summary.accountsMerged, 1)
        XCTAssertEqual(count(BBAccount.self, in: context), 1)
        XCTAssertEqual(count(BBVehicle.self, in: context), 2,
                       "both cars survive; only the duplicate account row goes")

        let survivor = try XCTUnwrap(try context.fetch(FetchDescriptor<BBAccount>()).first)
        XCTAssertEqual(survivor.safeVehicles.count, 2)
    }

    func testDistinctVINsAreLeftAlone() throws {
        let store = try container()
        let context = store.mainContext
        let owner = account(context)
        _ = vehicle(context, vin: "VIN-ONE", account: owner)
        _ = vehicle(context, vin: "VIN-TWO", account: owner)
        try context.save()

        let summary = StoreReconciler.reconcile(in: context)

        XCTAssertTrue(summary.isEmpty)
        XCTAssertEqual(count(BBVehicle.self, in: context), 2)
    }

    func testReconcileIsIdempotent() throws {
        let store = try container()
        let context = store.mainContext
        let owner = account(context)
        _ = vehicle(context, vin: "VIN-SAME", account: owner,
                    lastUpdated: Date(timeIntervalSince1970: 1_700_000_000))
        _ = vehicle(context, vin: "VIN-SAME", account: owner,
                    lastUpdated: Date(timeIntervalSince1970: 1_700_900_000))
        try context.save()

        let first = StoreReconciler.reconcile(in: context)
        let second = StoreReconciler.reconcile(in: context)

        XCTAssertEqual(first.vehiclesMerged, 1)
        XCTAssertTrue(second.isEmpty, "a reconciled store has nothing left to collapse")
    }
}
