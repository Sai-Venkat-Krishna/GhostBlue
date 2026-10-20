//
//  AutomationReminderScheduler.swift
//  GhostBlue
//
//  Keeps the repeating precondition-reminder notifications in step with the
//  VehicleAutomation rules in the store. Rules can disappear without passing
//  through the Automations screen (Clear All Data, Delete ownership data,
//  removing an account) and can appear from a backup restore, so the pending
//  requests are reconciled against the store instead of being edited one
//  rule at a time.
//

import BetterBlueKit
import Foundation
import SwiftData
import UserNotifications

@MainActor
enum AutomationReminderScheduler {
    static let identifierPrefix = "automation-"

    static func identifier(for rule: VehicleAutomation) -> String {
        identifierPrefix + rule.id.uuidString
    }

    /// The reminders that should be pending: enabled precondition rules whose
    /// vehicle still exists, and none while GhostBlue notifications are off.
    static func desiredRequests(
        in context: ModelContext,
        notificationsEnabled: Bool
    ) -> [UNNotificationRequest] {
        guard notificationsEnabled else { return [] }
        let kind = VehicleAutomationKind.preconditionReminder.rawValue
        let rules = (try? context.fetch(FetchDescriptor<VehicleAutomation>(
            predicate: #Predicate { $0.isEnabled && $0.kindRaw == kind }
        ))) ?? []
        guard !rules.isEmpty else { return [] }
        let knownVINs = Set(((try? context.fetch(FetchDescriptor<BBVehicle>())) ?? []).map(\.vin))

        return rules
            .filter { knownVINs.contains($0.vin) }
            .map { rule in
                let content = UNMutableNotificationContent()
                content.title = rule.title
                content.body = "Open GhostBlue to review conditions and start climate."
                content.sound = .default
                content.userInfo = ["vin": rule.vin]
                let trigger = UNCalendarNotificationTrigger(
                    dateMatching: DateComponents(
                        hour: min(23, max(0, rule.hour)),
                        minute: min(59, max(0, rule.minute))
                    ),
                    repeats: true
                )
                return UNNotificationRequest(identifier: identifier(for: rule), content: content, trigger: trigger)
            }
    }

    /// Removes reminders whose rule is gone or disabled and (re)adds the rest.
    /// Adding an existing identifier replaces it, so this is idempotent.
    static func sync(
        in context: ModelContext,
        notificationsEnabled: Bool = AppSettings.shared.notificationsEnabled
    ) async {
        let center = UNUserNotificationCenter.current()
        let desired = desiredRequests(in: context, notificationsEnabled: notificationsEnabled)
        let desiredIDs = Set(desired.map(\.identifier))
        let stale = await center.pendingNotificationRequests()
            .map(\.identifier)
            .filter { $0.hasPrefix(identifierPrefix) && !desiredIDs.contains($0) }
        if !stale.isEmpty {
            center.removePendingNotificationRequests(withIdentifiers: stale)
        }
        for request in desired {
            do {
                try await center.add(request)
            } catch {
                BBLogger.error(.app, "Automations: could not schedule reminder: \(error)")
            }
        }
    }
}
