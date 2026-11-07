//
//  MaterialDashboard.swift
//  GhostBlue
//
//  The Material You theme's Home, mirroring the Android app's
//  `MaterialDashboard` (android/.../ui/home/HomeScreen.kt and HomeCards.kt):
//  a top bar with the car switcher, refresh and overflow menu; the hero card;
//  the Controls card (Lock / Unlock as a connected button group, climate as a
//  split button with presets, charging with a charge-limits button); then
//  Smart briefing, Charging, Vehicle status and Location. Commands run
//  through the shared `RemoteCommands` trackers, so their progress shows on
//  the button that was tapped.
//

import BetterBlueKit
import MapKit
import SwiftData
import SwiftUI

struct MaterialDashboard: View {
    let bbVehicle: BBVehicle
    var vehicleOptions: [BBVehicle] = []
    var onSelectVehicle: ((Int) -> Void)?
    var isRefreshing = false
    var refreshSucceeded = false
    var refreshCoolingDown = false
    var onRefresh: () -> Void = {}
    var errorMessage: AttributedString?
    var onTapError: () -> Void = {}
    var onOpenSettings: () -> Void = {}
    var onOpenLocation: () -> Void = {}
    var onOpenCharging: () -> Void = {}
    var onOpenVehicleCenter: () -> Void = {}

    @State private var screenSize: CGSize = .zero
    @State private var appSettings = AppSettings.shared

