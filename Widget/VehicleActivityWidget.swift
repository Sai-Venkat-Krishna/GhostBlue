import ActivityKit
import AppIntents
import BetterBlueKit
import SwiftUI
import WidgetKit

struct VehicleActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: VehicleActivityAttributes.self) { context in
            // Lock screen/banner UI
            if AppTheme.persisted == .rivian {
                RivianActivityContentView(context: context)
            } else {
                VehicleActivityContentView(context: context, isLockScreen: true)
            }
        } dynamicIsland: { context in
            if AppTheme.persisted == .rivian {
                return rivianIsland(context)
            }
            return DynamicIsland {
                // Expanded UI
                DynamicIslandExpandedRegion(.leading) {
                    if context.state.activityType == .debug {
                        Image(systemName: "ant")
                            .font(.headline)
                            .fontWeight(.semibold)
                            .foregroundColor(.orange)
                            .padding(.leading, 8)
                    } else if context.state.activityType == .climate {
                        Image(systemName: context.state.climatePresetIcon ?? "fan")
                            .font(.headline)
                            .fontWeight(.semibold)
                            .foregroundColor(.blue)
                            .padding(.leading, 8)
                    } else {
                        Text(formattedRange(for: context.state.status))
                            .font(.headline)
                            .fontWeight(.semibold)
                            .padding(.leading, 8)
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if context.state.activityType == .debug {
                        Text("#\(context.state.wakeupCount)")
                            .font(.headline)
                            .fontWeight(.semibold)
                            .padding(.trailing, 8)
                    } else if context.state.activityType == .climate,
                              context.state.status.climateStatus.temperature.isPlausibleForDisplay {
                        let temp = context.state.status.climateStatus.temperature
                        Text("\(Int(temp.value))\(temp.units.symbol)")
                            .font(.headline)
                            .fontWeight(.semibold)
                            .padding(.trailing, 8)
                    } else {
                        Text("\(batteryPercentage(for: context.state.status))%")
                            .font(.headline)
                            .fontWeight(.semibold)
                            .padding(.trailing, 8)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    DynamicIslandExpandedContentView(context: context)
                        .padding(.horizontal, 8)
                        .padding(.bottom, 8)
                }
            } compactLeading: {
                if context.state.activityType == .debug {
                    Image(systemName: "ant")
                        .font(.caption2)
                        .fontWeight(.medium)
                        .foregroundColor(.orange)
                } else if context.state.activityType == .climate {
                    Image(systemName: context.state.climatePresetIcon ?? "fan")
                        .font(.caption2)
                        .fontWeight(.medium)
                        .foregroundColor(.blue)
                } else {
                    Text(formattedRange(for: context.state.status))
                        .font(.caption2)
                        .fontWeight(.medium)
                }
            } compactTrailing: {
                if context.state.activityType == .debug {
                    Text("#\(context.state.wakeupCount)")
                        .font(.caption2)
                        .fontWeight(.medium)
                } else if context.state.activityType == .climate,
                          context.state.status.climateStatus.temperature.isPlausibleForDisplay {
                    let temp = context.state.status.climateStatus.temperature
                    Text("\(Int(temp.value))\(temp.units.symbol)")
                        .font(.caption2)
                        .fontWeight(.medium)
                } else {
                    Text("\(batteryPercentage(for: context.state.status))%")
                        .font(.caption2)
                        .fontWeight(.medium)
                }
            } minimal: {
                if context.state.activityType == .debug {
                    Image(systemName: "ant")
                        .font(.caption2)
                        .foregroundColor(.orange)
                } else if context.state.activityType == .climate {
                    Image(systemName: context.state.climatePresetIcon ?? "fan")
                        .font(.caption2)
                        .foregroundColor(.blue)
                } else {
                    Text("\(batteryPercentage(for: context.state.status))%")
                        .font(.caption2)
                }
            }
            .widgetURL(URL(string: "ghostblue://vehicle/\(context.attributes.vin)"))
            .keylineTint(Color(red: 0.0, green: 0.84, blue: 1.0))
        }
    }

    /// Dynamic Island in the Rivian theme's palette (green charge, blue climate, gold keyline).
    private func rivianIsland(_ context: ActivityViewContext<VehicleActivityAttributes>) -> DynamicIsland {
        DynamicIsland {
            DynamicIslandExpandedRegion(.leading) {
                HStack(spacing: 6) {
                    RivianIslandGlyph(context: context, font: .headline)
                    if context.state.activityType == .charging {
                        Text(formattedRange(for: context.state.status))
                            .font(.headline)
                            .fontWeight(.semibold)
                    }
                }
                .padding(.leading, 8)
            }
            DynamicIslandExpandedRegion(.trailing) {
                RivianIslandFigure(context: context, font: .headline)
                    .padding(.trailing, 8)
            }
            DynamicIslandExpandedRegion(.bottom) {
                RivianIslandExpandedContent(context: context)
                    .padding(.horizontal, 8)
                    .padding(.bottom, 8)
            }
        } compactLeading: {
            RivianIslandGlyph(context: context)
        } compactTrailing: {
            RivianIslandFigure(context: context)
        } minimal: {
            RivianIslandGlyph(context: context)
        }
        .widgetURL(URL(string: "ghostblue://vehicle/\(context.attributes.vin)"))
        .keylineTint(RVTokens.accent)
    }

    func formattedRange(for status: VehicleStatus) -> String {
        let preferredUnit = AppSettings.shared.preferredDistanceUnit
        if let evStatus = status.evStatus, evStatus.evRange.range.length > 0 {
            return evStatus.evRange.range.units.format(evStatus.evRange.range.length, to: preferredUnit)
        } else if let gasRange = status.gasRange, gasRange.range.length > 0 {
            return gasRange.range.units.format(gasRange.range.length, to: preferredUnit)
        }
        return "--"
    }

    func batteryPercentage(for status: VehicleStatus) -> Int {
        if let evStatus = status.evStatus {
            return Int(evStatus.evRange.percentage)
        } else if let gasRange = status.gasRange {
            return Int(gasRange.percentage)
        }
        return 0
    }
}

