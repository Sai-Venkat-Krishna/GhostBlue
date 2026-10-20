//
//  SpecLargeWidgets.swift
//  GhostBlueWidget
//
//  Two additional large-only home-screen widgets implementing the
//  "iOS Widget Design Specs" (IONIQ 5 Widget):
//    - GhostBlueHeroWidget  → frame 1a "Consistent wordmark"
//    - GhostBlueWovenWidget → frame 1b "Original woven lettering"
//  Both share the dark neutral surface, range/battery row, charging
//  module with wave bar + finish time, parked status row, and
//  Lock/Unlock actions. They reuse VehicleWidgetIntent (vehicle, car
//  colour, accent colour) and VehicleTimelineProvider.
//

import AppIntents
import BetterBlueKit
import SwiftUI
import WidgetKit

// MARK: - Spec palette

private enum SpecInk {
    // Card surface — radial #1d2226 → #121417 → #0b0c0f
    static let bgTop    = Color(red: 0.114, green: 0.133, blue: 0.149)
    static let bgMid    = Color(red: 0.071, green: 0.078, blue: 0.090)
    static let bgBottom = Color(red: 0.043, green: 0.047, blue: 0.059)
    // Ink used on accent-filled buttons (#08110f)
    static let buttonInk = Color(red: 0.031, green: 0.067, blue: 0.059)
}

// MARK: - Wordmark helper

/// "2025 IONIQ 5" → "IONIQ 5"
private func specWordmark(for vehicle: VehicleEntity) -> String {
    let stripped = vehicle.model
        .replacingOccurrences(of: #"^\d{4}\s+"#, with: "", options: .regularExpression)
        .trimmingCharacters(in: .whitespaces)
    return (stripped.isEmpty ? vehicle.displayName : stripped).uppercased()
}

// MARK: - Top bar (odometer · updated · refresh)

private struct SpecTopBar: View {
    let vehicle: VehicleEntity

    var body: some View {
        HStack(spacing: 5) {
            if !vehicle.odometer.isEmpty {
                HStack(spacing: 4) {
                    Image(systemName: "smallcircle.filled.circle")
                        .font(.system(size: 11, weight: .semibold))
                    Text(vehicle.odometer)
                        .font(.system(size: 11, weight: .semibold))
                        .lineLimit(1)
                }
                .foregroundColor(.white.opacity(0.8))
            }

            Spacer(minLength: 4)

            Text("Updated \(vehicle.timestamp, format: .dateTime.hour().minute())")
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(.white.opacity(0.55))
                .lineLimit(1)

            Spacer(minLength: 4)

            Button(intent: {
                let i = RefreshVehicleStatusIntent()
                i.vehicle = vehicle
                return i
            }()) {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.white.opacity(0.55))
            }
            .buttonStyle(.plain)
        }
    }
}

// MARK: - Hero container (shared chrome for both hero styles)

private struct SpecHeroFrame<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(
                    RadialGradient(
                        colors: [Color.white.opacity(0.10), Color.white.opacity(0.018), Color.white.opacity(0.008)],
                        center: UnitPoint(x: 0.5, y: -0.06),
                        startRadius: 0, endRadius: 260
                    )
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(Color.white.opacity(0.08), lineWidth: 1)
                )
            content
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

// MARK: - Hero 1a: consistent wordmark over car render

private struct SpecWordmarkHero: View {
    let vehicle: VehicleEntity
    let accent: Color

    var body: some View {
        SpecHeroFrame {
            ZStack(alignment: .top) {
                // Car anchored to the bottom of the hero
                VStack(spacing: 0) {
                    Spacer(minLength: 0)
                    CarImageView(vehicle: vehicle, size: 72)
                        .padding(.bottom, 7)
                }

                // Wordmark rendered above the car (z-order per spec)
                Text(specWordmark(for: vehicle))
                    .font(.system(size: 26, weight: .heavy, design: .rounded))
                    .tracking(6)
                    .lineLimit(1)
                    .minimumScaleFactor(0.4)
                    .foregroundColor(accent)
                    .shadow(color: .black.opacity(0.4), radius: 8, y: 2)
                    .padding(.top, 10)
                    .padding(.horizontal, 12)
            }
        }
    }
}

// MARK: - Hero 1b: woven lettering with battery-level fill sweep

/// Oversized model lettering matching the comp: the model word runs
/// edge-to-edge across the top of the hero, and the trailing numeral
/// ("5") towers faintly behind on the right. Rendered twice by the
/// hero: a ghost layer, and an accent layer masked left→right by the
/// battery percentage — the per-letter fill sweep from the design comp.
private struct SpecWovenLetters: View {
    let wordmark: String
    let color: Color

