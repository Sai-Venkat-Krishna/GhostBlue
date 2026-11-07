//
//  VehicleHeroControlSurface.swift
//  GhostBlue
//
//  Makes the Home hero render the primary control surface, the way the
//  Rivian app does: live state is drawn onto the vehicle (doors, hood,
//  trunk, lock, charge port, climate) and hit regions on the render run
//  the same commands as LockButton / ClimateButton / ChargingButton.
//
//  Commands are slow round-trips, so a tapped region shows as in-flight
//  (its outline traces while the vehicle responds) and the drawn state
//  keeps coming from the vehicle's own status. The hero never shows a
//  state the vehicle has not confirmed. Commands run through the shared
//  `RemoteCommands` trackers, so the hero and the cards below it show the
//  same pending command.
//

import BetterBlueKit
import SwiftData
import SwiftUI

// MARK: - Hero control surface

/// The Home hero: the vehicle render with its live state drawn on it and
/// tap targets over the door, windshield and charge port.
struct VehicleHeroControlSurface: View {
    let bbVehicle: BBVehicle
    var heroSize: VehicleHeroSize = .standard
    var heroAlignment: VehicleHeroAlignment = .center

    @Environment(\.modelContext) private var modelContext
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Query private var allClimatePresets: [ClimatePreset]

    @State private var renderSize: CGSize?
    @State private var showingClimateSettings = false
    @State private var showingChargeLimits = false

    /// Side-view renders ship at this ratio; used until the real one loads
    /// and for the SF Symbol fallback.
    private static let fallbackAspect: CGFloat = 400.0 / 157.0

    // MARK: Vehicle state (never optimistic — always the confirmed status)

    private var isLocked: Bool {
        guard bbVehicle.modelContext != nil else { return false }
        return bbVehicle.lockStatus == .locked
    }

    private var evStatus: VehicleStatus.EVStatus? {
        guard bbVehicle.modelContext != nil else { return nil }
        return bbVehicle.evStatus
    }

    private var isClimateOn: Bool {
        guard bbVehicle.modelContext != nil else { return false }
        return bbVehicle.climateStatus?.airControlOn ?? false
    }

    private var isCharging: Bool { evStatus?.charging ?? false }
    private var isPluggedIn: Bool { evStatus?.pluggedIn ?? false }
    private var supportsCharging: Bool { evStatus != nil }

    private var doorOpen: VehicleStatus.DoorStatus? { bbVehicle.doorOpen }
    private var hoodOpen: Bool { bbVehicle.hoodOpen == true }
    private var trunkOpen: Bool { bbVehicle.trunkOpen == true }

    // MARK: Commands

    private var lockTracker: RemoteCommandTracker { RemoteCommands.tracker(.lock, for: bbVehicle) }
    private var climateTracker: RemoteCommandTracker { RemoteCommands.tracker(.climate, for: bbVehicle) }
    private var chargeTracker: RemoteCommandTracker { RemoteCommands.tracker(.charge, for: bbVehicle) }

    /// The one line of feedback shown under the render: the newest error
    /// wins, otherwise whatever command is still waiting on the vehicle.
    private var feedback: (text: String, isError: Bool)? {
        let trackers = [lockTracker, climateTracker, chargeTracker]
        if let error = trackers.lazy.compactMap(\.errorMessage).first { return (error, true) }
        if let running = trackers.first(where: \.isRunning) {
            return (running.statusText ?? "Sending command…", false)
        }
        return nil
    }

    // MARK: Climate presets (same resolution order as ClimateButton)

    private var filteredPresets: [ClimatePreset] {
        allClimatePresets.filter { $0.vehicle?.id == bbVehicle.id }
    }

    private var effectivePreset: ClimatePreset? {
        filteredPresets.first { $0.isSelected } ?? filteredPresets.first
    }

    // MARK: Body

