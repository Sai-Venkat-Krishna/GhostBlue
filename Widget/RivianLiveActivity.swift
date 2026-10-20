//
//  RivianLiveActivity.swift
//  GhostBlueWidget
//
//  Live Activity layouts used when the app's Rivian theme is on: an
//  adaptive lock-screen card with a big charge figure and green bar (or a
//  blue climate chip), and Dynamic Island content in the same palette.
//  Classic themes keep VehicleActivityContentView.
//

import ActivityKit
import AppIntents
import BetterBlueKit
import SwiftUI
import WidgetKit

/// Formatting shared by the lock-screen card and the Dynamic Island.
@MainActor
struct RivianActivityFigures {
    let context: ActivityViewContext<VehicleActivityAttributes>

    private var status: VehicleStatus { context.state.status }
    private var evStatus: VehicleStatus.EVStatus? { status.evStatus }

    var percentage: Int {
        if let evStatus { return Int(evStatus.evRange.percentage) }
        if let gasRange = status.gasRange { return Int(gasRange.percentage) }
        return 0
    }

    var range: String {
        let unit = AppSettings.shared.preferredDistanceUnit
        if let evStatus, evStatus.evRange.range.length > 0 {
            return evStatus.evRange.range.units.format(evStatus.evRange.range.length, to: unit)
        }
        if let gasRange = status.gasRange, gasRange.range.length > 0 {
            return gasRange.range.units.format(gasRange.range.length, to: unit)
        }
        return "--"
    }

    var chargeSpeed: String? {
        guard let evStatus, evStatus.chargeSpeed > 0 else { return nil }
        return String(format: "%.1f kW", evStatus.chargeSpeed)
    }

    var target: Double? { evStatus?.currentTargetSOC }

    var timeRemaining: String? {
        guard let evStatus, evStatus.chargeTime > .seconds(0) else { return nil }
        let time = evStatus.chargeTime.formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
        if let target { return "\(time) to \(Int(target))%" }
        return time
    }

    var finish: String? {
        guard let evStatus,
              let date = chargeFinishDate(
                  remainingSeconds: evStatus.chargeTime.components.seconds,
                  reportedAt: status.lastUpdated
              ) else { return nil }
        return formatChargeFinishTime(date)
    }

    var temperature: String? {
        let temp = status.climateStatus.temperature
        guard temp.isPlausibleForDisplay else { return nil }
        return "\(Int(temp.value))\(temp.units.symbol)"
    }

    var climateTitle: String {
        if let preset = context.state.climatePresetName { return "\(preset) preset" }
        return context.state.activityType.message(for: context.state.activityState)
    }
}

/// Charge bar with a target tick.
private struct RivianActivityBar: View {
    let percentage: Int
    let target: Double?

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            ZStack(alignment: .leading) {
                Capsule().fill(RVTokens.surfaceMuted)
                Capsule()
                    .fill(RVTokens.battery(Double(percentage)))
                    .frame(width: max(8, width * min(Double(percentage), 100) / 100))
                if let target, target > 0, target < 100 {
                    Capsule()
                        .fill(RVTokens.ink.opacity(0.55))
                        .frame(width: 2, height: 12)
                        .offset(x: width * target / 100 - 1)
                }
            }
        }
        .frame(height: 8)
    }
}

private struct RivianActivityChip: View {
    let systemImage: String
    let tint: Color
    var size: CGFloat = 40

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Circle().fill(tint))
    }
}

private struct RivianStopButton: View {
    let context: ActivityViewContext<VehicleActivityAttributes>

    var body: some View {
        Button(intent: StopLiveActivityIntent(
            vin: context.attributes.vin,
            activityType: context.state.activityType
        )) {
            Label("Stop", systemImage: context.state.activityType == .climate ? "power" : "stop.fill")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(RVTokens.danger))
        }
        .buttonStyle(.plain)
        .disabled(context.state.isRefreshing)
    }
}

// MARK: - Lock screen / banner

struct RivianActivityContentView: View {
    let context: ActivityViewContext<VehicleActivityAttributes>
    @Environment(\.colorScheme) private var colorScheme

