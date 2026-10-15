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

        let commands = VehicleHeroCommandCenter()
        commands.toggleLock(for: vehicle, modelContext: context)

        // The tap must read as in-flight rather than instantly succeeding:
        // BlueLink round-trips are slow and the hero may not run ahead of them.
        XCTAssertTrue(commands.isInFlight(.lock))

        // The command reaches the vehicle...
        try await waitUntil("the vehicle to report unlocked") {
            vehicle.lockStatus == .unlocked
        }
        // ...and the hero stays in-flight until the status poll confirms it.
        vehicle.wakeUpStatusWaiters()
        try await waitUntil("the lock region to settle") {
            !commands.isInFlight(.lock)
        }

        XCTAssertEqual(vehicle.lockStatus, .unlocked)
        XCTAssertNil(commands.errors[.lock])
    }

    func testClimateRegionStartsClimateWithTheSelectedPreset() async throws {
        let context = try makeContext()
        let vehicle = try makeFakeVehicle(in: context)
        let account = try XCTUnwrap(vehicle.account)
        try await account.initialize(modelContext: context)

        let commands = VehicleHeroCommandCenter()
        commands.toggleClimate(
            for: vehicle,
            options: ClimateOptions(),
            presetName: nil,
            presetIcon: nil,
            modelContext: context
        )
        XCTAssertTrue(commands.isInFlight(.climate))

        try await waitUntil("climate to report on") {
            vehicle.climateStatus?.airControlOn == true
        }
        vehicle.wakeUpStatusWaiters()
        try await waitUntil("the climate region to settle") {
            !commands.isInFlight(.climate)
        }

        XCTAssertEqual(vehicle.climateStatus?.airControlOn, true)
        XCTAssertNil(commands.errors[.climate])
    }

    func testASecondTapCancelsACommandStillWaitingOnTheVehicle() async throws {
        let context = try makeContext()
        let vehicle = try makeFakeVehicle(in: context)
        let account = try XCTUnwrap(vehicle.account)
        try await account.initialize(modelContext: context)

        let commands = VehicleHeroCommandCenter()
        commands.toggleLock(for: vehicle, modelContext: context)
        XCTAssertTrue(commands.isInFlight(.lock))

        commands.toggleLock(for: vehicle, modelContext: context)

        try await waitUntil("the cancelled region to settle") {
            !commands.isInFlight(.lock)
        }
        // Cancelling is not a failure, so no error is surfaced on the hero.
        XCTAssertNil(commands.errors[.lock])
    }
}
