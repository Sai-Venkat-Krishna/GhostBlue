//
//  RivianWidgets.swift
//  GhostBlueWidget
//
//  Home-screen widget layouts used when the app's Rivian theme is on:
//  adaptive light/dark surfaces, a big state-of-charge figure, a slim green
//  battery bar, and circular command buttons (solid ink marks the current
//  state, like the app's Rivian dashboard). Every widget keeps its own
//  identity — Command Center, Charge Dial, Hero Card and Woven Wordmark —
//  and every control it had. Classic themes never reach this file.
//

import AppIntents
import BetterBlueKit
import SwiftUI
import UIKit
import WidgetKit

// MARK: - Shared pieces

private func vehicleURL(_ vehicle: VehicleEntity) -> URL? {
    URL(string: "ghostblue://vehicle/\(vehicle.vin)")
}

/// One-line state summary, e.g. "Charging · 11 kW" or "Locked".
private func rivianStatusLine(_ vehicle: VehicleEntity) -> String {
    if vehicle.isCharging {
        return vehicle.chargeSpeedKW > 0
            ? String(format: "Charging · %.0f kW", vehicle.chargeSpeedKW)
            : "Charging"
    }
    if vehicle.isChargeComplete { return "Charge complete" }
    if vehicle.isPluggedIn { return "Plugged in" }
    if vehicle.isClimateOn { return "Climate on" }
    switch vehicle.isLocked {
    case true: return "Locked"
    case false: return "Unlocked"
    default: return "Parked"
    }
}

private func rivianStatusTint(_ vehicle: VehicleEntity) -> Color {
    if vehicle.isCharging || vehicle.isChargeComplete { return RVTokens.energy }
    if vehicle.isClimateOn { return RVTokens.climate }
    return RVTokens.inkSecondary
}

private func timeLeftText(_ vehicle: VehicleEntity) -> String? {
    let secs = vehicle.chargeRemainingSeconds
    guard vehicle.isCharging, secs > 60 else { return nil }
    let hours = secs / 3600
    let minutes = (secs % 3600) / 60
    return hours > 0 ? "\(hours)h \(minutes)m left" : "\(minutes) min left"
}

private func finishText(_ vehicle: VehicleEntity) -> String? {
    guard vehicle.isCharging,
          let finish = chargeFinishDate(
              remainingSeconds: vehicle.chargeRemainingSeconds,
              reportedAt: vehicle.timestamp
          ) else { return nil }
    return formatChargeFinishTime(finish)
}

/// Vehicle render without the classic widgets' blue glow.
private struct RivianCarImage: View {
    let vehicle: VehicleEntity
    let height: CGFloat

    private var image: UIImage? {
        if let path = vehicle.cachedImagePath, let image = UIImage(contentsOfFile: path) {
            return image
        }
        return UIImage(named: vehicle.carImageKey)
    }

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
            } else {
                EVCarSilhouette()
            }
        }
        .frame(height: height)
        .accessibilityHidden(true)
    }
}

/// "86%" in large rounded figures with the range beside it.
private struct RivianMetric: View {
    let vehicle: VehicleEntity
    var size: CGFloat = 34
    var showsRange = true

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 1) {
                Text(vehicle.batteryPercentage.map { "\(Int($0))" } ?? "--")
                    .font(.system(size: size, weight: .bold, design: .rounded))
                    .foregroundStyle(RVTokens.ink)
                Text("%")
                    .font(.system(size: size * 0.45, weight: .semibold, design: .rounded))
                    .foregroundStyle(RVTokens.inkSecondary)
            }
            .widgetAccentable()

            if showsRange {
                Text(vehicle.rangeText)
                    .font(.system(size: max(12, size * 0.4), weight: .semibold, design: .rounded))
                    .foregroundStyle(RVTokens.inkSecondary)
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.7)
    }
}