    private var isWide: Bool { AdaptiveLayout.isWide(screenSize.width) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                topBar
                if let errorMessage {
                    errorCard(errorMessage)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
                AdaptiveColumns(isWide: isWide, spacing: 12) {
                    MDHeroCard(vehicle: bbVehicle)
                    MDControlsCard(vehicle: bbVehicle)
                } trailing: {
                    MDBriefingCard(vehicle: bbVehicle)
                    if bbVehicle.evStatus != nil {
                        MDChargingCard(vehicle: bbVehicle, onOpen: onOpenCharging)
                    }
                    MDStatusCard(vehicle: bbVehicle)
                    if bbVehicle.coordinate != nil {
                        MDLocationCard(vehicle: bbVehicle, onOpen: onOpenLocation)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
            .animation(.spring(response: 0.32, dampingFraction: 0.8), value: errorMessage != nil)
        }
        .scrollIndicators(.hidden)
        .readSize(into: $screenSize)
        .rivianStatusBarBackdrop()
        .background(MDTokens.background.ignoresSafeArea())
        .tint(MDTokens.primary)
    }

    // MARK: Top bar (LargeFlexibleTopAppBar)

    private var topBar: some View {
        HStack(alignment: .center, spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                vehicleSwitcher
                Text(updatedLine)
                    .font(MDType.bodyMedium.weight(.medium))
                    .foregroundStyle(MDTokens.onSurfaceVariant)
                    .contentTransition(.opacity)
                    .animation(.easeInOut(duration: 0.25), value: updatedLine)
            }
            Spacer(minLength: 8)
            refreshButton
            overflowMenu
        }
        .padding(.top, 8)
    }

    private var updatedLine: String {
        if isRefreshing { return "Refreshing…" }
        guard let lastUpdated = bbVehicle.lastUpdated else { return "Not updated yet" }
        return "Updated \(lastUpdated.formatted(.relative(presentation: .named)))"
    }

    @ViewBuilder
    private var vehicleSwitcher: some View {
        let title = HStack(spacing: 6) {
            Text(bbVehicle.displayName)
                .font(MDType.topBarTitle)
                .foregroundStyle(MDTokens.onSurface)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            if vehicleOptions.count > 1 {
                Image(systemName: "chevron.down")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(MDTokens.onSurface)
            }
        }

        if vehicleOptions.count > 1, let onSelectVehicle {
            Menu {
                ForEach(Array(vehicleOptions.enumerated()), id: \.element.id) { index, vehicle in
                    Button {
                        onSelectVehicle(index)
                    } label: {
                        if vehicle.id == bbVehicle.id {
                            Label(vehicle.displayName, systemImage: "checkmark")
                        } else {
                            Text(vehicle.displayName)
                        }
                    }
                }
            } label: {
                title
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Vehicle: \(bbVehicle.displayName). Switch vehicle")
        } else {
            title.accessibilityAddTraits(.isHeader)
        }
    }

    private var refreshButton: some View {
        MDIconButton(accessibilityLabel: isRefreshing ? "Refreshing" : "Refresh from vehicle", action: onRefresh) {
            ZStack {
                if isRefreshing {
                    MDLoadingIndicator(size: 24)
                        .transition(.scale.combined(with: .opacity))
                } else {
                    Image(systemName: refreshCoolingDown ? "clock" : (refreshSucceeded ? "checkmark.circle.fill" : "arrow.clockwise"))
                        .foregroundStyle(
                            refreshCoolingDown ? MDTokens.danger : (refreshSucceeded ? MDTokens.energy : MDTokens.onSurfaceVariant)
                        )
                        .contentTransition(.symbolEffect(.replace))
                        .transition(.opacity)
                }
            }
            .animation(.spring(response: 0.32, dampingFraction: 0.8), value: isRefreshing)
            .animation(.spring(response: 0.32, dampingFraction: 0.8), value: refreshSucceeded)
        }
        .disabled(isRefreshing)
    }

    private var overflowMenu: some View {
        Menu {
            if !appSettings.hideVehicleLocation, let coordinate = bbVehicle.coordinate {
                ForEach(NavigationHelper.availableMapApps, id: \.rawValue) { app in
                    Button("Navigate with \(app.rawValue)") {
                        NavigationHelper.navigate(using: app, to: coordinate, destinationName: bbVehicle.displayName)
                    }
                }
            }
            Button("Vehicle Center", action: onOpenVehicleCenter)
            Button("Settings", action: onOpenSettings)
        } label: {
            Image(systemName: "ellipsis")
                .rotationEffect(.degrees(90))
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(MDTokens.onSurfaceVariant)
                .frame(width: 48, height: 48)
                .contentShape(Circle())
        }
        .accessibilityLabel("Vehicle actions")
    }

    private func errorCard(_ message: AttributedString) -> some View {
        Button(action: onTapError) {
            HStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                Text(message)
                    .font(MDType.bodyMedium)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
            }
            .foregroundStyle(Color(light: 0x410002, dark: 0xFFDAD6))
            .padding(16)
            .background(
                Color(light: 0xFFDAD6, dark: 0x93000A),
                in: RoundedRectangle(cornerRadius: MDTokens.cardRadius, style: .continuous)
            )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Shared helpers

@MainActor
private func mdDistance(_ distance: Distance) -> String {
    distance.units.format(distance.length, to: AppSettings.shared.preferredDistanceUnit)
}

private func mdRelative(_ date: Date) -> String {
    date.formatted(.relative(presentation: .named))
}

/// The line under a control: the command's progress, its error, or `idle`,
/// as Android's `CommandLine`.
private struct MDCommandLine: View {
    let tracker: RemoteCommandTracker
    let idle: String

    private var text: String {
        if let error = tracker.errorMessage { return error }
        if tracker.didSucceed { return "Confirmed by vehicle" }
        if tracker.isRunning { return tracker.statusText ?? idle }
        return idle
    }

    private var color: Color {
        if tracker.errorMessage != nil { return MDTokens.danger }
        if tracker.isRunning || tracker.didSucceed { return MDTokens.primary }
        return MDTokens.onSurfaceVariant
    }

    var body: some View {
        Text(text)
            .font(MDType.bodySmall)
            .foregroundStyle(color)
            .lineLimit(2)
            .contentTransition(.opacity)
            .animation(.easeInOut(duration: 0.25), value: text)
    }
}

/// Android's `ControlRow`: a cookie icon, a title and the command line, with
/// the loading indicator while the command runs.
private struct MDControlRow: View {
    let systemImage: String
    let title: String
    let tracker: RemoteCommandTracker
    var tint: Color = MDTokens.primary
    let idle: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 12) {
            MDShapedIcon(systemImage: systemImage, tint: tracker.errorMessage != nil ? MDTokens.danger : tint)
                .symbolEffect(.bounce, options: .nonRepeating, value: reduceMotion ? 0 : tracker.successCount)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(MDType.titleSmall)
                    .foregroundStyle(MDTokens.onSurface)
                    .contentTransition(.opacity)
                MDCommandLine(tracker: tracker, idle: idle)
            }
            Spacer(minLength: 8)
            if tracker.isRunning {
                MDLoadingIndicator(size: 32)
                    .transition(.scale.combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.32, dampingFraction: 0.8), value: tracker.isRunning)
        .animation(.spring(response: 0.32, dampingFraction: 0.8), value: title)
        .accessibilityElement(children: .combine)
    }
}

private extension View {
    /// The command's progress traced inside a Material button: onPrimary on
    /// filled buttons, primary on tonal ones.
    func mdCommandTrace(
        _ tracker: RemoteCommandTracker,
        onFilled: Bool,
        corners: CGFloat? = nil,
        isActive: Bool = true
    ) -> some View {
        // Trace the 40 pt button itself, not its touch padding.
        padding(.vertical, -MDButtonStyle.touchPadding)
            .commandProgressBorder(
                tracker,
                shape: corners.map { .rounded($0) } ?? .circle,
                tint: onFilled ? MDTokens.onPrimary : MDTokens.primary,
                successTint: onFilled ? MDTokens.onPrimary : MDTokens.primary,
                lineWidth: 2.5,
                showsWash: false,
                isActive: isActive
            )
            .padding(.vertical, MDButtonStyle.touchPadding)
    }
}

// MARK: - Hero

private struct MDHeroCard: View {
    let vehicle: BBVehicle
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var brandLine: String {
        let fuel: String = switch vehicle.fuelType {
        case .electric: "Electric"
        case .phev: "Plug-in hybrid"
        case .gas: "Gas"
        }
        return [vehicle.account?.brandEnum.displayName, fuel].compactMap { $0 }.joined(separator: " · ")
    }

    var body: some View {
        MDSectionCard(container: MDTokens.surfaceContainerHigh, padding: 20) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(vehicle.model)
                        .font(MDType.titleSmall.weight(.semibold))
                        .foregroundStyle(MDTokens.onSurface)
                        .lineLimit(1)
                    Text(brandLine)
                        .font(MDType.bodySmall)
                        .foregroundStyle(MDTokens.onSurfaceVariant)
                }
                Spacer(minLength: 8)
                MDStatePill(vehicle: vehicle)
            }

            HomeVehicleImageView(
                modelName: vehicle.model,
                colorName: vehicle.vehicleImageColor.rawValue
            )
            .frame(maxWidth: .infinity)
            .frame(height: 150)
            .padding(.vertical, 4)

            if let range = vehicle.evStatus?.evRange ?? vehicle.gasRange {
                rangeRow(range)
                let charging = vehicle.evStatus?.charging == true
                MDLinearProgress(
                    progress: range.percentage / 100,
                    tint: range.percentage <= 20 ? MDTokens.danger : MDTokens.energy,
                    wavy: charging
                )
                if charging, let ev = vehicle.evStatus {
                    Text(chargingLine(ev))
                        .font(MDType.bodySmall)
                        .foregroundStyle(MDTokens.energy)
                        .contentTransition(.opacity)
                }
            }
        }
        .accessibilityElement(children: .contain)
    }

    private func rangeRow(_ range: VehicleStatus.FuelRange) -> some View {
        HStack(alignment: .bottom, spacing: 12) {
            Text("\(Int(range.percentage))%")
                .font(MDType.display)
                .foregroundStyle(MDTokens.onSurface)
                .contentTransition(.numericText(value: range.percentage))
                .animation(reduceMotion ? nil : .snappy, value: Int(range.percentage))
            VStack(alignment: .leading, spacing: 2) {
                Text(mdDistance(range.range))
                    .font(MDType.titleSmall.weight(.medium))
                    .foregroundStyle(MDTokens.onSurface)
                    .contentTransition(.numericText())
                Text(vehicle.evStatus != nil ? "Estimated range" : "Fuel range")
                    .font(MDType.labelSmall)
                    .foregroundStyle(MDTokens.onSurfaceVariant)
            }
            .padding(.bottom, 8)
            Spacer(minLength: 0)
            if vehicle.evStatus == nil {
                Image(systemName: "fuelpump.fill")
                    .foregroundStyle(MDTokens.onSurfaceVariant)
                    .padding(.bottom, 10)
            }
        }
    }

    private func chargingLine(_ ev: VehicleStatus.EVStatus) -> String {
        var parts = ["Charging at \(ev.chargeSpeed.formatted(.number.precision(.fractionLength(1)))) kW"]
        if ev.chargeTime > .seconds(0) {
            parts.append("\(ev.chargeTime.formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))) left")
        }
        if let finish = chargeFinishDate(remainingSeconds: ev.chargeTime.components.seconds, reportedAt: vehicle.lastUpdated) {
            parts.append("done \(formatChargeFinishTime(finish))")
        }
        return parts.joined(separator: " · ")
    }
}

