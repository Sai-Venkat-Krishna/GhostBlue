//
//  ClimateSettingsToggles.swift
//  GhostBlue
//
//  Per-vehicle Climate Settings toggles + their info-panel chrome.
//  Extracted into a single shared component so the same controls back
//  both:
//    • the inline "Climate Settings" section in `VehicleInfoView`, and
//    • the half-sheet quick-config invoked from the toolbar on
//      `ClimateSettingsContent` for older vehicles where these
//      overrides matter most.
//

import BetterBlueKit
import SwiftData
import SwiftUI

// MARK: - Toggles

/// Two-toggle block: optional Seat Heat (only for older vehicles) and
/// Show Climate Duration. Designed to live inside a `Form` `Section`.
struct ClimateSettingsToggles: View {
    @Bindable var bbVehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext

    var body: some View {
        if bbVehicle.generation < 3 {
            Toggle("Seat Heat Controls", isOn: Binding(
                get: { bbVehicle.enableSeatHeatControls },
                set: { newValue in
                    bbVehicle.enableSeatHeatControls = newValue
                    try? modelContext.save()
                }
            ))
        }

        Toggle("Show Climate Duration", isOn: Binding(
            get: { bbVehicle.showClimateDuration },
            set: { newValue in
                bbVehicle.showClimateDurationOverride = newValue
                try? modelContext.save()
            }
        ))
    }
}

// MARK: - Info button + sheet

/// Small `info.circle` button (suited to a `Form` section header) that
/// opens `ClimateSettingsInfoSheet` describing both toggles.
struct ClimateSettingsInfoButton: View {
    @State private var showingInfo = false

    var body: some View {
        Button {
            showingInfo = true
        } label: {
            Image(systemName: "info.circle")
                .foregroundColor(.blue)
        }
        .buttonStyle(.plain)
        .textCase(nil)
        .sheet(isPresented: $showingInfo) {
            ClimateSettingsInfoSheet()
        }
    }
}

/// Full-text explanation of every toggle in the Climate Settings group.
struct ClimateSettingsInfoSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    settingDescription(
                        title: "Seat Heat Controls",
                        body: "Seat heating and cooling controls are available " +
                            "for newer vehicles (generation 3+). " +
                            "If the MyHyundai / Kia Connect app supports enabling seat " +
                            "heat / cooling, GhostBlue should be able to set it as well."
                    )

                    settingDescription(
                        title: "Show Climate Duration",
                        body: "Newer vehicles (generation 3+) support setting a duration " +
                            "for climate control. If the MyHyundai / Kia Connect app " +
                            "supports setting a climate duration, GhostBlue should be " +
                            "able to set it as well."
                    )
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle("Climate Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    @ViewBuilder
    private func settingDescription(title: String, body: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.headline)
            Text(body)
                .font(.body)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
