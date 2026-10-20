import BetterBlueKit
import SwiftData
import XCTest
@testable import GhostBlue

@MainActor
final class ChargingReliabilityTests: XCTestCase {
    private func vehicle() -> BBVehicle {
        let result = BBVehicle(from: Vehicle(vin: "TEST-CHARGING", regId: "test", model: "IONIQ 5",
            accountId: UUID(), fuelType: .electric, generation: 3, odometer: .init(length: 0, units: .miles)))
        result.notifyChargeComplete = false
        result.notifyChargeInterrupted = false
        result.notifyOpenPanels = false
        result.notifyLowEVBattery = false
        result.notifyLow12VBattery = false
        result.notifyTirePressure = false
        return result
    }

    private func container() throws -> ModelContainer {
        try ModelContainer(for: Schema([BBAccount.self, BBVehicle.self, ClimatePreset.self, ChargeSession.self]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }

    private func date(_ month: Int, _ day: Int, _ hour: Int, minute: Int = 0) -> Date {
        SDGESmartChargingSchedule.calendar().date(from: DateComponents(
            year: 2026, month: month, day: day, hour: hour, minute: minute))!
    }

    private func status(charging: Bool, at date: Date, percentage: Double = 30) -> VehicleStatus {
        VehicleStatus(vin: "TEST-CHARGING", evStatus: .init(charging: charging,
            chargeSpeed: charging ? 7.2 : 0, evRange: .init(range: .init(length: 100, units: .miles), percentage: percentage),
            plugType: .acCharger, chargeTime: .seconds(3600), targetSocAC: 80, targetSocDC: 90),
            location: .init(latitude: 32.7, longitude: -117.1), lockStatus: .locked,
            climateStatus: .init(defrostOn: false, airControlOn: false, steeringWheelHeatingOn: false,
                temperature: .init(value: 70, units: .fahrenheit)), syncDate: date)
    }

    func testTransientStopKeepsPresentationAndHistoryInAgreement() throws {
        let store = try container()
        let car = vehicle()
        store.mainContext.insert(car)
        let start = date(9, 14, 1)
        car.updateStatus(with: status(charging: true, at: start))
        let candidate = start.addingTimeInterval(60)
        let accepted = car.updateStatus(with: status(charging: false, at: candidate))
        XCTAssertTrue(accepted.evStatus?.charging == true)
        XCTAssertEqual(accepted.evStatus?.chargeSpeed, 0)
        XCTAssertEqual(car.chargingDisplayState(at: candidate), .confirmingStop)
        XCTAssertTrue(try XCTUnwrap(store.mainContext.fetch(FetchDescriptor<ChargeSession>()).first).isActive)
        car.updateStatus(with: status(charging: true, at: candidate.addingTimeInterval(60)))
        XCTAssertNil(car.pendingChargingStopDate)
        XCTAssertEqual(try store.mainContext.fetchCount(FetchDescriptor<ChargeSession>()), 1)
    }

    func testReplayedStopDoesNotBecomeConfirmedJustBecauseItWasFetchedAgain() throws {
        let store = try container()
        let car = vehicle()
        store.mainContext.insert(car)
        let start = date(9, 14, 1)
        car.updateStatus(with: status(charging: true, at: start))
        let stoppedAt = start.addingTimeInterval(60)
        let stopped = status(charging: false, at: stoppedAt)
        car.updateStatus(with: stopped)
        var replay = stopped
        replay.lastUpdated = stopped.lastUpdated.addingTimeInterval(120)
        car.updateStatus(with: replay)
        XCTAssertEqual(car.chargingDisplayState(at: stoppedAt), .confirmingStop)
        XCTAssertTrue(try XCTUnwrap(store.mainContext.fetch(FetchDescriptor<ChargeSession>()).first).isActive)
        car.updateStatus(with: status(charging: false, at: stoppedAt.addingTimeInterval(60)))
        XCTAssertFalse(car.evStatus?.charging ?? true)
        XCTAssertNil(car.pendingChargingStopDate)
        XCTAssertEqual(try store.mainContext.fetch(FetchDescriptor<ChargeSession>()).first?.endDate, stoppedAt)
    }

    func testOlderSampleCannotRollBackVehicleOrChargingState() {
        let car = vehicle()
        let latest = date(9, 14, 1)
        car.updateStatus(with: status(charging: true, at: latest, percentage: 50))
        let accepted = car.updateStatus(with: status(charging: false, at: latest.addingTimeInterval(-60), percentage: 20))
        XCTAssertEqual(car.evStatus?.evRange.percentage, 50)
        XCTAssertTrue(accepted.evStatus?.charging == true)
        XCTAssertEqual(accepted.syncDate, latest)
        XCTAssertNil(car.pendingChargingStopDate)
    }

    func testMissingEVFieldsDoNotSignalAStop() {
        let car = vehicle()
        let start = date(9, 14, 1)
        car.updateStatus(with: status(charging: true, at: start))
        var partial = status(charging: false, at: start.addingTimeInterval(60))
        partial.evStatus = nil
        let accepted = car.updateStatus(with: partial)
        XCTAssertTrue(accepted.evStatus?.charging == true)
        XCTAssertNil(car.pendingChargingStopDate)
    }

    func testChargingLabelsSeparateScheduledPauseCompletionAndOutdatedData() {
        let car = vehicle()
        car.smartChargingEnabled = true
        car.smartChargingHomeOnly = false
        let morningPause = date(9, 14, 7)
        car.updateStatus(with: status(charging: false, at: morningPause))
        XCTAssertEqual(car.chargingDisplayState(at: morningPause), .scheduledPause)
        XCTAssertEqual(car.chargingDisplayState(at: morningPause.addingTimeInterval(601)), .outdated)
        let midday = date(9, 14, 10)
        car.updateStatus(with: status(charging: false, at: midday, percentage: 80))
        XCTAssertEqual(car.chargingDisplayState(at: midday), .completed)
    }

    func testMeasuredEnergyIncludingZeroOverridesBatteryEstimateAndPersists() throws {
        let store = try container()
        let car = vehicle()
        car.batteryCapacityKWh = 77.4
        let session = ChargeSession(vin: car.vin, startDate: .now, startPercentage: 20, isDCFast: false)
        session.endPercentage = 50
        session.manualEnergyKWh = 27.6
        session.electricityRatePerKWh = 0.2
        session.rateWasLocationAware = true
        store.mainContext.insert(session)
        try store.mainContext.save()
        XCTAssertEqual(car.energyDelivered(for: session), 27.6)
        XCTAssertEqual(try XCTUnwrap(car.chargingCost(for: session)), 5.52, accuracy: 0.0001)
        session.manualEnergyKWh = 0
        XCTAssertEqual(car.energyDelivered(for: session), 0)
        session.manualEnergyKWh = nil
        XCTAssertEqual(car.energyDelivered(for: session), 23.22, accuracy: 0.0001)
    }

    private var tariff: ChargingTariff {
        ChargingTariff(standardRate: 0.3, offPeakRate: 0.1, peakRate: 0.6, usesSDGE: true,
            offPeakStartHour: 0, offPeakEndHour: 6, timeZoneIdentifier: "America/Los_Angeles")
    }

    func testTariffSplitsMiddayAndPeakBoundaries() {
        XCTAssertEqual(tariff.cost(energyKWh: 10, from: date(9, 14, 9), to: date(9, 14, 11)), 2, accuracy: 0.001)
        XCTAssertEqual(tariff.cost(energyKWh: 10, from: date(9, 14, 13), to: date(9, 14, 15)), 2, accuracy: 0.001)
        XCTAssertEqual(tariff.cost(energyKWh: 10, from: date(9, 14, 15), to: date(9, 14, 17)), 4.5, accuracy: 0.001)
    }

    func testTariffCrossesMidnightAndBothDSTChanges() {
        XCTAssertEqual(tariff.cost(energyKWh: 10, from: date(9, 14, 23), to: date(9, 15, 1)), 2, accuracy: 0.001)
        for (month, day) in [(3, 8), (11, 1)] {
            XCTAssertEqual(tariff.cost(energyKWh: 10, from: date(month, day, 0), to: date(month, day, 7)), 1, accuracy: 0.001)
        }
    }

    func testHistoricalTariffSnapshotAndReceiptWinOverCurrentSettings() throws {
        let store = try container()
        let car = vehicle()
        let session = ChargeSession(vin: car.vin, startDate: date(9, 14, 9), startPercentage: 20, isDCFast: false)
        session.endDate = date(9, 14, 11)
        session.manualEnergyKWh = 10
        session.tariff = tariff
        store.mainContext.insert(session)
        try store.mainContext.save()
        car.electricityRatePerKWh = 100
        XCTAssertEqual(try XCTUnwrap(car.chargingCost(for: session)), 2, accuracy: 0.001)
        session.actualCost = 1.25
        XCTAssertEqual(car.chargingCost(for: session), 1.25)
        let saved = try XCTUnwrap(store.mainContext.fetch(FetchDescriptor<ChargeSession>()).first)
        XCTAssertEqual(saved.tariff, tariff)
    }

    func testOverridesExpireAtActualNextBoundaryIncludingHolidays() {
        let car = vehicle()
        let pause = date(9, 14, 7)
        car.setChargingOverride(.chargeNow, at: pause)
        XCTAssertEqual(car.chargingPreference(at: pause), .charge)
        XCTAssertEqual(car.smartChargingOverrideExpiresAt, date(9, 14, 10))
        XCTAssertNil(car.activeChargingOverride(at: date(9, 14, 10)))
        car.setChargingOverride(.skipWindow, at: date(9, 7, 1)) // Labor Day
        XCTAssertEqual(car.smartChargingOverrideExpiresAt, date(9, 7, 14))
        car.setChargingOverride(nil)
        XCTAssertNil(car.smartChargingOverrideExpiresAt)
    }

    func testWeekendPreviewOmitsWeekdayPauseAndResume() {
        let transitions = SDGESmartChargingSchedule.transitions(after: date(9, 12, 1), count: 3)
        XCTAssertEqual(transitions.map(\.date), [date(9, 12, 14), date(9, 13, 0), date(9, 13, 14)])
    }

    func testFailedRetryDoesNotHideMissingConfirmedScheduleCheck() {
        let car = vehicle()
        let now = date(9, 14, 10, minute: 10)
        car.smartChargingEnabled = true
        car.smartChargingHomeOnly = false
        car.updateStatus(with: status(charging: false, at: now))
        car.smartChargingLastConfirmedAt = date(9, 14, 5)
        car.smartChargingLastRunAt = now
        XCTAssertNotNil(car.smartChargingWarning(at: now))
        car.smartChargingLastConfirmedAt = now
        XCTAssertNil(car.smartChargingWarning(at: now))
    }

    func testCSVUsesMeasuredEnergyReceiptAndUnambiguousDates() {
        let car = vehicle()
        car.batteryCapacityKWh = 77.4
        let session = ChargeSession(vin: car.vin, startDate: date(9, 14, 9), startPercentage: 20, isDCFast: true)
        session.endDate = date(9, 14, 10)
        session.endPercentage = 50
        session.manualEnergyKWh = 27.6
        session.actualCost = 8.2
        let csv = ChargingCSVExporter.csv(sessions: [session], vehicle: car)
        XCTAssertTrue(csv.contains("2026-09-14T16:00:00Z"))
        XCTAssertTrue(csv.contains("\"27.6\",\"Measured · manual\",\"8.2\",\"Receipt\""))
        XCTAssertFalse(csv.contains(car.vin))
        XCTAssertEqual(csv.components(separatedBy: "\r\n").count, 3)
    }

    func testWidgetFreshnessUsesSameChargingIntervalAsTimeline() {
        let interval = VehicleRefreshPolicy.interval(requested: 1800, pluggedIn: true)
        XCTAssertEqual(interval, 600)
        let now = Date()
        XCTAssertTrue(VehicleRefreshPolicy.shouldRefresh(lastFetch: now.addingTimeInterval(-601), now: now, interval: interval))
        XCTAssertFalse(VehicleRefreshPolicy.shouldRefresh(lastFetch: now.addingTimeInterval(-599), now: now, interval: interval))
    }

    func testCancelledRefreshLoopExitsWithoutExecutingAction() async {
        var actions = 0
        let task = Task { await VehicleRefreshPolicy.run(interval: { 60 }) { actions += 1 } }
        task.cancel()
        await task.value
        XCTAssertEqual(actions, 0)
    }

    func testCancellationDuringCommandWaitReturnsPromptly() async throws {
        let store = try container()
        let car = vehicle()
        store.mainContext.insert(car)
        let finished = expectation(description: "Command wait responds to cancellation")
        let task = Task { @MainActor in
            defer { finished.fulfill() }
            do {
                try await car.waitForStatusChange(modelContext: store.mainContext, condition: { _ in false }, initialDelaySeconds: 60)
                XCTFail("Expected cancellation")
            } catch is CancellationError {} catch { XCTFail("Unexpected error: \(error)") }
        }
        // Allow registration of the cancellable waiter before cancelling it.
        try await Task.sleep(for: .milliseconds(30))
        task.cancel()
        await fulfillment(of: [finished], timeout: 2)
    }
}

@MainActor
final class CachedStatusReliabilityTests: XCTestCase {
    private var vehicle: Vehicle {
        Vehicle(vin: "MOCK", regId: "mock", model: "Mock", accountId: UUID(), fuelType: .electric,
                generation: 3, odometer: .init(length: 0, units: .miles))
    }
    private var token: AuthToken { .init(accessToken: "test", refreshToken: "test", expiresAt: .distantFuture) }

    private func waitForCalls(_ count: Int, on mock: ControlledStatusClient) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(2))
        while mock.calls.count < count, ContinuousClock.now < deadline { await Task.yield() }
        XCTAssertEqual(mock.calls.count, count)
        guard mock.calls.count == count else { throw CancellationError() }
    }