/// Android's Energy `StatusHero`: battery, range, charging state, the last
/// reading and the charge button.
struct MDEnergyHero: View {
    let vehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var chargeTracker: RemoteCommandTracker { RemoteCommands.tracker(.charge, for: vehicle) }

    var body: some View {
        let state = vehicle.chargingDisplayState()
        let warn = state == .outdated || state == .confirmingStop
        MDSectionCard(container: MDTokens.surfaceContainerHigh) {
            if let ev = vehicle.evStatus {
                HStack(alignment: .bottom, spacing: 2) {
                    Text("\(Int(ev.evRange.percentage))")
                        .font(.system(size: 57, weight: .regular))
                        .foregroundStyle(MDTokens.onSurface)
                        .contentTransition(.numericText(value: ev.evRange.percentage))
                        .animation(reduceMotion ? nil : .snappy, value: Int(ev.evRange.percentage))
                    Text("%")
                        .font(.system(size: 28, weight: .regular))
                        .foregroundStyle(MDTokens.onSurface)
                        .padding(.bottom, 10)
                    Spacer(minLength: 8)
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(mdDistance(ev.evRange.range))
                            .font(MDType.titleSmall.weight(.medium))
                            .foregroundStyle(MDTokens.onSurface)
                        Text("Estimated range")
                            .font(MDType.labelSmall)
                            .foregroundStyle(MDTokens.onSurfaceVariant)
                    }
                    .padding(.bottom, 8)
                }
                MDLinearProgress(
                    progress: ev.evRange.percentage / 100,
                    tint: ev.evRange.percentage <= 20 && !ev.charging ? MDTokens.danger : MDTokens.energy,
                    wavy: ev.charging
                )
                if ev.charging, ev.chargeTime > .seconds(0) {
                    Text("\(ev.chargeTime.formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))) remaining")
                        .font(MDType.bodyMedium)
                        .foregroundStyle(MDTokens.energy)
                }
            } else {
                Text("Battery data unavailable")
                    .font(MDType.titleMedium)
                    .foregroundStyle(MDTokens.onSurface)
            }

            Label {
                Text(state.title)
                    .font(MDType.titleSmall.weight(.medium))
                    .foregroundStyle(warn ? MDTokens.service : MDTokens.onSurface)
            } icon: {
                Image(systemName: state == .outdated ? "clock" : "bolt.fill")
                    .foregroundStyle(warn ? MDTokens.service : MDTokens.energy)
            }
            if state == .confirmingStop {
                Text("The last update reported a stop. Waiting for a newer vehicle reading to confirm it.")
                    .font(MDType.bodySmall)
                    .foregroundStyle(MDTokens.onSurfaceVariant)
            }
            if let sample = vehicle.vehicleSampleDate {
                Text("Vehicle reading \(mdRelative(sample))")
                    .font(MDType.bodySmall)
                    .foregroundStyle(MDTokens.onSurfaceVariant)
            }

            if let ev = vehicle.evStatus {
                Button {
                    RemoteCommands.toggleCharging(vehicle, modelContext: modelContext)
                } label: {
                    Text(
                        chargeTracker.isRunning ? "Cancel"
                            : (ev.charging ? "Stop charging" : (ev.pluggedIn ? "Start charging" : "Unplugged"))
                    )
                    .frame(maxWidth: .infinity)
                    .contentTransition(.opacity)
                }
                .buttonStyle(MDButtonStyle(kind: .filled))
                .disabled(!(ev.pluggedIn || ev.charging || chargeTracker.isRunning))
                .mdCommandTrace(chargeTracker, onFilled: true, corners: 24)

                MDCommandLine(tracker: chargeTracker, idle: energyIdle(ev))
            }
        }
    }

    private func energyIdle(_ ev: VehicleStatus.EVStatus) -> String {
        if ev.charging, ev.chargeSpeed > 0 {
            return "\(ev.chargeSpeed.formatted(.number.precision(.fractionLength(1)))) kW"
        }
        if ev.charging { return "Tap to stop" }
        if ev.pluggedIn { return "Ready to charge" }
        return "Not plugged in"
    }
}

