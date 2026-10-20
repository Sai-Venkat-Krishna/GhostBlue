//
//  SmartChargingAutomationTests.swift
//  GhostBlueTests
//
//  End-to-end checks of the SDG&E off-peak automation: the real
//  reconcileSmartCharging path against the fake vehicle, so each decision
//  sends the actual start/stop command and is verified by status polling.
//

import BetterBlueKit
import SwiftData
import XCTest
@testable import GhostBlue

@MainActor
final class SmartChargingAutomationTests: XCTestCase {
    /// `mainContext` traps once its container is deallocated.
    private var container: ModelContainer?

    override func tearDown() {
        container = nil
        super.tearDown()
    }

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

    /// San Diego wall-clock time; 2026-10-19 is a Monday.
    private func sanDiego(_ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        SDGESmartChargingSchedule.calendar().date(
            from: DateComponents(year: 2026, month: month, day: day, hour: hour, minute: minute)
        )!
    }

    /// A fake-brand IONIQ 5 on a home AC charger with smart charging on.
    private func makeCar(
        charging: Bool,
        percentage: Double = 62,
        plugType: VehicleStatus.PlugType = .acCharger,
        in context: ModelContext
    ) async throws -> (BBAccount, BBVehicle) {
        let account = BBAccount(
            username: "testaccount@betterblue.com", password: "betterblue",
            pin: "1234", brand: .fake, region: .usa
        )
        context.insert(account)
        let car = BBVehicle(from: Vehicle(
            vin: "SMARTCHARGE00001", regId: "smart", model: "IONIQ 5",
            accountId: account.id, fuelType: .electric, generation: 3,
            odometer: Distance(length: 12_480, units: .miles)
        ))
        context.insert(car)
        car.account = account
        car.notifyChargeComplete = false
        car.notifyChargeInterrupted = false
        car.smartChargingEnabled = true
        car.smartChargingHomeOnly = true
        car.smartChargingTargetSOC = 80
        car.homeLatitude = 32.7157
        car.homeLongitude = -117.1611
        car.location = .init(latitude: 32.7158, longitude: -117.1612)
        car.lockStatus = .locked
        car.climateStatus = .init(defrostOn: false, airControlOn: false, steeringWheelHeatingOn: false,
                                  temperature: .init(value: 72, units: .fahrenheit))
        car.evStatus = .init(
            charging: charging, chargeSpeed: charging ? 10.9 : 0,
            evRange: .init(range: .init(length: 184, units: .miles), percentage: percentage),
            plugType: plugType, chargeTime: .seconds(charging ? 3600 : 0),
            targetSocAC: 100, targetSocDC: 90
        )
        car.syncDate = Date()
        car.lastUpdated = Date()
        try context.save()
        try await account.initialize(modelContext: context)
        return (account, car)
    }