/// Slim battery bar; while charging, a tick marks the target.
private struct RivianBatteryBar: View {
    let percentage: Double?
    var target: Double?
    var height: CGFloat = 6

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let fraction = min(max((percentage ?? 0) / 100, 0), 1)
            ZStack(alignment: .leading) {
                Capsule().fill(RVTokens.surfaceMuted)
                Capsule()
                    .fill(RVTokens.battery(percentage))
                    .frame(width: max(height, width * fraction))
                    .widgetAccentable()
                if let target, target > 0, target < 100 {
                    Capsule()
                        .fill(RVTokens.ink.opacity(0.55))
                        .frame(width: 2, height: height + 4)
                        .offset(x: width * target / 100 - 1)
                }
            }
        }
        .frame(height: height)
    }
}

private struct RivianChip: View {
    let systemImage: String
    let text: String
    var tint: Color = RVTokens.inkSecondary
    var iconOnly = false

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: systemImage)
                .font(.system(size: 9, weight: .bold))
            if !iconOnly {
                Text(text)
                    .font(.system(size: 10, weight: .semibold, design: .rounded))
                    .lineLimit(1)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(text)
        .foregroundStyle(tint)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Capsule().fill(tint.opacity(0.14)))
    }
}

/// Circular command button; solid ink when `active`.
private struct RivianCircleButton<Intent: AppIntent>: View {
    let intent: Intent
    let systemImage: String
    let title: String
    var showsTitle = false
    var active = false
    var diameter: CGFloat = 40