/// Android's `StatePill`: what the car is doing, tinted to match.
private struct MDStatePill: View {
    let vehicle: BBVehicle

    private var state: (title: String, systemImage: String, tint: Color) {
        if vehicle.doorOpen?.anyOpen == true || vehicle.hoodOpen == true || vehicle.trunkOpen == true
            || vehicle.tirePressureWarning?.hasWarning == true {
            return ("Check vehicle", "exclamationmark.triangle.fill", MDTokens.danger)
        }
        if vehicle.evStatus?.charging == true { return ("Charging", "bolt.fill", MDTokens.energy) }
        if vehicle.climateStatus?.airControlOn == true { return ("Climate on", "snowflake", MDTokens.climate) }
        if vehicle.lockStatus == .locked { return ("Locked", "lock.fill", MDTokens.energy) }
        return ("Vehicle", "checkmark.circle.fill", MDTokens.primary)
    }

    var body: some View {
        let state = state
        Label(state.title, systemImage: state.systemImage)
            .font(MDType.bodyLarge)
            .foregroundStyle(state.tint)
            .contentTransition(.symbolEffect(.replace))
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(state.tint.opacity(0.16), in: Capsule())
            .animation(.spring(response: 0.32, dampingFraction: 0.8), value: state.title)
    }
}

// MARK: - Controls

private struct MDControlsCard: View {
    let vehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext
    @Query private var allPresets: [ClimatePreset]
    @State private var appSettings = AppSettings.shared
    @State private var showingClimateSettings = false
    @State private var showingChargeLimits = false

    private var lockTracker: RemoteCommandTracker { RemoteCommands.tracker(.lock, for: vehicle) }
    private var climateTracker: RemoteCommandTracker { RemoteCommands.tracker(.climate, for: vehicle) }
    private var chargeTracker: RemoteCommandTracker { RemoteCommands.tracker(.charge, for: vehicle) }

