//
//  ClimateButton.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 8/25/25.
//

import BetterBlueKit
import SwiftData
import SwiftUI

struct ClimateButton: View {
    let bbVehicle: BBVehicle
    var transition: Namespace.ID?
    @Environment(\.modelContext) private var modelContext
    @State private var showingSettings = false
    @State private var appSettings = AppSettings.shared
    @Query private var allClimatePresets: [ClimatePreset]

    private var tracker: RemoteCommandTracker { RemoteCommands.tracker(.climate, for: bbVehicle) }

    private var filteredPresets: [ClimatePreset] {
        allClimatePresets.filter { $0.vehicle?.id == bbVehicle.id }
    }

    private var selectedPreset: ClimatePreset? {
        filteredPresets.first { $0.isSelected }
    }

    private var effectivePreset: ClimatePreset? {
        selectedPreset ?? filteredPresets.first
    }

    var isClimateOn: Bool {
        guard bbVehicle.modelContext != nil else { return false }
        return bbVehicle.climateStatus?.airControlOn ?? false
    }

    var climateRunningText: String {
        guard bbVehicle.modelContext != nil else { return "" }
        if isClimateOn, let climateStatus = bbVehicle.climateStatus {
            let temperature = climateStatus.temperature
            guard temperature.isPlausibleForDisplay else { return "Running" }
            let formattedTemp = temperature.units.format(
                temperature.value,
                to: appSettings.preferredTemperatureUnit
            )
            return "Running at \(formattedTemp)"
        }
        return ""
    }

    private var pendingStart: Bool { tracker.tag == "start" }

    private var idleDetail: String {
        if isClimateOn {
            return climateRunningText.isEmpty ? "Tap to stop" : climateRunningText
        }
        if let preset = effectivePreset {
            let temperature = preset.climateOptions.temperature
            let formatted = temperature.units.format(
                temperature.value,
                to: appSettings.preferredTemperatureUnit
            )
            return "Tap to start at \(formatted)"
        }
        return "Tap to start"
    }

    var body: some View {
        RemoteCommandCard(
            tracker: tracker,
            stateTitle: isClimateOn ? "Climate On" : "Climate Off",
            pendingTitle: pendingStart ? "Starting climate…" : "Stopping climate…",
            idleDetail: idleDetail,
            icon: Image(systemName: isClimateOn ? "fan" : "fan.slash"),
            pendingIcon: Image(systemName: pendingStart ? "fan" : "fan.slash"),
            tint: isClimateOn ? .cyan : .secondary,
            spinsIcon: isClimateOn
        ) {
            RemoteCommands.toggleClimate(
                bbVehicle,
                options: effectivePreset?.climateOptions,
                presetName: effectivePreset?.name,
                presetIcon: effectivePreset?.iconName,
                modelContext: modelContext
            )
        }
        .contextMenu {
            ForEach(filteredPresets) { preset in
                Button {
                    RemoteCommands.setClimate(
                        true,
                        vehicle: bbVehicle,
                        options: preset.climateOptions,
                        presetName: preset.name,
                        presetIcon: preset.iconName,
                        modelContext: modelContext
                    )
                } label: {
                    Label("Start \(preset.name)", systemImage: preset.iconName)
                }
            }
            if !filteredPresets.isEmpty { Divider() }
            Button {
                showingSettings = true
            } label: {
                Label("Climate Settings", systemImage: "gearshape.fill")
            }
        }
        .sheet(isPresented: $showingSettings) {
            ClimateSettingsSheet(vehicle: bbVehicle)
        }
    }
}