    var body: some View {
        Button(intent: intent) {
            VStack(spacing: 4) {
                Image(systemName: systemImage)
                    .font(.system(size: diameter * 0.38, weight: .semibold))
                    .foregroundStyle(active ? RVTokens.commandActiveIcon : RVTokens.commandInactiveIcon)
                    .frame(width: diameter, height: diameter)
                    .background(Circle().fill(active ? RVTokens.commandActiveFill : RVTokens.commandInactiveFill))
                    .widgetAccentable(active)
                if showsTitle {
                    Text(title)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(RVTokens.inkSecondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}

/// Lock and Unlock (the current state filled), climate start/stop, and
/// optionally refresh — the same actions the classic widgets offer.
private struct RivianCommandRow: View {
    let vehicle: VehicleEntity
    var showsLabels = false
    var diameter: CGFloat = 40
    var includesRefresh = false

    private var lockIntent: LockVehicleControlIntent {
        let intent = LockVehicleControlIntent()
        intent.vehicle = vehicle
        return intent
    }

    private var unlockIntent: UnlockVehicleControlIntent {
        let intent = UnlockVehicleControlIntent()
        intent.vehicle = vehicle
        return intent
    }

    private var stopClimateIntent: StopClimateControlIntent {
        let intent = StopClimateControlIntent()
        intent.vehicle = vehicle
        return intent
    }

    private var refreshIntent: RefreshVehicleStatusIntent {
        let intent = RefreshVehicleStatusIntent()
        intent.vehicle = vehicle
        return intent
    }

    var body: some View {
        HStack(spacing: 0) {
            RivianCircleButton(
                intent: lockIntent, systemImage: "lock.fill",
                title: "Lock", showsTitle: showsLabels,
                active: vehicle.isLocked == true, diameter: diameter
            )
            .frame(maxWidth: .infinity)

            RivianCircleButton(
                intent: unlockIntent, systemImage: "lock.open.fill",
                title: "Unlock", showsTitle: showsLabels,
                active: vehicle.isLocked == false, diameter: diameter
            )
            .frame(maxWidth: .infinity)

            Group {
                if vehicle.isClimateOn {
                    RivianCircleButton(
                        intent: stopClimateIntent, systemImage: "fan.fill",
                        title: "Stop Climate", showsTitle: showsLabels,
                        active: true, diameter: diameter
                    )
                } else {
                    RivianCircleButton(
                        intent: StartVehicleClimateIntent(vehicle: vehicle), systemImage: "fan",
                        title: "Climate", showsTitle: showsLabels,
                        diameter: diameter
                    )
                }
            }
            .frame(maxWidth: .infinity)

            if includesRefresh {
                RivianCircleButton(
                    intent: refreshIntent, systemImage: "arrow.clockwise",
                    title: "Refresh", showsTitle: showsLabels,
                    diameter: diameter
                )
                .frame(maxWidth: .infinity)
            }
        }
    }
}

/// Green-tinted charging summary row (also shows "Charge complete").
private struct RivianChargingRow: View {
    let vehicle: VehicleEntity

    private var title: String {
        if vehicle.isChargeComplete { return "Charge complete" }
        guard vehicle.isCharging else { return vehicle.chargingState.title }
        var title = "Charging"
        if vehicle.chargeSpeedKW > 0 { title += String(format: " · %.1f kW", vehicle.chargeSpeedKW) }
        if let plug = vehicle.plugTypeLabel { title += " · \(plug)" }
        return title
    }

    private var detail: String? {
        var parts: [String] = []
        if let left = timeLeftText(vehicle) { parts.append(left) }
        if let finish = finishText(vehicle) { parts.append("Full by \(finish)") }
        if let target = vehicle.targetSOC { parts.append("Target \(Int(target))%") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: vehicle.isChargeComplete ? "checkmark" : "bolt.fill")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(Circle().fill(RVTokens.energy))
                .widgetAccentable()

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(RVTokens.ink)
                if let detail {
                    Text(detail)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(RVTokens.inkSecondary)
                }
            }
            .lineLimit(1)
            .minimumScaleFactor(0.75)

            Spacer(minLength: 0)
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(RVTokens.energy.opacity(0.12))
        )
    }
}

private struct RivianHeader: View {
    let vehicle: VehicleEntity
    var nameSize: CGFloat = 20

    private var refreshIntent: RefreshVehicleStatusIntent {
        let intent = RefreshVehicleStatusIntent()
        intent.vehicle = vehicle
        return intent
    }

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(vehicle.displayName)
                    .font(.system(size: nameSize, weight: .bold, design: .rounded))
                    .foregroundStyle(RVTokens.ink)
                    .lineLimit(1)
                Text("\(rivianStatusLine(vehicle)) · \(vehicle.timestamp, format: .dateTime.hour().minute())")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(rivianStatusTint(vehicle))
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            Button(intent: refreshIntent) {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(RVTokens.commandInactiveIcon)
                    .frame(width: 30, height: 30)
                    .background(Circle().fill(RVTokens.commandInactiveFill))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Refresh")
        }
    }
}

private struct RivianEmptyWidget: View {
    var systemImage = "car.fill"

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: systemImage)
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(RVTokens.inkSecondary)
            Text("No Vehicle")
                .font(.system(size: 15, weight: .semibold, design: .rounded))
                .foregroundStyle(RVTokens.ink)
            Text("Open GhostBlue to add an account")
                .font(.system(size: 11))
                .foregroundStyle(RVTokens.inkSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private extension View {
    func rivianWidgetBackground() -> some View {
        containerBackground(for: .widget) { RVTokens.surface }
    }
}

// MARK: - Command Center

struct RivianCommandCenterView: View {
    let entry: VehicleWidgetEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Group {
            if let vehicle = entry.vehicle {
                switch family {
                case .systemSmall: small(vehicle)
                case .systemLarge: large(vehicle)
                default: medium(vehicle)
                }
            } else {
                RivianEmptyWidget()
            }
        }
        .rivianWidgetBackground()
    }

    private func small(_ vehicle: VehicleEntity) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 4) {
                Text(vehicle.displayName)
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .foregroundStyle(RVTokens.ink)
                    .lineLimit(1)
                Spacer(minLength: 2)
                if let locked = vehicle.isLocked {
                    Image(systemName: locked ? "lock.fill" : "lock.open.fill")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(locked ? RVTokens.inkSecondary : RVTokens.service)
                }
            }
            Text(rivianStatusLine(vehicle))
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(rivianStatusTint(vehicle))
                .lineLimit(1)

            Spacer(minLength: 2)
            RivianCarImage(vehicle: vehicle, height: 44)
                .frame(maxWidth: .infinity)
            Spacer(minLength: 2)

            RivianMetric(vehicle: vehicle, size: 28)
            RivianBatteryBar(
                percentage: vehicle.batteryPercentage,
                target: vehicle.isCharging ? vehicle.targetSOC : nil,
                height: 5
            )
            .padding(.top, 6)
        }
        .padding(14)
        .widgetURL(vehicleURL(vehicle))
    }

    private func medium(_ vehicle: VehicleEntity) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 0) {
                Text(vehicle.displayName)
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                    .foregroundStyle(RVTokens.ink)
                    .lineLimit(1)
                Text(rivianStatusLine(vehicle))
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(rivianStatusTint(vehicle))
                    .lineLimit(1)
                Spacer(minLength: 4)
                RivianMetric(vehicle: vehicle, size: 30)
                RivianBatteryBar(
                    percentage: vehicle.batteryPercentage,
                    target: vehicle.isCharging ? vehicle.targetSOC : nil
                )
                .padding(.top, 6)
                if let left = timeLeftText(vehicle) {
                    Text(left)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(RVTokens.inkSecondary)
                        .padding(.top, 4)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(spacing: 8) {
                Link(destination: vehicleURL(vehicle)!) {
                    RivianCarImage(vehicle: vehicle, height: 54)
                }
                RivianCommandRow(vehicle: vehicle, diameter: 38)
            }
            .frame(width: 150)
        }
        .padding(14)
    }

    private func large(_ vehicle: VehicleEntity) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            RivianHeader(vehicle: vehicle)

            Link(destination: vehicleURL(vehicle)!) {
                ZStack {
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .fill(RVTokens.surfaceMuted)
                    RivianCarImage(vehicle: vehicle, height: 84)
                        .padding(.horizontal, 12)
                }
            }
            .frame(maxHeight: .infinity)

            VStack(alignment: .leading, spacing: 6) {
                RivianMetric(vehicle: vehicle, size: 36)
                RivianBatteryBar(
                    percentage: vehicle.batteryPercentage,
                    target: vehicle.isCharging ? vehicle.targetSOC : nil,
                    height: 7
                )
            }

            if vehicle.isCharging || vehicle.isPluggedIn {
                RivianChargingRow(vehicle: vehicle)
            }

            RivianCommandRow(vehicle: vehicle, showsLabels: true, diameter: 44, includesRefresh: true)
        }
        .padding(16)
    }
}

