//
//  VehicleWidgetView.swift
//  GhostBlueWidget
//

import AppIntents
import BetterBlueKit
import SwiftUI
import UIKit
import WidgetKit

// MARK: - GhostBlue Design System

enum GhostBlue {
    static let midnight   = Color(red: 0.04, green: 0.05, blue: 0.15)
    static let deepBlue   = Color(red: 0.07, green: 0.11, blue: 0.30)
    static let electric   = Color(red: 0.12, green: 0.45, blue: 1.00)
    static let cyan       = Color(red: 0.00, green: 0.84, blue: 1.00)
    static let glow       = Color(red: 0.20, green: 0.60, blue: 1.00)
    static let softWhite  = Color.white.opacity(0.90)
    static let dimWhite   = Color.white.opacity(0.50)
    static let gradient: [Color] = [midnight, deepBlue]

    // Button accent colours
    static let lockRed    = Color(red: 1.00, green: 0.30, blue: 0.30)
    static let unlockGreen = Color(red: 0.20, green: 0.90, blue: 0.45)
    static let climateGray = Color(red: 0.55, green: 0.55, blue: 0.62)
}

// MARK: - Glass helper modifier

private extension View {
    /// Frosted panel: ultraThinMaterial with a hairline stroke.
    /// Don't use `glassEffect` here — inside a widget on iOS 26 it renders
    /// nothing, hiding the wrapped content along with the glass.
    func ghostGlass(cornerRadius: CGFloat = 14) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        return background {
            shape
                .fill(.ultraThinMaterial)
                .overlay { shape.stroke(Color.white.opacity(0.18), lineWidth: 0.5) }
                // The widget background is always dark; keep the material dark too.
                .environment(\.colorScheme, .dark)
        }
    }
}

// MARK: - Code-drawn EV silhouette (IONIQ 5 inspired, no image assets required)
// Internal (not private): also used by the spec widgets in SpecLargeWidgets.swift.

struct EVCarSilhouette: View {
    var body: some View {
        Canvas { ctx, size in
            let W = size.width, H = size.height
            func pt(_ nx: Double, _ ny: Double) -> CGPoint { CGPoint(x: nx * W, y: ny * H) }

            // ── Body ──────────────────────────────────────────────────────
            var carBody = Path()
            carBody.move(to: pt(0.06, 0.96))
            carBody.addLine(to: pt(0.02, 0.80))
            carBody.addLine(to: pt(0.04, 0.64))
            carBody.addLine(to: pt(0.10, 0.57))
            carBody.addLine(to: pt(0.20, 0.54))
            // A-pillar (steep windshield)
            carBody.addCurve(to: pt(0.27, 0.22),
                             control1: pt(0.21, 0.53), control2: pt(0.24, 0.31))
            // Flat roof (IONIQ 5 signature)
            carBody.addLine(to: pt(0.78, 0.21))
            // D-pillar (nearly vertical)
            carBody.addCurve(to: pt(0.87, 0.52),
                             control1: pt(0.82, 0.21), control2: pt(0.87, 0.35))
            carBody.addLine(to: pt(0.93, 0.57))
            carBody.addLine(to: pt(0.97, 0.74))
            carBody.addLine(to: pt(0.96, 0.88))
            carBody.addLine(to: pt(0.92, 0.96))
            carBody.addLine(to: pt(0.06, 0.96))
            carBody.closeSubpath()

            ctx.fill(carBody, with: .linearGradient(
                Gradient(colors: [GhostBlue.cyan.opacity(0.85), GhostBlue.electric]),
                startPoint: pt(0.1, 0.05), endPoint: pt(0.9, 0.95)
            ))

            // Lower body panel darker strip
            var lower = Path()
            lower.move(to: pt(0.06, 0.82))
            lower.addLine(to: pt(0.92, 0.82))
            lower.addLine(to: pt(0.92, 0.96))
            lower.addLine(to: pt(0.06, 0.96))
            lower.closeSubpath()
            ctx.fill(lower, with: .color(.black.opacity(0.18)))

            // ── Window ────────────────────────────────────────────────────
            var win = Path()
            win.move(to: pt(0.28, 0.25))
            win.addLine(to: pt(0.77, 0.23))
            win.addLine(to: pt(0.86, 0.51))
            win.addLine(to: pt(0.26, 0.53))
            win.closeSubpath()
            ctx.fill(win, with: .color(Color(red: 0.04, green: 0.05, blue: 0.18).opacity(0.90)))

            // B-pillar
            var bp = Path()
            bp.move(to: pt(0.54, 0.25))
            bp.addLine(to: pt(0.56, 0.53))
            ctx.stroke(bp, with: .color(Color(red: 0.04, green: 0.05, blue: 0.18)), lineWidth: W * 0.016)

            // Roof highlight
            var roof = Path()
            roof.move(to: pt(0.27, 0.22))
            roof.addLine(to: pt(0.78, 0.21))
            ctx.stroke(roof, with: .color(.white.opacity(0.35)), lineWidth: 0.8)

            // Body border
            ctx.stroke(carBody, with: .color(.white.opacity(0.15)), lineWidth: 0.5)

            // ── Wheels ────────────────────────────────────────────────────
            let wR = W * 0.083
            let wY = H * 0.96
            for cx in [W * 0.215, W * 0.760] {
                // Tire
                ctx.fill(
                    Path(ellipseIn: CGRect(x: cx-wR, y: wY-wR, width: wR*2, height: wR*2)),
                    with: .color(Color(red: 0.08, green: 0.09, blue: 0.12))
                )
                // Alloy rim
                let rR = wR * 0.60
                ctx.fill(
                    Path(ellipseIn: CGRect(x: cx-rR, y: wY-rR, width: rR*2, height: rR*2)),
                    with: .color(Color(red: 0.42, green: 0.46, blue: 0.56))
                )
                ctx.stroke(
                    Path(ellipseIn: CGRect(x: cx-rR, y: wY-rR, width: rR*2, height: rR*2)),
                    with: .color(Color(red: 0.20, green: 0.22, blue: 0.28)), lineWidth: 1.0
                )
                // Center cap
                let cR = wR * 0.26
                ctx.fill(
                    Path(ellipseIn: CGRect(x: cx-cR, y: wY-cR, width: cR*2, height: cR*2)),
                    with: .color(Color(red: 0.65, green: 0.68, blue: 0.74))
                )
            }

            // ── IONIQ 5 pixel DRL headlight strip ─────────────────────────
            var hl = Path()
            hl.move(to: pt(0.026, 0.60))
            hl.addLine(to: pt(0.026, 0.69))
            ctx.stroke(hl, with: .color(GhostBlue.cyan), lineWidth: W * 0.010)

            // Tail light strip
            var tl = Path()
            tl.move(to: pt(0.966, 0.63))
            tl.addLine(to: pt(0.966, 0.73))
            ctx.stroke(tl, with: .color(Color(red: 1.0, green: 0.22, blue: 0.18).opacity(0.9)),
                       lineWidth: W * 0.010)

            // Ground glow
            ctx.fill(
                Path(ellipseIn: CGRect(x: W*0.06, y: H*0.97, width: W*0.88, height: H*0.12)),
                with: .linearGradient(
                    Gradient(stops: [
                        .init(color: GhostBlue.glow.opacity(0.18), location: 0),
                        .init(color: .clear, location: 1)
                    ]),
                    startPoint: pt(0.5, 0.97), endPoint: pt(0.5, 1.1)
                )
            )
        }
        .aspectRatio(2.5, contentMode: .fit)
    }
}

