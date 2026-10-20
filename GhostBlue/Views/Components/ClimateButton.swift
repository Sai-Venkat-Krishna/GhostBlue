//
//  ClimateButton.swift
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

struct ClimateButton: View {
    let bbVehicle: BBVehicle
    var transition: Namespace.ID?
    @Environment(\.modelContext) private var modelContext
    @State private var showingSettings = false
    @State private var appSettings = AppSettings.shared
    @Query private var allClimatePresets: [ClimatePreset]

    @State private var iconDegrees: Double = 0
    @State private var inProgress = false
    @State private var statusText = ""
    @State private var errorText: String?
    @State private var currentTask: Task<Void, Never>?

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

    var climateStartedText: String {
        guard let preset = effectivePreset else { return "Climate started" }
        let formattedTemp = preset.climateOptions.temperature.units.format(
            preset.climateOptions.temperature.value,
            to: appSettings.preferredTemperatureUnit
        )
        return "Started at \(formattedTemp)"
    }

    private var climateColor: Color { isClimateOn ? .blue : .secondary }

    private var displayStatusText: String {
        if let e = errorText { return e }
        if inProgress { return statusText.isEmpty ? "Sending command..." : statusText }
        if isClimateOn {
            return climateRunningText.isEmpty ? "Tap to stop" : climateRunningText
        }
        return "Tap to start"
    }

    var body: some View {
        Button { handleTap() } label: {
            HStack(spacing: 14) {
                ZStack {
                    // Pulsing ring during in-progress
                    Circle()
                        .stroke(climateColor.opacity(0.5), lineWidth: 2)
                        .scaleEffect(inProgress ? 1.55 : 1.0)
                        .opacity(inProgress ? 0.0 : 0.35)
                        .animation(
                            inProgress
                                ? .easeInOut(duration: 0.85).repeatForever(autoreverses: true)
                                : .default,
                            value: inProgress
                        )

                    Image(systemName: isClimateOn ? "fan" : "fan.slash")
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(inProgress ? Color.secondary : climateColor)
                        .spin(isClimateOn && !inProgress)
                        .rotation3DEffect(.degrees(iconDegrees), axis: (x: 0, y: 1, z: 0))
                        .animation(.spring(response: 0.45, dampingFraction: 0.65), value: iconDegrees)
                }
                .frame(width: 52, height: 52)

                VStack(alignment: .leading, spacing: 3) {
                    Text(isClimateOn ? "Climate On" : "Climate Off")
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundStyle(.primary)
                        .animation(.spring(response: 0.3), value: isClimateOn)

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
        }
        .buttonStyle(.plain)
        .vehicleCardGlassEffect()
        .bouncePress()
        .contextMenu {
            // Preset options
            ForEach(filteredPresets) { preset in
                Button {
                    currentTask = Task {
                        await performClimate(true, options: preset.climateOptions)
                    }
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
        .onChange(of: bbVehicle.climateStatus?.airControlOn) { _, _ in
            withAnimation(.spring(response: 0.45, dampingFraction: 0.65)) {
                iconDegrees += 180
            }
        }
        .sheet(isPresented: $showingSettings) {
            ClimateSettingsSheet(vehicle: bbVehicle)
        }
    }

    private func handleTap() {
        if inProgress {
            currentTask?.cancel()
            return
        }
        let shouldStart = !isClimateOn
        currentTask = Task {
            await performClimate(shouldStart)
        }
    }

    @MainActor
    private func performClimate(_ shouldStart: Bool, options: ClimateOptions? = nil) async {
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
                let climateOptions = options ?? effectivePreset?.climateOptions ?? ClimateOptions()
                try await account.startClimate(
                    bbVehicle,
                    options: climateOptions,
                    modelContext: context,
                    presetName: effectivePreset?.name,
                    presetIcon: effectivePreset?.iconName
                )
            } else {
                // No immediate read: it would wake the car only to see the
                // old state. waitForStatusChange polls once it has settled.
                try await account.stopClimate(bbVehicle, modelContext: context)
            }

            try await bbVehicle.waitForStatusChange(
                modelContext: context,
                condition: { status in status.climateStatus.airControlOn == shouldStart },
                statusMessageUpdater: { msg in
                    Task { @MainActor in statusText = msg }
                }
            )

            #if canImport(UIKit)
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            #endif
        } catch is CancellationError {
            // cancelled by user tap — nothing to do
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