// MARK: - Charge Dial

/// 270° arc gauge: muted track, green (red when low) fill, figure in the middle.
private struct RivianDial: View {
    let vehicle: VehicleEntity
    var showsRange = true

    private var fraction: Double { min(max((vehicle.batteryPercentage ?? 0) / 100, 0), 1) }

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            let line = side * 0.085
            ZStack {
                Circle()
                    .trim(from: 0, to: 0.75)
                    .stroke(RVTokens.surfaceMuted, style: StrokeStyle(lineWidth: line, lineCap: .round))
                    .rotationEffect(.degrees(135))
                Circle()
                    .trim(from: 0, to: 0.75 * fraction)
                    .stroke(
                        RVTokens.battery(vehicle.batteryPercentage),
                        style: StrokeStyle(lineWidth: line, lineCap: .round)
                    )
                    .rotationEffect(.degrees(135))
                    .widgetAccentable()

                VStack(spacing: side * 0.01) {
                    Image(systemName: vehicle.isCharging
                        ? "bolt.fill"
                        : (vehicle.fuelType.hasElectricCapability ? "bolt" : "fuelpump"))
                        .font(.system(size: side * 0.09, weight: .bold))
                        .foregroundStyle(vehicle.isCharging ? RVTokens.energy : RVTokens.inkSecondary)
                    HStack(alignment: .firstTextBaseline, spacing: 1) {
                        Text(vehicle.batteryPercentage.map { "\(Int($0))" } ?? "--")
                            .font(.system(size: side * 0.27, weight: .bold, design: .rounded))
                            .foregroundStyle(RVTokens.ink)
                        Text("%")
                            .font(.system(size: side * 0.11, weight: .semibold, design: .rounded))
                            .foregroundStyle(RVTokens.inkSecondary)
                    }
                    if showsRange {
                        Text(vehicle.rangeText)
                            .font(.system(size: side * 0.09, weight: .semibold, design: .rounded))
                            .foregroundStyle(RVTokens.inkSecondary)
                    }
                }
                .frame(width: side * 0.66)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            }
            .padding(line / 2)
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .aspectRatio(1, contentMode: .fit)
    }
}

