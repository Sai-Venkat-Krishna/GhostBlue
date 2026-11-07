//
//  VehicleHeroControlTests.swift
//  GhostBlueTests
//
//  Covers the Home hero's control surface: a tapped region runs the real
//  command, shows as in-flight while the vehicle is responding, and only
//  settles once the status confirms the change.
//

import BetterBlueKit
import SwiftData
import XCTest
@testable import GhostBlue

@MainActor
final class VehicleHeroControlTests: XCTestCase {
    /// Held for the lifetime of the test: `mainContext` traps once its
    /// container is deallocated.
    private var container: ModelContainer?

    override func tearDown() {
        container = nil
        super.tearDown()
    }

    private func makeContext() throws -> ModelContext {
        let container = try ModelContainer(
            for: Schema([
                BBAccount.self,
                BBVehicle.self,
                BBHTTPLog.self,
                ClimatePreset.self,
                ChargeSession.self,
                TripRecord.self,
                RemoteCommandActivity.self,
                MaintenanceReminder.self,
                VehicleAutomation.self
            ]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        self.container = container
        return container.mainContext
    }

    /// A fake-brand account with one EV, locked and climate off.
    private func makeFakeVehicle(in context: ModelContext) throws -> BBVehicle {
        let account = BBAccount(
            username: "testaccount@betterblue.com",
            password: "betterblue",
            pin: "1234",
            brand: .fake,
            region: .usa
        )
        context.insert(account)

        let vehicle = BBVehicle(
            from: Vehicle(
                vin: "HEROTESTVIN00001",
                regId: "hero-test",
                model: "2025 IONIQ 5",
                accountId: account.id,
                fuelType: .electric,
                generation: 3,
                odometer: Distance(length: 12_480, units: .miles)
            )
        )
        // Insert before wiring the relationship: SwiftData traps when a
        // managed model is attached to one the context has not seen yet.
        context.insert(vehicle)
        vehicle.account = account
        vehicle.lockStatus = .locked
        vehicle.climateStatus = VehicleStatus.ClimateStatus(
            defrostOn: false,
            airControlOn: false,
            steeringWheelHeatingOn: false,
            temperature: Temperature(value: 72, units: .fahrenheit)
        )
        vehicle.evStatus = VehicleStatus.EVStatus(
            charging: false,
            chargeSpeed: 0,
            evRange: VehicleStatus.FuelRange(
                range: Distance(length: 184, units: .miles),
                percentage: 62
            ),
            plugType: .acCharger,
            chargeTime: .seconds(0)
        )
        try context.save()

        return vehicle
    }

    /// Polls `condition` on the main actor until it holds or the budget runs out.
    private func waitUntil(
        _ description: String,
        timeout: TimeInterval = 30,
        _ condition: () -> Bool
    ) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() >= deadline {
                XCTFail("Timed out waiting for \(description)")
                return
            }
            try await Task.sleep(for: .milliseconds(100))
        }
    }

    func testLockRegionUnlocksTheVehicleAndSettlesOnConfirmedStatus() async throws {
        let context = try makeContext()
        let vehicle = try makeFakeVehicle(in: context)
        let account = try XCTUnwrap(vehicle.account)
        try await account.initialize(modelContext: context)

        let tracker = RemoteCommands.tracker(.lock, for: vehicle)
        RemoteCommands.toggleLock(vehicle, modelContext: context)

        // The tap must read as in-flight rather than instantly succeeding:
        // BlueLink round-trips are slow and the hero may not run ahead of them.
        XCTAssertTrue(tracker.isRunning)
        XCTAssertEqual(tracker.tag, "unlock")

        // The command reaches the vehicle...
        try await waitUntil("the vehicle to report unlocked") {
            vehicle.lockStatus == .unlocked
        }
        // ...and the hero stays in-flight until the status poll confirms it.
        try await waitUntil("the wait to report the sent command") {
            if case .waiting = tracker.phase { return true }
            return false
        }
        XCTAssertTrue(tracker.isRunning)
        vehicle.wakeUpStatusWaiters()
        try await waitUntil("the lock region to settle") {
            !tracker.isRunning
        }

        XCTAssertEqual(vehicle.lockStatus, .unlocked)
        XCTAssertEqual(tracker.phase, .succeeded)
        XCTAssertNil(tracker.errorMessage)
    }

    func testClimateRegionStartsClimateWithTheSelectedPreset() async throws {
        let context = try makeContext()
        let vehicle = try makeFakeVehicle(in: context)
        let account = try XCTUnwrap(vehicle.account)
        try await account.initialize(modelContext: context)

        let tracker = RemoteCommands.tracker(.climate, for: vehicle)
        RemoteCommands.toggleClimate(
            vehicle,
            options: ClimateOptions(),
            presetName: nil,
            presetIcon: nil,
            modelContext: context
        )
        XCTAssertTrue(tracker.isRunning)

        try await waitUntil("climate to report on") {
            vehicle.climateStatus?.airControlOn == true
        }
        vehicle.wakeUpStatusWaiters()
        try await waitUntil("the climate region to settle") {
            !tracker.isRunning
        }

        XCTAssertEqual(vehicle.climateStatus?.airControlOn, true)
        XCTAssertNil(tracker.errorMessage)
    }

    func testASecondTapCancelsACommandStillWaitingOnTheVehicle() async throws {
        let context = try makeContext()
        let vehicle = try makeFakeVehicle(in: context)
        let account = try XCTUnwrap(vehicle.account)
        try await account.initialize(modelContext: context)

        let tracker = RemoteCommands.tracker(.lock, for: vehicle)
        RemoteCommands.toggleLock(vehicle, modelContext: context)
        XCTAssertTrue(tracker.isRunning)

        RemoteCommands.toggleLock(vehicle, modelContext: context)

        try await waitUntil("the cancelled region to settle") {
            !tracker.isRunning
        }
        // Cancelling is not a failure, so no error is surfaced on the hero.
        XCTAssertEqual(tracker.phase, .idle)
        XCTAssertNil(tracker.errorMessage)
    }

    func testAFailedCommandShowsItsOwnStateThenSettles() async throws {
        let context = try makeContext()
        let vehicle = try makeFakeVehicle(in: context)
        var debug = BBDebugConfiguration()
        debug.shouldFailLock = true
        vehicle.debugConfiguration = debug
        try context.save()
        let account = try XCTUnwrap(vehicle.account)
        try await account.initialize(modelContext: context)

        // The fixture starts locked; unlock first so Lock is the next tap.
        vehicle.lockStatus = .unlocked
        let tracker = RemoteCommands.tracker(.lock, for: vehicle)
        RemoteCommands.setLocked(true, vehicle: vehicle, modelContext: context)

        try await waitUntil("the lock command to fail") {
            tracker.errorMessage != nil
        }
        XCTAssertFalse(tracker.isRunning)
        XCTAssertEqual(tracker.failureCount, 1)
        XCTAssertEqual(vehicle.lockStatus, .unlocked)
    }
}
