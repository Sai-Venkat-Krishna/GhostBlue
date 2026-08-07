import BetterBlueKit
import SwiftData
import SwiftUI

/// Per-vehicle values that make charge-history energy and cost estimates
/// useful without requiring a third-party tariff service.
struct ChargingCostSettingsView: View {
    @Bindable var vehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext
    @State private var batteryCapacity: Double = 0
    @State private var electricityRate: Double = 0

    var body: some View {
        Form {
            Section("Battery") {
                TextField("Usable capacity", value: $batteryCapacity, format: .number.precision(.fractionLength(1)))
                    .keyboardType(.decimalPad)
                    .onChange(of: batteryCapacity) { _, value in
                        vehicle.batteryCapacityKWh = value > 0 ? value : nil
                        save()
                    }

                Text("Enter the usable battery capacity in kWh. This makes charge-session energy estimates accurate even when the app was not polling continuously.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Electricity price") {
                TextField("Price per kWh", value: $electricityRate, format: .currency(code: Locale.current.currency?.identifier ?? "USD").precision(.fractionLength(3)))
                    .keyboardType(.decimalPad)
                    .onChange(of: electricityRate) { _, value in
                        vehicle.electricityRatePerKWh = value > 0 ? value : nil
                        save()
                    }

                Text("Optional. GhostBlue uses this one rate for a clear estimated session and lifetime charging cost.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Charging Costs")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            batteryCapacity = vehicle.batteryCapacityKWh ?? 0
            electricityRate = vehicle.electricityRatePerKWh ?? 0
        }
    }

    private func save() {
        do {
            try modelContext.save()
        } catch {
            BBLogger.error(.app, "Failed to save charging cost settings: \(error)")
        }
    }
}