    func testFreshReadNeverJoinsCachedReadAndLateCachedResponseCannotOverwriteIt() async throws {
        let mock = ControlledStatusClient()
        defer { mock.finishAll() }
        let client = CachedAPIClient(underlyingClient: mock)
        let car = vehicle, auth = token
        let cached = Task { try await client.fetchVehicleStatus(for: car, authToken: auth, cached: true) }
        try await waitForCalls(1, on: mock)
        let fresh = Task { try await client.fetchVehicleStatus(for: car, authToken: auth, cached: false) }
        try await waitForCalls(2, on: mock)
        XCTAssertEqual(mock.calls, [true, false])
        mock.finish(1, charging: true)
        let freshStatus = try await fresh.value
        XCTAssertTrue(freshStatus.evStatus?.charging == true)
        mock.finish(0, charging: false)
        _ = try await cached.value
        let subsequent = try await client.fetchVehicleStatus(for: car, authToken: auth, cached: true)
        XCTAssertTrue(subsequent.evStatus?.charging == true)
        XCTAssertEqual(mock.calls.count, 2)
    }

    func testIdenticalFreshReadsCoalesce() async throws {
        let mock = ControlledStatusClient()
        defer { mock.finishAll() }
        let client = CachedAPIClient(underlyingClient: mock)
        let car = vehicle, auth = token
        let first = Task { try await client.fetchVehicleStatus(for: car, authToken: auth, cached: false) }
        try await waitForCalls(1, on: mock)
        let second = Task { try await client.fetchVehicleStatus(for: car, authToken: auth, cached: false) }
        for _ in 0 ..< 20 { await Task.yield() }
        XCTAssertEqual(mock.calls.count, 1)
        mock.finish(0, charging: true)
        _ = try await first.value
        _ = try await second.value
    }