    private var presets: [ClimatePreset] {
        allPresets.filter { $0.vehicle?.id == vehicle.id }.sorted { $0.sortOrder < $1.sortOrder }
    }

    private var selectedPreset: ClimatePreset? { presets.first { $0.isSelected } ?? presets.first }

    var body: some View {
        MDSectionCard {
            MDCardHeader(title: "Controls")
            lockControls
            climateControls
            if let ev = vehicle.evStatus {
                chargeControls(ev)
            }
        }
        .sheet(isPresented: $showingClimateSettings) {
            ClimateSettingsSheet(vehicle: vehicle)
        }
        .sheet(isPresented: $showingChargeLimits) {
            ChargeLimitSettingsSheet(vehicle: vehicle)
        }
    }

    // Lock and unlock: a connected button group; the checked one is what the car reports.
    @ViewBuilder
    private var lockControls: some View {
        let locked = vehicle.lockStatus == .locked
        let unlocked = vehicle.lockStatus == .unlocked
        MDControlRow(
            systemImage: unlocked ? "lock.open.fill" : "lock.fill",
            title: locked ? "Locked" : (unlocked ? "Unlocked" : "Lock status unknown"),
            tracker: lockTracker,
            idle: locked ? "Tap Unlock to open" : "Tap Lock to secure"
        )
        HStack(spacing: 2) {
            toggleButton(title: "Lock", systemImage: "lock.fill", checked: locked, tag: "lock", corners: .mdLeading()) {
                RemoteCommands.setLocked(true, vehicle: vehicle, modelContext: modelContext)
            }
            toggleButton(title: "Unlock", systemImage: "lock.open", checked: unlocked, tag: "unlock", corners: .mdTrailing()) {
                RemoteCommands.setLocked(false, vehicle: vehicle, modelContext: modelContext)
            }
        }
    }

    private func toggleButton(
        title: String,
        systemImage: String,
        checked: Bool,
        tag: String,
        corners: RectangleCornerRadii,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .frame(maxWidth: .infinity)
        }
        // A checked toggle is fully round, as Material's toggle buttons.
        .buttonStyle(MDButtonStyle(kind: checked ? .filled : .toggleOff, corners: checked ? nil : corners))
        .mdCommandTrace(lockTracker, onFilled: checked, corners: checked ? 24 : 8, isActive: lockTracker.tag == tag)
        .animation(.spring(response: 0.32, dampingFraction: 0.8), value: checked)
        .accessibilityAddTraits(checked ? .isSelected : [])
    }

    // Climate: a split button, the menu starting any preset.
    @ViewBuilder
    private var climateControls: some View {
        let climateOn = vehicle.climateStatus?.airControlOn == true
        MDControlRow(
            systemImage: "snowflake",
            title: climateOn ? "Climate on" : "Climate off",
            tracker: climateTracker,
            tint: MDTokens.climate,
            idle: climateIdle(climateOn)
        )
        HStack(spacing: 2) {
            Button {
                RemoteCommands.toggleClimate(
                    vehicle,
                    options: selectedPreset?.climateOptions,
                    presetName: selectedPreset?.name,
                    presetIcon: selectedPreset?.iconName,
                    modelContext: modelContext
                )
            } label: {
                Label(
                    climateTracker.isRunning ? "Cancel" : (climateOn ? "Stop climate" : "Start climate"),
                    systemImage: climateOn ? "wind" : "snowflake"
                )
                .contentTransition(.opacity)
            }
            .buttonStyle(MDButtonStyle(kind: .filled, corners: .mdLeading(inner: 4)))
            .mdCommandTrace(climateTracker, onFilled: true, corners: 4)

            Menu {
                ForEach(presets) { preset in
                    Button {
                        RemoteCommands.setClimate(
                            true,
                            vehicle: vehicle,
                            options: preset.climateOptions,
                            presetName: preset.name,
                            presetIcon: preset.iconName,
                            modelContext: modelContext
                        )
                    } label: {
                        Label("Start \(preset.name)", systemImage: preset.iconName)
                    }
                }
                Button {
                    showingClimateSettings = true
                } label: {
                    Label("Climate settings", systemImage: "slider.horizontal.3")
                }
            } label: {
                Image(systemName: "chevron.down")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(MDTokens.onPrimary)
                    .frame(width: 52, height: 40)
                    .background(
                        UnevenRoundedRectangle(cornerRadii: .mdTrailing(inner: 4), style: .continuous)
                            .fill(MDTokens.primary)
                    )
                    .padding(.vertical, MDButtonStyle.touchPadding)
            }
            .accessibilityLabel("Climate presets")
        }

        if presets.count > 1 {
            ScrollView(.horizontal) {
                HStack(spacing: 2) {
                    ForEach(Array(presets.enumerated()), id: \.element.id) { index, preset in
                        Button {
                            select(preset)
                        } label: {
                            Label(preset.name, systemImage: preset.iconName)
                        }
                        .buttonStyle(
                            MDButtonStyle(
                                kind: preset.isSelected ? .filled : .toggleOff,
                                corners: preset.isSelected ? nil : (index == 0 ? .mdLeading() : (index == presets.count - 1 ? .mdTrailing() : .mdMiddle()))
                            )
                        )
                    }
                }
            }
            .scrollIndicators(.hidden)
        }
    }

    private func climateIdle(_ climateOn: Bool) -> String {
        if climateOn, let temperature = vehicle.climateStatus?.temperature, temperature.isPlausibleForDisplay {
            return "Running at \(temperature.units.format(temperature.value, to: appSettings.preferredTemperatureUnit))"
        }
        if climateOn { return "Running" }
        if let selectedPreset { return "Starts with \(selectedPreset.name)" }
        return "Tap to start"
    }

    private func select(_ preset: ClimatePreset) {
        for other in presets { other.isSelected = other.id == preset.id }
        try? modelContext.save()
    }

    // Charging.
    @ViewBuilder
    private func chargeControls(_ ev: VehicleStatus.EVStatus) -> some View {
        MDControlRow(
            systemImage: ev.charging ? "bolt.fill" : "ev.plug.ac.type.1",
            title: ev.charging ? "Charging" : (ev.pluggedIn ? "Ready to charge" : "Unplugged"),
            tracker: chargeTracker,
            tint: MDTokens.energy,
            idle: chargeIdle(ev)
        )
        HStack(spacing: 8) {
            Button {
                RemoteCommands.toggleCharging(vehicle, modelContext: modelContext)
            } label: {
                Label(
                    chargeTracker.isRunning ? "Cancel" : (ev.charging ? "Stop charging" : "Start charging"),
                    systemImage: "bolt.fill"
                )
                .frame(maxWidth: .infinity)
                .contentTransition(.opacity)
            }
            .buttonStyle(MDButtonStyle(kind: .filled))
            .disabled(!(ev.pluggedIn || ev.charging || chargeTracker.isRunning))
            .mdCommandTrace(chargeTracker, onFilled: true, corners: 24)

            MDTonalIconButton(systemImage: "battery.100percent.bolt", accessibilityLabel: "Charge limits") {
                showingChargeLimits = true
            }
        }
    }

    private func chargeIdle(_ ev: VehicleStatus.EVStatus) -> String {
        if ev.charging, ev.chargeSpeed > 0 {
            return "\(ev.chargeSpeed.formatted(.number.precision(.fractionLength(1)))) kW"
        }
        if ev.charging { return "Tap to stop" }
        if ev.pluggedIn { return "Tap to start" }
        return "Not plugged in"
    }
}