    var body: some View {
        ZStack {
            Ellipse()
                .fill(.black.opacity(0.38))
                .frame(maxWidth: 220, maxHeight: 24)
                .blur(radius: 8)
                .offset(y: heroSize.imageHeight * 0.31)

            HomeVehicleImageView(
                modelName: bbVehicle.model,
                colorName: bbVehicle.vehicleImageColor.rawValue,
                onImageSize: { renderSize = $0 }
            )
            .frame(maxWidth: .infinity, alignment: heroAlignment.alignment)
            .frame(height: heroSize.imageHeight)
            .shadow(color: heroGlow, radius: isCharging ? 28 : 18, y: 8)
            .animation(.easeInOut(duration: 0.6), value: heroGlow)
            .overlay { controlOverlay }

            if isCharging {
                ChargingHeroFlowIndicator(tint: .green)
                    .frame(maxWidth: 180)
                    .offset(y: heroSize.imageHeight * 0.34)
                    .allowsHitTesting(false)
            }
        }
        .frame(maxWidth: .infinity)
        .overlay(alignment: .bottom) { feedbackStrip }
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: feedback?.text)
        .sheet(isPresented: $showingClimateSettings) {
            ClimateSettingsSheet(vehicle: bbVehicle)
        }
        .sheet(isPresented: $showingChargeLimits) {
            ChargeLimitSettingsSheet(vehicle: bbVehicle)
        }
    }

    private var heroGlow: Color {
        if doorOpen?.anyOpen == true || hoodOpen || trunkOpen
            || bbVehicle.tirePressureWarning?.hasWarning == true {
            return .orange.opacity(0.45)
        }
        if isCharging { return .green.opacity(0.48) }
        if isClimateOn { return .cyan.opacity(0.42) }
        return AppSettings.shared.accentColor.opacity(0.25)
    }

    // MARK: Overlay

    /// Laid out as an overlay so the hero's measured size — and therefore
    /// the surrounding `ViewThatFits` layout — is unchanged.
    private var controlOverlay: some View {
        GeometryReader { geometry in
            let rect = renderRect(in: geometry.size)

            ZStack(alignment: .topLeading) {
                ForEach(stateBadges, id: \.id) { badge in
                    HeroStateBadge(
                        systemImage: badge.systemImage,
                        tint: badge.tint,
                        diameter: badgeDiameter,
                        reduceMotion: reduceMotion
                    )
                    .heroAnchored(badge.position, in: rect, hitTarget: badgeDiameter)
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.4).combined(with: .opacity))
                    .accessibilityLabel(badge.label)
                }

                lockRegion
                    .heroAnchored(HeroAnchor.lock, in: rect, hitTarget: hitTarget)

                climateRegion
                    .heroAnchored(HeroAnchor.climate, in: rect, hitTarget: hitTarget)

                if supportsCharging {
                    chargeRegion
                        .heroAnchored(HeroAnchor.charge, in: rect, hitTarget: hitTarget)
                }
            }
            .animation(.spring(response: 0.4, dampingFraction: 0.8), value: stateBadges.map(\.id))
        }
    }

    // MARK: Regions

    private var lockRegion: some View {
        // While waiting, the glyph shows where the command is heading,
        // greyed, so nothing unconfirmed is drawn as fact.
        let showsLocked = lockTracker.isRunning ? lockTracker.tag == "lock" : isLocked
        return HeroControlPuck(
            systemImage: showsLocked ? "lock.fill" : "lock.open.fill",
            tint: isLocked ? .green : .orange,
            isLit: true,
            tracker: lockTracker,
            diameter: puckDiameter,
            reduceMotion: reduceMotion
        ) {
            RemoteCommands.toggleLock(bbVehicle, modelContext: modelContext)
        }
        .accessibilityLabel(isLocked ? "Locked" : "Unlocked")
        .accessibilityHint(isLocked ? "Double tap to unlock" : "Double tap to lock")
        .accessibilityAddTraits(.isButton)
    }

    private var climateRegion: some View {
        HeroControlPuck(
            systemImage: isClimateOn ? "fan" : "fan.slash",
            tint: isClimateOn ? .cyan : .secondary,
            isLit: isClimateOn,
            tracker: climateTracker,
            spinning: isClimateOn && !climateTracker.isRunning,
            diameter: puckDiameter,
            reduceMotion: reduceMotion
        ) {
            RemoteCommands.toggleClimate(
                bbVehicle,
                options: effectivePreset?.climateOptions,
                presetName: effectivePreset?.name,
                presetIcon: effectivePreset?.iconName,
                modelContext: modelContext
            )
        }
        .accessibilityLabel(isClimateOn ? "Climate on" : "Climate off")
        .accessibilityHint(isClimateOn ? "Double tap to stop climate" : "Double tap to start climate")
        .accessibilityAddTraits(.isButton)
        .contextMenu {
            ForEach(filteredPresets) { preset in
                Button {
                    RemoteCommands.setClimate(
                        true,
                        vehicle: bbVehicle,
                        options: preset.climateOptions,
                        presetName: preset.name,
                        presetIcon: preset.iconName,
                        modelContext: modelContext
                    )
                } label: {
                    Label("Start \(preset.name)", systemImage: preset.iconName)
                }
            }
            if !filteredPresets.isEmpty { Divider() }
            Button {
                showingClimateSettings = true
            } label: {
                Label("Climate Settings", systemImage: "gearshape.fill")
            }
        }
    }

    private var chargeRegion: some View {
        HeroControlPuck(
            image: isCharging ? Image(systemName: "bolt.fill") : bbVehicle.plugIcon(for: evStatus?.plugType),
            tint: chargeTint,
            isLit: isPluggedIn || isCharging,
            tracker: chargeTracker,
            pulsing: isCharging && !chargeTracker.isRunning,
            diameter: puckDiameter,
            reduceMotion: reduceMotion
        ) {
            RemoteCommands.toggleCharging(bbVehicle, modelContext: modelContext)
        }
        .disabled(!isPluggedIn && !isCharging)
        .opacity(!isPluggedIn && !isCharging ? 0.45 : 1)
        .accessibilityLabel(chargeAccessibilityLabel)
        .accessibilityHint(isCharging ? "Double tap to stop charging" : "Double tap to start charging")
        .accessibilityAddTraits(.isButton)
        .contextMenu {
            Button {
                showingChargeLimits = true
            } label: {
                Label("Charge Limits", systemImage: "battery.100percent")
            }
        }
    }

    private var chargeTint: Color {
        if isCharging { return .green }
        if isPluggedIn { return .blue }
        return .secondary
    }

    private var chargeAccessibilityLabel: String {
        if isCharging { return "Charging" }
        if isPluggedIn { return "Plugged in, ready to charge" }
        return "Charge port, not plugged in"
    }

    // MARK: State badges

    private struct StateBadge {
        let id: String
        let systemImage: String
        let label: String
        let tint: Color
        let position: CGPoint
    }

    private var stateBadges: [StateBadge] {
        var badges: [StateBadge] = []

        if hoodOpen {
            badges.append(
                StateBadge(
                    id: "hood",
                    systemImage: "car.side.front.open",
                    label: "Hood open",
                    tint: .orange,
                    position: HeroAnchor.hood
                )
            )
        }

        if trunkOpen {
            badges.append(
                StateBadge(
                    id: "trunk",
                    systemImage: "car.side.rear.open",
                    label: "Trunk open",
                    tint: .orange,
                    position: HeroAnchor.trunk
                )
            )
        }

        if let doorOpen, doorOpen.anyOpen {
            badges.append(
                StateBadge(
                    id: "doors",
                    systemImage: "door.left.hand.open",
                    label: "Doors open: \(doorOpen.openDoorsDescription)",
                    tint: .orange,
                    position: HeroAnchor.doors
                )
            )
        }

        return badges
    }

    // MARK: Feedback

    @ViewBuilder
    private var feedbackStrip: some View {
        if let feedback {
            Text(feedback.text)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(feedback.isError ? Color.red : Color.secondary)
                .lineLimit(1)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(.ultraThinMaterial, in: Capsule())
                .overlay { Capsule().stroke(Color.white.opacity(0.12), lineWidth: 0.5) }
                .contentTransition(.opacity)
                .transition(
                    reduceMotion
                        ? .opacity
                        : .opacity.combined(with: .move(edge: .bottom)).combined(with: .scale(scale: 0.9))
                )
                .allowsHitTesting(false)
                .accessibilityLabel(feedback.text)
        }
    }

    // MARK: Geometry

    private var aspect: CGFloat {
        guard let renderSize, renderSize.width > 0, renderSize.height > 0 else {
            return Self.fallbackAspect
        }
        return renderSize.width / renderSize.height
    }

    /// The rect the render actually occupies inside `size`, matching the
    /// aspect-fit image and the user's hero alignment.
    private func renderRect(in size: CGSize) -> CGRect {
        guard size.width > 0, size.height > 0 else { return .zero }

        let width = min(size.width, size.height * aspect)
        let height = width / aspect

        let originX: CGFloat = switch heroAlignment {
        case .leading: 0
        case .center: (size.width - width) / 2
        case .trailing: size.width - width
        }

        return CGRect(
            x: originX,
            y: (size.height - height) / 2,
            width: width,
            height: height
        )
    }

    private var puckDiameter: CGFloat {
        switch heroSize {
        case .compact: 30
        case .standard: 34
        case .large: 38
        }
    }

    private var badgeDiameter: CGFloat { puckDiameter * 0.72 }

    private var hitTarget: CGFloat { max(40, puckDiameter + 10) }
}