/// Content view for Dynamic Island expanded region
struct DynamicIslandExpandedContentView: View {
    let context: ActivityViewContext<VehicleActivityAttributes>

    private var evStatus: VehicleStatus.EVStatus? {
        context.state.status.evStatus
    }

    private var batteryPercentage: Int {
        Int(evStatus?.evRange.percentage ?? 0)
    }

    private var chargeSpeed: String? {
        guard let evStatus, evStatus.chargeSpeed > 0 else { return nil }
        return String(format: "%.1f kW", evStatus.chargeSpeed)
    }

    private var chargeTimeRemaining: String? {
        guard let evStatus else { return nil }
        let duration = evStatus.chargeTime
        guard duration > .seconds(0) else { return nil }
        let formattedTime = duration.formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
        if let targetSOC = evStatus.currentTargetSOC {
            return "\(formattedTime) to \(Int(targetSOC))%"
        }
        return formattedTime
    }

    private var chargeFinishText: String? {
        guard let evStatus,
              let finish = chargeFinishDate(
                  remainingSeconds: evStatus.chargeTime.components.seconds,
                  reportedAt: context.state.status.lastUpdated
              ) else { return nil }
        return formatChargeFinishTime(finish)
    }

    var body: some View {
        VStack(spacing: 8) {
            // Progress bar for charging
            if context.state.activityType == .charging {
                EVChargingProgressView(
                    batteryPercentage: batteryPercentage,
                    isCharging: true,
                    chargeSpeed: chargeSpeed,
                    chargeTimeRemaining: chargeTimeRemaining,
                    chargeFinishText: chargeFinishText,
                    targetSOC: evStatus?.currentTargetSOC,
                    showHeader: false
                )
            }

            // Climate status
            if context.state.activityType == .climate {
                HStack {
                    if let presetName = context.state.climatePresetName {
                        Text("\(presetName) Climate Preset Active")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                    } else {
                        Text(context.state.activityType.message(for: context.state.activityState))
                            .font(.subheadline)
                            .fontWeight(.semibold)
                    }
                    Spacer()
                }
            }

            // Debug status
            if context.state.activityType == .debug {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Debug Live Activity")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                        if let lastWakeup = context.state.lastWakeupTime {
                            Text("Last wakeup: \(lastWakeup, style: .time)")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        } else {
                            Text("No wakeups yet")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                    Spacer()
                }
            }

            // Footer with refresh status and action buttons
            HStack {
                if context.state.isRefreshing {
                    ProgressView()
                        .scaleEffect(0.6)
                    Text("Refreshing...")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                } else {
                    Text("\(context.attributes.vehicleName) • Updated \(context.state.status.lastUpdated, style: .time)")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }

                Spacer()

                // Stop button (only for climate and debug, not charging)
                if context.state.activityType != .charging {
                    Button(intent: StopLiveActivityIntent(vin: context.attributes.vin, activityType: context.state.activityType)) {
                        Image(systemName: context.state.activityType == .climate ? "power" : "stop.fill")
                            .font(.caption2)
                            .foregroundColor(.red)
                    }
                    .buttonStyle(.plain)
                    .disabled(context.state.isRefreshing)
                }
            }
        }
    }

}