// MARK: - Car image
// Priority: (1) fetched photo from bluelink.andyfase.com cached on disk
//           (2) bundled PNG asset matching carImageKey (e.g. "ioniq5")
//           (3) code-drawn IONIQ 5 silhouette
// Internal (not private): also used by the spec widgets in SpecLargeWidgets.swift.

struct CarImageView: View {
    let vehicle: VehicleEntity
    let size: CGFloat

    private var resolvedImage: UIImage? {
        // 1. Fetched & cached photo
        if let path = vehicle.cachedImagePath,
           let img = UIImage(contentsOfFile: path) {
            return img
        }
        // 2. Bundled asset
        if let img = UIImage(named: vehicle.carImageKey) {
            return img
        }
        return nil
    }

    var body: some View {
        if let uiImg = resolvedImage {
            Image(uiImage: uiImg)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(height: size)
                .shadow(color: GhostBlue.glow.opacity(0.30), radius: 8)
        } else {
            EVCarSilhouette()
                .frame(height: size)
                .shadow(color: GhostBlue.glow.opacity(0.40), radius: 8)
        }
    }
}

// MARK: - Battery bar

private struct GhostBatteryBar: View {
    let percentage: Double

    private var barColor: Color {
        if percentage > 50 { return GhostBlue.electric }
        if percentage > 20 { return .orange }
        return .red
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.12)).frame(height: 4)
                Capsule()
                    .fill(LinearGradient(
                        colors: [barColor.opacity(0.7), barColor],
                        startPoint: .leading, endPoint: .trailing
                    ))
                    .frame(width: geo.size.width * (percentage / 100), height: 4)
                    .shadow(color: barColor.opacity(0.55), radius: 3)
            }
        }
        .frame(height: 4)
    }
}

// MARK: - Info panel (top section of combined widget)

private struct VehicleInfoPanel: View {
    let vehicle: VehicleEntity

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            // Car image
            CarImageView(vehicle: vehicle, size: 46)
                .frame(width: 46)

