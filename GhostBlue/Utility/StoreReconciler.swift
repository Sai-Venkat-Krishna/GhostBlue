//
//  StoreReconciler.swift
//  GhostBlue
//
//  Collapses duplicate records by their real-world identity.
//
//  CloudKit cannot enforce uniqueness — `@Attribute(.unique)` is not
//  allowed in a synced schema — so when two devices that already hold
//  the same car start mirroring, each one's `BBVehicle` arrives on the
//  other as a separate row with a different UUID. The VIN is what
//  actually identifies a car, so that is what the collapse runs on.
//
//  Everything here is idempotent: a second pass over a reconciled store
//  finds nothing to do.
//

import BetterBlueKit
import Foundation
import SwiftData

struct StoreReconcileSummary: Equatable {
    var accountsMerged = 0
    var vehiclesMerged = 0
    var presetsMerged = 0
    var chargeSessionsMerged = 0
    var tripsMerged = 0
    var remindersMerged = 0
    var automationsMerged = 0
    var remoteCommandsMerged = 0

    var total: Int {
        accountsMerged + vehiclesMerged + presetsMerged + chargeSessionsMerged
            + tripsMerged + remindersMerged + automationsMerged + remoteCommandsMerged
    }

    var isEmpty: Bool { total == 0 }
}

enum StoreReconciler {
    /// Collapses duplicates across the whole store and saves if anything
    /// changed. Safe to call on every launch and after every remote change.
    @MainActor
    @discardableResult
    static func reconcile(in context: ModelContext) -> StoreReconcileSummary {
        var summary = StoreReconcileSummary()

        summary.accountsMerged = collapseAccounts(in: context)
        summary.vehiclesMerged = collapseVehicles(in: context)
        summary.presetsMerged = collapse(
            ClimatePreset.self, in: context,
            key: { "\($0.vehicle?.vin ?? "")|\($0.name.lowercased())" },
            preferring: { $0.isSelected && !$1.isSelected }
        )
        summary.chargeSessionsMerged = collapse(
            ChargeSession.self, in: context,
            key: { "\($0.vin)|\(stamp($0.startDate))" },
            // Keep the richest copy: a finished session beats a still-open
            // one, and more energy recorded beats less.
            preferring: { lhs, rhs in
                if (lhs.endDate != nil) != (rhs.endDate != nil) { return lhs.endDate != nil }
                return lhs.energyAddedKWh > rhs.energyAddedKWh
            }
        )
        summary.tripsMerged = collapse(
            TripRecord.self, in: context,
            key: { "\($0.vin)|\($0.tripKey)" },
            preferring: { $0.isFavorite && !$1.isFavorite }
        )
        summary.remindersMerged = collapse(
            MaintenanceReminder.self, in: context,
            key: { "\($0.vin)|\($0.title.lowercased())|\(stamp($0.createdAt))" },
            preferring: { $0.completedAt != nil && $1.completedAt == nil }
        )
        summary.automationsMerged = collapse(
            VehicleAutomation.self, in: context,
            key: { "\($0.vin)|\($0.kindRaw)|\($0.title.lowercased())" },
            preferring: { $0.isEnabled && !$1.isEnabled }
        )
        summary.remoteCommandsMerged = collapse(
            RemoteCommandActivity.self, in: context,
            key: { "\($0.vin)|\($0.commandName)|\(stamp($0.startedAt))" },
            preferring: { $0.completedAt != nil && $1.completedAt == nil }
        )

        if !summary.isEmpty {
            do {
                try context.save()
                BBLogger.info(.app, "StoreReconciler: collapsed \(summary.total) duplicate record(s)")
            } catch {
                BBLogger.error(.app, "StoreReconciler: failed to save after reconcile: \(error)")
            }
        }

        return summary
    }

    private static func stamp(_ date: Date) -> Int { Int(date.timeIntervalSince1970.rounded()) }

    private static func fetchAll<T: PersistentModel>(_: T.Type, in context: ModelContext) -> [T] {
        (try? context.fetch(FetchDescriptor<T>())) ?? []
    }

    /// Generic collapse for models with no children to rescue: group by
    /// `key`, keep the winner of `preferring`, delete the rest.
    @MainActor
    private static func collapse<T: PersistentModel>(
        _: T.Type,
        in context: ModelContext,
        key: (T) -> String,
        preferring better: (T, T) -> Bool
    ) -> Int {
        var survivors: [String: T] = [:]
        var removed = 0

        for model in fetchAll(T.self, in: context) {
            let identity = key(model)
            guard let incumbent = survivors[identity] else {
                survivors[identity] = model
                continue
            }
            if better(model, incumbent) {
                survivors[identity] = model
                context.delete(incumbent)
            } else {
                context.delete(model)
            }
            removed += 1
        }

        return removed
    }