#Preview("Charging", as: .content, using: VehicleActivityAttributes(vehicleName: "My Ioniq 5", vin: "VIN123", vehicleId: UUID())) {
    VehicleActivityWidget()
} contentStates: {
    VehicleActivityAttributes.ContentState(
        status: VehicleStatus(
            vin: "VIN123",
            gasRange: nil,
            evStatus: VehicleStatus.EVStatus(
                charging: true,
                chargeSpeed: 7.2,
                evRange: VehicleStatus.FuelRange(
                    range: Distance(length: 200, units: .miles),
                    percentage: 65
                ),
                plugType: .acCharger,
                chargeTime: .seconds(3600),
                targetSocAC: 80,
                targetSocDC: 80
            ),
            location: VehicleStatus.Location(latitude: 0, longitude: 0),
            lockStatus: .locked,
            climateStatus: VehicleStatus.ClimateStatus(
                defrostOn: false,
                airControlOn: false,
                steeringWheelHeatingOn: false,
                temperature: Temperature(units: 1, value: "72")
            ),
            odometer: Distance(length: 10000, units: .miles),
            syncDate: Date()
        ),
        activityType: .charging
    )
}

#Preview("Climate", as: .content, using: VehicleActivityAttributes(vehicleName: "My EV6", vin: "VIN456", vehicleId: UUID())) {
    VehicleActivityWidget()
} contentStates: {
    VehicleActivityAttributes.ContentState(
        status: VehicleStatus(
            vin: "VIN456",
            gasRange: nil,
            evStatus: VehicleStatus.EVStatus(
                charging: false,
                chargeSpeed: 0,
                evRange: VehicleStatus.FuelRange(
                    range: Distance(length: 250, units: .miles),
                    percentage: 80
                ),
                plugType: .unplugged,
                chargeTime: .seconds(0)
            ),
            location: VehicleStatus.Location(latitude: 0, longitude: 0),
            lockStatus: .locked,
            climateStatus: VehicleStatus.ClimateStatus(
                defrostOn: false,
                airControlOn: true,
                steeringWheelHeatingOn: false,
                temperature: Temperature(units: 1, value: "70")
            ),
            odometer: Distance(length: 5000, units: .miles),
            syncDate: Date()
        ),
        activityType: .climate,
        climatePresetName: "Warm Up",
        climatePresetIcon: "sun.max"
    )
}