            VStack(alignment: .leading, spacing: 3) {
                // Name + refresh button
                HStack(alignment: .firstTextBaseline) {
                    Text(vehicle.displayName)
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Button(intent: {
                        let i = RefreshVehicleStatusIntent()
                        i.vehicle = vehicle
                        return i
                    }()) {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundColor(GhostBlue.dimWhite)
                            .padding(5)
                            .background(Circle().fill(Color.white.opacity(0.10)))
                    }
                    .buttonStyle(.plain)
                }

                // Range + battery %
                HStack(spacing: 4) {
                    Image(systemName: vehicle.fuelType.hasElectricCapability ? "bolt.fill" : "fuelpump.fill")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundColor(GhostBlue.cyan)
                    Text(vehicle.rangeText)
                        .font(.system(size: 13, weight: .semibold, design: .rounded))
                        .foregroundColor(GhostBlue.softWhite)
                    if let pct = vehicle.batteryPercentage {
                        Text("•")
                            .font(.caption2)
                            .foregroundColor(GhostBlue.dimWhite)
                        Text("\(Int(pct))%")
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundColor(GhostBlue.softWhite)
                    }
                }

                // Battery bar
                if let pct = vehicle.batteryPercentage {
                    GhostBatteryBar(percentage: pct)
                }

                // Odometer + timestamp
                HStack(spacing: 4) {
                    if !vehicle.odometer.isEmpty {
                        Image(systemName: "gauge.with.needle")
                            .font(.system(size: 8))
                            .foregroundColor(GhostBlue.dimWhite)
                        Text(vehicle.odometer)
                            .font(.system(size: 9, weight: .medium, design: .rounded))
                            .foregroundColor(GhostBlue.dimWhite)
                        Text("·").font(.system(size: 9)).foregroundColor(GhostBlue.dimWhite.opacity(0.5))
                    }
                    Text("Updated \(vehicle.timestamp, style: .time)")
                        .font(.system(size: 9))
                        .foregroundColor(GhostBlue.dimWhite)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .ghostGlass(cornerRadius: 14)
    }
}

// MARK: - Charging status strip

private struct ChargingStatusView: View {
    let vehicle: VehicleEntity

    private var timeLabel: String? {
        let secs = vehicle.chargeRemainingSeconds
        guard secs > 60 else { return nil }
        let h = secs / 3600
        let m = (secs % 3600) / 60
        return h > 0 ? "\(h)h \(m)m left" : "\(m) min left"
    }

    /// Clock time the charge is estimated to finish, e.g. "3:41 PM".
    private var finishTimeLabel: String? {
        guard let finish = chargeFinishDate(
            remainingSeconds: vehicle.chargeRemainingSeconds,
            reportedAt: vehicle.timestamp
        ) else { return nil }
        return formatChargeFinishTime(finish)
    }

    private var currentPct: Double { (vehicle.batteryPercentage ?? 0) / 100.0 }
    private var targetPct: Double  { (vehicle.targetSOC ?? 100.0) / 100.0 }

    /// BlueLink's cached snapshot often lags during a charge; a stale but
    /// charging snapshot still shows progress (its age is shown elsewhere)
    /// rather than collapsing to "Status outdated".
    private var showsChargingProgress: Bool {
        switch vehicle.chargingState {
        case .charging, .unknown: true
        case .outdated: vehicle.isCharging
        default: false
        }
    }

    var body: some View {
        if vehicle.isChargeComplete {
            chargeCompleteView
        } else if !showsChargingProgress {
            Label(vehicle.chargingState.title, systemImage: "bolt.badge.clock")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
        } else {
            activeChargingView
        }
    }

