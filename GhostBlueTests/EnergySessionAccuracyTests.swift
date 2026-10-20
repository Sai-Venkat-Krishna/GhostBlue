import BetterBlueKit
import SwiftData
import XCTest
@testable import GhostBlue

/// Session detection and energy accounting behind the Energy tab.
@MainActor
final class EnergySessionAccuracyTests: XCTestCase {
    private let vin = "TEST-ENERGY"
    private let t0 = Date(timeIntervalSince1970: 1_790_000_000)

    private func container() throws -> ModelContainer {
        try ModelContainer(
            for: Schema([
                BBAccount.self, BBVehicle.self, ClimatePreset.self, ChargeSession.self,
                VehicleAutomation.self, MaintenanceReminder.self
            ]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
    }

    private func vehicle(model: String = "IONIQ 5", fuelType: FuelType = .electric, in store: ModelContainer) -> BBVehicle {
        let car = BBVehicle(from: Vehicle(vin: vin, regId: "test", model: model, accountId: UUID(),
            fuelType: fuelType, generation: 3, odometer: .init(length: 1000, units: .miles)))
        car.notifyChargeComplete = false
        car.notifyChargeInterrupted = false
        car.notifyOpenPanels = false
        car.notifyLowEVBattery = false
        car.notifyLow12VBattery = false
        car.notifyTirePressure = false
        store.mainContext.insert(car)
        return car
    }

    private func status(
        charging: Bool,
        at syncDate: Date,
        fetchedAt: Date? = nil,
        percentage: Double,
        plugType: VehicleStatus.PlugType = .acCharger,
        speed: Double? = nil,
        odometer: Double = 1000
    ) -> VehicleStatus {
        var result = VehicleStatus(
            vin: vin,
            evStatus: .init(
                charging: charging,
                chargeSpeed: speed ?? (charging ? 7.2 : 0),
                evRange: .init(range: .init(length: percentage * 2.5, units: .miles), percentage: percentage),
                plugType: plugType,
                chargeTime: .seconds(charging ? 3600 : 0),
                targetSocAC: 80,
                targetSocDC: 90
            ),
            location: .init(latitude: 32.7, longitude: -117.1),
            lockStatus: .locked,
            climateStatus: .init(defrostOn: false, airControlOn: false, steeringWheelHeatingOn: false,
                                 temperature: .init(value: 70, units: .fahrenheit)),
            odometer: .init(length: odometer, units: .miles),
            syncDate: syncDate
        )
        result.lastUpdated = fetchedAt ?? syncDate
        return result
    }

    private func sessions(_ store: ModelContainer) throws -> [ChargeSession] {
        try store.mainContext.fetch(FetchDescriptor<ChargeSession>(sortBy: [SortDescriptor(\.startDate)]))
    }

    // MARK: - Stop detection

    func testUnplugEndsSessionOnFirstSample() throws {
        let store = try container()
        let car = vehicle(in: store)
        car.updateStatus(with: status(charging: true, at: t0, percentage: 40))
        let unplugged = t0.addingTimeInterval(1800)
        let accepted = car.updateStatus(with: status(charging: false, at: unplugged, percentage: 52, plugType: .unplugged))

        XCTAssertEqual(accepted.evStatus?.charging, false)
        XCTAssertEqual(accepted.evStatus?.pluggedIn, false)
        XCTAssertNil(car.pendingChargingStopDate)
        XCTAssertNotEqual(car.chargingDisplayState(at: unplugged), .confirmingStop)
        let session = try XCTUnwrap(sessions(store).first)
        XCTAssertEqual(session.endDate, unplugged)
        XCTAssertEqual(session.endPercentage, 52)
    }

    func testReachingTargetEndsSessionOnFirstSample() throws {
        let store = try container()
        let car = vehicle(in: store)
        car.updateStatus(with: status(charging: true, at: t0, percentage: 60))
        let done = t0.addingTimeInterval(3600)
        car.updateStatus(with: status(charging: false, at: done, percentage: 80))

        XCTAssertNil(car.pendingChargingStopDate)
        XCTAssertEqual(car.chargingDisplayState(at: done), .completed)
        XCTAssertEqual(try XCTUnwrap(sessions(store).first).endDate, done)
    }

    func testUnchallengedCachedStopConfirmsAfterDelay() throws {
        let store = try container()
        let car = vehicle(in: store)
        car.updateStatus(with: status(charging: true, at: t0, percentage: 40))
        let stopAt = t0.addingTimeInterval(600)
        // BlueLink keeps serving the same snapshot: same sync date, later fetches.
        car.updateStatus(with: status(charging: false, at: stopAt, fetchedAt: stopAt, percentage: 45))
        car.updateStatus(with: status(charging: false, at: stopAt, fetchedAt: stopAt.addingTimeInterval(300), percentage: 45))
        XCTAssertNotNil(car.pendingChargingStopDate)
        XCTAssertTrue(try XCTUnwrap(sessions(store).first).isActive)

        car.updateStatus(with: status(
            charging: false, at: stopAt,
            fetchedAt: stopAt.addingTimeInterval(ChargeStopPolicy.confirmationDelay + 1), percentage: 45
        ))
        XCTAssertNil(car.pendingChargingStopDate)
        XCTAssertEqual(car.evStatus?.charging, false)
        let session = try XCTUnwrap(sessions(store).first)
        XCTAssertEqual(session.endDate, stopAt, "The stop is dated by the snapshot, not by when it was confirmed")
    }

    func testMalformedZeroPercentStopIsNotDefinitive() {
        let zero = VehicleStatus.EVStatus(
            charging: false, chargeSpeed: 0,
            evRange: .init(range: .init(length: 0, units: .miles), percentage: 0),
            plugType: .unplugged, chargeTime: .seconds(0)
        )
        XCTAssertFalse(ChargeStopPolicy.isDefinitive(zero))
    }

    // MARK: - Session boundaries

    func testLongPollingGapKeepsOneSessionAndItsEnergy() throws {
        let store = try container()
        let car = vehicle(in: store)
        car.updateStatus(with: status(charging: true, at: t0, percentage: 30))
        car.updateStatus(with: status(charging: true, at: t0.addingTimeInterval(8 * 3600), percentage: 70))

        let all = try sessions(store)
        XCTAssertEqual(all.count, 1)
        XCTAssertEqual(all[0].percentAdded, 40)
        XCTAssertEqual(car.energyDelivered(for: all[0]), 77.4 * 0.4, accuracy: 0.001)
    }

    func testDrivingBetweenChargingSamplesStartsNewSession() throws {
        let store = try container()
        let car = vehicle(in: store)
        car.updateStatus(with: status(charging: true, at: t0, percentage: 30, odometer: 1000))
        car.updateStatus(with: status(charging: true, at: t0.addingTimeInterval(3 * 3600), percentage: 55, odometer: 1042))

        let all = try sessions(store)
        XCTAssertEqual(all.count, 2)
        XCTAssertEqual(all[0].endDate, t0)
        XCTAssertNil(all[1].endDate)
        XCTAssertEqual(all[1].startPercentage, 55)
    }

    func testChargeLostBetweenSamplesStartsNewSession() throws {
        let store = try container()
        let car = vehicle(in: store)
        car.updateStatus(with: status(charging: true, at: t0, percentage: 60))
        car.updateStatus(with: status(charging: true, at: t0.addingTimeInterval(5 * 3600), percentage: 41))
        XCTAssertEqual(try sessions(store).count, 2)
    }

    // MARK: - Energy

    func testHeldChargerReadingCannotExceedChargeAdded() {
        let session = ChargeSession(vin: vin, startDate: t0, startPercentage: 92, isDCFast: false)
        session.endPercentage = 92
        session.energyAddedKWh = 110.36
        XCTAssertEqual(session.resolvedEnergyKWh(batteryCapacityKWh: nil), 1.2, accuracy: 0.001)
        XCTAssertEqual(session.resolvedEnergyKWh(batteryCapacityKWh: 77.4), 0.774, accuracy: 0.001)
    }

    func testPowerIsIntegratedAsTrapezoidAndConservativelyAcrossLongGaps() throws {
        let store = try container()
        let car = vehicle(model: "Unknown EV", in: store)
        car.updateStatus(with: status(charging: true, at: t0, percentage: 40, speed: 0))
        car.updateStatus(with: status(charging: true, at: t0.addingTimeInterval(600), percentage: 41, speed: 10))
        let session = try XCTUnwrap(sessions(store).first)
        XCTAssertEqual(session.energyAddedKWh, 10.0 / 2 * 600 / 3600, accuracy: 0.0001)

        car.updateStatus(with: status(charging: true, at: t0.addingTimeInterval(600 + 7200), percentage: 60, speed: 4))
        XCTAssertEqual(session.energyAddedKWh, 0.8333 + 4 * 2, accuracy: 0.001)
    }

    func testModelDefaultCapacityIsUsedOnlyWithoutOwnerValue() throws {
        let store = try container()
        let car = vehicle(in: store)
        XCTAssertEqual(car.effectiveBatteryCapacityKWh, 77.4)
        XCTAssertTrue(car.isUsingModelDefaultBatteryCapacity)
        car.batteryCapacityKWh = 84
        XCTAssertEqual(car.effectiveBatteryCapacityKWh, 84)
        XCTAssertFalse(car.isUsingModelDefaultBatteryCapacity)

        let session = ChargeSession(vin: vin, startDate: t0, startPercentage: 20, isDCFast: false)
        session.endPercentage = 70
        XCTAssertEqual(car.energySource(for: session), "Estimated from battery · 84 kWh")
        car.batteryCapacityKWh = nil
        XCTAssertEqual(car.energySource(for: session), "Estimated from battery · 77.4 kWh model default")
    }

    func testBatteryCatalog() {
        func capacity(_ model: String, _ fuel: FuelType = .electric) -> Double? {
            BatteryCapacityCatalog.defaultCapacityKWh(model: model, fuelType: fuel)
        }
        XCTAssertEqual(capacity("IONIQ 5"), 77.4)
        XCTAssertEqual(capacity("Hyundai IONIQ 5"), 77.4)
        XCTAssertEqual(capacity("IONIQ 5 N"), 84.0)
        XCTAssertEqual(capacity("IONIQ 6"), 77.4)
        XCTAssertEqual(capacity("Ioniq Electric"), 38.3)
        XCTAssertEqual(capacity("EV9"), 99.8)
        XCTAssertEqual(capacity("Kona Electric"), 64.8)
        XCTAssertEqual(capacity("TUCSON", .phev), 13.8)
        XCTAssertNil(capacity("TUCSON", .gas))
        XCTAssertNil(capacity("Mystery EV"))
    }

    // MARK: - Automations

    func testUnpluggedAutomationFiresOnTheUnplugSample() throws {
        let store = try container()
        let car = vehicle(in: store)
        let rule = VehicleAutomation(vin: vin, title: "Unplugged", kind: .unplugged)
        store.mainContext.insert(rule)
        car.updateStatus(with: status(charging: true, at: t0, percentage: 50))
        car.updateStatus(with: status(charging: false, at: t0.addingTimeInterval(900), percentage: 58, plugType: .unplugged))
        XCTAssertNotNil(rule.lastTriggeredAt, "Unplugging must not wait for a second stop sample")
    }

    func testReminderRequestsFollowRulesVehiclesAndNotificationSetting() throws {
        let store = try container()
        _ = vehicle(in: store)
        let reminder = VehicleAutomation(vin: vin, title: "Precondition", kind: .preconditionReminder, hour: 6, minute: 45)
        let orphan = VehicleAutomation(vin: "GONE", title: "Old car", kind: .preconditionReminder)
        let statusRule = VehicleAutomation(vin: vin, title: "Low", kind: .lowBattery)
        [reminder, orphan, statusRule].forEach(store.mainContext.insert)

        let requests = AutomationReminderScheduler.desiredRequests(in: store.mainContext, notificationsEnabled: true)
        XCTAssertEqual(requests.map(\.identifier), [AutomationReminderScheduler.identifier(for: reminder)])
        let trigger = try XCTUnwrap(requests.first?.trigger as? UNCalendarNotificationTrigger)
        XCTAssertEqual(trigger.dateComponents.hour, 6)
        XCTAssertEqual(trigger.dateComponents.minute, 45)
        XCTAssertTrue(trigger.repeats)

        XCTAssertTrue(AutomationReminderScheduler.desiredRequests(in: store.mainContext, notificationsEnabled: false).isEmpty)
        reminder.isEnabled = false
        XCTAssertTrue(AutomationReminderScheduler.desiredRequests(in: store.mainContext, notificationsEnabled: true).isEmpty)
    }

    // MARK: - Widget refresh

    func testOnTimeWidgetReloadFetchesEvenThoughFetchWasStampedLate() {
        let interval = VehicleRefreshPolicy.interval(requested: 4 * 3600, pluggedIn: false)
        let window = VehicleRefreshPolicy.freshnessWindow(forReloadInterval: interval)
        // The previous fetch finished 20 s after the timeline was built, so an
        // on-time reload sees data 20 s younger than the interval.
        let now = Date()
        let lastFetch = now.addingTimeInterval(-(interval - 20))
        XCTAssertTrue(VehicleRefreshPolicy.shouldRefresh(lastFetch: lastFetch, now: now, interval: window))
        XCTAssertEqual(VehicleRefreshPolicy.freshnessWindow(forReloadInterval: 600), 450)
        XCTAssertFalse(VehicleRefreshPolicy.shouldRefresh(lastFetch: now.addingTimeInterval(-60), now: now, interval: window))
    }

    // MARK: - Fake vehicle

    func testFakeVehicleChargesTowardTargetAndStops() async throws {
        let store = try container()
        let car = vehicle(in: store)
        let start = Date().addingTimeInterval(-3600)
        car.evStatus = .init(
            charging: true, chargeSpeed: 10.9,
            evRange: .init(range: .init(length: 100, units: .miles), percentage: 40),
            plugType: .acCharger, chargeTime: .seconds(3600), targetSocAC: 80, targetSocDC: 90
        )
        car.syncDate = start
        car.lastUpdated = start
        let provider = SwiftDataFakeVehicleProvider(modelContext: store.mainContext)

        let charging = try await provider.getVehicleStatus(for: vin, accountId: car.accountId)
        let ev = try XCTUnwrap(charging.evStatus)
        XCTAssertTrue(ev.charging)
        XCTAssertEqual(ev.evRange.percentage, 40 + 10.9 / 77.4 * 100, accuracy: 0.2)

        car.syncDate = Date().addingTimeInterval(-6 * 3600)
        let finished = try await provider.getVehicleStatus(for: vin, accountId: car.accountId)
        XCTAssertEqual(finished.evStatus?.charging, false)
        XCTAssertEqual(finished.evStatus?.evRange.percentage, 80)
        XCTAssertEqual(finished.evStatus?.pluggedIn, true)
    }

    func testFakeVehicleReportsStopInsteadOfEchoingPendingPlaceholder() async throws {
        let store = try container()
        let car = vehicle(in: store)
        car.updateStatus(with: status(charging: true, at: Date().addingTimeInterval(-120), percentage: 50))
        car.updateStatus(with: status(charging: false, at: Date().addingTimeInterval(-60), percentage: 51))
        XCTAssertEqual(car.evStatus?.charging, true, "Placeholder while the stop is confirmed")

        let provider = SwiftDataFakeVehicleProvider(modelContext: store.mainContext)
        let next = try await provider.getVehicleStatus(for: vin, accountId: car.accountId)
        XCTAssertEqual(next.evStatus?.charging, false)
    }
}