    private func openSessions(_ context: ModelContext) throws -> [ChargeSession] {
        try context.fetch(FetchDescriptor<ChargeSession>(predicate: #Predicate { $0.endDate == nil }))
    }

    // MARK: - Commands sent by the schedule

    func testWeekdayMorningPausesAChargingCarAndConfirmsOnFirstPoll() async throws {
        let context = try makeContext()
        let (account, car) = try await makeCar(charging: true, in: context)

        // The refresh inside reconcile opens the session the pause must close.
        let start = Date()
        let result = try await account.reconcileSmartCharging(for: car, at: sanDiego(10, 19, 7), modelContext: context)

        XCTAssertEqual(result.action, .stopped)
        XCTAssertEqual(result.message, "Charging paused and confirmed.")
        XCTAssertEqual(car.evStatus?.charging, false)
        XCTAssertNil(car.pendingChargingStopDate, "The commanded stop must not wait for a second sample")
        XCTAssertNotNil(car.smartChargingLastConfirmedAt)
        XCTAssertTrue(try openSessions(context).isEmpty, "The pause must close the charging session")
        // One 8 s settle delay and one verification poll, not two polls.
        XCTAssertLessThan(Date().timeIntervalSince(start), 14)
    }

    func testMiddaySuperOffPeakStartsChargingBelowTarget() async throws {
        let context = try makeContext()
        let (account, car) = try await makeCar(charging: false, in: context)

        let result = try await account.reconcileSmartCharging(for: car, at: sanDiego(10, 19, 11), modelContext: context)

        XCTAssertEqual(result.action, .started)
        XCTAssertEqual(car.evStatus?.charging, true)
        XCTAssertEqual(try openSessions(context).count, 1, "Starting the charge opens a session")
    }

    func testChargeNowOverrideStartsChargingDuringAPause() async throws {
        let context = try makeContext()
        let (account, car) = try await makeCar(charging: false, in: context)
        let pauseTime = sanDiego(10, 19, 7, 30)
        car.setChargingOverride(.chargeNow, at: pauseTime)

        let result = try await account.reconcileSmartCharging(for: car, at: pauseTime, modelContext: context)
        XCTAssertEqual(result.action, .started)
        XCTAssertEqual(car.evStatus?.charging, true)
    }

    // MARK: - Decisions that must not send a command

    func testWeekendAndHolidayMorningsAreChargeWindows() async throws {
        let context = try makeContext()
        let (account, car) = try await makeCar(charging: true, in: context)
        for date in [sanDiego(10, 24, 7), sanDiego(11, 26, 7)] { // Saturday; Thanksgiving
            let result = try await account.reconcileSmartCharging(for: car, at: date, modelContext: context)
            XCTAssertEqual(result.action, .alreadyCorrect, "\(date)")
            XCTAssertEqual(result.message, "Charging is already running as requested.")
        }
    }

    func testAfternoonPauseLeavesAStoppedCarAlone() async throws {
        let context = try makeContext()
        let (account, car) = try await makeCar(charging: false, in: context)
        let result = try await account.reconcileSmartCharging(for: car, at: sanDiego(10, 19, 15), modelContext: context)
        XCTAssertEqual(result.action, .alreadyCorrect)
        XCTAssertEqual(result.message, "Charging is already paused as requested.")
        XCTAssertNotNil(car.smartChargingLastConfirmedAt)
    }

    func testCarAtTargetIsNotStartedInAChargeWindow() async throws {
        let context = try makeContext()
        let (account, car) = try await makeCar(charging: false, percentage: 80, in: context)
        let result = try await account.reconcileSmartCharging(for: car, at: sanDiego(10, 19, 1), modelContext: context)
        XCTAssertEqual(result.action, .alreadyCorrect)
        XCTAssertEqual(result.message, "Battery is already at the 80% target.")
        XCTAssertEqual(car.evStatus?.charging, false)
    }

    func testSkipsUnpluggedAwayAndDCFastCars() async throws {
        let context = try makeContext()

        let (account, unplugged) = try await makeCar(charging: false, plugType: .unplugged, in: context)
        var result = try await account.reconcileSmartCharging(for: unplugged, at: sanDiego(10, 19, 11), modelContext: context)
        XCTAssertEqual(result.action, .skipped)
        XCTAssertEqual(result.message, "Vehicle is not plugged in.")

        unplugged.evStatus = .init(
            charging: false, chargeSpeed: 0,
            evRange: .init(range: .init(length: 184, units: .miles), percentage: 62),
            plugType: .acCharger, chargeTime: .seconds(0)
        )
        unplugged.location = .init(latitude: 32.80, longitude: -117.25) // ~11 km from home
        result = try await account.reconcileSmartCharging(for: unplugged, at: sanDiego(10, 19, 11), modelContext: context)
        XCTAssertEqual(result.action, .skipped)
        XCTAssertEqual(result.message, "Vehicle is outside the saved home area.")

        unplugged.location = .init(latitude: 32.7158, longitude: -117.1612)
        unplugged.evStatus = .init(
            charging: true, chargeSpeed: 150,
            evRange: .init(range: .init(length: 184, units: .miles), percentage: 62),
            plugType: .dcCharger, chargeTime: .seconds(600)
        )
        result = try await account.reconcileSmartCharging(for: unplugged, at: sanDiego(10, 19, 7), modelContext: context)
        XCTAssertEqual(result.action, .skipped, "A DC fast session is never paused")
        XCTAssertEqual(unplugged.evStatus?.charging, true)
    }

    // MARK: - Commanded stop policy

    func testCommandedStopConfirmsOnlyInsideItsWindow() throws {
        let context = try makeContext()
        let car = BBVehicle(from: Vehicle(vin: "CMDSTOP", regId: "c", model: "IONIQ 5", accountId: UUID(),
            fuelType: .electric, generation: 3, odometer: .init(length: 0, units: .miles)))
        car.notifyChargeComplete = false
        car.notifyChargeInterrupted = false
        context.insert(car)
        let t0 = Date(timeIntervalSince1970: 1_790_000_000)
        func status(_ charging: Bool, _ at: Date) -> VehicleStatus {
            var result = VehicleStatus(
                vin: "CMDSTOP",
                evStatus: .init(charging: charging, chargeSpeed: charging ? 7 : 0,
                    evRange: .init(range: .init(length: 150, units: .miles), percentage: 50),
                    plugType: .acCharger, chargeTime: .seconds(0), targetSocAC: 80),
                location: .init(latitude: 0, longitude: 0), lockStatus: .locked,
                climateStatus: .init(defrostOn: false, airControlOn: false, steeringWheelHeatingOn: false,
                                     temperature: .init(value: 70, units: .fahrenheit)),
                syncDate: at
            )
            result.lastUpdated = at
            return result
        }

        car.updateStatus(with: status(true, t0))
        car.chargeStopRequestedAt = t0.addingTimeInterval(30)
        car.updateStatus(with: status(false, t0.addingTimeInterval(60)))
        XCTAssertNil(car.pendingChargingStopDate)
        XCTAssertNil(car.chargeStopRequestedAt)
        XCTAssertEqual(car.evStatus?.charging, false)
        XCTAssertTrue(try openSessions(context).isEmpty)

        // A request older than the window is ordinary debounce again.
        car.updateStatus(with: status(true, t0.addingTimeInterval(3600)))
        car.chargeStopRequestedAt = t0.addingTimeInterval(3600)
        car.updateStatus(with: status(false, t0.addingTimeInterval(3600 + ChargeStopPolicy.commandWindow + 60)))
        XCTAssertNotNil(car.pendingChargingStopDate)
        XCTAssertNil(car.chargeStopRequestedAt, "An expired request is cleared")
    }
}