    private var chargeCompleteView: some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 14, weight: .bold))
                .foregroundColor(.green)
            Text("Charging Complete")
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .foregroundColor(.white)
            Spacer(minLength: 0)
            if let pct = vehicle.batteryPercentage {
                Text("\(Int(pct))%")
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .foregroundColor(.green)
            }
            if let target = vehicle.targetSOC {
                Text("/ Target \(Int(target))%")
                    .font(.system(size: 10, weight: .medium, design: .rounded))
                    .foregroundColor(GhostBlue.dimWhite)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.green.opacity(0.07))
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(Color.green.opacity(0.45), lineWidth: 0.8)
                )
        )
    }

    @ViewBuilder
    private var activeChargingView: some View {
        VStack(spacing: 5) {
            // ── Header row ──────────────────────────────────────────────
            HStack(spacing: 5) {
                // Charging bolt + label
                Image(systemName: "bolt.fill")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(GhostBlue.cyan)

                Text("Charging")
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .foregroundColor(.white)

                if let label = vehicle.plugTypeLabel {
                    Text(label)
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(GhostBlue.midnight)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(
                            Capsule().fill(GhostBlue.cyan)
                        )
                }

                if vehicle.chargeSpeedKW > 0 {
                    Text("·")
                        .foregroundColor(GhostBlue.dimWhite)
                    Text(String(format: "%.1f kW", vehicle.chargeSpeedKW))
                        .font(.system(size: 12, weight: .semibold, design: .rounded))
                        .foregroundColor(GhostBlue.cyan)
                }

                Spacer(minLength: 0)

                if let label = timeLabel {
                    HStack(spacing: 3) {
                        Image(systemName: "clock")
                            .font(.system(size: 10))
                            .foregroundColor(GhostBlue.dimWhite)
                        Text(label)
                            .font(.system(size: 11, weight: .medium, design: .rounded))
                            .foregroundColor(GhostBlue.dimWhite)
                    }
                }
            }

            // ── Progress bar ─────────────────────────────────────────────
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    // Track
                    Capsule()
                        .fill(Color.white.opacity(0.09))
                        .frame(height: 7)

                    // Target zone (dim cyan)
                    Capsule()
                        .fill(GhostBlue.cyan.opacity(0.18))
                        .frame(width: geo.size.width * targetPct, height: 7)

                    // Filled charge level
                    Capsule()
                        .fill(LinearGradient(
                            colors: [GhostBlue.electric, GhostBlue.cyan],
                            startPoint: .leading, endPoint: .trailing
                        ))
                        .frame(width: max(7, geo.size.width * currentPct), height: 7)
                        .shadow(color: GhostBlue.cyan.opacity(0.65), radius: 5)

                    // Glowing tip dot
                    Circle()
                        .fill(Color.white)
                        .frame(width: 9, height: 9)
                        .shadow(color: GhostBlue.cyan, radius: 5)
                        .offset(x: max(0, geo.size.width * currentPct - 4))
                }
                .frame(height: 9)
            }
            .frame(height: 9)

            // ── Footer row: % labels + estimated finish time ─────────────
            HStack {
                Text("\(Int(vehicle.batteryPercentage ?? 0))%")
                    .font(.system(size: 10, weight: .bold, design: .rounded))
                    .foregroundColor(GhostBlue.cyan)

                Spacer(minLength: 0)

                if let finish = finishTimeLabel {
                    HStack(spacing: 3) {
                        Image(systemName: "bolt.badge.checkmark.fill")
                            .font(.system(size: 9, weight: .semibold))
                        Text("done at \(finish)")
                            .font(.system(size: 10, weight: .semibold, design: .rounded))
                    }
                    .foregroundColor(GhostBlue.cyan)

                    Spacer(minLength: 0)
                }

                if let target = vehicle.targetSOC {
                    Text("Target \(Int(target))%")
                        .font(.system(size: 10, weight: .medium, design: .rounded))
                        .foregroundColor(GhostBlue.dimWhite)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(GhostBlue.cyan.opacity(0.07))
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(
                            LinearGradient(
                                colors: [GhostBlue.cyan.opacity(0.50), GhostBlue.electric.opacity(0.20)],
                                startPoint: .topLeading, endPoint: .bottomTrailing
                            ),
                            lineWidth: 0.8
                        )
                )
        )
    } // end activeChargingView
}

// MARK: - Glass button style

struct GhostWidgetButtonStyle: ButtonStyle {
    let color: Color

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundColor(.white)
            .font(.system(size: 11, weight: .semibold, design: .rounded))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.vertical, 9)
            .background {
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .fill(color.opacity(configuration.isPressed ? 0.55 : 0.32))
                    .overlay {
                        RoundedRectangle(cornerRadius: 11, style: .continuous)
                            .stroke(color.opacity(0.45), lineWidth: 0.6)
                    }
            }
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeInOut(duration: 0.08), value: configuration.isPressed)
    }
}

// MARK: - Button data model

struct GhostButtonData {
    let label: String
    let icon: String
    let color: Color
    let intent: () -> any AppIntent
}

// MARK: - Button grid (bottom section of combined widget)

private struct VehicleButtonsPanel: View {
    let vehicle: VehicleEntity
    let isSmall: Bool

    private var buttons: [GhostButtonData] {
        var actions: [GhostButtonData] = []

        if vehicle.isLocked == true {
            actions.append(GhostButtonData(label: "Unlock", icon: "lock.open.fill", color: GhostBlue.unlockGreen, intent: {
                let i = UnlockVehicleControlIntent(); i.vehicle = vehicle; return i
            }))
        } else {
            actions.append(GhostButtonData(label: "Lock", icon: "lock.fill", color: GhostBlue.lockRed, intent: {
                let i = LockVehicleControlIntent(); i.vehicle = vehicle; return i
            }))
        }

        if vehicle.isClimateOn {
            actions.append(GhostButtonData(label: "Stop Climate", icon: "fan.slash", color: GhostBlue.climateGray, intent: {
                let i = StopClimateControlIntent(); i.vehicle = vehicle; return i
            }))
        } else {
            actions.append(GhostButtonData(label: "Start Climate", icon: "fan", color: GhostBlue.electric, intent: {
                StartVehicleClimateIntent(vehicle: vehicle)
            }))
        }

        return actions
    }