    // "IONIQ 5" → main word "IONIQ" + trailing numeral "5".
    // Single-token names ("KONA") get no ghost numeral.
    private var parts: (main: String, trail: String) {
        let tokens = wordmark.split(separator: " ")
        if tokens.count >= 2, let last = tokens.last {
            return (tokens.dropLast().joined(separator: " "), String(last))
        }
        return (wordmark, "")
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            // Ghost numeral towering behind on the right
            if !parts.trail.isEmpty {
                Text(parts.trail)
                    .font(.system(size: 118, weight: .heavy, design: .rounded))
                    .opacity(0.35)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                    .padding(.trailing, 2)
            }

            // Model word spanning the hero width
            Text(parts.main)
                .font(.system(size: 60, weight: .heavy, design: .rounded))
                .tracking(3)
                .lineLimit(1)
                .minimumScaleFactor(0.3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 4)
                .padding(.trailing, 26)
        }
        .foregroundColor(color)
        .padding(.horizontal, 10)
    }
}

private struct SpecWovenHero: View {
    let vehicle: VehicleEntity
    let accent: Color

    private var fillFraction: Double {
        (vehicle.batteryPercentage ?? 0) / 100.0
    }

    var body: some View {
        SpecHeroFrame {
            ZStack {
                // Ghost (unfilled) lettering
                SpecWovenLetters(wordmark: specWordmark(for: vehicle), color: .white.opacity(0.22))

                // Accent lettering, revealed left→right by battery level
                SpecWovenLetters(wordmark: specWordmark(for: vehicle), color: accent)
                    .mask(
                        GeometryReader { geo in
                            Rectangle()
                                .frame(width: geo.size.width * fillFraction)
                                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                        }
                    )

                // Car woven in front, hugging the bottom edge
                VStack(spacing: 0) {
                    Spacer(minLength: 0)
                    CarImageView(vehicle: vehicle, size: 84)
                        .padding(.bottom, 4)
                }
            }
        }
    }
}

// MARK: - Battery glyph

private struct SpecBatteryGlyph: View {
    let percentage: Double
    let charging: Bool
    let accent: Color

    var body: some View {
        HStack(spacing: 1.5) {
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3.5, style: .continuous)
                    .stroke(Color.white.opacity(0.45), lineWidth: 1.6)
                RoundedRectangle(cornerRadius: 1.5)
                    .fill(charging ? accent : Color.white.opacity(0.85))
                    .frame(width: max(2, 22 * percentage / 100), height: 8)
                    .padding(.leading, 3)
                    .shadow(color: charging ? accent.opacity(0.5) : .clear, radius: 3)
                if charging {
                    Image(systemName: "bolt.fill")
                        .font(.system(size: 8, weight: .bold))
                        .foregroundColor(.black.opacity(0.7))
                        .frame(maxWidth: .infinity)
                }
            }
            .frame(width: 28, height: 14)

            RoundedRectangle(cornerRadius: 1)
                .fill(Color.white.opacity(0.45))
                .frame(width: 2.5, height: 6)
        }
    }
}

// MARK: - Range + battery row

private struct SpecRangeBatteryRow: View {
    let vehicle: VehicleEntity
    let accent: Color

    /// "253 mi" → ("253", "mi"); non-numeric text passes through whole.
    private var rangeParts: (value: String, unit: String) {
        let comps = vehicle.rangeText.split(separator: " ", maxSplits: 1)
        if let first = comps.first, first.first?.isNumber == true {
            return (String(first), comps.count > 1 ? String(comps[1]) : "")
        }
        return (vehicle.rangeText, "")
    }

    private func caption(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 9, weight: .semibold))
            .tracking(1)
            .foregroundColor(.white.opacity(0.42))
    }

    var body: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(rangeParts.value)
                        .font(.system(size: 34, weight: .heavy))
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                        .foregroundColor(.white)
                    if !rangeParts.unit.isEmpty {
                        Text(rangeParts.unit)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(.white.opacity(0.5))
                    }
                }
                caption("RANGE")
            }

            Spacer(minLength: 8)

            if let pct = vehicle.batteryPercentage {
                VStack(alignment: .trailing, spacing: 4) {
                    HStack(spacing: 6) {
                        SpecBatteryGlyph(percentage: pct, charging: vehicle.isCharging, accent: accent)
                        Text("\(Int(pct))%")
                            .font(.system(size: 20, weight: .heavy))
                            .foregroundColor(.white)
                    }
                    caption("BATTERY")
                }
            }
        }
    }
}

