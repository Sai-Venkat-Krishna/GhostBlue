//
//  SmartChargingIntent.swift
//  GhostBlue
//

import AppIntents
import SwiftData
import WidgetKit

struct ReconcileSmartChargingIntent: AppIntent {
    static let title: LocalizedStringResource = "Reconcile Smart Charging"
    static let description = IntentDescription(
        "Apply the configured SDG&E charging window to a plugged-in home vehicle"
    )
    static let openAppWhenRun: Bool = false

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let modelContainer = try createSharedModelContainer(enableCloudKit: false)
        let context = ModelContext(modelContainer)
        let vehicles = try context.fetch(FetchDescriptor<BBVehicle>(predicate: #Predicate { $0.smartChargingEnabled }))

        guard !vehicles.isEmpty else {
            return .result(dialog: "No vehicles have Smart Charging enabled in GhostBlue.")
        }

        var messages: [String] = []
        for vehicle in vehicles {
            guard let account = vehicle.account else {
                messages.append("\(vehicle.displayName): account not found")
                continue
            }

            do {
                let result = try await account.reconcileSmartCharging(
                    for: vehicle,
                    modelContext: context
                )
                messages.append("\(vehicle.displayName): \(result.message)")
            } catch {
                messages.append("\(vehicle.displayName): \(error.localizedDescription)")
            }
        }

        WidgetCenter.shared.reloadAllTimelines()
        return .result(dialog: IntentDialog(stringLiteral: messages.joined(separator: "\n")))
    }
}

struct GhostBlueSmartChargingShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: ReconcileSmartChargingIntent(),
            phrases: [
                "Reconcile smart charging in \(.applicationName)",
                "Apply my charging schedule in \(.applicationName)"
            ],
            shortTitle: "Smart Charging",
            systemImageName: "bolt.badge.clock"
        )
    }
}