    func testPostCommandVerificationDoesNotJoinPreCommandRead() async throws {
        let mock = ControlledStatusClient()
        defer { mock.finishAll() }
        let client = CachedAPIClient(underlyingClient: mock)
        let car = vehicle, auth = token
        let old = Task { try await client.fetchVehicleStatus(for: car, authToken: auth, cached: false) }
        try await waitForCalls(1, on: mock)
        try await client.sendCommand(for: car, command: .startCharge, authToken: auth)
        let verification = Task { try await client.fetchVehicleStatus(for: car, authToken: auth, cached: false) }
        try await waitForCalls(2, on: mock)
        mock.finish(0, charging: false)
        _ = try await old.value
        mock.finish(1, charging: true)
        let verified = try await verification.value
        XCTAssertTrue(verified.evStatus?.charging == true)
    }
}

@MainActor
private final class ControlledStatusClient: APIClientProtocol {
    var calls: [Bool] = []
    private var pending: [Int: CheckedContinuation<VehicleStatus, Error>] = [:]
    func login() async throws -> AuthToken { .init(accessToken: "mock", refreshToken: "mock", expiresAt: .distantFuture) }
    func fetchVehicles(authToken: AuthToken) async throws -> [Vehicle] { [] }
    func sendCommand(for vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) async throws {}
    func fetchVehicleStatus(for vehicle: Vehicle, authToken: AuthToken, cached: Bool) async throws -> VehicleStatus {
        let index = calls.count
        calls.append(cached)
        return try await withCheckedThrowingContinuation { pending[index] = $0 }
    }
    func finish(_ index: Int, charging: Bool) {
        let status = VehicleStatus(vin: "MOCK", evStatus: .init(charging: charging, chargeSpeed: charging ? 7 : 0,
            evRange: .init(range: .init(length: 100, units: .miles), percentage: 30), plugType: .acCharger, chargeTime: .seconds(60)),
            location: .init(latitude: 0, longitude: 0), lockStatus: .locked,
            climateStatus: .init(defrostOn: false, airControlOn: false, steeringWheelHeatingOn: false,
                                temperature: .init(value: 70, units: .fahrenheit)))
        pending.removeValue(forKey: index)?.resume(returning: status)
    }
    func finishAll() {
        for continuation in pending.values { continuation.resume(throwing: CancellationError()) }
        pending.removeAll()
    }
}
