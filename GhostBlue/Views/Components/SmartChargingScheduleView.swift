import SwiftData
import SwiftUI
import AppIntents
import WidgetKit

struct ChargingStatusSummary: View {
    let vehicle: BBVehicle

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { timeline in
            let state = vehicle.chargingDisplayState(at: timeline.date)
            VStack(alignment: .leading, spacing: 5) {
                Label(state.title, systemImage: state == .outdated ? "clock.badge.exclamationmark" : "bolt.circle")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(state == .outdated || state == .confirmingStop ? Color.orange : Color.cyan)
                if state == .confirmingStop {
                    Text("The last update reported a stop. Waiting for a newer vehicle reading to confirm it.")
                        .font(.caption)
                }
                if let sampleDate = vehicle.vehicleSampleDate {
                    Text("Vehicle reading \(sampleDate, format: .relative(presentation: .named))")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
    }
}

/// Shared by Charging History and Vehicle Center so both edit one persisted schedule.
struct SmartChargingScheduleSection: View {
    @Bindable var vehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext
    @State private var isRunning = false
    @State private var message: String?

    var body: some View {
        Section {
            Toggle("SDG&E smart charging", isOn: $vehicle.smartChargingEnabled)
                .onChange(of: vehicle.smartChargingEnabled) { _, enabled in
                    if !enabled { vehicle.setChargingOverride(nil) }
                    saveSettings()
                }
            if vehicle.smartChargingEnabled {
                ChargingStatusSummary(vehicle: vehicle)
                LabeledContent("Weekdays", value: "12–6 AM, 10 AM–2 PM")
                LabeledContent("Weekends & holidays", value: "12 AM–2 PM")
                Text("All schedule times use San Diego local time.")
                    .font(.caption).foregroundStyle(.secondary)
                Toggle("Only control charging at home", isOn: $vehicle.smartChargingHomeOnly)
                    .onChange(of: vehicle.smartChargingHomeOnly) { saveSettings() }
                Stepper("Charge target: \(vehicle.smartChargingTargetSOC)%",
                        value: $vehicle.smartChargingTargetSOC, in: 50 ... 100, step: 10)
                    .onChange(of: vehicle.smartChargingTargetSOC) { saveSettings() }

                if vehicle.smartChargingHomeOnly,
                   vehicle.homeLatitude == nil || vehicle.homeLongitude == nil {
                    Label("Save a home location in Vehicle Center before running home-only charging.",
                          systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundStyle(.orange)
                }

                TimelineView(.periodic(from: .now, by: 60)) { timeline in
                    VStack(alignment: .leading, spacing: 10) {
                        if let override = vehicle.activeChargingOverride(at: timeline.date),
                           let expiry = vehicle.smartChargingOverrideExpiresAt {
                            Text("\(override.title) until \(SDGESmartChargingSchedule.formattedTime(expiry))")
                                .font(.subheadline.weight(.semibold))
                        }
                        ForEach(SDGESmartChargingSchedule.transitions(after: timeline.date, count: 3)) { transition in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(transition.title).font(.caption.weight(.semibold))
                                Text(SDGESmartChargingSchedule.formattedTime(transition.date))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        if let warning = vehicle.smartChargingWarning(at: timeline.date) {
                            Label(warning, systemImage: "exclamationmark.triangle")
                                .font(.caption).foregroundStyle(.orange)
                        }
                    }
                }

                Button("Charge now until next boundary", systemImage: "bolt.fill") { run(override: .chargeNow) }
                    .disabled(isRunning)
                Button("Skip this window", systemImage: "pause.circle") { run(override: .skipWindow) }
                    .disabled(isRunning)
                if vehicle.smartChargingOverrideRaw != nil {
                    Button("Resume normal schedule", systemImage: "calendar") { run(clearOverride: true) }
                        .disabled(isRunning)
                }
                Button { run() } label: {
                    HStack {
                        if isRunning { ProgressView() }
                        Text(isRunning ? "Checking vehicle…" : "Check charging now")
                    }
                }
                .disabled(isRunning)
                ShortcutsLink()
                if let message = message ?? vehicle.smartChargingLastResult {
                    Text(message).font(.caption).foregroundStyle(.secondary)
                }
                if let lastCheck = vehicle.smartChargingLastRunAt {
                    Text("Last attempt \(lastCheck, format: .relative(presentation: .named))")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("Charging schedule")
        } footer: {
            Text("Keep Hyundai’s 12 AM–2 PM vehicle schedule as a backup. In Shortcuts, run Reconcile Smart Charging daily at midnight, 6 AM, 10 AM, and 2 PM with Run Immediately. The app handles weekdays and holidays. Overrides expire at the next displayed boundary; a Shortcut or an open app must run to apply that change. The car’s own schedule and charge limit may also restrict charging.")
        }
    }

    private func saveSettings() {
        do { try modelContext.save() }
        catch { message = "Could not save these settings: \(error.localizedDescription)" }
    }

    private func run(override: SmartChargingOverride? = nil, clearOverride: Bool = false) {
        guard !isRunning else { return }
        if override != nil || clearOverride { vehicle.setChargingOverride(override) }
        do { try modelContext.save() }
        catch { message = "Could not save the override: \(error.localizedDescription)"; return }
        isRunning = true
        message = nil
        Task { @MainActor in
            defer { isRunning = false }
            guard let account = vehicle.account else { message = "Vehicle account not found."; return }
            do {
                let result = try await account.reconcileSmartCharging(for: vehicle, modelContext: modelContext)
                message = result.message
                WidgetCenter.shared.reloadAllTimelines()
            } catch {
                message = "Charging was not confirmed: \(error.localizedDescription)"
            }
        }
    }
}