    var body: some View {
        LazyVGrid(
            columns: [GridItem(.flexible(), spacing: 5), GridItem(.flexible(), spacing: 5)],
            spacing: 5
        ) {
            ForEach(Array(buttons.enumerated()), id: \.offset) { _, btn in
                Button(intent: btn.intent()) {
                    if isSmall {
                        Image(systemName: btn.icon)
                    } else {
                        Label(btn.label, systemImage: btn.icon)
                    }
                }
                .buttonStyle(GhostWidgetButtonStyle(color: btn.color))
            }
        }
        .labelStyle(.titleAndIcon)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Small widget (info only, links to app)

private struct VehicleSmallWidget: View {
    let vehicle: VehicleEntity

    var body: some View {
        Link(destination: URL(string: "ghostblue://vehicle/\(vehicle.vin)")!) {
            VStack(alignment: .leading, spacing: 4) {
                // Name + battery %
                HStack {
                    Text(vehicle.displayName)
                        .font(.system(size: 11, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                        .lineLimit(1)
                    Spacer(minLength: 2)
                    if let pct = vehicle.batteryPercentage {
                        Text("\(Int(pct))%")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundColor(GhostBlue.cyan)
                    }
                }

                Spacer(minLength: 0)

                // Car image
                CarImageView(vehicle: vehicle, size: 46)
                    .frame(maxWidth: .infinity, alignment: .center)

                Spacer(minLength: 0)

                // Range
                HStack(spacing: 3) {
                    Image(systemName: vehicle.fuelType.hasElectricCapability ? "bolt.fill" : "fuelpump.fill")
                        .font(.system(size: 9))
                        .foregroundColor(GhostBlue.cyan)
                    Text(vehicle.rangeText)
                        .font(.system(size: 14, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                }

                // Battery bar
                if let pct = vehicle.batteryPercentage {
                    GhostBatteryBar(percentage: pct)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

// MARK: - Compact charging indicator (medium widget)

private struct ChargingStatusMini: View {
    let vehicle: VehicleEntity

    private var timeLabel: String? {
        let secs = vehicle.chargeRemainingSeconds
        guard secs > 60 else { return nil }
        let h = secs / 3600
        let m = (secs % 3600) / 60
        let duration = h > 0 ? "\(h)h \(m)m" : "\(m) min"
        // Append the estimated finish clock time, e.g. "1h 30m · 3:41 PM"
        if let finish = chargeFinishDate(remainingSeconds: secs, reportedAt: vehicle.timestamp) {
            return "\(duration) · \(formatChargeFinishTime(finish))"
        }
        return duration
    }

    private var currentPct: Double { (vehicle.batteryPercentage ?? 0) / 100.0 }
    private var targetPct: Double  { (vehicle.targetSOC ?? 100.0) / 100.0 }

    /// BlueLink's cached snapshot often lags during a charge; a stale but
    /// charging snapshot still shows progress (its age is shown elsewhere)
    /// rather than collapsing to "Status outdated".
    private var showsChargingProgress: Bool {
        switch vehicle.chargingState {
        case .charging, .unknown: true
        case .outdated: vehicle.isCharging
        default: false
        }
    }

    var body: some View {
        if vehicle.isChargeComplete {
            chargeMiniCompleteView
        } else if !showsChargingProgress {
            Text(vehicle.chargingState.title).font(.caption2).foregroundStyle(.secondary)
        } else {
            activeMiniChargingView
        }
    }

    private var chargeMiniCompleteView: some View {
        HStack(spacing: 6) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(.green)
            Text("Charged")
                .font(.system(size: 11, weight: .semibold, design: .rounded))
                .foregroundColor(.white)
            Spacer(minLength: 0)
            if let pct = vehicle.batteryPercentage {
                Text("\(Int(pct))%")
                    .font(.system(size: 11, weight: .bold, design: .rounded))
                    .foregroundColor(.green)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Color.green.opacity(0.07))
                .overlay(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .stroke(Color.green.opacity(0.35), lineWidth: 0.6)
                )
        )
    }

    private var activeMiniChargingView: some View {
        VStack(spacing: 4) {
            HStack(spacing: 5) {
                Image(systemName: "bolt.fill")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(GhostBlue.cyan)

                if let label = vehicle.plugTypeLabel {
                    Text(label)
                        .font(.system(size: 8, weight: .bold))
                        .foregroundColor(GhostBlue.midnight)
                        .padding(.horizontal, 4).padding(.vertical, 1)
                        .background(Capsule().fill(GhostBlue.cyan))
                }

                if vehicle.chargeSpeedKW > 0 {
                    Text(String(format: "%.0f kW", vehicle.chargeSpeedKW))
                        .font(.system(size: 10, weight: .semibold, design: .rounded))
                        .foregroundColor(GhostBlue.cyan)
                }

                Spacer(minLength: 0)

                if let time = timeLabel {
                    Image(systemName: "clock")
                        .font(.system(size: 9))
                        .foregroundColor(GhostBlue.dimWhite)
                    Text(time)
                        .font(.system(size: 10, weight: .medium, design: .rounded))
                        .foregroundColor(GhostBlue.dimWhite)
                }

                if let target = vehicle.targetSOC {
                    Text("→ \(Int(target))%")
                        .font(.system(size: 10, weight: .medium, design: .rounded))
                        .foregroundColor(GhostBlue.dimWhite)
                }
            }

            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.09)).frame(height: 4)
                    Capsule()
                        .fill(GhostBlue.cyan.opacity(0.20))
                        .frame(width: geo.size.width * targetPct, height: 4)
                    Capsule()
                        .fill(LinearGradient(
                            colors: [GhostBlue.electric, GhostBlue.cyan],
                            startPoint: .leading, endPoint: .trailing
                        ))
                        .frame(width: max(4, geo.size.width * currentPct), height: 4)
                        .shadow(color: GhostBlue.cyan.opacity(0.65), radius: 3)
                }
                .frame(height: 4)
            }
            .frame(height: 4)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(GhostBlue.cyan.opacity(0.07))
                .overlay(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .stroke(GhostBlue.cyan.opacity(0.30), lineWidth: 0.6)
                )
        )
    }
}

// MARK: - Medium widget (combined: info + buttons)

private struct VehicleMediumWidget: View {
    let vehicle: VehicleEntity

    var body: some View {
        VStack(spacing: 5) {
            VehicleInfoPanel(vehicle: vehicle)
            if vehicle.isCharging || vehicle.isPluggedIn {
                ChargingStatusMini(vehicle: vehicle)
            }
            VehicleButtonsPanel(vehicle: vehicle, isSmall: false)
        }
        .padding(10)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Large widget hero section

private struct VehicleHeroCard: View {
    let vehicle: VehicleEntity

    private func batteryColor(_ pct: Double) -> Color {
        if pct > 50 { return Color(red: 0.20, green: 0.90, blue: 0.45) }
        if pct > 20 { return .orange }
        return .red
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Name row + refresh button
            HStack(alignment: .firstTextBaseline) {
                Text(vehicle.displayName)
                    .font(.system(size: 24, weight: .heavy, design: .rounded))
                    .foregroundColor(.white)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Button(intent: {
                    let i = RefreshVehicleStatusIntent()
                    i.vehicle = vehicle
                    return i
                }()) {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(GhostBlue.dimWhite)
                        .padding(7)
                        .background(Circle().fill(Color.white.opacity(0.10)))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 14)
            .padding(.top, 12)

            Spacer(minLength: 6)

            // Car image + stats side by side
            HStack(alignment: .center, spacing: 0) {
                // Car image (PNG from assets) or SF Symbol fallback
                CarImageView(vehicle: vehicle, size: 90)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 10)

                // Range + battery stacked on right
                VStack(alignment: .trailing, spacing: 6) {
                    Text(vehicle.rangeText)
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .foregroundColor(.white)

                    if let pct = vehicle.batteryPercentage {
                        HStack(spacing: 5) {
                            Image(systemName: pct > 75 ? "battery.100percent" : pct > 40 ? "battery.50percent" : "battery.25percent")
                                .font(.system(size: 18))
                                .foregroundColor(batteryColor(pct))
                            Text("\(Int(pct))%")
                                .font(.system(size: 24, weight: .bold, design: .rounded))
                                .foregroundColor(batteryColor(pct))
                        }

                        GhostBatteryBar(percentage: pct)
                            .frame(width: 100)
                    }
                }
                .padding(.trailing, 14)
            }

            Spacer(minLength: 6)

            // Footer: odometer + last updated
            HStack(spacing: 0) {
                // Odometer
                if !vehicle.odometer.isEmpty {
                    HStack(spacing: 3) {
                        Image(systemName: "gauge.with.needle")
                            .font(.system(size: 10))
                            .foregroundColor(GhostBlue.dimWhite)
                        Text(vehicle.odometer)
                            .font(.system(size: 10, weight: .medium, design: .rounded))
                            .foregroundColor(GhostBlue.dimWhite)
                    }
                    Text("  ·  ")
                        .font(.system(size: 10))
                        .foregroundColor(GhostBlue.dimWhite.opacity(0.5))
                }
                // Timestamp
                HStack(spacing: 3) {
                    Image(systemName: "clock")
                        .font(.system(size: 10))
                        .foregroundColor(GhostBlue.dimWhite)
                    Text(vehicle.timestamp, style: .date)
                        .font(.system(size: 10))
                        .foregroundColor(GhostBlue.dimWhite)
                    Text("at")
                        .font(.system(size: 10))
                        .foregroundColor(GhostBlue.dimWhite)
                    Text(vehicle.timestamp, style: .time)
                        .font(.system(size: 10))
                        .foregroundColor(GhostBlue.dimWhite)
                }
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 10)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color.white.opacity(0.06))
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(
                            LinearGradient(
                                colors: [GhostBlue.cyan.opacity(0.35), GhostBlue.electric.opacity(0.15)],
                                startPoint: .topLeading, endPoint: .bottomTrailing
                            ),
                            lineWidth: 0.8
                        )
                )
        )
    }
}

// MARK: - Large widget (hero card + optional charging strip + button grid)

private struct VehicleLargeWidget: View {
    let vehicle: VehicleEntity

    var body: some View {
        VStack(spacing: 8) {
            VehicleHeroCard(vehicle: vehicle)
                .frame(maxWidth: .infinity)
                .layoutPriority(1)

            if vehicle.isCharging || vehicle.isPluggedIn {
                ChargingStatusView(vehicle: vehicle)
            }

            VehicleButtonsPanel(vehicle: vehicle, isSmall: false)
                .frame(maxWidth: .infinity)
                .frame(height: 110)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color.white.opacity(0.06))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .stroke(Color.white.opacity(0.12), lineWidth: 0.5)
                        )
                )
        }
        .padding(10)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Root entry view

struct VehicleWidgetEntryView: View {
    let entry: VehicleWidgetEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if AppTheme.persisted == .rivian {
            RivianCommandCenterView(entry: entry)
        } else {
            classicBody
        }
    }

    private var classicBody: some View {
        Group {
            if let vehicle = entry.vehicle {
                switch family {
                case .systemSmall:
                    VehicleSmallWidget(vehicle: vehicle)
                case .systemLarge:
                    VehicleLargeWidget(vehicle: vehicle)
                default:
                    VehicleMediumWidget(vehicle: vehicle)
                }
            } else {
                VStack(spacing: 6) {
                    Image(systemName: "car.fill")
                        .font(.largeTitle)
                        .foregroundColor(GhostBlue.dimWhite)
                    Text("No Vehicle")
                        .font(.headline)
                        .foregroundColor(GhostBlue.softWhite)
                    Text("Open GhostBlue to add an account")
                        .font(.caption)
                        .foregroundColor(GhostBlue.dimWhite)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .containerBackground(for: .widget) {
            LinearGradient(colors: GhostBlue.gradient, startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }
}

// MARK: - Lock screen widgets (unchanged layout, ghost colours applied)

struct LockScreenVehicleWidgetView: View {
    let entry: VehicleWidgetEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Group {
            if let vehicle = entry.vehicle {
                switch family {
                case .accessoryCircular:
                    LockScreenRangeWidget(vehicle: vehicle)
                case .accessoryRectangular:
                    LockScreenWideRangeWidget(vehicle: vehicle)
                case .accessoryInline:
                    LockScreenInlineRangeWidget(vehicle: vehicle)
                default:
                    LockScreenRangeWidget(vehicle: vehicle)
                }
            } else {
                Image(systemName: "car.fill")
                    .foregroundColor(.secondary)
                    .font(.title2)
            }
        }
        .containerBackground(for: .widget) { Color.clear }
    }
}

struct LockScreenProgressIcon: View {
    let vehicle: VehicleEntity
    private let lineWidth = 3.0

    private var rangePercentage: Double {
        (vehicle.batteryPercentage ?? 0) / 100.0
    }

    var body: some View {
        ZStack {
            Circle().stroke(Color.secondary.opacity(0.3), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: rangePercentage)
                .stroke(Color.primary, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.easeInOut(duration: 1), value: rangePercentage)
            Image(systemName: vehicle.fuelType.hasElectricCapability ? "bolt.car.fill" : "car.fill")
        }
        .frame(width: 44, height: 44)
    }
}

struct LockScreenRangeWidget: View {
    let vehicle: VehicleEntity
    var body: some View { LockScreenProgressIcon(vehicle: vehicle) }
}

/// One-line lock screen readout above the clock, e.g. "⚡ 72% · 214 mi".
struct LockScreenInlineRangeWidget: View {
    let vehicle: VehicleEntity

    private var inlineText: String {
        if let pct = vehicle.batteryPercentage {
            return "\(Int(pct))% · \(vehicle.rangeText)"
        }
        return vehicle.rangeText
    }

    var body: some View {
        Label {
            Text(inlineText)
        } icon: {
            Image(systemName: vehicle.isCharging
                ? "bolt.fill"
                : (vehicle.fuelType.hasElectricCapability ? "bolt.car.fill" : "car.fill"))
        }
    }
}

struct LockScreenWideRangeWidget: View {
    let vehicle: VehicleEntity
    var body: some View {
        HStack(spacing: 8) {
            LockScreenProgressIcon(vehicle: vehicle)
            VStack(alignment: .leading, spacing: 1) {
                Text(vehicle.displayName).fontWeight(.medium).lineLimit(1)
                Text(vehicle.rangeText).foregroundColor(.secondary).lineLimit(1)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Previews

#Preview(as: .systemLarge) {
    GhostBlueWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: VehicleEntity(
        id: .init(), displayName: "2025 IONIQ 5", vin: "test",
        fuelType: .electric, rangeText: "227 mi", batteryPercentage: 86.0,
        timestamp: Date(), backgroundColorName: "ghostBlue"
    ), configuration: VehicleWidgetIntent())
}

#Preview(as: .systemMedium) {
    GhostBlueWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: VehicleEntity(
        id: .init(), displayName: "2025 IONIQ 5", vin: "test",
        fuelType: .electric, rangeText: "227 mi", batteryPercentage: 86.0,
        timestamp: Date(), backgroundColorName: "ghostBlue"
    ), configuration: VehicleWidgetIntent())
}

#Preview("Medium – Charging", as: .systemMedium) {
    GhostBlueWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: {
        var v = VehicleEntity(
            id: .init(), displayName: "2025 IONIQ 5", vin: "test",
            fuelType: .electric, rangeText: "189 mi", batteryPercentage: 62.0,
            timestamp: Date(), backgroundColorName: "ghostBlue"
        )
        v.isCharging = true
        v.isPluggedIn = true
        v.chargeSpeedKW = 11.0
        v.chargeRemainingSeconds = 5400
        v.targetSOC = 80.0
        v.plugTypeLabel = "AC"
        return v
    }(), configuration: VehicleWidgetIntent())
}

#Preview("Large – Charging", as: .systemLarge) {
    GhostBlueWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: {
        var v = VehicleEntity(
            id: .init(), displayName: "2025 IONIQ 5", vin: "test",
            fuelType: .electric, rangeText: "189 mi", batteryPercentage: 62.0,
            timestamp: Date(), backgroundColorName: "ghostBlue"
        )
        v.isCharging = true
        v.isPluggedIn = true
        v.chargeSpeedKW = 150.0
        v.chargeRemainingSeconds = 1980
        v.targetSOC = 80.0
        v.plugTypeLabel = "DC"
        return v
    }(), configuration: VehicleWidgetIntent())
}

#Preview(as: .systemSmall) {
    GhostBlueWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: VehicleEntity(
        id: .init(), displayName: "EV6", vin: "test",
        fuelType: .electric, rangeText: "250 mi", batteryPercentage: 72.0,
        timestamp: Date(), backgroundColorName: "ghostBlue"
    ), configuration: VehicleWidgetIntent())
}

#Preview(as: .accessoryRectangular) {
    GhostBlueLockScreenWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: VehicleEntity(
        id: .init(), displayName: "IONIQ 5", vin: "test",
        fuelType: .electric, rangeText: "227 mi", batteryPercentage: 86.0,
        timestamp: Date(), backgroundColorName: "ghostBlue"
    ), configuration: VehicleWidgetIntent())
}

#Preview("Command Center – Gas", as: .systemMedium) {
    GhostBlueWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: {
        var vehicle = VehicleEntity(
            id: .init(), displayName: "Tucson", vin: "test",
            fuelType: .gas, rangeText: "310 mi", batteryPercentage: 64,
            timestamp: .now, backgroundColorName: "default"
        )
        vehicle.isLocked = false
        return vehicle
    }(), configuration: VehicleWidgetIntent())
}

#Preview("Range Ring – Inline", as: .accessoryInline) {
    GhostBlueLockScreenWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: {
        var vehicle = VehicleEntity(
            id: .init(), displayName: "IONIQ 5", vin: "test",
            fuelType: .electric, rangeText: "214 mi", batteryPercentage: 72,
            timestamp: .now, backgroundColorName: "ghostBlue"
        )
        vehicle.isCharging = true
        return vehicle
    }(), configuration: VehicleWidgetIntent())
}

#Preview("Command Center – No Vehicle", as: .systemSmall) {
    GhostBlueWidget()
} timeline: {
    VehicleWidgetEntry(date: .now, vehicle: nil, configuration: VehicleWidgetIntent())
}