// MARK: - Anchors

/// Where each control sits on the side-view render, as a fraction of the
/// rendered image. All renders are the same left-side view with the front
/// of the car at the right, so one set of anchors fits every model.
private enum HeroAnchor {
    /// Side glass above the front door.
    static let climate = CGPoint(x: 0.40, y: 0.25)
    /// Front door, between the wheels.
    static let lock = CGPoint(x: 0.62, y: 0.55)
    /// Rear quarter panel, where the charge port lives.
    static let charge = CGPoint(x: 0.15, y: 0.44)
    /// Front clip, over the hood.
    static let hood = CGPoint(x: 0.88, y: 0.42)
    /// Rear hatch.
    static let trunk = CGPoint(x: 0.06, y: 0.34)
    /// Rear door.
    static let doors = CGPoint(x: 0.34, y: 0.56)
}

private extension View {
    /// Positions a hero control at a normalized anchor inside the render.
    func heroAnchored(_ anchor: CGPoint, in rect: CGRect, hitTarget: CGFloat) -> some View {
        frame(width: hitTarget, height: hitTarget)
            .contentShape(Circle())
            .position(
                x: rect.minX + rect.width * anchor.x,
                y: rect.minY + rect.height * anchor.y
            )
    }
}

// MARK: - Puck

/// A tappable region on the render: a glass puck carrying the live state
/// of one control. While the vehicle is responding its outline traces
/// (`commandProgressBorder`); it flashes on confirmation and turns red and
/// shakes on failure.
private struct HeroControlPuck: View {
    var image: Image
    let tint: Color
    let isLit: Bool
    let tracker: RemoteCommandTracker
    var spinning: Bool = false
    var pulsing: Bool = false
    let diameter: CGFloat
    let reduceMotion: Bool
    let action: () -> Void