// MARK: - Wave charge bar

private struct SpecWaveShape: Shape {
    let wavelength: CGFloat
    let amplitude: CGFloat

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let midY = rect.midY
        path.move(to: CGPoint(x: rect.minX, y: midY))
        var x = rect.minX
        var up = true
        while x < rect.maxX {
            let nextX = min(x + wavelength / 2, rect.maxX)
            path.addQuadCurve(
                to: CGPoint(x: nextX, y: midY),
                control: CGPoint(x: x + wavelength / 4, y: midY + (up ? -amplitude : amplitude))
            )
            x = nextX
            up.toggle()
        }
        return path
    }
}

private struct SpecWaveChargeBar: View {
    let progress: Double  // 0...1
    let target: Double?   // 0...1
    let accent: Color

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.08))

                Capsule()
                    .fill(LinearGradient(
                        colors: [accent.opacity(0.6), accent],
                        startPoint: .leading, endPoint: .trailing
                    ))
                    .frame(width: max(12, width * progress))
                    .overlay(
                        SpecWaveShape(wavelength: 20, amplitude: 2.6)
                            .stroke(Color.white.opacity(0.5), lineWidth: 1.6)
                            .clipShape(Capsule())
                    )
                    .shadow(color: accent.opacity(0.5), radius: 4)

                // Target SOC tick
                if let target, target < 1 {
                    RoundedRectangle(cornerRadius: 1)
                        .fill(Color.white.opacity(0.7))
                        .frame(width: 2)
                        .padding(.vertical, -2)
                        .shadow(color: .white.opacity(0.4), radius: 2)
                        .offset(x: min(width * target, width - 2))
                }
            }
        }
    }
}

// MARK: - Charging module

private struct SpecChargingModule: View {
    let vehicle: VehicleEntity
    let accent: Color

    private var titleText: String {
        var title = "Charging"
        if let plug = vehicle.plugTypeLabel { title += " · \(plug)" }
        if vehicle.chargeSpeedKW > 0 {
            title += String(format: " · %.1f kW", vehicle.chargeSpeedKW)
        }
        return title
    }

    private var timeLeftLabel: String? {
        let secs = vehicle.chargeRemainingSeconds
        guard secs > 60 else { return nil }
        let h = secs / 3600
        let m = (secs % 3600) / 60
        return h > 0 ? "\(h)h \(m)m left" : "\(m)m left"
    }

    private var finishLabel: String? {
        guard let finish = chargeFinishDate(
            remainingSeconds: vehicle.chargeRemainingSeconds,
            reportedAt: vehicle.timestamp
        ) else { return nil }
        return formatChargeFinishTime(finish)
    }

    var body: some View {
        VStack(spacing: 8) {
            // Header: bolt · title · time-left pill
            HStack(spacing: 7) {
                Image(systemName: "bolt.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(accent)
                    .shadow(color: accent.opacity(0.6), radius: 3)

                Text(titleText)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)

                Spacer(minLength: 4)

                if let left = timeLeftLabel {
                    Text(left)
                        .font(.system(size: 10.5, weight: .semibold))
                        .foregroundColor(accent)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(accent.opacity(0.14)))
                        .lineLimit(1)
                }
            }

            SpecWaveChargeBar(
                progress: (vehicle.batteryPercentage ?? 0) / 100.0,
                target: vehicle.targetSOC.map { $0 / 100.0 },
                accent: accent
            )
            .frame(height: 12)

            // Footer: finish time · target
            HStack {
                if let finish = finishLabel {
                    HStack(spacing: 4) {
                        Image(systemName: "clock")
                            .font(.system(size: 9.5))
                        Text("Full by \(finish)")
                    }
                    .font(.system(size: 10.5, weight: .medium))
                    .foregroundColor(.white.opacity(0.72))
                    .lineLimit(1)
                }

                Spacer(minLength: 4)

                if let target = vehicle.targetSOC {
                    Text("Target \(Int(target))%")
                        .font(.system(size: 10.5, weight: .medium))
                        .foregroundColor(.white.opacity(0.55))
                        .lineLimit(1)
                }
            }
        }
        .padding(EdgeInsets(top: 10, leading: 12, bottom: 10, trailing: 12))
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.white.opacity(0.05))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(Color.white.opacity(0.09), lineWidth: 1)
                )
        )
    }
}

// MARK: - Parked / charge-complete status row

private struct SpecStatusRow: View {
    let vehicle: VehicleEntity
    let accent: Color