#Preview("Dynamic Island", as: .dynamicIsland(.expanded), using: VehicleActivityAttributes(vehicleName: "My Ioniq 5", vin: "VIN123", vehicleId: UUID())) {
    VehicleActivityWidget()
} contentStates: {
    VehicleActivityAttributes.ContentState(
        status: VehicleStatus(
            vin: "VIN123",
            gasRange: nil,
            evStatus: VehicleStatus.EVStatus(
                charging: true,
                chargeSpeed: 150.0,
                evRange: VehicleStatus.FuelRange(
                    range: Distance(length: 120, units: .miles),
                    percentage: 45
                ),
                plugType: .dcCharger,
                chargeTime: .seconds(1800),
                targetSocAC: 80,
                targetSocDC: 80
            ),
            location: VehicleStatus.Location(latitude: 0, longitude: 0),
            lockStatus: .locked,
            climateStatus: VehicleStatus.ClimateStatus(
                defrostOn: false,
                airControlOn: false,
                steeringWheelHeatingOn: false,
                temperature: Temperature(units: 1, value: "72")
            ),
            odometer: Distance(length: 10000, units: .miles),
            syncDate: Date()
        ),
        activityType: .charging
    )
}

#Preview("Climate Dynamic Island", as: .dynamicIsland(.expanded), using: VehicleActivityAttributes(vehicleName: "My EV6", vin: "VIN456", vehicleId: UUID())) {
    VehicleActivityWidget()
} contentStates: {
    VehicleActivityAttributes.ContentState(
        status: VehicleStatus(
            vin: "VIN456",
            gasRange: nil,
            evStatus: VehicleStatus.EVStatus(
                charging: false,
                chargeSpeed: 0,
                evRange: VehicleStatus.FuelRange(
                    range: Distance(length: 250, units: .miles),
                    percentage: 80
                ),
                plugType: .unplugged,
                chargeTime: .seconds(0)
            ),
            location: VehicleStatus.Location(latitude: 0, longitude: 0),
            lockStatus: .locked,
            climateStatus: VehicleStatus.ClimateStatus(
                defrostOn: false,
                airControlOn: true,
                steeringWheelHeatingOn: false,
                temperature: Temperature(units: 1, value: "70")
            ),
            odometer: Distance(length: 5000, units: .miles),
            syncDate: Date()
        ),
        activityType: .climate,
        climatePresetName: "Warm Up",
        climatePresetIcon: "sun.max"
    )
}

/// Best-effort lookup of the cached car render for a Live Activity. The
/// disk cache (filled by the widget timeline provider) is keyed by model,
/// but activity attributes only carry the display name — so match by
/// key-in-name, falling back to the only cached image when the garage has
/// just one car.
private func cachedCarUIImage(for vin: String, vehicleName: String) -> UIImage? {
    let fileManager = FileManager.default
    guard let group = fileManager.containerURL(
        forSecurityApplicationGroupIdentifier: "group.com.saivenkatkrishna.ghostblue.shared"
    ) else { return nil }

    let directory = group.appendingPathComponent("CarImages", isDirectory: true)

    if let hint = VehicleAppearanceStore.hint(for: vin) {
        let normalizedModel = hint.model
            .lowercased()
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")
        let imageKeys = [
            "ioniq5n", "ioniq5", "ioniq6", "ioniq9", "ioniq",
            "ev6", "ev9", "kona", "niro"
        ]
        if let modelKey = imageKeys.first(where: { normalizedModel.contains($0) }) {
            let selected = directory.appendingPathComponent(
                "\(modelKey)_\(hint.color).png"
            )
            if let image = UIImage(contentsOfFile: selected.path) {
                return image
            }

            let white = directory.appendingPathComponent("\(modelKey)_white.png")
            if let image = UIImage(contentsOfFile: white.path) {
                return image
            }
        }
    }

    guard let files = try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil),
          !files.isEmpty else { return nil }

    let normalized = vehicleName.lowercased().replacingOccurrences(of: " ", with: "")
    let match = files.first { file in
        let key = file.deletingPathExtension().lastPathComponent
            .split(separator: "_").first.map(String.init) ?? ""
        return !key.isEmpty && normalized.contains(key)
    }

    guard let chosen = match ?? (files.count == 1 ? files.first : nil) else { return nil }
    return UIImage(contentsOfFile: chosen.path)
}

