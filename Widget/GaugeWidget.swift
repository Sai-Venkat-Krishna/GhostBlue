//
//  GaugeWidget.swift
//  GhostBlueWidget
//
//  "Cockpit" widget: a speedometer-style state-of-charge dial with a
//  tick ring, giant percentage, and range — deliberately different from
//  the card-based GhostBlueWidget. Small is a pure dial; medium adds
//  status chips and quick controls.
//

import AppIntents
import BetterBlueKit
import SwiftUI
import WidgetKit

// MARK: - Cockpit dial

/// 270° tick-ring dial. Ticks below the current level glow with a
/// blue→cyan sweep; the rest stay dim. Center shows the percentage,
/// with the range readout beneath.
struct CockpitDial: View {
    /// 0...100; nil renders an empty dial with "--"
    let percentage: Double?
    var charging = false
    var isElectric = true
    var rangeText: String = ""

    private static let startAngle = 135.0 // degrees, 270° sweep
    private static let sweep = 270.0
    private static let tickCount = 45

    private var fraction: Double { min(max((percentage ?? 0) / 100.0, 0), 1) }

    private var accent: Color {
        guard let percentage else { return GhostBlue.dimWhite }
        if charging { return GhostBlue.cyan }
        if percentage <= 20 { return .red }
        if percentage <= 40 { return .orange }
        return GhostBlue.cyan
    }

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)

            ZStack {
                dialTicks(side: side)

                VStack(spacing: side * 0.005) {
                    Image(systemName: charging ? "bolt.fill" : (isElectric ? "bolt" : "fuelpump"))
                        .font(.system(size: side * 0.10, weight: .bold))
                        .foregroundColor(charging ? GhostBlue.cyan : GhostBlue.dimWhite)

                    HStack(alignment: .firstTextBaseline, spacing: 1) {
                        Text(percentage.map { "\(Int($0))" } ?? "--")
                            .font(.system(size: side * 0.30, weight: .heavy, design: .rounded))
                            .foregroundColor(.white)
                        Text("%")
                            .font(.system(size: side * 0.11, weight: .bold, design: .rounded))
                            .foregroundColor(GhostBlue.dimWhite)
                    }

                    if !rangeText.isEmpty {
                        Text(rangeText)
                            .font(.system(size: side * 0.10, weight: .semibold, design: .rounded))
                            .foregroundColor(GhostBlue.softWhite)
                    }
                }
                .frame(width: side * 0.62)
                .minimumScaleFactor(0.6)
                .lineLimit(1)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .aspectRatio(1, contentMode: .fit)
    }

    private func dialTicks(side: CGFloat) -> some View {
        Canvas { ctx, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let outer = min(size.width, size.height) / 2
            let litUpTo = fraction

            for tick in 0 ..< Self.tickCount {
                let tickFraction = Double(tick) / Double(Self.tickCount - 1)
                let angle = Angle.degrees(Self.startAngle + Self.sweep * tickFraction)
                let isMajor = tick % 11 == 0
                let length = outer * (isMajor ? 0.16 : 0.10)
                let lit = percentage != nil && tickFraction <= litUpTo

                let cosA = cos(angle.radians)
                let sinA = sin(angle.radians)
                var path = Path()
                path.move(to: CGPoint(
                    x: center.x + cosA * (outer - length),
                    y: center.y + sinA * (outer - length)
                ))
                path.addLine(to: CGPoint(
                    x: center.x + cosA * outer,
                    y: center.y + sinA * outer
                ))

                let color: Color
                if lit {
                    // Sweep from deep electric blue toward the accent
                    color = tickFraction < 0.5
                        ? GhostBlue.electric.opacity(0.85 + 0.15 * tickFraction)
                        : accent
                } else {
                    color = Color.white.opacity(0.14)
                }

                ctx.stroke(
                    path,
                    with: .color(color),
                    style: StrokeStyle(lineWidth: outer * (isMajor ? 0.032 : 0.020), lineCap: .round)
                )

                if lit, tickFraction > litUpTo - 0.05 {
                    // Glow on the leading edge ticks
                    ctx.stroke(
                        path,
                        with: .color(accent.opacity(0.55)),
                        style: StrokeStyle(lineWidth: outer * 0.055, lineCap: .round)
                    )
                }
            }
        }
    }
}

// MARK: - Status chip

private struct DialChip: View {
    let icon: String
    let text: String
    var tint: Color = GhostBlue.dimWhite

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 9, weight: .bold))
            Text(text)
                .font(.system(size: 10, weight: .semibold, design: .rounded))
                .lineLimit(1)
        }
        .foregroundColor(tint)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(
            Capsule()
                .fill(tint.opacity(0.14))
                .overlay(Capsule().stroke(tint.opacity(0.35), lineWidth: 0.6))
        )
    }
}

