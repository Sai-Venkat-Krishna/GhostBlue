//
//  WakeUpReductionTests.swift
//  GhostBlueTests
//
//  Real-time reads wake the car's modem (12V drain, Hyundai rate limits).
//  These pin the behaviour that keeps them to what a command needs.
//

import BetterBlueKit
import SwiftData
import XCTest
@testable import GhostBlue

@MainActor
final class WakeUpReductionTests: XCTestCase {
    private var container: ModelContainer?

    override func tearDown() {
        container = nil
        super.tearDown()
    }

    // MARK: - Re-login only for session errors

    private func error(_ type: APIError.ErrorType, code: Int? = nil, message: String = "test") -> APIError {
        APIError(message: message, code: code, apiName: "Test", errorType: type)
    }

    func testOnlySessionErrorsTriggerAReLogin() {
        // The token is the problem: log in again.
        XCTAssertTrue(BBAccount.shouldReauthenticate(for: error(.invalidCredentials, code: 401)))
        XCTAssertTrue(BBAccount.shouldReauthenticate(for: error(.invalidVehicleSession, code: 1005)))
        XCTAssertTrue(BBAccount.shouldReauthenticate(for: error(.general, code: 403)))
        XCTAssertTrue(BBAccount.shouldReauthenticate(for: error(.general)),
                      "An unrecognised failure still re-authenticates so the app cannot get stuck")

        // Transient or not fixable by a login: keep the token, no retry.
        for transient in [
            error(.serverError, code: 502),       // modem did not answer a real-time read
            error(.general, code: 429),           // rate limited
            error(.general, code: 503),
            error(.general, message: "Network error: The Internet connection appears to be offline."),
            error(.general, code: -1009),         // URLError: offline
            error(.general, code: -1001),         // URLError: timed out
            error(.concurrentRequest, code: 502),
            error(.invalidPin),
            error(.kiaInvalidRequest, code: 502),
            error(.regionNotSupported),
            error(.failedRetryLogin, code: 502),
            error(.requiresMFA)
        ] {
            XCTAssertFalse(BBAccount.shouldReauthenticate(for: transient), "\(transient.errorType) \(transient.code ?? 0)")
        }
    }

    // MARK: - Status waiter ordering

    func testAWakeUpIssuedBeforeASleepDoesNotCutItShort() async throws {
        let manager = BBVehicle.StatusWaitingManager()
        let vehicleId = UUID()
        // The wake from a poll's own status update, delivered late.
        let staleWake = ContinuousClock.now
        let start = ContinuousClock.now

        let sleeper = Task { try await manager.wait(vehicleId: vehicleId, seconds: 1) }
        try await Task.sleep(for: .milliseconds(150))
        let ended = await manager.wakeUp(vehicleId: vehicleId, issuedAt: staleWake)
        try await sleeper.value

        XCTAssertFalse(ended)
        XCTAssertGreaterThanOrEqual(ContinuousClock.now - start, .milliseconds(900))
    }

    func testAWakeUpIssuedDuringASleepEndsIt() async throws {
        let manager = BBVehicle.StatusWaitingManager()
        let vehicleId = UUID()
        let start = ContinuousClock.now

        let sleeper = Task { try await manager.wait(vehicleId: vehicleId, seconds: 10) }
        try await Task.sleep(for: .milliseconds(150))
        let ended = await manager.wakeUp(vehicleId: vehicleId, issuedAt: .now)
        try await sleeper.value

        XCTAssertTrue(ended)
        XCTAssertLessThan(ContinuousClock.now - start, .seconds(2))
    }

    // MARK: - One read per stop

    func testStoppingChargeFromTheHeroReadsTheCarOnce() async throws {
        let context = try makeContext()
        let vehicle = try await makeChargingFakeVehicle(in: context)
        let reads = StatusReadCounter(vin: vehicle.vin)
        defer { reads.stop() }

        let commands = VehicleHeroCommandCenter()
        commands.toggleCharge(for: vehicle, modelContext: context)
        XCTAssertTrue(commands.isInFlight(.charge))

        let deadline = Date().addingTimeInterval(30)
        while commands.isInFlight(.charge), Date() < deadline {
            try await Task.sleep(for: .milliseconds(100))
        }

        XCTAssertFalse(commands.isInFlight(.charge))
        XCTAssertNil(commands.errors[.charge])
        XCTAssertEqual(vehicle.evStatus?.charging, false)
        // Previously: an immediate read that still saw "charging", then the
        // verification poll. The commanded stop now confirms on the poll.
        XCTAssertEqual(reads.count, 1)
    }

    // MARK: - Helpers

    private func makeContext() throws -> ModelContext {
        let container = try ModelContainer(
            for: Schema([
                BBAccount.self, BBVehicle.self, BBHTTPLog.self, ClimatePreset.self,
                ChargeSession.self, TripRecord.self, RemoteCommandActivity.self,
                MaintenanceReminder.self, VehicleAutomation.self
            ]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        self.container = container
        return container.mainContext
    }

    private func makeChargingFakeVehicle(in context: ModelContext) async throws -> BBVehicle {
        let account = BBAccount(
            username: "testaccount@betterblue.com", password: "betterblue",
            pin: "1234", brand: .fake, region: .usa
        )
        context.insert(account)
        let vehicle = BBVehicle(from: Vehicle(
            vin: "WAKETEST00000001", regId: "wake", model: "IONIQ 5",
            accountId: account.id, fuelType: .electric, generation: 3,
            odometer: Distance(length: 1_000, units: .miles)
        ))
        context.insert(vehicle)
        vehicle.account = account
        vehicle.notifyChargeComplete = false
        vehicle.notifyChargeInterrupted = false
        vehicle.lockStatus = .locked
        vehicle.climateStatus = .init(defrostOn: false, airControlOn: false, steeringWheelHeatingOn: false,
                                      temperature: .init(value: 72, units: .fahrenheit))
        vehicle.location = .init(latitude: 32.7, longitude: -117.1)
        vehicle.evStatus = .init(
            charging: true, chargeSpeed: 10.9,
            evRange: .init(range: .init(length: 150, units: .miles), percentage: 55),
            plugType: .acCharger, chargeTime: .seconds(3600), targetSocAC: 80, targetSocDC: 90
        )
        vehicle.syncDate = Date()
        vehicle.lastUpdated = Date()
        try context.save()
        try await account.initialize(modelContext: context)
        return vehicle
    }
}

/// Counts status reads applied to one vehicle: every refresh path posts
/// `.bbVehicleStatusUpdated` from `BBVehicle.updateStatus`.
private final class StatusReadCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0
    private var observer: NSObjectProtocol?

    var count: Int { lock.withLock { value } }

    init(vin: String) {
        observer = NotificationCenter.default.addObserver(
            forName: .bbVehicleStatusUpdated, object: nil, queue: nil
        ) { [weak self] note in
            guard let self, (note.object as? String) == vin else { return }
            lock.withLock { value += 1 }
        }
    }

    func stop() {
        if let observer { NotificationCenter.default.removeObserver(observer) }
    }
}