    private var lockIcon: String {
        switch vehicle.isLocked {
        case true: return "lock.fill"
        case false: return "lock.open.fill"
        default: return "car.fill"
        }
    }

    private var statusText: String {
        switch vehicle.isLocked {
        case true: return "Locked · Parked"
        case false: return "Unlocked · Parked"
        default: return "Parked"
        }
    }

    var body: some View {
        HStack(spacing: 9) {
            if vehicle.isChargeComplete {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(accent)
                Text("Charge Complete")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.white.opacity(0.85))
            } else if vehicle.isPluggedIn {
                // Parked cars keep the lock state; the age is shown beside it.
                Text(vehicle.chargingState.title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.secondary)
            } else {
                Image(systemName: lockIcon)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.white.opacity(0.8))
                Text(statusText)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.white.opacity(0.72))
            }

            Spacer(minLength: 4)

            Text("\(vehicle.timestamp, style: .relative) ago")
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.white.opacity(0.45))
                .lineLimit(1)
        }
        .padding(EdgeInsets(top: 13, leading: 12, bottom: 13, trailing: 12))
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.white.opacity(0.05))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(Color.white.opacity(0.09), lineWidth: 1)
                )
        )
    }
}

// MARK: - Lock / Unlock buttons

private struct SpecActionButtons: View {
    let vehicle: VehicleEntity
    let accent: Color

    var body: some View {
        HStack(spacing: 8) {
            Button(intent: {
                let i = LockVehicleControlIntent()
                i.vehicle = vehicle
                return i
            }()) {
                HStack(spacing: 7) {
                    Image(systemName: "lock.fill")
                    Text("Lock")
                }
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(SpecInk.buttonInk)
                .frame(maxWidth: .infinity)
                .frame(height: 40)
                .background(
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .fill(accent)
                        .shadow(color: accent.opacity(0.3), radius: 8, y: 3)
                )
            }
            .buttonStyle(.plain)

            Button(intent: {
                let i = UnlockVehicleControlIntent()
                i.vehicle = vehicle
                return i
            }()) {
                HStack(spacing: 7) {
                    Image(systemName: "lock.open.fill")
                    Text("Unlock")
                }
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 40)
                .background(
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .fill(Color.white.opacity(0.07))
                        .overlay(
                            RoundedRectangle(cornerRadius: 13, style: .continuous)
                                .stroke(Color.white.opacity(0.13), lineWidth: 1)
                        )
                )
            }
            .buttonStyle(.plain)
        }
    }
}

// MARK: - Shared large layout

private enum SpecHeroStyle {
    case wordmark   // frame 1a
    case woven      // frame 1b
}

private struct SpecLargeLayout: View {
    let vehicle: VehicleEntity
    let accent: Color
    let heroStyle: SpecHeroStyle

