//
//  ChargingButton.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 8/25/25.
//

import BetterBlueKit
import SwiftData
import SwiftUI

struct ChargingButton: View {
    let bbVehicle: BBVehicle
    var transition: Namespace.ID?
    @Environment(\.modelContext) private var modelContext
    @State private var showingChargeLimitSettings = false

    private var tracker: RemoteCommandTracker { RemoteCommands.tracker(.charge, for: bbVehicle) }

    var evStatus: VehicleStatus.EVStatus? {
        guard bbVehicle.modelContext != nil else { return nil }
        return bbVehicle.evStatus
    }

    var isCharging: Bool { evStatus?.charging ?? false }
    var isPluggedIn: Bool { evStatus?.pluggedIn ?? false }

    var plugIcon: Image { bbVehicle.plugIcon(for: evStatus?.plugType) }

    private var chargeColor: Color {
        if isCharging { return .green }
        if isPluggedIn { return .blue }
        return .secondary
    }

    private var stateLabel: String {
        if isCharging { return "Charging" }
        if isPluggedIn { return "Ready to Charge" }
        return "Unplugged"
    }

    private var pendingStart: Bool { tracker.tag == "start" }

    private var idleDetail: String {
        if isCharging, let speed = evStatus?.chargeSpeed, speed > 0 {
            return String(format: "%.1f kW · tap to stop", speed)
        }
        if isCharging { return "Tap to stop" }
        if isPluggedIn { return "Tap to start" }
        return "Not plugged in"
    }

    var body: some View {
        RemoteCommandCard(
            tracker: tracker,
            stateTitle: stateLabel,
            pendingTitle: pendingStart ? "Starting charge…" : "Stopping charge…",
            idleDetail: idleDetail,
            icon: isCharging ? Image(systemName: "bolt.fill") : plugIcon,
            pendingIcon: pendingStart ? Image(systemName: "bolt.fill") : plugIcon,
            tint: chargeColor,
            pulsesIcon: isCharging,
            isEnabled: isPluggedIn || isCharging
        ) {
            RemoteCommands.toggleCharging(bbVehicle, modelContext: modelContext)
        }
        .contextMenu {
            Button {
                showingChargeLimitSettings = true
            } label: {
                Label("Charge Limits", systemImage: "battery.100percent")
            }
        }
        .sheet(isPresented: $showingChargeLimitSettings) {
            ChargeLimitSettingsSheet(vehicle: bbVehicle)
        }
    }
}
