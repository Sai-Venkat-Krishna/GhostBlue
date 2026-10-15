//
//  SharedModelContainer.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 9/4/25.
//

import BetterBlueKit
import Foundation
import SwiftData

func getSimulatorStoreURL() -> URL {
    // Compatibility and UI tests can isolate their SwiftData store per
    // simulator runtime. This avoids an older runtime trying to open a store
    // last migrated by a newer Persistence framework. Normal launches retain
    // the historical shared path used by app, widget, and Watch targets.
    if let overridePath = ProcessInfo.processInfo.environment["GHOSTBLUE_SIMULATOR_STORE_PATH"],
       !overridePath.isEmpty {
        let storeURL = URL(fileURLWithPath: overridePath)
        try? FileManager.default.createDirectory(
            at: storeURL.deletingLastPathComponent(),
            withIntermediateDirectories: true,
            attributes: nil
        )
        return storeURL
    }

    // In simulator, use a fixed shared location to work around App Group container isolation
    let sharedSimulatorPath = "/tmp/GhostBlue_Shared"
    try? FileManager.default.createDirectory(
        atPath: sharedSimulatorPath,
        withIntermediateDirectories: true,
        attributes: nil,
    )
    return URL(fileURLWithPath: sharedSimulatorPath).appendingPathComponent("GhostBlue.sqlite")
}

/// This account own iCloud container. The value that shipped here
/// previously belonged to the upstream author, so sync could never have
/// worked against it.
///
/// Dormant for now: the signing team is a personal (free) Apple ID team,
/// and personal teams cannot hold the iCloud capability at all — adding
/// the entitlement makes the provisioning profile fail outright. Joining
/// the paid Apple Developer Program is the only way to switch this on,
/// and it then needs the iCloud container plus the CloudKit service back
/// in GhostBlue.entitlements and a toggle bound to `iCloudSyncEnabled`.
/// Until then `StoreReconciler` and the backup/restore flow are what keep
/// two stores in agreement, both keyed on VIN.
let cloudKitContainerIdentifier = "iCloud.com.saivenkatkrishna.GhostBlue"

/// Lives here rather than on `AppSettings`, which is main-actor isolated:
/// container creation happens off the main actor in widget and intent
/// processes.
let iCloudSyncEnabledDefaultsKey = "ICloudSyncEnabled"

/// Read from the shared suite rather than `AppSettings` so widget and
/// intent processes, which build a container without ever constructing
/// `AppSettings`, agree with the app about which store to open.
func isICloudSyncEnabled() -> Bool {
    UserDefaults(suiteName: "group.com.saivenkatkrishna.ghostblue.shared")?
        .bool(forKey: iCloudSyncEnabledDefaultsKey) ?? false
}

func getAppGroupStoreURL() throws -> URL {
    let appGroupID = "group.com.saivenkatkrishna.ghostblue.shared"
    if let appGroupURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupID) {
        return appGroupURL.appendingPathComponent("GhostBlue.sqlite")
    } else {
        BBLogger.warning(.app, "GhostBlue: App Group container not accessible from current context")
        throw NSError(
            domain: "GhostBlue",
            code: 1001,
            userInfo: [
                NSLocalizedDescriptionKey:
                    "Vehicle data not accessible. Please open the GhostBlue app first to sync your vehicles.",
                NSLocalizedRecoverySuggestionErrorKey:
                    "Open the GhostBlue app and try again."
            ],
        )
    }
}

func createContainer(storeURL: URL, schema: Schema, cloudKitDatabase: ModelConfiguration.CloudKitDatabase = .automatic) throws -> ModelContainer {
    do {
        let modelConfiguration = ModelConfiguration(url: storeURL, cloudKitDatabase: cloudKitDatabase)
        return try ModelContainer(for: schema, configurations: [modelConfiguration])
    } catch {
        BBLogger.error(.app, "GhostBlue: Failed to create ModelContainer: \(error)")
        throw NSError(
            domain: "GhostBlue",
            code: 1002,
            userInfo: [
                NSLocalizedDescriptionKey:
                    "Failed to create data storage",
                NSLocalizedRecoverySuggestionErrorKey:
                    "Try restarting the app. If the problem persists, contact support."
            ],
        )
    }
}