/// Live Activities cannot rely on a network fetch. Prefer the cached vehicle
/// render, but always show the compact vector silhouette on a fresh install
/// or when the app group cache is unavailable.
struct LiveActivityCarThumbnail: View {
    let vin: String
    let vehicleName: String
    var size = CGSize(width: 38, height: 22)

    var body: some View {
        Group {
            if let image = cachedCarUIImage(for: vin, vehicleName: vehicleName) {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
            } else {
                EVCarSilhouette()
            }
        }
        .frame(width: size.width, height: size.height)
    }
}

/// Bold lock-screen charging card: cockpit dial on the left, live charge
/// stats and a target-marked progress bar on the right.
private struct ChargingActivityCard: View {
    let batteryPercentage: Int
    let formattedRange: String
    let chargeSpeed: String?
    let chargeTimeRemaining: String?
    let chargeFinishText: String?
    let targetSOC: Double?

    var body: some View {
        HStack(spacing: 14) {
            CockpitDial(
                percentage: Double(batteryPercentage),
                charging: true,
                isElectric: true
            )
            .frame(width: 82, height: 82)

            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    Text("CHARGING")
                        .font(.system(size: 10, weight: .heavy))
                        .tracking(1.8)
                        .foregroundColor(GhostBlue.cyan)

                    Spacer(minLength: 4)

                    if let chargeSpeed {
                        Text(chargeSpeed)
                            .font(.system(size: 15, weight: .heavy, design: .rounded))
                            .foregroundColor(.white)
                    }
                }

                HStack(spacing: 4) {
                    Text(formattedRange)
                        .font(.system(size: 20, weight: .heavy, design: .rounded))
                        .foregroundColor(.white)
                    Text("est. range")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(GhostBlue.dimWhite)
                }

                ActivityChargeBar(
                    percentage: Double(batteryPercentage),
                    targetPercentage: targetSOC
                )

                HStack(spacing: 4) {
                    if let chargeTimeRemaining {
                        Image(systemName: "clock")
                            .font(.system(size: 9))
                        Text(chargeTimeRemaining)
                            .font(.system(size: 11, weight: .semibold, design: .rounded))
                    }
                    if let chargeFinishText {
                        Text("· \(chargeFinishText)")
                            .font(.system(size: 11, weight: .medium, design: .rounded))
                    }
                    Spacer(minLength: 0)
                }
                .foregroundColor(GhostBlue.dimWhite)
            }
        }
    }
}

/// Slim charge-progress capsule with a target-SOC marker.
private struct ActivityChargeBar: View {
    let percentage: Double
    let targetPercentage: Double?

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.white.opacity(0.12))

                Capsule()
                    .fill(
                        LinearGradient(
                            colors: [GhostBlue.electric, GhostBlue.cyan],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .frame(width: max(6, geo.size.width * min(percentage, 100) / 100))
                    .shadow(color: GhostBlue.cyan.opacity(0.6), radius: 4)

                if let target = targetPercentage, target > 0, target < 100 {
                    Rectangle()
                        .fill(Color.white.opacity(0.7))
                        .frame(width: 2)
                        .offset(x: geo.size.width * target / 100)
                }
            }
        }
        .frame(height: 6)
    }
}

struct VehicleActivityContentView: View {
    let context: ActivityViewContext<VehicleActivityAttributes>
    let isLockScreen: Bool

    private var evStatus: VehicleStatus.EVStatus? {
        context.state.status.evStatus
    }

    private var batteryPercentage: Int {
        Int(evStatus?.evRange.percentage ?? 0)
    }

    private var chargeSpeed: String? {
        guard let evStatus, evStatus.chargeSpeed > 0 else { return nil }
        return String(format: "%.1f kW", evStatus.chargeSpeed)
    }

    private var chargeTimeRemaining: String? {
        guard let evStatus else { return nil }
        let duration = evStatus.chargeTime
        guard duration > .seconds(0) else { return nil }
        let formattedTime = duration.formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
        if let targetSOC = evStatus.currentTargetSOC {
            return "\(formattedTime) to \(Int(targetSOC))%"
        }
        return formattedTime
    }