// MARK: - Smart briefing

private struct MDBriefingCard: View {
    let vehicle: BBVehicle

    private enum Severity { case good, info, warning, critical }

    private struct Item: Identifiable {
        let title: String
        let detail: String
        let severity: Severity
        var id: String { title }
    }

    /// The same items, in the same order and words, as Android's `briefingItems`.
    private var items: [Item] {
        var items: [Item] = []
        let hasOpenPanel = vehicle.doorOpen?.anyOpen == true || vehicle.hoodOpen == true || vehicle.trunkOpen == true
        if hasOpenPanel {
            let parts = [
                vehicle.doorOpen.flatMap { $0.anyOpen ? "Doors: \($0.openDoorsDescription)" : nil },
                vehicle.hoodOpen == true ? "Hood" : nil,
                vehicle.trunkOpen == true ? "Trunk" : nil
            ].compactMap { $0 }
            items.append(Item(title: "Vehicle is open", detail: parts.joined(separator: " · "), severity: .warning))
        }
        if let tires = vehicle.tirePressureWarning, tires.hasWarning {
            items.append(Item(title: "Check tire pressure", detail: tires.warningDescription, severity: .warning))
        }
        if let battery = vehicle.battery12V, battery < 40 {
            items.append(Item(title: "12V battery is low", detail: "\(battery)%", severity: battery < 20 ? .critical : .warning))
        }
        if vehicle.lockStatus == .unlocked {
            items.append(Item(title: "Vehicle is unlocked", detail: "Lock it from the controls below.", severity: .info))
        }
        if let ev = vehicle.evStatus {
            let percentage = ev.evRange.percentage
            if ev.charging {
                let target = Int(min(max(ev.currentTargetSOC ?? 100, min(percentage, 100)), 100))
                let finish = chargeFinishDate(remainingSeconds: ev.chargeTime.components.seconds, reportedAt: vehicle.lastUpdated)
                    .map { " · done around \(formatChargeFinishTime($0))" } ?? ""
                items.append(Item(title: "Charging to \(target)%", detail: "\(Int(percentage))% now\(finish)", severity: .good))
            } else if percentage <= 20 {
                items.append(Item(
                    title: "Battery is low",
                    detail: "\(Int(percentage))% · \(mdDistance(ev.evRange.range)) left",
                    severity: percentage <= 10 ? .critical : .warning
                ))
            }
        }
        if let updated = vehicle.lastUpdated, Date().timeIntervalSince(updated) >= 15 * 60 {
            let age = updated.formatted(.relative(presentation: .numeric)).replacingOccurrences(of: " ago", with: "")
            items.append(Item(title: "Status is \(age) old", detail: "Pull down to ask the car for a fresh reading.", severity: .info))
        }
        if items.isEmpty {
            items.append(Item(title: "Everything looks good", detail: "Locked, closed and ready.", severity: .good))
        }
        return items
    }