// MARK: - Small: pure dial

private struct GaugeSmallView: View {
    let vehicle: VehicleEntity

    var body: some View {
        Link(destination: URL(string: "ghostblue://vehicle/\(vehicle.vin)")!) {
            VStack(spacing: 2) {
                Text(vehicle.displayName)
                    .font(.system(size: 10, weight: .bold, design: .rounded))
                    .foregroundColor(GhostBlue.dimWhite)
                    .lineLimit(1)

                CockpitDial(
                    percentage: vehicle.batteryPercentage,
                    charging: vehicle.isCharging,
                    isElectric: vehicle.fuelType.hasElectricCapability,
                    rangeText: vehicle.rangeText
                )
            }
            .padding(10)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

// MARK: - Medium: dial + details + quick controls

private struct GaugeMediumView: View {
    let vehicle: VehicleEntity

    private var chargeTimeText: String? {
        let secs = vehicle.chargeRemainingSeconds
        guard vehicle.isCharging, secs > 60 else { return nil }
        let hours = secs / 3600
        let minutes = (secs % 3600) / 60
        let duration = hours > 0 ? "\(hours)h \(minutes)m" : "\(minutes) min"
        if let target = vehicle.targetSOC {
            return "\(duration) → \(Int(target))%"
        }
        return duration
    }

    var body: some View {
        HStack(spacing: 12) {
            CockpitDial(
                percentage: vehicle.batteryPercentage,
                charging: vehicle.isCharging,
                isElectric: vehicle.fuelType.hasElectricCapability,
                rangeText: vehicle.rangeText
            )

            VStack(alignment: .leading, spacing: 7) {
                Text(vehicle.displayName)
                    .font(.system(size: 16, weight: .heavy, design: .rounded))
                    .foregroundColor(.white)
                    .lineLimit(1)

                // Status chips
                HStack(spacing: 5) {
                    if let isLocked = vehicle.isLocked {
                        DialChip(
                            icon: isLocked ? "lock.fill" : "lock.open.fill",
                            text: isLocked ? "Locked" : "Unlocked",
                            tint: isLocked ? GhostBlue.unlockGreen : .orange
                        )
                    }

                    if vehicle.isCharging {
                        DialChip(
                            icon: "bolt.fill",
                            text: vehicle.chargeSpeedKW > 0
                                ? String(format: "%.0f kW", vehicle.chargeSpeedKW)
                                : "Charging",
                            tint: GhostBlue.cyan
                        )
                    } else if vehicle.isPluggedIn {
                        DialChip(icon: "powerplug.fill", text: "Charged", tint: GhostBlue.unlockGreen)
                    }
                }

                if let chargeTimeText {
                    HStack(spacing: 3) {
                        Image(systemName: "clock")
                            .font(.system(size: 9))
                        Text(chargeTimeText)
                            .font(.system(size: 10, weight: .medium, design: .rounded))
                    }
                    .foregroundColor(GhostBlue.dimWhite)
                }

                Spacer(minLength: 0)

                // Quick controls
                HStack(spacing: 5) {
                    if vehicle.isLocked == true {
                        Button(intent: {
                            let intent = UnlockVehicleControlIntent()
                            intent.vehicle = vehicle
                            return intent
                        }()) {
                            Label("Unlock", systemImage: "lock.open.fill")
                        }
                        .buttonStyle(GhostWidgetButtonStyle(color: GhostBlue.unlockGreen))
                    } else {
                        Button(intent: {
                            let intent = LockVehicleControlIntent()
                            intent.vehicle = vehicle
                            return intent
                        }()) {
                            Label("Lock", systemImage: "lock.fill")
                        }
                        .buttonStyle(GhostWidgetButtonStyle(color: GhostBlue.lockRed))
                    }

                    if vehicle.isClimateOn {
                        Button(intent: {
                            let intent = StopClimateControlIntent()
                            intent.vehicle = vehicle
                            return intent
                        }()) {
                            Label("Stop Climate", systemImage: "fan.slash")
                        }
                        .buttonStyle(GhostWidgetButtonStyle(color: GhostBlue.climateGray))
                    } else {
                        Button(intent: StartVehicleClimateIntent(vehicle: vehicle)) {
                            Label("Start Climate", systemImage: "fan")
                        }
                        .buttonStyle(GhostWidgetButtonStyle(color: GhostBlue.electric))
                    }
                }
                .frame(height: 32)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Large: dial hero + chips + full controls

private struct GaugeLargeView: View {
    let vehicle: VehicleEntity

    private var chargeTimeText: String? {
        let secs = vehicle.chargeRemainingSeconds
        guard vehicle.isCharging, secs > 60 else { return nil }
        let hours = secs / 3600
        let minutes = (secs % 3600) / 60
        return hours > 0 ? "\(hours)h \(minutes)m left" : "\(minutes) min left"
    }

    private var finishText: String? {
        guard vehicle.isCharging,
              let finish = chargeFinishDate(
                  remainingSeconds: vehicle.chargeRemainingSeconds,
                  reportedAt: vehicle.timestamp
              ) else { return nil }
        return formatChargeFinishTime(finish)
    }

    var body: some View {
        VStack(spacing: 10) {
            // Header
            HStack(alignment: .firstTextBaseline) {
                Text(vehicle.displayName)
                    .font(.system(size: 18, weight: .heavy, design: .rounded))
                    .foregroundColor(.white)
                    .lineLimit(1)

                Spacer(minLength: 6)

                Text("Updated \(vehicle.timestamp, format: .dateTime.hour().minute())")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(GhostBlue.dimWhite)

                Button(intent: {
                    var intent = RefreshVehicleStatusIntent()
                    intent.vehicle = vehicle
                    return intent
                }()) {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(GhostBlue.dimWhite)
                        .padding(5)
                        .background(Circle().fill(Color.white.opacity(0.10)))
                }
                .buttonStyle(.plain)
            }

            // Dial + status chips side by side
            HStack(spacing: 12) {
                CockpitDial(
                    percentage: vehicle.batteryPercentage,
                    charging: vehicle.isCharging,
                    isElectric: vehicle.fuelType.hasElectricCapability,
                    rangeText: vehicle.rangeText
                )
                .frame(maxWidth: .infinity)

                VStack(alignment: .leading, spacing: 6) {
                    if let isLocked = vehicle.isLocked {
                        DialChip(
                            icon: isLocked ? "lock.fill" : "lock.open.fill",
                            text: isLocked ? "Locked" : "Unlocked",
                            tint: isLocked ? GhostBlue.unlockGreen : .orange
                        )
                    }

                    if vehicle.isCharging {
                        DialChip(
                            icon: "bolt.fill",
                            text: vehicle.chargeSpeedKW > 0
                                ? String(format: "%.0f kW", vehicle.chargeSpeedKW)
                                : "Charging",
                            tint: GhostBlue.cyan
                        )
                        if let chargeTimeText {
                            DialChip(icon: "clock", text: chargeTimeText, tint: GhostBlue.dimWhite)
                        }
                        if let finishText {
                            DialChip(icon: "bolt.badge.checkmark.fill", text: "Full \(finishText)", tint: GhostBlue.cyan)
                        }
                        if let target = vehicle.targetSOC {
                            DialChip(icon: "flag.checkered", text: "Target \(Int(target))%", tint: GhostBlue.dimWhite)
                        }
                    } else if vehicle.isPluggedIn {
                        DialChip(icon: "powerplug.fill", text: "Charged", tint: GhostBlue.unlockGreen)
                    }

                    if !vehicle.odometer.isEmpty {
                        DialChip(icon: "gauge.with.needle", text: vehicle.odometer, tint: GhostBlue.dimWhite)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: .infinity)

            // Car render woven between the dial and the controls
            CarImageView(vehicle: vehicle, size: 58)

            // State-aware actions: one lock action and one climate action.
            HStack(
                spacing: 6
            ) {
                if vehicle.isLocked == true {
                    Button(intent: {
                        let intent = UnlockVehicleControlIntent(); intent.vehicle = vehicle; return intent
                    }()) { Label("Unlock", systemImage: "lock.open.fill") }
                        .buttonStyle(GhostWidgetButtonStyle(color: GhostBlue.unlockGreen))
                } else {
                    Button(intent: {
                        let intent = LockVehicleControlIntent(); intent.vehicle = vehicle; return intent
                    }()) { Label("Lock", systemImage: "lock.fill") }
                        .buttonStyle(GhostWidgetButtonStyle(color: GhostBlue.lockRed))
                }

                if vehicle.isClimateOn {
                    Button(intent: {
                        let intent = StopClimateControlIntent(); intent.vehicle = vehicle; return intent
                    }()) { Label("Stop Climate", systemImage: "fan.slash") }
                        .buttonStyle(GhostWidgetButtonStyle(color: GhostBlue.climateGray))
                } else {
                    Button(intent: StartVehicleClimateIntent(vehicle: vehicle)) {
                        Label("Start Climate", systemImage: "fan")
                    }
                    .buttonStyle(GhostWidgetButtonStyle(color: GhostBlue.electric))
                }
            }
            .frame(height: 76)
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Entry view

struct GaugeWidgetEntryView: View {
    let entry: VehicleWidgetEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Group {
            if let vehicle = entry.vehicle {
                switch family {
                case .systemSmall:
                    GaugeSmallView(vehicle: vehicle)
                case .systemLarge:
                    GaugeLargeView(vehicle: vehicle)
                default:
                    GaugeMediumView(vehicle: vehicle)
                }
            } else {
                VStack(spacing: 6) {
                    Image(systemName: "gauge.with.needle")
                        .font(.largeTitle)
                        .foregroundColor(GhostBlue.dimWhite)
                    Text("No Vehicle")
                        .font(.headline)
                        .foregroundColor(GhostBlue.softWhite)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .containerBackground(for: .widget) {
            ZStack {
                LinearGradient(
                    colors: [GhostBlue.midnight, Color.black],
                    startPoint: .top,
                    endPoint: .bottom
                )
                RadialGradient(
                    colors: [GhostBlue.glow.opacity(0.16), .clear],
                    center: .center,
                    startRadius: 0,
                    endRadius: 160
                )
            }
        }
    }
}

// MARK: - Widget

struct GhostBlueGaugeWidget: Widget {
    let kind: String = "GhostBlueGaugeWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: kind,
            intent: VehicleWidgetIntent.self,
            provider: VehicleTimelineProvider(),
        ) { entry in
            GaugeWidgetEntryView(entry: entry)
        }
        .contentMarginsDisabled()
        .configurationDisplayName("Charge Dial")
        .description("Speedometer-style dial with state of charge, range, live charging chips, and quick controls.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

// MARK: - Previews

#Preview("Dial – Small", as: .systemSmall) {
    GhostBlueGaugeWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: {
        var vehicle = VehicleEntity(
            id: .init(), displayName: "IONIQ 5", vin: "test",
            fuelType: .electric, rangeText: "214 mi", batteryPercentage: 72.0,
            timestamp: Date(), backgroundColorName: "ghostBlue"
        )
        vehicle.isCharging = true
        vehicle.chargeSpeedKW = 49.5
        return vehicle
    }(), configuration: VehicleWidgetIntent())
}

#Preview("Dial – Medium", as: .systemMedium) {
    GhostBlueGaugeWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: {
        var vehicle = VehicleEntity(
            id: .init(), displayName: "2025 IONIQ 5", vin: "test",
            fuelType: .electric, rangeText: "214 mi", batteryPercentage: 72.0,
            timestamp: Date(), backgroundColorName: "ghostBlue"
        )
        vehicle.isCharging = true
        vehicle.isPluggedIn = true
        vehicle.chargeSpeedKW = 49.5
        vehicle.chargeRemainingSeconds = 4980
        vehicle.targetSOC = 90
        vehicle.isLocked = true
        return vehicle
    }(), configuration: VehicleWidgetIntent())
}

#Preview("Dial – Large", as: .systemLarge) {
    GhostBlueGaugeWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: {
        var vehicle = VehicleEntity(
            id: .init(), displayName: "2025 IONIQ 5", vin: "test",
            fuelType: .electric, rangeText: "214 mi", batteryPercentage: 72.0,
            timestamp: Date(), backgroundColorName: "ghostBlue",
            odometer: "12,483 mi"
        )
        vehicle.isCharging = true
        vehicle.isPluggedIn = true
        vehicle.chargeSpeedKW = 49.5
        vehicle.chargeRemainingSeconds = 4980
        vehicle.targetSOC = 90
        vehicle.isLocked = true
        return vehicle
    }(), configuration: VehicleWidgetIntent())
}

#Preview("Dial – Low battery", as: .systemSmall) {
    GhostBlueGaugeWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: VehicleEntity(
        id: .init(), displayName: "EV6", vin: "test",
        fuelType: .electric, rangeText: "31 mi", batteryPercentage: 12.0,
        timestamp: Date(), backgroundColorName: "ghostBlue"
    ), configuration: VehicleWidgetIntent())
}

#Preview("Dial – Parked / Climate On", as: .systemMedium) {
    GhostBlueGaugeWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: {
        var vehicle = VehicleEntity(
            id: .init(), displayName: "IONIQ 5", vin: "test",
            fuelType: .electric, rangeText: "242 mi", batteryPercentage: 81,
            timestamp: .now, backgroundColorName: "ghostBlue"
        )
        vehicle.isLocked = false
        vehicle.isClimateOn = true
        return vehicle
    }(), configuration: VehicleWidgetIntent())
}

#Preview("Dial – No Vehicle", as: .systemMedium) {
    GhostBlueGaugeWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: nil, configuration: VehicleWidgetIntent())
}
