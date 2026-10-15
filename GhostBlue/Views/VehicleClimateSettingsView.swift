//
//  VehicleClimateSettingsView.swift
//  GhostBlue
//
//  Vehicle Center → Climate. The single home for per-vehicle climate
//  options and preset management. The toggles used to live inline in
//  Vehicle Info and, in a second copy, behind a gear inside the climate
//  preset sheet; the preset list used to live in Vehicle Info.
//

import BetterBlueKit
import SwiftData
import SwiftUI

struct VehicleClimateSettingsView: View {
    let bbVehicle: BBVehicle
    @Query private var allClimatePresets: [ClimatePreset]

    private var vehiclePresets: [ClimatePreset] {
        allClimatePresets
            .filter { $0.vehicle?.id == bbVehicle.id }
            .sorted { $0.sortOrder < $1.sortOrder }
    }

    var body: some View {
        Form {
            Section {
                ClimateSettingsToggles(bbVehicle: bbVehicle)
            } header: {
                HStack {
                    Text("Climate Settings")
                    ClimateSettingsInfoButton()
                    Spacer()
                }
            }

            ClimatePresetsSection(bbVehicle: bbVehicle, vehiclePresets: vehiclePresets)
        }
        .navigationTitle("Climate")
        .navigationBarTitleDisplayMode(.inline)
    }
}