    private func tint(_ severity: Severity) -> Color {
        switch severity {
        case .good: MDTokens.energy
        case .info: MDTokens.primary
        case .warning: MDTokens.service
        case .critical: MDTokens.danger
        }
    }

    var body: some View {
        let items = items
        MDSectionCard {
            MDCardHeader(title: "Smart briefing")
            ForEach(items.prefix(3)) { item in
                HStack(spacing: 12) {
                    Circle()
                        .fill(tint(item.severity))
                        .frame(width: 10, height: 10)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title)
                            .font(MDType.bodyLarge)
                            .foregroundStyle(MDTokens.onSurface)
                        if !item.detail.isEmpty {
                            Text(item.detail)
                                .font(MDType.bodySmall)
                                .foregroundStyle(MDTokens.onSurfaceVariant)
                        }
                    }
                }
                .accessibilityElement(children: .combine)
                .transition(.opacity)
            }
            if items.count > 3 {
                Text("+\(items.count - 3) more")
                    .font(MDType.labelMedium)
                    .foregroundStyle(MDTokens.onSurfaceVariant)
            }
        }
        .animation(.spring(response: 0.32, dampingFraction: 0.8), value: items.map(\.id))
    }
}

// MARK: - Charging

private struct MDChargingCard: View {
    let vehicle: BBVehicle
    let onOpen: () -> Void

    var body: some View {
        if let ev = vehicle.evStatus {
            Button(action: onOpen) {
                MDSectionCard {
                    MDCardHeader(title: "Charging", subtitle: subtitle)
                    if ev.charging {
                        let target = Int(min(max(ev.currentTargetSOC ?? 100, min(ev.evRange.percentage, 100)), 100))
                        Text("\(Int(ev.evRange.percentage))% of \(target)% target")
                            .font(MDType.bodyMedium)
                            .foregroundStyle(MDTokens.energy)
                        MDStatGrid(items: stats(ev, target: Double(target)))
                    } else {
                        HStack {
                            Text("Charge limit")
                                .font(MDType.bodyMedium)
                                .foregroundStyle(MDTokens.onSurfaceVariant)
                            Spacer()
                            Text(limitText(ev))
                                .font(MDType.bodyMedium.weight(.medium))
                                .foregroundStyle(MDTokens.onSurface)
                        }
                    }
                    ForEach(warnings(ev), id: \.self) { warning in
                        Label(warning, systemImage: "info.circle.fill")
                            .font(MDType.bodySmall)
                            .foregroundStyle(MDTokens.service)
                    }
                }
            }
            .buttonStyle(.plain)
        }
    }

    private var subtitle: String {
        var text = vehicle.chargingDisplayState().title
        if let sample = vehicle.vehicleSampleDate { text += " · reading \(mdRelative(sample))" }
        return text
    }

    private func limitText(_ ev: VehicleStatus.EVStatus) -> String {
        if let target = ev.currentTargetSOC { return "\(Int(target))%" }
        let ac = ev.targetSocAC.map { "\(Int($0))" } ?? "--"
        let dc = ev.targetSocDC.map { "\(Int($0))" } ?? "--"
        return "AC \(ac)% · DC \(dc)%"
    }

    private func stats(_ ev: VehicleStatus.EVStatus, target: Double) -> [(label: String, value: String)] {
        let remaining = ev.chargeTime > .seconds(0)
            ? ev.chargeTime.formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
            : "Calculating"
        let speed = ev.chargeSpeed > 0 ? "\(ev.chargeSpeed.formatted(.number.precision(.fractionLength(1)))) kW" : "--"
        let energyLeft = vehicle.effectiveBatteryCapacityKWh.map { max(0, $0 * (target - ev.evRange.percentage) / 100) }
        let isDC = ev.plugType == .dcCharger
        let rate = vehicle.chargingRate(
            at: Date(),
            locationType: vehicle.inferredChargingLocationType(
                latitude: vehicle.location?.latitude,
                longitude: vehicle.location?.longitude,
                isDCFast: isDC
            ),
            isDCFast: isDC
        )
        let cost = energyLeft.flatMap { energy in rate.flatMap { $0 > 0 ? energy * $0 : nil } }
        return [
            ("Remaining", remaining),
            ("Speed", speed),
            ("Energy left", energyLeft.map { "\($0.formatted(.number.precision(.fractionLength(1)))) kWh" } ?? "--"),
            ("Est. cost", cost.map { $0.formatted(.currency(code: Locale.current.currency?.identifier ?? "USD")) } ?? "--")
        ]
    }

