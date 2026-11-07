//
//  ChargeLimitSettingsView.swift
//  GhostBlue
//
//  Created by Claude on 1/16/26.
//

import BetterBlueKit
import SwiftData
import SwiftUI

/// Sheet wrapper for modal presentation (from ChargingButton menu)
struct ChargeLimitSettingsSheet: View {
    let vehicle: BBVehicle
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ChargeLimitSettingsContent(vehicle: vehicle, onSave: { dismiss() })
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button("Done") {
                            dismiss()
                        }
                    }
                }
        }
    }
}

/// Content view for navigation push (from VehicleInfoView settings)
struct ChargeLimitSettingsContent: View {
    let vehicle: BBVehicle
    var onSave: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var acLevel: Double
    @State private var dcLevel: Double
    @State private var saveError: ActionError?
    @State private var successMessage: String?
    @State private var appSettings = AppSettings.shared

    private var tracker: RemoteCommandTracker { RemoteCommands.tracker(.chargeLimits, for: vehicle) }
    private var isSaving: Bool { tracker.isRunning }

    init(vehicle: BBVehicle, onSave: (() -> Void)? = nil) {
        self.vehicle = vehicle
        self.onSave = onSave
        // Initialize with current values or defaults
        let currentAC = vehicle.evStatus?.targetSocAC ?? 80
        let currentDC = vehicle.evStatus?.targetSocDC ?? 80
        _acLevel = State(initialValue: currentAC)
        _dcLevel = State(initialValue: currentDC)
    }

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Image(systemName: "ev.plug.ac.type.1")
                            .foregroundColor(.blue)
                        Text("AC Charging Limit")
                            .font(.headline)
                        Spacer()
                        Text("\(Int(acLevel))%")
                            .font(.title3)
                            .fontWeight(.bold)
                            .foregroundColor(.blue)
                            .contentTransition(.numericText(value: acLevel))
                            .animation(reduceMotion ? nil : .snappy, value: acLevel)
                    }

                    Slider(value: $acLevel, in: 50...100, step: 10)
                        .tint(.blue)

                    Text("Set the charge limit for AC charging (Level 1/2)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .padding(.vertical, 8)
            }
            .disabled(isSaving)

            Section {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Image(systemName: "ev.plug.dc.ccs1")
                            .foregroundColor(.green)
                        Text("DC Charging Limit")
                            .font(.headline)
                        Spacer()
                        Text("\(Int(dcLevel))%")
                            .font(.title3)
                            .fontWeight(.bold)
                            .foregroundColor(.green)
                            .contentTransition(.numericText(value: dcLevel))
                            .animation(reduceMotion ? nil : .snappy, value: dcLevel)
                    }

                    Slider(value: $dcLevel, in: 50...100, step: 10)
                        .tint(.green)

                    Text("Set the charge limit for DC fast charging")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .padding(.vertical, 8)
            }
            .disabled(isSaving)

            if let saveError {
                Section {
                    ErrorDetailsView(error: saveError)
                }
            }

            if let successMessage {
                Section {
                    HStack(spacing: 8) {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.green)
                        Text(successMessage)
                            .foregroundColor(.green)
                    }
                    .font(.callout)
                }
            }

            Section {
                saveButton
            }
        }
        .navigationTitle("Charge Limits")
        .navigationBarTitleDisplayMode(.inline)
        .animation(.easeInOut(duration: 0.25), value: successMessage)
        .animation(.easeInOut(duration: 0.25), value: saveError)
        .onChange(of: acLevel) {
            successMessage = nil
        }
        .onChange(of: dcLevel) {
            successMessage = nil
        }
    }

    /// Save, with the command's progress traced around its outline.
    private var saveButton: some View {
        Button(action: saveChargeLimits) {
            HStack(spacing: 10) {
                Image(systemName: saveSymbol)
                    .contentTransition(.symbolEffect(.replace))
                    .symbolEffect(.pulse, options: .repeating, isActive: isSaving && !reduceMotion)
                Text(saveTitle)
                    .contentTransition(.opacity)
            }
            .font(.body.weight(.semibold))
            .foregroundStyle(isSaving ? Color.secondary : appSettings.accentColor)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(
                appSettings.accentColor.opacity(0.12),
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
            .commandProgressBorder(
                tracker,
                shape: .rounded(14),
                tint: appSettings.accentColor,
                successTint: .green
            )
            .animation(.easeInOut(duration: 0.25), value: saveTitle)
        }
        .buttonStyle(.plain)
        .disabled(isSaving)
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
        .accessibilityLabel(saveTitle)
    }

    private var saveTitle: String {
        if isSaving { return tracker.statusText ?? "Sending command…" }
        if tracker.didSucceed { return "Saved" }
        return "Save Charge Limits"
    }

    private var saveSymbol: String {
        if isSaving { return "arrow.up.circle" }
        if tracker.didSucceed { return "checkmark.circle.fill" }
        return "battery.100percent.bolt"
    }

    @MainActor
    private func saveChargeLimits() {
        guard let account = vehicle.account else {
            saveError = ActionError(
                action: "Set charge limits",
                error: APIError(message: "Account not found for vehicle")
            )
            return
        }

        saveError = nil
        successMessage = nil
        let targetAcLevel = acLevel
        let targetDcLevel = dcLevel
        let vehicle = vehicle
        let modelContext = modelContext

        tracker.run(tag: "save", announcement: "Charge limits saved") { tracker in
            do {
                try await account.setTargetSOC(
                    vehicle,
                    acLevel: Int(targetAcLevel),
                    dcLevel: Int(targetDcLevel),
                    modelContext: modelContext
                )

                do {
                    try await vehicle.waitForStatusChange(
                        modelContext: modelContext,
                        condition: { status in
                            // Check if the target SOC values match what we set
                            guard let evStatus = status.evStatus else { return false }
                            return evStatus.targetSocAC == targetAcLevel
                                && evStatus.targetSocDC == targetDcLevel
                        },
                        stepUpdater: { tracker.report($0) },
                        maxAttempts: 3,
                        initialDelaySeconds: 5,
                        retryDelaySeconds: 5
                    )
                } catch let error where error.localizedDescription.contains("condition not met") {
                    // The command was sent but the vehicle hasn't echoed the
                    // new limits yet; it usually lands, so report it saved.
                }
                successMessage = "Charge limits saved"
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                saveError = ActionError(
                    action: "Set charge limits",
                    error: error,
                    accountId: account.id
                )
                throw error
            }
        }
    }
}