    var body: some View {
        VStack(spacing: 10) {
            SpecTopBar(vehicle: vehicle)

            Group {
                switch heroStyle {
                case .wordmark:
                    SpecWordmarkHero(vehicle: vehicle, accent: accent)
                case .woven:
                    SpecWovenHero(vehicle: vehicle, accent: accent)
                }
            }
            .frame(maxHeight: .infinity)

            SpecRangeBatteryRow(vehicle: vehicle, accent: accent)

            if vehicle.isCharging {
                SpecChargingModule(vehicle: vehicle, accent: accent)
            } else {
                SpecStatusRow(vehicle: vehicle, accent: accent)
            }

            SpecActionButtons(vehicle: vehicle, accent: accent)
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Background (spec surface + accent glow)

private struct SpecWidgetBackground: View {
    let accent: Color
    let charging: Bool

    var body: some View {
        ZStack(alignment: .top) {
            RadialGradient(
                colors: [SpecInk.bgTop, SpecInk.bgMid, SpecInk.bgBottom],
                center: UnitPoint(x: 0.5, y: -0.12),
                startRadius: 0,
                endRadius: 520
            )
            // Top accent glow — stronger while charging (per spec)
            Ellipse()
                .fill(accent.opacity(charging ? 0.22 : 0.12))
                .frame(width: 280, height: 140)
                .blur(radius: 42)
                .offset(y: -70)
        }
    }
}

// MARK: - Entry view

private struct SpecWidgetEntryView: View {
    let entry: VehicleWidgetEntry
    let heroStyle: SpecHeroStyle

    private var accent: Color { entry.configuration.accentColor.color }

    var body: some View {
        if AppTheme.persisted == .rivian {
            RivianSpecView(entry: entry, woven: heroStyle == .woven)
        } else {
            classicBody
        }
    }

    private var classicBody: some View {
        Group {
            if let vehicle = entry.vehicle {
                SpecLargeLayout(vehicle: vehicle, accent: accent, heroStyle: heroStyle)
            } else {
                VStack(spacing: 6) {
                    Image(systemName: "car.fill")
                        .font(.largeTitle)
                        .foregroundColor(.white.opacity(0.5))
                    Text("No Vehicle")
                        .font(.headline)
                        .foregroundColor(.white.opacity(0.9))
                    Text("Open GhostBlue to add an account")
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.5))
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .containerBackground(for: .widget) {
            SpecWidgetBackground(
                accent: accent,
                charging: entry.vehicle?.isCharging == true
            )
        }
    }
}

// MARK: - Widgets

struct GhostBlueHeroWidget: Widget {
    let kind: String = "GhostBlueHeroWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: kind,
            intent: VehicleWidgetIntent.self,
            provider: VehicleTimelineProvider(),
        ) { entry in
            SpecWidgetEntryView(entry: entry, heroStyle: .wordmark)
        }
        .contentMarginsDisabled()
        .configurationDisplayName("Hero Card")
        .description("Large status card with model wordmark, charging progress with finish time, and lock controls.")
        .supportedFamilies([.systemLarge])
    }
}

struct GhostBlueWovenWidget: Widget {
    let kind: String = "GhostBlueWovenWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: kind,
            intent: VehicleWidgetIntent.self,
            provider: VehicleTimelineProvider(),
        ) { entry in
            SpecWidgetEntryView(entry: entry, heroStyle: .woven)
        }
        .contentMarginsDisabled()
        .configurationDisplayName("Woven Wordmark")
        .description("Large status card where the battery level fills the model lettering, plus charging progress and lock controls.")
        .supportedFamilies([.systemLarge])
    }
}

// MARK: - Previews

#Preview("Hero – Charging", as: .systemLarge) {
    GhostBlueHeroWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: {
        var v = VehicleEntity(
            id: .init(), displayName: "2025 IONIQ 5", vin: "test",
            fuelType: .electric, rangeText: "253 mi", batteryPercentage: 87.0,
            timestamp: Date(), backgroundColorName: "ghostBlue",
            odometer: "9,789 mi", model: "2025 IONIQ 5"
        )
        v.isCharging = true
        v.isPluggedIn = true
        v.chargeSpeedKW = 6.9
        v.chargeRemainingSeconds = 4320
        v.targetSOC = 90.0
        v.plugTypeLabel = "AC"
        v.isLocked = true
        return v
    }(), configuration: VehicleWidgetIntent())
}

#Preview("Hero – Parked", as: .systemLarge) {
    GhostBlueHeroWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: {
        var v = VehicleEntity(
            id: .init(), displayName: "2025 IONIQ 5", vin: "test",
            fuelType: .electric, rangeText: "253 mi", batteryPercentage: 87.0,
            timestamp: Date().addingTimeInterval(-120), backgroundColorName: "ghostBlue",
            odometer: "9,789 mi", model: "2025 IONIQ 5"
        )
        v.isLocked = true
        return v
    }(), configuration: VehicleWidgetIntent())
}

#Preview("Woven – Charging", as: .systemLarge) {
    GhostBlueWovenWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: {
        var v = VehicleEntity(
            id: .init(), displayName: "2025 IONIQ 5", vin: "test",
            fuelType: .electric, rangeText: "253 mi", batteryPercentage: 87.0,
            timestamp: Date(), backgroundColorName: "ghostBlue",
            odometer: "9,789 mi", model: "2025 IONIQ 5"
        )
        v.isCharging = true
        v.isPluggedIn = true
        v.chargeSpeedKW = 6.9
        v.chargeRemainingSeconds = 4320
        v.targetSOC = 90.0
        v.plugTypeLabel = "AC"
        v.isLocked = true
        return v
    }(), configuration: VehicleWidgetIntent())
}

#Preview("Woven – Parked", as: .systemLarge) {
    GhostBlueWovenWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: {
        var v = VehicleEntity(
            id: .init(), displayName: "2025 IONIQ 5", vin: "test",
            fuelType: .electric, rangeText: "253 mi", batteryPercentage: 62.0,
            timestamp: Date().addingTimeInterval(-120), backgroundColorName: "ghostBlue",
            odometer: "9,789 mi", model: "2025 IONIQ 5"
        )
        v.isLocked = true
        return v
    }(), configuration: VehicleWidgetIntent())
}
