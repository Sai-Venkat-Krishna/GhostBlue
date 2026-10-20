import BetterBlueKit
import SwiftData
import SwiftUI

/// Per-vehicle values that make charge-history energy and cost estimates
/// useful without requiring a third-party tariff service.
struct ChargingCostSettingsView: View {
    @Bindable var vehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext
    @State private var batteryCapacity: Double = 0
    @State private var defaultElectricityRate: Double = 0
    @State private var homeSuperOffPeakRate: Double = 0
    @State private var dcFastChargingRate: Double = 0

    var body: some View {
        Form {
            Section("Battery") {
                TextField("Usable capacity", value: $batteryCapacity, format: .number.precision(.fractionLength(1)))
                    .keyboardType(.decimalPad)
                    .onChange(of: batteryCapacity) { _, value in
                        vehicle.batteryCapacityKWh = value > 0 ? value : nil
                        save()
                    }

                if batteryCapacity <= 0,
                   let modelDefault = BatteryCapacityCatalog.defaultCapacityKWh(
                       model: vehicle.model,
                       fuelType: vehicle.fuelType
                   ) {
                    Button("Use \(modelDefault.formatted(.number.precision(.fractionLength(0 ... 1)))) kWh (\(vehicle.model) default)") {
                        batteryCapacity = modelDefault
                    }
                }

                Text(
                    batteryCapacity <= 0 && vehicle.effectiveBatteryCapacityKWh != nil
                        ? "Charging history currently uses the model default. Enter your car's usable capacity in kWh if it has a different battery pack."
                        : "Enter the usable battery capacity in kWh. This makes charge-session energy estimates accurate even when the app was not polling continuously."
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            }

            Section("Charging prices") {
                TextField("Home super off-peak / kWh", value: $homeSuperOffPeakRate, format: currencyFormat)
                    .keyboardType(.decimalPad)
                    .onChange(of: homeSuperOffPeakRate) { _, value in
                        vehicle.offPeakElectricityRatePerKWh = value > 0 ? value : nil
                        save()
                    }

                TextField("DC fast default / kWh", value: $dcFastChargingRate, format: currencyFormat)
                    .keyboardType(.decimalPad)
                    .onChange(of: dcFastChargingRate) { _, value in
                        vehicle.dcFastChargingRatePerKWh = value > 0 ? value : nil
                        save()
                    }

                TextField("Other charging / kWh", value: $defaultElectricityRate, format: currencyFormat)
                    .keyboardType(.decimalPad)
                    .onChange(of: defaultElectricityRate) { _, value in
                        vehicle.electricityRatePerKWh = value > 0 ? value : nil
                        save()
                    }

                Text("Home sessions use the super-off-peak rate. Public DC sessions use the DC default, and you can enter the actual receipt total on any session in the Charging tab.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Charging Costs")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            batteryCapacity = vehicle.batteryCapacityKWh ?? 0
            defaultElectricityRate = vehicle.electricityRatePerKWh ?? 0
            homeSuperOffPeakRate = vehicle.offPeakElectricityRatePerKWh ?? 0
            dcFastChargingRate = vehicle.dcFastChargingRatePerKWh ?? 0
        }
    }

    private var currencyFormat: FloatingPointFormatStyle<Double>.Currency {
        .currency(code: Locale.current.currency?.identifier ?? "USD")
            .precision(.fractionLength(3))
    }

    private func save() {
        do {
            try modelContext.save()
        } catch {
            BBLogger.error(.app, "Failed to save charging cost settings: \(error)")
        }
    }
}