private struct RivianDialChips: View {
    let vehicle: VehicleEntity
    var detailed = false
    /// Icon-only lock/climate/plug chips (the charge-speed chip keeps its text).
    var compact = false

    var body: some View {
        if let locked = vehicle.isLocked {
            RivianChip(
                systemImage: locked ? "lock.fill" : "lock.open.fill",
                text: locked ? "Locked" : "Unlocked",
                tint: locked ? RVTokens.inkSecondary : RVTokens.service,
                iconOnly: compact
            )
        }
        if vehicle.isCharging {
            RivianChip(
                systemImage: "bolt.fill",
                text: vehicle.chargeSpeedKW > 0
                    ? String(format: "%.0f kW", vehicle.chargeSpeedKW)
                    : "Charging",
                tint: RVTokens.energy
            )
            if detailed {
                if let left = timeLeftText(vehicle) {
                    RivianChip(systemImage: "clock", text: left)
                }
                if let finish = finishText(vehicle) {
                    RivianChip(systemImage: "bolt.badge.checkmark.fill", text: "Full \(finish)", tint: RVTokens.energy)
                }
                if let target = vehicle.targetSOC {
                    RivianChip(systemImage: "flag.checkered", text: "Target \(Int(target))%")
                }
            }
        } else if vehicle.isPluggedIn {
            RivianChip(systemImage: "powerplug.fill", text: "Charged", tint: RVTokens.energy, iconOnly: compact)
        }
        if vehicle.isClimateOn {
            RivianChip(systemImage: "fan.fill", text: "Climate on", tint: RVTokens.climate, iconOnly: compact)
        }
        if detailed, !vehicle.odometer.isEmpty {
            RivianChip(systemImage: "gauge.with.needle", text: vehicle.odometer)
        }
    }
}