    private var figures: RivianActivityFigures { RivianActivityFigures(context: context) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            switch context.state.activityType {
            case .charging: charging
            case .climate: climate
            case .debug: debug
            case .none: EmptyView()
            }
            footer
        }
        .padding(16)
        // Match the card to the appearance the content renders in.
        .activityBackgroundTint(RVTokens.surface(for: colorScheme))
        .activitySystemActionForegroundColor(RVTokens.ink(for: colorScheme))
    }

    private var charging: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(figures.chargeSpeed.map { "CHARGING · \($0)" } ?? "CHARGING")
                        .font(.system(size: 11, weight: .bold))
                        .tracking(1.2)
                        .foregroundStyle(RVTokens.energy)
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        HStack(alignment: .firstTextBaseline, spacing: 1) {
                            Text("\(figures.percentage)")
                                .font(.system(size: 40, weight: .bold, design: .rounded))
                            Text("%")
                                .font(.system(size: 18, weight: .semibold, design: .rounded))
                                .foregroundStyle(RVTokens.inkSecondary)
                        }
                        .foregroundStyle(RVTokens.ink)
                        Text(figures.range)
                            .font(.system(size: 15, weight: .semibold, design: .rounded))
                            .foregroundStyle(RVTokens.inkSecondary)
                    }
                    .lineLimit(1)
                }
                Spacer(minLength: 8)
                LiveActivityCarThumbnail(
                    vin: context.attributes.vin,
                    vehicleName: context.attributes.vehicleName,
                    size: CGSize(width: 96, height: 50)
                )
            }

            RivianActivityBar(percentage: figures.percentage, target: figures.target)

            HStack(spacing: 4) {
                if let time = figures.timeRemaining {
                    Image(systemName: "clock")
                    Text(time)
                }
                if let finish = figures.finish {
                    Text("· Full by \(finish)")
                }
                Spacer(minLength: 0)
            }
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(RVTokens.inkSecondary)
            .lineLimit(1)
        }
    }

    private var climate: some View {
        HStack(spacing: 12) {
            RivianActivityChip(systemImage: context.state.climatePresetIcon ?? "fan", tint: RVTokens.climate)
            VStack(alignment: .leading, spacing: 2) {
                Text("CLIMATE")
                    .font(.system(size: 11, weight: .bold))
                    .tracking(1.2)
                    .foregroundStyle(RVTokens.climate)
                Text(figures.climateTitle)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(RVTokens.ink)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if let temperature = figures.temperature {
                Text(temperature)
                    .font(.system(size: 30, weight: .bold, design: .rounded))
                    .foregroundStyle(RVTokens.ink)
            }
        }
    }

    private var debug: some View {
        HStack(spacing: 12) {
            RivianActivityChip(systemImage: "ant", tint: RVTokens.service)
            VStack(alignment: .leading, spacing: 2) {
                Text("Debug Live Activity")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(RVTokens.ink)
                Group {
                    if let lastWakeup = context.state.lastWakeupTime {
                        Text("Last wakeup: \(lastWakeup, style: .time)")
                    } else {
                        Text("Waiting for wakeup...")
                    }
                }
                .font(.system(size: 12))
                .foregroundStyle(RVTokens.inkSecondary)
            }
            Spacer(minLength: 8)
            Text("#\(context.state.wakeupCount)")
                .font(.system(size: 28, weight: .bold, design: .rounded))
                .foregroundStyle(RVTokens.service)
        }
    }

    private var footer: some View {
        HStack(spacing: 8) {
            if context.state.activityType != .charging {
                LiveActivityCarThumbnail(
                    vin: context.attributes.vin,
                    vehicleName: context.attributes.vehicleName
                )
            }
            if context.state.isRefreshing {
                ProgressView()
                    .scaleEffect(0.7)
                Text("Refreshing...")
            } else {
                Text("\(context.attributes.vehicleName) · Updated \(context.state.status.lastUpdated, style: .time)")
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if context.state.activityType != .charging {
                RivianStopButton(context: context)
            }
        }
        .font(.system(size: 11, weight: .medium))
        .foregroundStyle(RVTokens.inkSecondary)
    }
}

// MARK: - Dynamic Island (always dark)

struct RivianIslandExpandedContent: View {
    let context: ActivityViewContext<VehicleActivityAttributes>

    private var figures: RivianActivityFigures { RivianActivityFigures(context: context) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch context.state.activityType {
            case .charging:
                RivianActivityBar(percentage: figures.percentage, target: figures.target)
                HStack(spacing: 4) {
                    if let speed = figures.chargeSpeed {
                        Text(speed).foregroundStyle(RVTokens.energy)
                    }
                    if let time = figures.timeRemaining {
                        Text("· \(time)")
                    }
                    if let finish = figures.finish {
                        Text("· Full by \(finish)")
                    }
                    Spacer(minLength: 0)
                }
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(RVTokens.inkSecondary)
                .lineLimit(1)
            case .climate:
                Text(figures.climateTitle)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(RVTokens.ink)
            case .debug:
                Text(context.state.lastWakeupTime.map { "Last wakeup \($0.formatted(date: .omitted, time: .shortened))" }
                    ?? "No wakeups yet")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(RVTokens.ink)
            case .none:
                EmptyView()
            }

            HStack(spacing: 6) {
                if context.state.isRefreshing {
                    ProgressView().scaleEffect(0.6)
                    Text("Refreshing...")
                } else {
                    Text("\(context.attributes.vehicleName) · Updated \(context.state.status.lastUpdated, style: .time)")
                        .lineLimit(1)
                }
                Spacer(minLength: 4)
                if context.state.activityType != .charging {
                    RivianStopButton(context: context)
                }
            }
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(RVTokens.inkSecondary)
        }
    }
}

/// Leading/compact glyph for the island: green bolt, blue climate icon or orange debug ant.
struct RivianIslandGlyph: View {
    let context: ActivityViewContext<VehicleActivityAttributes>
    var font: Font = .caption2

    var body: some View {
        switch context.state.activityType {
        case .climate:
            Image(systemName: context.state.climatePresetIcon ?? "fan")
                .font(font.weight(.semibold))
                .foregroundStyle(RVTokens.climate)
        case .debug:
            Image(systemName: "ant")
                .font(font.weight(.semibold))
                .foregroundStyle(RVTokens.service)
        default:
            Image(systemName: "bolt.fill")
                .font(font.weight(.semibold))
                .foregroundStyle(RVTokens.energy)
        }
    }
}

/// Trailing figure for the island: temperature for climate, wakeups for debug, charge otherwise.
struct RivianIslandFigure: View {
    let context: ActivityViewContext<VehicleActivityAttributes>
    var font: Font = .caption2

    private var figures: RivianActivityFigures { RivianActivityFigures(context: context) }

    var body: some View {
        Group {
            switch context.state.activityType {
            case .climate:
                Text(figures.temperature ?? "--")
            case .debug:
                Text("#\(context.state.wakeupCount)")
            default:
                Text("\(figures.percentage)%").foregroundStyle(RVTokens.energy)
            }
        }
        .font(font.weight(.semibold))
        .monospacedDigit()
    }
}
