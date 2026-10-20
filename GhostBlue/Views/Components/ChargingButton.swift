//
//  ChargingButton.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 8/25/25.
//

import BetterBlueKit
import SwiftData
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

struct ChargingButton: View {
    let bbVehicle: BBVehicle
    var transition: Namespace.ID?
    @Environment(\.modelContext) private var modelContext
    @State private var showingChargeLimitSettings = false

    @State private var iconDegrees: Double = 0
    @State private var inProgress = false
    @State private var statusText = ""
    @State private var errorText: String?
    @State private var currentTask: Task<Void, Never>?

    var evStatus: VehicleStatus.EVStatus? {
        guard bbVehicle.modelContext != nil else { return nil }
        return bbVehicle.evStatus
    }

    var isCharging: Bool { evStatus?.charging ?? false }
    var isPluggedIn: Bool { evStatus?.pluggedIn ?? false }

    var plugIcon: Image { bbVehicle.plugIcon(for: evStatus?.plugType) }

    private var chargeIcon: some View {
        Group {
            if isCharging {
                Image(systemName: "bolt.fill")
            } else {
                plugIcon
            }
        }
    }

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

    private var chargeSpeedText: String? {
        guard isCharging, let speed = evStatus?.chargeSpeed, speed > 0 else { return nil }
        return String(format: "%.1f kW", speed)
    }

    private var displayStatusText: String {
        if let e = errorText { return e }
        if inProgress { return statusText.isEmpty ? "Sending command..." : statusText }
        if let speed = chargeSpeedText { return speed }
        if isCharging { return "Tap to stop" }
        if isPluggedIn { return "Tap to start" }
        return "Not plugged in"
    }

    var body: some View {
        Button { handleTap() } label: {
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .stroke(chargeColor.opacity(0.5), lineWidth: 2)
                        .scaleEffect(inProgress ? 1.55 : 1.0)
                        .opacity(inProgress ? 0.0 : 0.35)
                        .animation(
                            inProgress
                                ? .easeInOut(duration: 0.85).repeatForever(autoreverses: true)
                                : .default,
                            value: inProgress
                        )

                    chargeIcon
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(inProgress ? Color.secondary : chargeColor)
                        .pulse(isCharging && !inProgress)
                        .rotation3DEffect(.degrees(iconDegrees), axis: (x: 0, y: 1, z: 0))
                        .animation(.spring(response: 0.45, dampingFraction: 0.65), value: iconDegrees)
                }
                .frame(width: 52, height: 52)

                VStack(alignment: .leading, spacing: 3) {
                    Text(stateLabel)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundStyle(.primary)
                        .animation(.spring(response: 0.3), value: isCharging)

                    Text(displayStatusText)
                        .font(.caption)
                        .foregroundStyle(errorText != nil ? Color.red : Color.secondary)
                        .lineLimit(1)
                        .animation(.easeInOut(duration: 0.2), value: displayStatusText)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .opacity(inProgress ? 0 : 1)
                    .animation(.easeInOut(duration: 0.15), value: inProgress)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            .opacity((!isPluggedIn && !isCharging) ? 0.5 : 1.0)
        }
        .buttonStyle(.plain)
        .vehicleCardGlassEffect()
        .bouncePress()
        .disabled(!isPluggedIn && !isCharging)
        .contextMenu {
            Button {
                showingChargeLimitSettings = true
            } label: {
                Label("Charge Limits", systemImage: "battery.100percent")
            }
        }
        .onChange(of: bbVehicle.evStatus?.charging) { _, _ in
            withAnimation(.spring(response: 0.45, dampingFraction: 0.65)) {
                iconDegrees += 180
            }
        }
        .sheet(isPresented: $showingChargeLimitSettings) {
            ChargeLimitSettingsSheet(vehicle: bbVehicle)
        }
    }

    private func handleTap() {
        if inProgress {
            currentTask?.cancel()
            return
        }
        let shouldStart = !isCharging
        currentTask = Task {
            await performCharge(shouldStart)
        }
    }

    @MainActor
    private func performCharge(_ shouldStart: Bool) async {
        inProgress = true
        statusText = ""
        errorText = nil
        defer {
            inProgress = false
            currentTask = nil
        }

        do {
            guard let account = bbVehicle.account else {
                throw APIError(message: "Account not found for vehicle")
            }
            let context = modelContext

            if shouldStart {
                try await account.startCharge(bbVehicle, modelContext: context)
            } else {
                // No immediate read: it would wake the car only to see the
                // old state. waitForStatusChange polls once it has settled.
                try await account.stopCharge(bbVehicle, modelContext: context)
            }

            try await bbVehicle.waitForStatusChange(
                modelContext: context,
                condition: { status in status.evStatus?.charging == shouldStart },
                statusMessageUpdater: { msg in
                    Task { @MainActor in statusText = msg }
                }
            )

            #if canImport(UIKit)
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            #endif
        } catch is CancellationError {
            // cancelled by user — nothing to do
        } catch {
            let msg = (error as? APIError)?.message ?? error.localizedDescription
            errorText = msg
            Task {
                try? await Task.sleep(for: .seconds(4))
                await MainActor.run { errorText = nil }
            }
        }
    }
}
