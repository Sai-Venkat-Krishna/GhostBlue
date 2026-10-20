//
//  SwiftDataFakeVehicleProvider.swift
//  GhostBlue
//
//  SwiftData implementation of FakeVehicleProvider
//

import BetterBlueKit
import Foundation
import SwiftData

// MARK: - Debug Configuration Model

struct BBDebugConfiguration: Codable {
    var id: UUID = .init()

    // Debug failure modes
    var shouldFailCredentialValidation: Bool = false
    var shouldFailLogin: Bool = false
    var shouldFailVehicleFetch: Bool = false
    var shouldFailStatusFetch: Bool = false
    var shouldFailPinValidation: Bool = false

    // Command-specific failures
    var shouldFailLock: Bool = false
    var shouldFailUnlock: Bool = false
    var shouldFailStartClimate: Bool = false
    var shouldFailStopClimate: Bool = false
    var shouldFailStartCharge: Bool = false
    var shouldFailStopCharge: Bool = false

    // Custom error messages
    var customCredentialErrorMessage: String = "Invalid credentials"
    var customPinErrorMessage: String = "Invalid PIN"

    func shouldFailCommand(_ command: VehicleCommand) -> Bool {
        switch command {
        case .lock:
            shouldFailLock
        case .unlock:
            shouldFailUnlock
        case .startClimate:
            shouldFailStartClimate
        case .stopClimate:
            shouldFailStopClimate
        case .startCharge:
            shouldFailStartCharge
        case .stopCharge:
            shouldFailStopCharge
        case .setTargetSOC:
            false
        }
    }
}

// MARK: - SwiftData Vehicle Provider

@MainActor
public class SwiftDataFakeVehicleProvider: FakeVehicleProvider {
    private let modelContext: ModelContext

    public init(modelContext: ModelContext) {
        self.modelContext = modelContext
        BBLogger.info(.fakeAPI, "SwiftDataFakeVehicleProvider: Initialized with SwiftData context")
    }

    public func getFakeVehicles(for _: String, accountId: UUID) async throws -> [Vehicle] {
        // Fetch existing fake vehicles for this account from SwiftData
        let accountPredicate = #Predicate<BBVehicle> { vehicle in
            vehicle.accountId == accountId
        }

        let descriptor = FetchDescriptor<BBVehicle>(predicate: accountPredicate)
        let existingVehicles = try modelContext.fetch(descriptor)