/// Removes orphaned climate presets that have no vehicle relationship.
/// These are leftover from before the relationship was properly set during creation.
@MainActor
func cleanupOrphanedClimatePresets(container: ModelContainer) {
    let context = container.mainContext

    do {
        let presetDescriptor = FetchDescriptor<ClimatePreset>()
        let allPresets = try context.fetch(presetDescriptor)

        var deletedCount = 0
        for preset in allPresets where preset.vehicle == nil {
            context.delete(preset)
            deletedCount += 1
        }

        if deletedCount > 0 {
            try context.save()
            BBLogger.info(.app, "Cleaned up \(deletedCount) orphaned climate preset(s)")
        }
    } catch {
        BBLogger.error(.app, "Failed to cleanup orphaned climate presets: \(error)")
    }
}

/// Removes "zombie" vehicles whose parent `BBAccount` no longer exists.
/// Normally `BBAccount` → `BBVehicle` is cascade-delete, but interrupted
/// CloudKit syncs and earlier schema iterations can leave orphan vehicle
/// rows that Siri/App Intents/widget pickers would otherwise still list.
/// Their cascaded `climatePresets` are dropped by SwiftData automatically
/// once the vehicle goes away.
@MainActor
func cleanupOrphanedVehicles(container: ModelContainer) {
    let context = container.mainContext

    do {
        let vehicleDescriptor = FetchDescriptor<BBVehicle>()
        let allVehicles = try context.fetch(vehicleDescriptor)

        var deletedCount = 0
        for vehicle in allVehicles where vehicle.account == nil {
            BBLogger.info(.app, "Purging orphaned vehicle \(vehicle.vin) (\(vehicle.displayName))")
            context.delete(vehicle)
            deletedCount += 1
        }

        if deletedCount > 0 {
            try context.save()
            BBLogger.info(.app, "Cleaned up \(deletedCount) orphaned vehicle(s)")
        }
    } catch {
        BBLogger.error(.app, "Failed to cleanup orphaned vehicles: \(error)")
    }
}

/// Creates a shared ModelContainer for use across main app, widget, and watch app.
/// - Parameter enableCloudKit: Whether to enable CloudKit sync. Requires a paid developer
///   account with iCloud entitlements. Set to `false` (default) when using a free account
///   or to avoid `0xdead10cc` crashes in widgets/App Intents during process suspension.
func createSharedModelContainer(enableCloudKit: Bool = false) throws -> ModelContainer {
    let schema = Schema([
        BBAccount.self,
        BBVehicle.self,
        BBHTTPLog.self,
        ClimatePreset.self,
        ChargeSession.self,
        TripRecord.self,
        RemoteCommandActivity.self,
        MaintenanceReminder.self,
        VehicleAutomation.self
    ], version: .init(1, 0, 18))

    #if DEBUG
    if ProcessInfo.processInfo.environment["GHOSTBLUE_TESTING"] == "1" {
        return try ModelContainer(for: schema, configurations: [
            ModelConfiguration(isStoredInMemoryOnly: true, cloudKitDatabase: .none)
        ])
    }
    #endif

    #if targetEnvironment(simulator)
        let storeURL = getSimulatorStoreURL()
        return try createContainer(storeURL: storeURL, schema: schema, cloudKitDatabase: .none)
    #else
        let storeURL = try getAppGroupStoreURL()

        // Sync keeps the existing app group store file and simply mirrors it.
        // Letting CloudKit pick its own default location would strand
        // everything already saved on the device, which is the whole problem
        // this is meant to solve.
        if enableCloudKit || isICloudSyncEnabled() {
            let cloudConfig = ModelConfiguration(
                url: storeURL,
                cloudKitDatabase: .private(cloudKitContainerIdentifier)
            )
            if let container = try? ModelContainer(for: schema, configurations: [cloudConfig]) {
                return container
            }
            BBLogger.warning(
                .app,
                "GhostBlue: iCloud sync is on but the CloudKit container could not be opened; "
                    + "falling back to local storage"
            )
        }

        return try createContainer(storeURL: storeURL, schema: schema, cloudKitDatabase: .none)
    #endif
}