    /// Accounts are identified by the credentials they sign in with, not by
    /// their row id. Vehicles are moved onto the survivor before anything is
    /// deleted, because the relationship cascades.
    @MainActor
    private static func collapseAccounts(in context: ModelContext) -> Int {
        var survivors: [String: BBAccount] = [:]
        var duplicates: [BBAccount] = []

        for account in fetchAll(BBAccount.self, in: context) {
            let identity = "\(account.username.lowercased())|\(account.brand.lowercased())"
            guard let incumbent = survivors[identity] else {
                survivors[identity] = account
                continue
            }
            // Keep whichever was created first, so ids stay stable across runs.
            let (keep, drop) = account.dateCreated < incumbent.dateCreated
                ? (account, incumbent)
                : (incumbent, account)
            survivors[identity] = keep
            duplicates.append(drop)
        }

        for duplicate in duplicates {
            for vehicle in duplicate.safeVehicles {
                let identity = "\(duplicate.username.lowercased())|\(duplicate.brand.lowercased())"
                guard let survivor = survivors[identity] else { continue }
                vehicle.account = survivor
                vehicle.accountId = survivor.id
            }
            duplicate.vehicles = []
            context.delete(duplicate)
        }

        return duplicates.count
    }

    /// The VIN collapse. Climate presets are re-homed onto the survivor and
    /// any setting the survivor has no value for is taken from the copy being
    /// dropped, so nothing the user typed on either device is lost.
    @MainActor
    private static func collapseVehicles(in context: ModelContext) -> Int {
        var survivors: [String: BBVehicle] = [:]
        var duplicates: [(keep: BBVehicle, drop: BBVehicle)] = []

        for vehicle in fetchAll(BBVehicle.self, in: context) where !vehicle.vin.isEmpty {
            guard let incumbent = survivors[vehicle.vin] else {
                survivors[vehicle.vin] = vehicle
                continue
            }
            let (keep, drop) = isFresher(vehicle, than: incumbent)
                ? (vehicle, incumbent)
                : (incumbent, vehicle)
            survivors[vehicle.vin] = keep
            duplicates.append((keep, drop))
        }

        for (keep, drop) in duplicates {
            adoptSettings(of: drop, into: keep)

            for preset in drop.safeClimatePresets {
                let alreadyThere = keep.safeClimatePresets.contains {
                    $0.name.lowercased() == preset.name.lowercased()
                }
                if alreadyThere {
                    context.delete(preset)
                } else {
                    preset.vehicle = keep
                }
            }
            drop.climatePresets = []
            context.delete(drop)
        }

        return duplicates.count
    }

    /// The copy with the newer status wins; with no status on either side,
    /// the one carrying more of the user's own settings does.
    private static func isFresher(_ lhs: BBVehicle, than rhs: BBVehicle) -> Bool {
        switch (lhs.lastUpdated, rhs.lastUpdated) {
        case let (left?, right?):
            if left != right { return left > right }
        case (.some, .none):
            return true
        case (.none, .some):
            return false
        case (.none, .none):
            break
        }
        return settingsWeight(lhs) > settingsWeight(rhs)
    }

    private static func settingsWeight(_ vehicle: BBVehicle) -> Int {
        var weight = 0
        if vehicle.customName != nil { weight += 1 }
        if vehicle.offPeakElectricityRatePerKWh != nil { weight += 1 }
        if vehicle.electricityRatePerKWh != nil { weight += 1 }
        if vehicle.dcFastChargingRatePerKWh != nil { weight += 1 }
        if vehicle.homeLatitude != nil { weight += 1 }
        if vehicle.abrpUserToken != nil { weight += 1 }
        if vehicle.batteryCapacityKWh != nil { weight += 1 }
        if vehicle.monthlyChargingBudget != nil { weight += 1 }
        weight += vehicle.safeClimatePresets.count
        return weight
    }

    /// Fills only the blanks, so the survivor never loses a value it has.
    @MainActor
    private static func adoptSettings(of donor: BBVehicle, into survivor: BBVehicle) {
        func fill<T>(_ keyPath: ReferenceWritableKeyPath<BBVehicle, T?>) {
            guard survivor[keyPath: keyPath] == nil,
                  let value = donor[keyPath: keyPath] else { return }
            survivor[keyPath: keyPath] = value
        }

        fill(\.customName)
        fill(\.batteryCapacityKWh)
        fill(\.electricityRatePerKWh)
        fill(\.offPeakElectricityRatePerKWh)
        fill(\.dcFastChargingRatePerKWh)
        fill(\.peakElectricityRatePerKWh)
        fill(\.monthlyChargingBudget)
        fill(\.homeLatitude)
        fill(\.homeLongitude)
        fill(\.abrpUserToken)
        fill(\.abrpCarModel)
        fill(\.fuelTypeOverrideRaw)
        fill(\.showClimateDurationOverride)
        fill(\.primaryColorName)
        fill(\.chargingColorName)
        fill(\.gasColorName)
        fill(\.lockColorName)
        fill(\.unlockColorName)
        fill(\.startClimateColorName)
        fill(\.stopColorName)
        fill(\.lastUpdated)
        fill(\.syncDate)
        fill(\.evStatus)
        fill(\.gasRange)
        fill(\.location)
        fill(\.lockStatus)
        fill(\.climateStatus)
        fill(\.battery12V)
    }
}