    @State private var appSettings = AppSettings.shared

    init(
        systemImage: String,
        tint: Color,
        isLit: Bool,
        tracker: RemoteCommandTracker,
        spinning: Bool = false,
        pulsing: Bool = false,
        diameter: CGFloat,
        reduceMotion: Bool,
        action: @escaping () -> Void
    ) {
        self.init(
            image: Image(systemName: systemImage),
            tint: tint,
            isLit: isLit,
            tracker: tracker,
            spinning: spinning,
            pulsing: pulsing,
            diameter: diameter,
            reduceMotion: reduceMotion,
            action: action
        )
    }

    init(
        image: Image,
        tint: Color,
        isLit: Bool,
        tracker: RemoteCommandTracker,
        spinning: Bool = false,
        pulsing: Bool = false,
        diameter: CGFloat,
        reduceMotion: Bool,
        action: @escaping () -> Void
    ) {
        self.image = image
        self.tint = tint
        self.isLit = isLit
        self.tracker = tracker
        self.spinning = spinning
        self.pulsing = pulsing
        self.diameter = diameter
        self.reduceMotion = reduceMotion
        self.action = action
    }

    private var inProgress: Bool { tracker.isRunning }

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(.ultraThinMaterial)
                    .overlay {
                        Circle().fill(tint.opacity(isLit ? 0.22 : 0.08))
                    }
                    .overlay {
                        Circle().stroke(
                            tint.opacity(isLit ? 0.65 : 0.3),
                            lineWidth: isLit ? 1.4 : 0.8
                        )
                    }
                    .shadow(color: isLit ? tint.opacity(0.45) : .black.opacity(0.3), radius: 6)