    private var chargeFinishText: String? {
        guard let evStatus,
              let finish = chargeFinishDate(
                  remainingSeconds: evStatus.chargeTime.components.seconds,
                  reportedAt: context.state.status.lastUpdated
              ) else { return nil }
        return formatChargeFinishTime(finish)
    }

    private var formattedRange: String {
        guard let evStatus, evStatus.evRange.range.length > 0 else { return "--" }
        return evStatus.evRange.range.units.format(evStatus.evRange.range.length, to: AppSettings.shared.preferredDistanceUnit)
    }

    var body: some View {
        VStack(spacing: 12) {
            // Charging activity: bold cockpit-dial layout
            if context.state.activityType == .charging, isLockScreen {
                ChargingActivityCard(
                    batteryPercentage: batteryPercentage,
                    formattedRange: formattedRange,
                    chargeSpeed: chargeSpeed,
                    chargeTimeRemaining: chargeTimeRemaining,
                    chargeFinishText: chargeFinishText,
                    targetSOC: evStatus?.currentTargetSOC
                )
            }

            // Climate status (if climate is running)
            if context.state.activityType == .climate, isLockScreen {
                HStack(spacing: 12) {
                    Image(systemName: context.state.climatePresetIcon ?? "fan")
                        .font(.title2)
                        .foregroundColor(.blue)

                    if let presetName = context.state.climatePresetName {
                        Text("\(presetName) Climate Preset Active")
                            .font(.headline)
                            .fontWeight(.semibold)
                    } else {
                        Text(context.state.activityType.message(for: context.state.activityState))
                            .font(.headline)
                            .fontWeight(.semibold)
                    }

                    Spacer()

                    if context.state.status.climateStatus.temperature.isPlausibleForDisplay {
                        let temp = context.state.status.climateStatus.temperature
                        Text("\(Int(temp.value))\(temp.units.symbol)")
                            .font(.title3)
                            .fontWeight(.semibold)
                    }
                }
            }

            // Debug status (if debug is running)
            if context.state.activityType == .debug, isLockScreen {
                HStack(spacing: 12) {
                    Image(systemName: "ant")
                        .font(.title2)
                        .foregroundColor(.orange)

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Debug Live Activity")
                            .font(.headline)
                            .fontWeight(.semibold)
                        if let lastWakeup = context.state.lastWakeupTime {
                            Text("Last wakeup: \(lastWakeup, style: .time)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        } else {
                            Text("Waiting for wakeup...")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    Spacer()

                    Text("#\(context.state.wakeupCount)")
                        .font(.title)
                        .fontWeight(.bold)
                        .foregroundColor(.orange)
                }
            }

            // Footer with car thumbnail and action buttons
            HStack {
                if isLockScreen {
                    LiveActivityCarThumbnail(
                        vin: context.attributes.vin,
                        vehicleName: context.attributes.vehicleName
                    )
                }

                if context.state.isRefreshing {
                    ProgressView()
                        .scaleEffect(0.7)
                    Text("Refreshing...")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                } else {
                    Text("\(context.attributes.vehicleName) • Updated \(context.state.status.lastUpdated, style: .time)")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }

                Spacer()

                // Stop button (only for climate and debug, not charging)
                if context.state.activityType != .charging {
                    Button(intent: StopLiveActivityIntent(vin: context.attributes.vin, activityType: context.state.activityType)) {
                        HStack(spacing: 4) {
                            Image(systemName: context.state.activityType == .climate ? "power" : "stop.fill")
                            Text("Stop")
                        }
                        .font(.caption2)
                        .foregroundColor(.red)
                    }
                    .buttonStyle(.plain)
                    .disabled(context.state.isRefreshing)
                }
            }
        }
        .padding(isLockScreen ? 16 : 0)
        .activityBackgroundTint(Color(red: 0.04, green: 0.05, blue: 0.20).opacity(0.92))
        .activitySystemActionForegroundColor(Color(red: 0.0, green: 0.84, blue: 1.0))
    }
}