        BBLogger.debug(.fakeAPI, "SwiftDataFakeVehicleProvider: Found \(existingVehicles.count) existing fake vehicles for account")
        return existingVehicles.map { $0.toVehicle() }
    }

    public func getVehicleStatus(for vin: String, accountId: UUID) async throws -> VehicleStatus {
        guard let bbVehicle = getBBVehicle(for: vin, accountId: accountId) else {
            throw APIError.logError("Fake vehicle not found: \(vin)", apiName: "FakeAPI")
        }

        let now = Date()
        advanceSimulatedCharge(for: bbVehicle, to: now)

        // Update lastUpdated to simulate a fresh fetch from the server
        bbVehicle.lastUpdated = now
        bbVehicle.syncDate = now
        try? modelContext.save()

        return createVehicleStatus(from: bbVehicle)
    }

    /// The fake "server" shares `bbVehicle.evStatus` with the app, which
    /// shows `charging = true` at 0 kW while it confirms a stop. The server
    /// itself already reported the stop, so report that rather than echoing
    /// the placeholder back as a resumed charge.
    private func serverEVStatus(for bbVehicle: BBVehicle) -> VehicleStatus.EVStatus? {
        guard var evStatus = bbVehicle.evStatus else { return nil }
        if bbVehicle.pendingChargingStopDate != nil, evStatus.charging, evStatus.chargeSpeed == 0 {
            evStatus.charging = false
        }
        return evStatus
    }

    /// Simulates a charging car between fetches: state of charge and range
    /// rise with the charger's power until the plug type's target, where
    /// charging stops and the car stays plugged in. Lets the Energy tab's
    /// session log be exercised without a real vehicle.
    private func advanceSimulatedCharge(for bbVehicle: BBVehicle, to now: Date) {
        guard var evStatus = serverEVStatus(for: bbVehicle) else { return }
        defer { bbVehicle.evStatus = evStatus }
        guard evStatus.charging, evStatus.pluggedIn,
              let last = bbVehicle.syncDate ?? bbVehicle.lastUpdated
        else { return }

        let elapsed = min(max(0, now.timeIntervalSince(last)), 6 * 3600)
        let capacity = bbVehicle.effectiveBatteryCapacityKWh ?? 77.4
        let speed = evStatus.chargeSpeed > 0 ? evStatus.chargeSpeed : Self.simulatedSpeedKW(for: evStatus.plugType)
        let target = evStatus.currentTargetSOC ?? 100
        let current = evStatus.evRange.percentage
        let gained = speed * elapsed / 3600 / capacity * 100
        // Never lower the charge of a car already at or above its target.
        let next = current >= target ? current : min(target, current + gained)

        let rangePerPercent = current > 0 ? evStatus.evRange.range.length / current : 3
        evStatus.evRange.percentage = next
        evStatus.evRange.range.length = (rangePerPercent * next).rounded()

        if next >= target {
            evStatus = VehicleStatus.EVStatus(
                charging: false,
                chargeSpeed: 0,
                evRange: evStatus.evRange,
                plugType: evStatus.plugType,
                chargeTime: .seconds(0),
                targetSocAC: evStatus.targetSocAC,
                targetSocDC: evStatus.targetSocDC
            )
        } else {
            let remainingMinutes = Int64(((target - next) / 100 * capacity / speed * 60).rounded(.up))
            evStatus = VehicleStatus.EVStatus(
                charging: true,
                chargeSpeed: speed,
                evRange: evStatus.evRange,
                plugType: evStatus.plugType,
                chargeTime: .seconds(remainingMinutes * 60),
                targetSocAC: evStatus.targetSocAC,
                targetSocDC: evStatus.targetSocDC
            )
        }
    }

    private static func simulatedSpeedKW(for plugType: VehicleStatus.PlugType) -> Double {
        plugType == .dcCharger ? 120 : 10.9
    }

    public func executeCommand(_ command: VehicleCommand, for vin: String, accountId: UUID) async throws {
        guard let bbVehicle = getBBVehicle(for: vin, accountId: accountId) else {
            throw APIError.logError("Fake vehicle not found for command: \(vin)", apiName: "FakeAPI")
        }

        // Update the BBVehicle directly based on the command
        switch command {
        case .lock:
            BBLogger.info(.fakeAPI, "FakeAPI: Locking fake vehicle '\(bbVehicle.vin)'")
            bbVehicle.lockStatus = .locked
        case .unlock:
            BBLogger.info(.fakeAPI, "FakeAPI: Unlocking fake vehicle '\(bbVehicle.vin)'")
            bbVehicle.lockStatus = .unlocked
        case let .startClimate(options):
            BBLogger.info(.fakeAPI, "FakeAPI: Starting climate for fake vehicle '\(bbVehicle.vin)' at \(options.temperature.value)°")
            bbVehicle.climateStatus = VehicleStatus.ClimateStatus(
                defrostOn: options.defrost,
                airControlOn: options.climate,
                steeringWheelHeatingOn: options.steeringWheel != 0,
                temperature: options.temperature,
            )
        case .stopClimate:
            BBLogger.info(.fakeAPI, "FakeAPI: Stopping climate for fake vehicle '\(bbVehicle.vin)'")
            if let currentClimate = bbVehicle.climateStatus {
                bbVehicle.climateStatus = VehicleStatus.ClimateStatus(
                    defrostOn: false,
                    airControlOn: false,
                    steeringWheelHeatingOn: false,
                    temperature: currentClimate.temperature,
                )
            }
        case .startCharge:
            BBLogger.info(.fakeAPI, "FakeAPI: Starting charge for fake vehicle '\(bbVehicle.vin)'")
            if let evStatus = bbVehicle.evStatus {
                let plugType = evStatus.plugType != .unplugged ? evStatus.plugType : .acCharger
                let speed = Self.simulatedSpeedKW(for: plugType)
                let capacity = bbVehicle.effectiveBatteryCapacityKWh ?? 77.4
                let target = (plugType == .dcCharger ? evStatus.targetSocDC : evStatus.targetSocAC) ?? 100
                let remainingPercentage = max(0, target - evStatus.evRange.percentage)
                let chargeTimeMinutes = Int64((remainingPercentage / 100 * capacity / speed * 60).rounded(.up))

                bbVehicle.evStatus = VehicleStatus.EVStatus(
                    charging: true,
                    chargeSpeed: speed,
                    evRange: evStatus.evRange,
                    plugType: plugType,
                    chargeTime: .seconds(chargeTimeMinutes * 60),
                    targetSocAC: evStatus.targetSocAC,
                    targetSocDC: evStatus.targetSocDC
                )
                // Simulated charge progress is measured from this moment.
                bbVehicle.syncDate = Date()
            }
        case .stopCharge:
            BBLogger.info(.fakeAPI, "FakeAPI: Stopping charge for fake vehicle '\(bbVehicle.vin)'")
            if let evStatus = bbVehicle.evStatus {
                bbVehicle.evStatus = VehicleStatus.EVStatus(
                    charging: false,
                    chargeSpeed: 0.0,
                    evRange: evStatus.evRange,
                    plugType: evStatus.plugType,
                    chargeTime: .seconds(0),
                    targetSocAC: evStatus.targetSocAC,
                    targetSocDC: evStatus.targetSocDC
                )
            }
        case let .setTargetSOC(acLevel, dcLevel):
            BBLogger.info(.fakeAPI, "FakeAPI: Setting target SOC for fake vehicle '\(bbVehicle.vin)' - AC: \(acLevel)%, DC: \(dcLevel)%")
            if let evStatus = bbVehicle.evStatus {
                bbVehicle.evStatus = VehicleStatus.EVStatus(
                    charging: evStatus.charging,
                    chargeSpeed: evStatus.chargeSpeed,
                    evRange: evStatus.evRange,
                    plugType: evStatus.plugType,
                    chargeTime: evStatus.chargeTime,
                    targetSocAC: Double(acLevel),
                    targetSocDC: Double(dcLevel)
                )
            }
        }

        bbVehicle.lastUpdated = Date()

        // Save changes to SwiftData
        try modelContext.save()
        BBLogger.debug(.fakeAPI, "SwiftDataFakeVehicleProvider: Saved vehicle status changes to SwiftData")
    }

    public func shouldFailCredentialValidation(accountId: UUID) async throws -> Bool {
        let accountPredicate = #Predicate<BBVehicle> { vehicle in
            vehicle.accountId == accountId
        }

        let descriptor = FetchDescriptor<BBVehicle>(predicate: accountPredicate)
        let vehicles = try modelContext.fetch(descriptor)

        return vehicles.compactMap(\.debugConfiguration).contains { $0.shouldFailCredentialValidation }
    }

    public func shouldFailLogin(accountId: UUID) async throws -> Bool {
        let accountPredicate = #Predicate<BBVehicle> { vehicle in
            vehicle.accountId == accountId
        }

        let descriptor = FetchDescriptor<BBVehicle>(predicate: accountPredicate)
        let vehicles = try modelContext.fetch(descriptor)

        return vehicles.compactMap(\.debugConfiguration).contains { $0.shouldFailLogin }
    }

    public func shouldFailVehicleFetch(accountId: UUID) async throws -> Bool {
        let accountPredicate = #Predicate<BBVehicle> { vehicle in
            vehicle.accountId == accountId
        }

        let descriptor = FetchDescriptor<BBVehicle>(predicate: accountPredicate)
        let vehicles = try modelContext.fetch(descriptor)

        return vehicles.compactMap(\.debugConfiguration).contains { $0.shouldFailVehicleFetch }
    }

    public func shouldFailStatusFetch(for vin: String, accountId: UUID) async throws -> Bool {
        guard let bbVehicle = getBBVehicle(for: vin, accountId: accountId),
              let debugConfig = bbVehicle.debugConfiguration
        else {
            return false
        }
        return debugConfig.shouldFailStatusFetch
    }

    public func shouldFailPinValidation(for vin: String, accountId: UUID) async throws -> Bool {
        guard let bbVehicle = getBBVehicle(for: vin, accountId: accountId),
              let debugConfig = bbVehicle.debugConfiguration
        else {
            return false
        }
        return debugConfig.shouldFailPinValidation
    }

    public func shouldFailCommand(_ command: VehicleCommand, for vin: String, accountId: UUID) async throws -> Bool {
        guard let bbVehicle = getBBVehicle(for: vin, accountId: accountId),
              let debugConfig = bbVehicle.debugConfiguration
        else {
            return false
        }
        return debugConfig.shouldFailCommand(command)
    }

    public func getCustomCredentialErrorMessage(accountId: UUID) async throws -> String {
        let accountPredicate = #Predicate<BBVehicle> { vehicle in
            vehicle.accountId == accountId
        }

        let descriptor = FetchDescriptor<BBVehicle>(predicate: accountPredicate)
        let vehicles = try modelContext.fetch(descriptor)

        return vehicles.compactMap(\.debugConfiguration).first?.customCredentialErrorMessage ?? "Invalid credentials"
    }

    public func getCustomPinErrorMessage(for vin: String, accountId: UUID) async throws -> String {
        guard let bbVehicle = getBBVehicle(for: vin, accountId: accountId),
              let debugConfig = bbVehicle.debugConfiguration
        else {
            return "Invalid PIN"
        }
        return debugConfig.customPinErrorMessage
    }

    private func getBBVehicle(for vin: String, accountId: UUID) -> BBVehicle? {
        let vinPredicate = #Predicate<BBVehicle> { vehicle in
            vehicle.vin == vin && vehicle.accountId == accountId
        }

        let descriptor = FetchDescriptor<BBVehicle>(predicate: vinPredicate)
        return try? modelContext.fetch(descriptor).first
    }

    private func createVehicleStatus(from bbVehicle: BBVehicle) -> VehicleStatus {
        var status = VehicleStatus(
            vin: bbVehicle.vin,
            gasRange: bbVehicle.gasRange,
            evStatus: bbVehicle.evStatus,
            location: bbVehicle.location ?? VehicleStatus.Location(latitude: 0, longitude: 0),
            lockStatus: bbVehicle.lockStatus ?? .unknown,
            climateStatus: bbVehicle.climateStatus ?? VehicleStatus.ClimateStatus(
                defrostOn: false,
                airControlOn: false,
                steeringWheelHeatingOn: false,
                temperature: Temperature(value: 70, units: .fahrenheit)
            ),
            odometer: bbVehicle.odometer,
            syncDate: bbVehicle.syncDate ?? Date(),
            battery12V: bbVehicle.battery12V,
            doorOpen: bbVehicle.doorOpen,
            trunkOpen: bbVehicle.trunkOpen,
            hoodOpen: bbVehicle.hoodOpen,
            tirePressureWarning: bbVehicle.tirePressureWarning
        )

        // Set lastUpdated separately since it's not part of the constructor
        status.lastUpdated = bbVehicle.lastUpdated ?? Date()

        return status
    }

    // Deterministic hash function that returns the same value across app launches and platforms
    private func deterministicHash(of string: String) -> Int {
        var hash = 5381
        for char in string.utf8 {
            hash = ((hash << 5) &+ hash) &+ Int(char)
        }
        return hash
    }
}