                image
                    .font(.system(size: diameter * 0.44, weight: .semibold))
                    .foregroundStyle(inProgress ? Color.secondary : (isLit ? tint : Color.secondary))
                    .contentTransition(.symbolEffect(.replace))
                    .symbolEffect(.bounce, options: .nonRepeating, value: reduceMotion ? 0 : tracker.successCount)
                    .spin(spinning && !reduceMotion)
                    .pulse(pulsing && !reduceMotion)
            }
            .frame(width: diameter, height: diameter)
            // Drawn just outside the puck so the glass ring stays visible.
            .padding(4)
            .commandProgressBorder(
                tracker,
                shape: .circle,
                tint: appSettings.accentColor,
                successTint: tint,
                lineWidth: 2,
                showsWash: false
            )
            .padding(-4)
            .animation(.spring(response: 0.4, dampingFraction: 0.75), value: tint)
            .animation(.spring(response: 0.4, dampingFraction: 0.75), value: inProgress)
        }
        .buttonStyle(.plain)
        .bouncePress()
    }
}

// MARK: - State badge

/// A non-interactive warning drawn on the render (door, hood or trunk open).
private struct HeroStateBadge: View {
    let systemImage: String
    let tint: Color
    let diameter: CGFloat
    let reduceMotion: Bool

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: diameter * 0.62, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: diameter, height: diameter)
            .background(tint, in: Circle())
            .overlay { Circle().stroke(.white.opacity(0.65), lineWidth: 1) }
            .shadow(color: tint.opacity(0.6), radius: 6)
            .pulse(!reduceMotion, duration: 1.6)
            .allowsHitTesting(false)
    }
}

// MARK: - Charging flow

/// Bolt that travels along the rocker while the vehicle is charging.
struct ChargingHeroFlowIndicator: View {
    let tint: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var flowOffset: CGFloat = -58

    var body: some View {
        ZStack {
            Capsule()
                .stroke(tint.opacity(0.26), style: StrokeStyle(lineWidth: 2, dash: [4, 6]))

            Image(systemName: "bolt.fill")
                .font(.caption.bold())
                .foregroundStyle(tint)
                .shadow(color: tint.opacity(0.7), radius: 6)
                .offset(x: reduceMotion ? 0 : flowOffset)
        }
        .frame(height: 22)
        .clipShape(Capsule())
        .accessibilityHidden(true)
        .onAppear { restartFlow(reduceMotion: reduceMotion) }
        .onChange(of: reduceMotion) { _, enabled in
            restartFlow(reduceMotion: enabled)
        }
    }

    private func restartFlow(reduceMotion: Bool) {
        guard !reduceMotion else {
            flowOffset = 0
            return
        }
        flowOffset = -58
        withAnimation(.linear(duration: 1.45).repeatForever(autoreverses: false)) {
            flowOffset = 58
        }
    }
}