struct RivianGaugeView: View {
    let entry: VehicleWidgetEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Group {
            if let vehicle = entry.vehicle {
                switch family {
                case .systemSmall: small(vehicle)
                case .systemLarge: large(vehicle)
                default: medium(vehicle)
                }
            } else {
                RivianEmptyWidget(systemImage: "gauge.with.needle")
            }
        }
        .rivianWidgetBackground()
    }

    private func small(_ vehicle: VehicleEntity) -> some View {
        VStack(spacing: 4) {
            Text(vehicle.displayName)
                .font(.system(size: 11, weight: .semibold, design: .rounded))
                .foregroundStyle(RVTokens.inkSecondary)
                .lineLimit(1)
            RivianDial(vehicle: vehicle)
        }
        .padding(12)
        .widgetURL(vehicleURL(vehicle))
    }

    private func medium(_ vehicle: VehicleEntity) -> some View {
        HStack(spacing: 12) {
            RivianDial(vehicle: vehicle)

            VStack(alignment: .leading, spacing: 6) {
                Text(vehicle.displayName)
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                    .foregroundStyle(RVTokens.ink)
                    .lineLimit(1)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 5) { RivianDialChips(vehicle: vehicle) }
                    HStack(spacing: 5) { RivianDialChips(vehicle: vehicle, compact: true) }
                }
                if let left = timeLeftText(vehicle) {
                    Text(left)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(RVTokens.inkSecondary)
                }
                Spacer(minLength: 0)
                RivianCommandRow(vehicle: vehicle, diameter: 36)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
    }

    private func large(_ vehicle: VehicleEntity) -> some View {
        VStack(spacing: 12) {
            RivianHeader(vehicle: vehicle, nameSize: 18)

            HStack(spacing: 12) {
                RivianDial(vehicle: vehicle)
                    .frame(maxWidth: .infinity)
                VStack(alignment: .leading, spacing: 6) {
                    RivianDialChips(vehicle: vehicle, detailed: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: .infinity)

            Link(destination: vehicleURL(vehicle)!) {
                RivianCarImage(vehicle: vehicle, height: 52)
            }

            RivianCommandRow(vehicle: vehicle, showsLabels: true, diameter: 44)
        }
        .padding(16)
    }
}

// MARK: - Hero Card and Woven Wordmark

/// "2025 IONIQ 5" → "IONIQ 5"
private func rivianWordmark(_ vehicle: VehicleEntity) -> String {
    let stripped = vehicle.model
        .replacingOccurrences(of: #"^\d{4}\s+"#, with: "", options: .regularExpression)
        .trimmingCharacters(in: .whitespaces)
    return (stripped.isEmpty ? vehicle.displayName : stripped).uppercased()
}

/// Model lettering; the woven variant fills the letters with the charge level.
private struct RivianWordmark: View {
    let vehicle: VehicleEntity
    let woven: Bool

    private var lettering: some View {
        Text(rivianWordmark(vehicle))
            .font(.system(size: woven ? 38 : 30, weight: woven ? .black : .heavy, design: .rounded))
            .tracking(woven ? 4 : 3)
            .lineLimit(1)
            .minimumScaleFactor(0.5)
    }

    var body: some View {
        if woven {
            let fraction = min(max((vehicle.batteryPercentage ?? 0) / 100, 0), 1)
            ZStack {
                lettering.foregroundStyle(RVTokens.ink.opacity(0.12))
                lettering
                    .foregroundStyle(RVTokens.battery(vehicle.batteryPercentage))
                    .mask {
                        GeometryReader { geo in
                            Rectangle().frame(width: geo.size.width * fraction)
                        }
                    }
                    .widgetAccentable()
            }
        } else {
            lettering.foregroundStyle(RVTokens.ink)
        }
    }
}

struct RivianSpecView: View {
    let entry: VehicleWidgetEntry
    let woven: Bool

    var body: some View {
        Group {
            if let vehicle = entry.vehicle {
                layout(vehicle)
            } else {
                RivianEmptyWidget()
            }
        }
        .rivianWidgetBackground()
    }

    private func layout(_ vehicle: VehicleEntity) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            RivianHeader(vehicle: vehicle, nameSize: 15)

            Link(destination: vehicleURL(vehicle)!) {
                ZStack {
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .fill(RVTokens.surfaceMuted)
                    VStack(spacing: 2) {
                        RivianWordmark(vehicle: vehicle, woven: woven)
                            .padding(.horizontal, 14)
                        RivianCarImage(vehicle: vehicle, height: woven ? 58 : 66)
                    }
                    .padding(.vertical, 10)
                }
            }
            .frame(maxHeight: .infinity)

            VStack(alignment: .leading, spacing: 6) {
                RivianMetric(vehicle: vehicle, size: 32)
                RivianBatteryBar(
                    percentage: vehicle.batteryPercentage,
                    target: vehicle.isCharging ? vehicle.targetSOC : nil
                )
            }

            if vehicle.isCharging || vehicle.isPluggedIn {
                RivianChargingRow(vehicle: vehicle)
            }

            lockButtons(vehicle)
        }
        .padding(16)
    }

    /// The spec widgets' Lock / Unlock pair, as Rivian pills: the action
    /// that changes the current state is filled gold.
    private func lockButtons(_ vehicle: VehicleEntity) -> some View {
        let lock = LockVehicleControlIntent()
        lock.vehicle = vehicle
        let unlock = UnlockVehicleControlIntent()
        unlock.vehicle = vehicle
        let unlockedNow = vehicle.isLocked == false

        return HStack(spacing: 8) {
            Button(intent: lock) {
                pill("Lock", systemImage: "lock.fill", prominent: unlockedNow || vehicle.isLocked == nil)
            }
            .buttonStyle(.plain)
            Button(intent: unlock) {
                pill("Unlock", systemImage: "lock.open.fill", prominent: vehicle.isLocked == true)
            }
            .buttonStyle(.plain)
        }
    }

    private func pill(_ title: String, systemImage: String, prominent: Bool) -> some View {
        HStack(spacing: 6) {
            Image(systemName: systemImage)
            Text(title)
        }
        .font(.system(size: 13, weight: .semibold))
        .foregroundStyle(prominent ? Color.black : RVTokens.ink)
        .frame(maxWidth: .infinity)
        .frame(height: 40)
        .background(
            Capsule().fill(prominent ? RVTokens.accent : RVTokens.commandInactiveFill)
        )
        .widgetAccentable(prominent)
    }
}