    private func warnings(_ ev: VehicleStatus.EVStatus) -> [String] {
        var warnings: [String] = []
        if ev.charging {
            if ev.chargeSpeed <= 0 {
                warnings.append("Charging may be paused. The car reports no power flowing.")
            } else if ev.plugType != .dcCharger, ev.chargeSpeed < 2 {
                warnings.append("Charging is slower than expected for an AC charger.")
            }
        }
        if let updated = vehicle.lastUpdated, Date().timeIntervalSince(updated) > 600 {
            warnings.append("This reading is more than 10 minutes old.")
        }
        return warnings
    }
}

// MARK: - Vehicle status

private struct MDStatusCard: View {
    let vehicle: BBVehicle
    @State private var appSettings = AppSettings.shared

    private var health: String {
        let openPanel = vehicle.doorOpen?.anyOpen == true || vehicle.hoodOpen == true || vehicle.trunkOpen == true
        if openPanel || vehicle.tirePressureWarning?.hasWarning == true { return "Check vehicle" }
        if vehicle.evStatus?.charging == true { return "Charging" }
        if vehicle.lockStatus == .locked, vehicle.doorOpen?.anyOpen != true { return "All secure" }
        return "Status ready"
    }

    private var items: [(label: String, value: String)] {
        var items: [(label: String, value: String)] = [
            ("Odometer", mdDistance(vehicle.odometer)),
            ("12V battery", vehicle.battery12V.map { "\($0)%" } ?? "--"),
            ("Doors", vehicle.doorOpen.map { $0.anyOpen ? $0.openDoorsDescription : "Closed" } ?? "Unknown"),
            ("Hood & trunk", hoodTrunk),
            ("Tires", vehicle.tirePressureWarning.map { $0.hasWarning ? $0.warningDescription : "OK" } ?? "OK"),
            ("Climate", climate)
        ]
        if vehicle.fuelType == .phev {
            items.append(("Fuel", vehicle.gasRange.map { "\(Int($0.percentage))%" } ?? "--"))
        }
        return items
    }

    private var hoodTrunk: String {
        switch (vehicle.hoodOpen, vehicle.trunkOpen) {
        case (true, true): "Both open"
        case (true, _): "Hood open"
        case (_, true): "Trunk open"
        case (nil, nil): "Unknown"
        default: "Closed"
        }
    }

    private var climate: String {
        guard let status = vehicle.climateStatus, status.airControlOn else { return "Off" }
        guard status.temperature.isPlausibleForDisplay else { return "On" }
        return status.temperature.units.format(status.temperature.value, to: appSettings.preferredTemperatureUnit)
    }

    var body: some View {
        MDSectionCard {
            MDCardHeader(
                title: "Vehicle status",
                subtitle: health + (vehicle.lastUpdated.map { " · \(mdRelative($0))" } ?? " · Not updated")
            )
            MDStatGrid(items: items)
        }
        .animation(.snappy, value: items.map(\.value))
    }
}

// MARK: - Location

private struct MDLocationCard: View {
    let vehicle: BBVehicle
    let onOpen: () -> Void
    @State private var appSettings = AppSettings.shared

    var body: some View {
        MDSectionCard {
            MDCardHeader(
                title: "Location",
                subtitle: vehicle.lastUpdated.map { "Parked · \(mdRelative($0))" } ?? "Parked"
            ) {
                Button(action: onOpen) {
                    Label("View map", systemImage: "map.fill")
                }
                .buttonStyle(MDButtonStyle(kind: .tonal))
                .fixedSize()
            }
            if appSettings.hideVehicleLocation {
                Text("Exact location hidden")
                    .font(MDType.bodyMedium)
                    .foregroundStyle(MDTokens.onSurfaceVariant)
            } else if let coordinate = vehicle.coordinate {
                Map(
                    initialPosition: .region(
                        MKCoordinateRegion(
                            center: coordinate,
                            span: MKCoordinateSpan(latitudeDelta: 0.006, longitudeDelta: 0.006)
                        )
                    )
                ) {
                    Annotation("", coordinate: coordinate) {
                        Image(systemName: "car.fill")
                            .font(.callout)
                            .foregroundStyle(MDTokens.onPrimary)
                            .padding(8)
                            .background(Circle().fill(MDTokens.primary))
                    }
                }
                .frame(height: 150)
                .allowsHitTesting(false)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .id("\(coordinate.latitude),\(coordinate.longitude)")
            }
        }
    }
}
