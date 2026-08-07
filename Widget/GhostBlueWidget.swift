//
//  GhostBlueWidget.swift
//  GhostBlueWidget
//
//  Created by Mark Schmidt on 8/29/25.
//

import AppIntents
import BetterBlueKit
import SwiftData
import SwiftUI
import WidgetKit

struct GhostBlueWidget: Widget {
    let kind: String = "GhostBlueWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: kind,
            intent: VehicleWidgetIntent.self,
            provider: VehicleTimelineProvider(),
        ) { entry in
            VehicleWidgetEntryView(entry: entry)
        }
        .contentMarginsDisabled()
        .configurationDisplayName("Command Center")
        .description("Vehicle status card with the full set of quick controls: lock, unlock, and climate.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct GhostBlueLockScreenWidget: Widget {
    let kind: String = "GhostBlueLockScreenWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: kind,
            intent: VehicleWidgetIntent.self,
            provider: VehicleTimelineProvider(),
        ) { entry in
            LockScreenVehicleWidgetView(entry: entry)
        }
        .configurationDisplayName("Range Ring")
        .description("Lock screen ring, line, and inline readouts of charge and range.")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

// MARK: - Control Center Widgets

@available(iOS 18, *)
struct VehicleLockControlWidget: ControlWidget {
    var body: some ControlWidgetConfiguration {
        AppIntentControlConfiguration(
            kind: "com.ghostblue.lock",
            intent: LockVehicleControlIntent.self
        ) { intent in
            ControlWidgetButton(action: intent) {
                if let vehicle = intent.vehicle {
                    Label("Lock \(vehicle.displayName)", systemImage: "lock.fill")
                } else {
                    Label("Select Vehicle", systemImage: "lock.fill")
                }
            }
        }
        .promptsForUserConfiguration()
        .displayName("Lock Vehicle")
        .description("Lock your vehicle from Control Center")
    }
}

@available(iOS 18, *)
struct VehicleUnlockControlWidget: ControlWidget {
    var body: some ControlWidgetConfiguration {
        AppIntentControlConfiguration(
            kind: "com.ghostblue.unlock",
            intent: UnlockVehicleControlIntent.self
        ) { intent in
            ControlWidgetButton(action: intent) {
                if let vehicle = intent.vehicle {
                    Label("Unlock \(vehicle.displayName)", systemImage: "lock.open.fill")
                } else {
                    Label("Select Vehicle", systemImage: "lock.open.fill")
                }
            }
        }
        .promptsForUserConfiguration()
        .displayName("Unlock Vehicle")
        .description("Unlock your vehicle from Control Center")
    }
}

@available(iOS 18, *)
struct ClimateStartControlWidget: ControlWidget {
    var body: some ControlWidgetConfiguration {
        AppIntentControlConfiguration(
            kind: "com.ghostblue.climate.start",
            intent: StartClimateControlIntent.self
        ) { intent in
            ControlWidgetButton(action: intent) {
                if let preset = intent.preset {
                    Label("Start \(preset.vehicleName) - \(preset.presetName)", systemImage: "thermometer.sun.fill")
                } else {
                    Label("Select Preset", systemImage: "thermometer.snowflake")
                }
            }
        }
        .promptsForUserConfiguration()
        .displayName("Start Climate")
        .description("Start climate control from Control Center")
    }
}

@available(iOS 18, *)
struct ClimateStopControlWidget: ControlWidget {
    var body: some ControlWidgetConfiguration {
        AppIntentControlConfiguration(
            kind: "com.ghostblue.climate.stop",
            intent: StopClimateControlIntent.self
        ) { intent in
            ControlWidgetButton(action: intent) {
                if intent.vehicle != nil {
                    Label("Stop Climate", systemImage: "thermometer.snowflake")
                } else {
                    Label("Select Vehicle", systemImage: "thermometer.snowflake")
                }
            }
        }
        .promptsForUserConfiguration()
        .displayName("Stop Climate")
        .description("Stop climate control from Control Center")
    }
}

@available(iOS 18, *)
struct StartChargeControlWidget: ControlWidget {
    var body: some ControlWidgetConfiguration {
        AppIntentControlConfiguration(
            kind: "com.ghostblue.charge.start",
            intent: StartChargeControlIntent.self
        ) { intent in
            ControlWidgetButton(action: intent) {
                if intent.vehicle != nil {
                    Label("Start Charge", systemImage: "bolt.fill")
                } else {
                    Label("Select Vehicle", systemImage: "bolt.fill")
                }
            }
        }
        .promptsForUserConfiguration()
        .displayName("Start Charge")
        .description("Start charging from Control Center")
    }
}

@available(iOS 18, *)
struct StopChargeControlWidget: ControlWidget {
    var body: some ControlWidgetConfiguration {
        AppIntentControlConfiguration(
            kind: "com.ghostblue.charge.stop",
            intent: StopChargeControlIntent.self
        ) { intent in
            ControlWidgetButton(action: intent) {
                if intent.vehicle != nil {
                    Label("Stop Charge", systemImage: "bolt.slash.fill")
                } else {
                    Label("Select Vehicle", systemImage: "bolt.slash.fill")
                }
            }
        }
        .promptsForUserConfiguration()
        .displayName("Stop Charge")
        .description("Stop charging from Control Center")
    }
}
