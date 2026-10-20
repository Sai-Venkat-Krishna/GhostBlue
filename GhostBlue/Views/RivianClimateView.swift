//
//  RivianClimateView.swift
//  GhostBlue
//
//  Rivian-style Climate screen: live cabin status, the functional climate
//  start/stop control, presets, and the full climate settings (temperature,
//  seats, defrost). Built from the app's existing climate components on the RV
//  design system (light + dark). Original UI; no Rivian assets.
//

import BetterBlueKit
import SwiftData
import SwiftUI

struct RivianClimateView: View {
    let bbVehicle: BBVehicle
    var transition: Namespace.ID?
    var onClose: () -> Void = {}

    @Query private var allClimatePresets: [ClimatePreset]
    @State private var appSettings = AppSettings.shared
    @State private var showingSettings = false

    private var vehiclePresets: [ClimatePreset] {
        allClimatePresets
            .filter { $0.vehicle?.id == bbVehicle.id }
            .sorted { $0.sortOrder < $1.sortOrder }
    }

    private var isOn: Bool { bbVehicle.climateStatus?.airControlOn == true }

    private var temperatureText: String? {
        guard let status = bbVehicle.climateStatus else { return nil }
        let temperature = status.temperature
        guard temperature.isPlausibleForDisplay else { return nil }
        return temperature.units.format(temperature.value, to: appSettings.preferredTemperatureUnit)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    statusHero
                        .listRowBackground(RV.surface)
                }
                Section {
                    ClimateButton(bbVehicle: bbVehicle, transition: transition)
                        .listRowBackground(RV.surface)
                } footer: {
                    Text("Starts climate with your selected preset.")
                }
                ClimatePresetsSection(bbVehicle: bbVehicle, vehiclePresets: vehiclePresets)
                    .listRowBackground(RV.surface)
                Section {
                    Button { showingSettings = true } label: {
                        RVRow(title: "Climate settings",
                              subtitle: "Temperature, seats, defrost and presets",
                              systemImage: "slider.horizontal.3",
                              tint: RV.climate)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(RV.surface)
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(RVBackground())
            .navigationTitle("Climate")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done", action: onClose) }
            }
            .sheet(isPresented: $showingSettings) {
                ClimateSettingsSheet(vehicle: bbVehicle)
            }
        }
        .tint(RV.accent)
        .rivianSurfaces()
    }

    private var statusHero: some View {
        HStack(alignment: .center, spacing: RV.Space.l) {
            VStack(alignment: .leading, spacing: RV.Space.xs) {
                Text(isOn ? "Cabin climate on" : "Cabin climate off")
                    .font(RV.Font.headline)
                    .foregroundStyle(RV.ink)
                if let temperatureText {
                    Text(temperatureText)
                        .font(RV.Font.metric)
                        .foregroundStyle(isOn ? RV.climate : RV.ink)
                }
                Text(isOn ? "Conditioning your cabin" : "Start climate to precondition")
                    .font(.subheadline)
                    .foregroundStyle(RV.inkSecondary)
            }
            Spacer(minLength: 0)
            Image(systemName: isOn ? "fan.fill" : "fan")
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(isOn ? RV.climate : RV.inkSecondary)
                .accessibilityHidden(true)
        }
        .padding(.vertical, RV.Space.s)
        .accessibilityElement(children: .combine)
    }
}
