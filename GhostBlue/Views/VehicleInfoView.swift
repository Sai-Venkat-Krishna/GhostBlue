//
//  VehicleInfoView.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 9/7/25.
//

import BetterBlueKit
import SwiftData
import SwiftUI
import WidgetKit

struct VehicleInfoView: View {
    let bbVehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @State private var customName: String = ""
    @State private var showingCopiedMessage = false
    @State private var appSettings = AppSettings.shared
    @Query private var allClimatePresets: [ClimatePreset]

    private var vehiclePresets: [ClimatePreset] {
        allClimatePresets.filter { $0.vehicle?.id == bbVehicle.id }.sorted { $0.sortOrder < $1.sortOrder }
    }

    var body: some View {
        Form {
            VehicleBasicInfoSection(
                bbVehicle: bbVehicle,
                showingCopiedMessage: $showingCopiedMessage
            )

            Section("Custom Name") {
                TextField("Vehicle Name", text: $customName)
                    .autocapitalization(.words)
                    .onChange(of: customName) { _, newValue in
                        bbVehicle.customName = newValue.isEmpty ? nil : newValue
                        do {
                            try modelContext.save()
                        } catch {
                            BBLogger.error(.app, "Failed to save custom name: \(error)")
                        }
                    }
            }

            Section {
                HomeVehicleImageView(
                    modelName: bbVehicle.model,
                    colorName: bbVehicle.vehicleImageColor.rawValue
                )
                .frame(height: 92)
                .frame(maxWidth: .infinity)

                LazyVGrid(
                    columns: Array(repeating: GridItem(.flexible()), count: 4),
                    spacing: 12
                ) {
                    ForEach(VehicleImageColor.availableColors(for: bbVehicle.model)) { color in
                        Button {
                            updateVehicleImageColor(color)
                        } label: {
                            VStack(spacing: 5) {
                                Circle()
                                    .fill(color.swatchColor)
                                    .frame(width: 30, height: 30)
                                    .overlay {
                                        Circle()
                                            .stroke(
                                                bbVehicle.vehicleImageColor == color
                                                    ? Color.accentColor
                                                    : Color.primary.opacity(0.2),
                                                lineWidth: bbVehicle.vehicleImageColor == color ? 3 : 1
                                            )
                                    }
                                    .overlay {
                                        if bbVehicle.vehicleImageColor == color {
                                            Image(systemName: "checkmark")
                                                .font(.caption.bold())
                                                .foregroundStyle(
                                                    color == .white || color == .silver
                                                        ? Color.black
                                                        : Color.white
                                                )
                                        }
                                    }

                                Text(color.displayName)
                                    .font(.caption2)
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("\(color.displayName) vehicle image")
                        .accessibilityAddTraits(
                            bbVehicle.vehicleImageColor == color ? .isSelected : []
                        )
                    }
                }
            } header: {
                Text("Vehicle Appearance")
            } footer: {
                Text("This color is shared by Home, every widget, Live Activities, and Apple Watch.")
            }

            VehicleWidgetConfigSection(bbVehicle: bbVehicle)

            if let account = bbVehicle.account {
                Section("Account Info") {
                    NavigationLink(account.username, destination: AccountInfoView(
                        account: account,
                    ))
                }
            }

            // Climate Settings — per-vehicle toggles (seat heat for older vehicles + show duration)
            Section {
                ClimateSettingsToggles(bbVehicle: bbVehicle)
            } header: {
                HStack {
                    Text("Climate Settings")
                    ClimateSettingsInfoButton()
                    Spacer()
                }
            }

            // Powertrain override — pin gas / electric / phev when the API misreports.
            Section {
                Picker("Powertrain", selection: Binding(
                    get: { bbVehicle.fuelTypeOverride },
                    set: { newValue in
                        bbVehicle.fuelTypeOverride = newValue
                        try? modelContext.save()
                    }
                )) {
                    Text("Auto (detected)").tag(FuelType?.none)
                    Text("Gas").tag(FuelType?.some(.gas))
                    Text("Electric").tag(FuelType?.some(.electric))
                    Text("PHEV").tag(FuelType?.some(.phev))
                }
            } header: {
                Text("Powertrain")
            } footer: {
                Text("Use this when the API misreports the powertrain (e.g. a real EV showing as PHEV).")
            }

            ClimatePresetsSection(bbVehicle: bbVehicle, vehiclePresets: vehiclePresets)

            // EV Settings (only for electric vehicles)
            if bbVehicle.fuelType.hasElectricCapability {
                Section("EV Settings") {
                    Picker("Charge Port Type", selection: Binding(
                        get: { bbVehicle.chargePortType },
                        set: { newValue in
                            bbVehicle.chargePortType = newValue
                            try? modelContext.save()
                        }
                    )) {
                        ForEach(ChargePortType.allCases, id: \.self) { portType in
                            Label(portType.displayName, systemImage: portType.dcPlugIcon).tag(portType)
                        }
                    }

                    NavigationLink(destination: ChargeLimitSettingsContent(vehicle: bbVehicle)) {
                        HStack {
                            Text("Charge Limits")
                            Spacer()
                            if let evStatus = bbVehicle.evStatus,
                               let acTarget = evStatus.targetSocAC,
                               let dcTarget = evStatus.targetSocDC {
                                Text("AC: \(Int(acTarget))% / DC: \(Int(dcTarget))%")
                                    .foregroundColor(.secondary)
                                    .font(.caption)
                            }
                        }
                    }

                    NavigationLink(destination: ChargingCostSettingsView(vehicle: bbVehicle)) {
                        HStack {
                            Label("Charging Costs", systemImage: "dollarsign.circle")
                            Spacer()
                            if let homeRate = bbVehicle.offPeakElectricityRatePerKWh {
                                Text("Home \(homeRate.formatted(.currency(code: Locale.current.currency?.identifier ?? "USD")))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }

                    // Discoverable Trip History entry. The same destination is also
                    // available via the title-card context menu (long-press the vehicle
                    // name on the main screen), but most users won't think to look there
                    // — this is the obvious place.
                    if bbVehicle.account?.supportsEVTripDetails == true {
                        NavigationLink(destination: TripDetailsView(bbVehicle: bbVehicle)) {
                            Label("Trip History", systemImage: "chart.line.uptrend.xyaxis")
                        }
                    }
                }

                Section {
                    TextField("Live Data Token", text: Binding(
                        get: { bbVehicle.abrpUserToken ?? "" },
                        set: { newValue in
                            bbVehicle.abrpUserToken = newValue.isEmpty ? nil : newValue
                            try? modelContext.save()
                        }
                    ))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                    NavigationLink(destination: ABRPCarModelPicker(bbVehicle: bbVehicle)) {
                        HStack {
                            Text("Vehicle Model")
                            Spacer()
                            if let carModel = bbVehicle.abrpCarModel {
                                Text(carModel)
                                    .foregroundColor(.secondary)
                                    .font(.caption)
                                    .lineLimit(1)
                            }
                        }
                    }
                } header: {
                    Text("ABRP")
                } footer: {
                    Text("The Live Data token comes from the ABRP app (Settings > Live Data) and lets GhostBlue send this vehicle's battery level to your ABRP account. The vehicle model tells ABRP which consumption model to use for trip planning.")
                }
            }

            // Fake Vehicle Configuration (only for fake vehicles)
            if let account = bbVehicle.account, account.brandEnum == .fake {
                Section("Fake Vehicle Configuration") {
                    NavigationLink("Configure Vehicle", destination: FakeVehicleDetailView(vehicle: bbVehicle))
                }
            }

            #if DEBUG
            Toggle("Debug Live Activity", isOn: Binding(
                get: { bbVehicle.debugLiveActivity },
                set: { newValue in
                    bbVehicle.debugLiveActivity = newValue
                    try? modelContext.save()
                    LiveActivityManager.shared.updateDebugActivity(for: bbVehicle)
                }
            ))

            #endif

        }
        .navigationTitle("Vehicle Info")
        .navigationBarTitleDisplayMode(.inline)
        .overlay(alignment: .top) {
            if showingCopiedMessage {
                Text("VIN copied to clipboard")
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Color.black.opacity(0.8))
                    .foregroundColor(.white)
                    .cornerRadius(8)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                    .zIndex(1)
            }
        }
        .navigationTitle("Vehicle Info")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            customName = bbVehicle.customName ?? ""
            createDefaultPresetIfNeeded()
        }
    }

    private func createDefaultPresetIfNeeded() {
        if vehiclePresets.isEmpty {
            let defaultPreset = ClimatePreset(
                name: "Default",
                iconName: "fan",
                climateOptions: ClimateOptions(),
                isSelected: true,
                vehicle: bbVehicle
            )
            defaultPreset.sortOrder = 0
            modelContext.insert(defaultPreset)

            do {
                try modelContext.save()
            } catch {
                BBLogger.error(.app, "Failed to create default preset: \(error)")
            }
        }
    }

    private func updateVehicleImageColor(_ color: VehicleImageColor) {
        bbVehicle.vehicleImageColor = color
        try? modelContext.save()
        #if canImport(WatchConnectivity)
            CompanionPreferenceSync.shared.sendVehicleAppearance(
                vin: bbVehicle.vin,
                model: bbVehicle.model,
                color: color.rawValue
            )
        #endif

        Task {
            _ = await HomeVehicleImageRepository.image(
                for: bbVehicle.model,
                color: color.rawValue
            )
            WidgetCenter.shared.reloadAllTimelines()
            LiveActivityManager.shared.refreshAppearance(for: bbVehicle)
        }
    }
}

// MARK: - Seat Heat Info Button

private struct SeatHeatInfoButton: View {
    @State private var showingInfo = false

    var body: some View {
        Button {
            showingInfo = true
        } label: {
            Image(systemName: "info.circle")
                .foregroundColor(.blue)
        }
        .buttonStyle(.plain)
        .alert("Seat Heat Controls", isPresented: $showingInfo) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(
                "Seat heating and cooling controls are automatically available for newer " +
                "vehicles (generation 3+). Some older vehicles also support these features " +
                "but require manual enabling.\n\n" +
                "If your vehicle has heated or ventilated seats, you can enable this option " +
                "to show seat controls in your climate presets. If your vehicle doesn't " +
                "support these features, the controls will have no effect."
            )
        }
    }
}

#Preview {
    struct PreviewWrapper: View {
        var body: some View {
            let testAccount = BBAccount(
                username: "test@example.com",
                password: "password",
                pin: "1234",
                brand: .hyundai,
                region: .usa
            )

            let testVehicle = BBVehicle(from: Vehicle(
                vin: "KMHL14JA5KA123456",
                regId: "REG123",
                model: "Ioniq 5",
                accountId: testAccount.id,
                fuelType: .electric,
                generation: 3,
                odometer: Distance(length: 25000, units: .miles)
            ))

            NavigationView {
                VehicleInfoView(bbVehicle: testVehicle)
            }
            .modelContainer(for: [BBAccount.self, BBVehicle.self, ClimatePreset.self])
        }
    }
    return PreviewWrapper()
}
