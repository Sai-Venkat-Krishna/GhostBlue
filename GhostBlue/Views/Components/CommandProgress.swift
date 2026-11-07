//
//  CommandProgress.swift
//  GhostBlue
//
//  Shared feedback for remote commands (lock, climate, charge, charge
//  limits).
//
//  `RemoteCommandTracker` runs one command and reports where it is: sending,
//  waiting for the vehicle, confirmed or failed. There is one tracker per
//  vehicle and command, so every surface that can send Lock (the hero puck,
//  the Lock card, the Rivian buttons, the Controls sheet) shows the same
//  pending state, and a second tap anywhere cancels the same command.
//
//  `commandProgressBorder` draws that state on a button's outline: a stroke
//  traces around it while the vehicle responds, paced to the real
//  `waitForStatusChange` delays, closes and flashes when the vehicle
//  confirms, and turns red with a shake when the command fails.
//

import BetterBlueKit
import SwiftData
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Tracker

enum RemoteCommandKind: String, Sendable {
    case lock
    case climate
    case charge
    case chargeLimits
}

@MainActor
@Observable
final class RemoteCommandTracker {
    enum Phase: Equatable {
        case idle
        /// The request is on its way to the vehicle service.
        case sending
        /// The service accepted it; waiting for the vehicle to report back.
        case waiting(BBVehicle.StatusWaitStep)
        /// The vehicle reported the new state.
        case succeeded
        /// The command, or confirming it, failed.
        case failed(String)
    }

    private(set) var phase: Phase = .idle
    /// Which way the running (or last) command goes, e.g. "lock" or
    /// "unlock". Lets a pair of buttons show the border on the one tapped.
    private(set) var tag: String?
    /// Bumped on every run, so views can restart per-run animation state.
    private(set) var runID = 0
    /// Bumped on success and failure, for one-shot effects such as the
    /// symbol bounce and the failure shake.
    private(set) var successCount = 0
    private(set) var failureCount = 0
    /// A second tap asked to stop; the command stays "running" until its
    /// task has actually ended, so a new one can't overlap it.
    private(set) var isCancelling = false

    private var task: Task<Void, Never>?
    private var settleTask: Task<Void, Never>?

    var isRunning: Bool {
        switch phase {
        case .sending, .waiting: true
        default: false
        }
    }

    var didSucceed: Bool { phase == .succeeded }

    var errorMessage: String? {
        if case let .failed(message) = phase { return message }
        return nil
    }

    /// One short line for the current phase, or nil when idle.
    var statusText: String? {
        switch phase {
        case .idle:
            nil
        case .sending where isCancelling, .waiting where isCancelling:
            "Canceling…"
        case .sending:
            "Sending command…"
        case let .waiting(step):
            switch step {
            case .commandSent:
                "Waiting for vehicle…"
            case let .checking(attempt, total):
                attempt == 1 ? "Checking vehicle…" : "Checking again (\(attempt)/\(total))…"
            case let .retrying(_, attempt, total):
                "No change yet, retrying (\(attempt)/\(total))…"
            }
        case .succeeded:
            "Confirmed by vehicle"
        case let .failed(message):
            message
        }
    }

    /// Starts `operation` unless a command is already running. The
    /// operation reports its wait through `report(_:)`, normally by passing
    /// it as `waitForStatusChange`'s `stepUpdater`.
    func run(
        tag: String,
        announcement: String,
        operation: @escaping @MainActor (RemoteCommandTracker) async throws -> Void
    ) {
        guard !isRunning else { return }
        settleTask?.cancel()
        self.tag = tag
        isCancelling = false
        runID += 1
        phase = .sending

        task = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await operation(self)
                try Task.checkCancellation()
                finish(.succeeded, announcement: announcement)
            } catch is CancellationError {
                phase = .idle
            } catch {
                if Task.isCancelled {
                    phase = .idle
                } else {
                    let message = (error as? APIError)?.message ?? error.localizedDescription
                    finish(.failed(message), announcement: message)
                }
            }
            isCancelling = false
            task = nil
        }
    }

    /// Advances the waiting phase. Ignored once the command has finished
    /// or is being cancelled.
    func report(_ step: BBVehicle.StatusWaitStep) {
        guard isRunning, !isCancelling else { return }
        phase = .waiting(step)
    }

    /// Cancels the running command (a second tap). It settles to idle,
    /// with nothing reported, once its task has stopped.
    func cancel() {
        guard isRunning, let task else { return }
        isCancelling = true
        task.cancel()
    }

    private func finish(_ result: Phase, announcement: String) {
        phase = result
        let isSuccess = result == .succeeded
        if isSuccess { successCount += 1 } else { failureCount += 1 }

        #if canImport(UIKit)
        UINotificationFeedbackGenerator().notificationOccurred(isSuccess ? .success : .error)
        #endif
        AccessibilityNotification.Announcement(announcement).post()

        // Hold the result long enough to read, then settle back to idle.
        let hold: Duration = isSuccess ? .seconds(1.6) : .seconds(4.5)
        settleTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: hold)
            guard let self, !Task.isCancelled, phase == result else { return }
            phase = .idle
        }
    }
}

/// One tracker per vehicle and command, shared by every surface.
@MainActor
enum RemoteCommandTrackers {
    private static var trackers: [String: RemoteCommandTracker] = [:]

    static func tracker(_ kind: RemoteCommandKind, vin: String) -> RemoteCommandTracker {
        let key = "\(vin)|\(kind.rawValue)"
        if let tracker = trackers[key] { return tracker }
        let tracker = RemoteCommandTracker()
        trackers[key] = tracker
        return tracker
    }
}

// MARK: - Commands

/// The remote commands, each run through its shared tracker. Every surface
/// calls these, so the command and its confirmation wait are the same no
/// matter where it was sent from.
@MainActor
enum RemoteCommands {
    static func tracker(_ kind: RemoteCommandKind, for vehicle: BBVehicle) -> RemoteCommandTracker {
        RemoteCommandTrackers.tracker(kind, vin: vehicle.vin)
    }

    /// Toggles the lock, or cancels the wait if a lock command is running.
    static func toggleLock(_ vehicle: BBVehicle, modelContext: ModelContext) {
        let tracker = tracker(.lock, for: vehicle)
        if tracker.isRunning {
            tracker.cancel()
        } else {
            setLocked(vehicle.lockStatus != .locked, vehicle: vehicle, modelContext: modelContext)
        }
    }

    /// Locks or unlocks. A tap on the button already running cancels it;
    /// a tap on the other one while a command runs is ignored.
    static func setLocked(_ shouldLock: Bool, vehicle: BBVehicle, modelContext: ModelContext) {
        let tracker = tracker(.lock, for: vehicle)
        let tag = shouldLock ? "lock" : "unlock"
        if tracker.isRunning {
            if tracker.tag == tag { tracker.cancel() }
            return
        }

        tracker.run(tag: tag, announcement: shouldLock ? "Locked" : "Unlocked") { tracker in
            guard let account = vehicle.account else {
                throw APIError(message: "Account not found for vehicle")
            }
            if shouldLock {
                try await account.lockVehicle(vehicle, modelContext: modelContext)
            } else {
                try await account.unlockVehicle(vehicle, modelContext: modelContext)
            }
            let target: VehicleStatus.LockStatus = shouldLock ? .locked : .unlocked
            try await vehicle.waitForStatusChange(
                modelContext: modelContext,
                condition: { $0.lockStatus == target },
                stepUpdater: { tracker.report($0) }
            )
        }
    }

    /// Toggles climate, or cancels the wait if a climate command is running.
    static func toggleClimate(
        _ vehicle: BBVehicle,
        options: ClimateOptions?,
        presetName: String?,
        presetIcon: String?,
        modelContext: ModelContext
    ) {
        let tracker = tracker(.climate, for: vehicle)
        if tracker.isRunning {
            tracker.cancel()
        } else {
            setClimate(
                !(vehicle.climateStatus?.airControlOn ?? false),
                vehicle: vehicle,
                options: options,
                presetName: presetName,
                presetIcon: presetIcon,
                modelContext: modelContext
            )
        }
    }

    static func setClimate(
        _ shouldStart: Bool,
        vehicle: BBVehicle,
        options: ClimateOptions?,
        presetName: String?,
        presetIcon: String?,
        modelContext: ModelContext
    ) {
        let tracker = tracker(.climate, for: vehicle)
        guard !tracker.isRunning else { return }

        tracker.run(
            tag: shouldStart ? "start" : "stop",
            announcement: shouldStart ? "Climate on" : "Climate off"
        ) { tracker in
            guard let account = vehicle.account else {
                throw APIError(message: "Account not found for vehicle")
            }
            if shouldStart {
                try await account.startClimate(
                    vehicle,
                    options: options ?? ClimateOptions(),
                    modelContext: modelContext,
                    presetName: presetName,
                    presetIcon: presetIcon
                )
            } else {
                // No immediate read: it would wake the car only to see the
                // old state. waitForStatusChange polls once it has settled.
                try await account.stopClimate(vehicle, modelContext: modelContext)
            }
            try await vehicle.waitForStatusChange(
                modelContext: modelContext,
                condition: { $0.climateStatus.airControlOn == shouldStart },
                stepUpdater: { tracker.report($0) }
            )
        }
    }

    /// Toggles charging, or cancels the wait if a charge command is running.
    static func toggleCharging(_ vehicle: BBVehicle, modelContext: ModelContext) {
        let tracker = tracker(.charge, for: vehicle)
        if tracker.isRunning {
            tracker.cancel()
            return
        }
        let shouldStart = !(vehicle.evStatus?.charging ?? false)

        tracker.run(
            tag: shouldStart ? "start" : "stop",
            announcement: shouldStart ? "Charging" : "Charging stopped"
        ) { tracker in
            guard let account = vehicle.account else {
                throw APIError(message: "Account not found for vehicle")
            }
            if shouldStart {
                try await account.startCharge(vehicle, modelContext: modelContext)
            } else {
                // No immediate read: it would wake the car only to see the
                // old state. waitForStatusChange polls once it has settled.
                try await account.stopCharge(vehicle, modelContext: modelContext)
            }
            try await vehicle.waitForStatusChange(
                modelContext: modelContext,
                condition: { $0.evStatus?.charging == shouldStart },
                stepUpdater: { tracker.report($0) }
            )
        }
    }
}

// MARK: - Outline shape

/// A rounded rectangle (or circle / capsule when `cornerRadius` is nil)
/// whose path starts at the top centre and runs clockwise, so a trimmed
/// stroke reads as progress filling around the button.
struct CommandOutline: InsettableShape {
    var cornerRadius: CGFloat?
    var insetAmount: CGFloat = 0

    static let circle = CommandOutline(cornerRadius: nil)

    static func rounded(_ radius: CGFloat) -> CommandOutline {
        CommandOutline(cornerRadius: radius)
    }

    func inset(by amount: CGFloat) -> CommandOutline {
        var shape = self
        shape.insetAmount += amount
        return shape
    }

    func path(in rect: CGRect) -> Path {
        let rect = rect.insetBy(dx: insetAmount, dy: insetAmount)
        guard rect.width > 0, rect.height > 0 else { return Path() }
        let maxRadius = min(rect.width, rect.height) / 2
        let radius = min(max((cornerRadius ?? maxRadius) - insetAmount, 0), maxRadius)

        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - radius, y: rect.minY))
        path.addArc(
            center: CGPoint(x: rect.maxX - radius, y: rect.minY + radius),
            radius: radius, startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false
        )
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - radius))
        path.addArc(
            center: CGPoint(x: rect.maxX - radius, y: rect.maxY - radius),
            radius: radius, startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false
        )
        path.addLine(to: CGPoint(x: rect.minX + radius, y: rect.maxY))
        path.addArc(
            center: CGPoint(x: rect.minX + radius, y: rect.maxY - radius),
            radius: radius, startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false
        )
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + radius))
        path.addArc(
            center: CGPoint(x: rect.minX + radius, y: rect.minY + radius),
            radius: radius, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false
        )
        path.closeSubpath()
        return path
    }
}

// MARK: - Pacing

/// Progress that eases toward a cap and never moves backwards. Each phase
/// sets a new cap and time constant, starting from wherever the stroke is,
/// so a long wait keeps creeping forward instead of stalling.
private struct ProgressPacer: Equatable {
    var from: Double = 0
    var cap: Double = 0
    var tau: Double = 1
    var start = Date()

    func value(at date: Date) -> Double {
        let elapsed = max(0, date.timeIntervalSince(start))
        return cap - (cap - from) * exp(-elapsed / max(tau, 0.01))
    }

    mutating func retarget(cap newCap: Double, tau newTau: Double, at date: Date = .now) {
        let current = value(at: date)
        from = current
        cap = max(current, newCap)
        tau = newTau
        start = date
    }

    /// Where the stroke should head for a phase, and how quickly.
    static func target(for phase: RemoteCommandTracker.Phase) -> (cap: Double, tau: Double) {
        switch phase {
        case .idle:
            return (0, 1)
        case .sending:
            return (0.2, 0.9)
        case let .waiting(step):
            switch step {
            case let .commandSent(delay):
                // Reaches ~55% when the first check is due.
                return (0.66, max(Double(delay), 1) / 2.2)
            case let .checking(attempt, total):
                let share = Double(attempt) / Double(max(total, 1))
                return (0.66 + 0.2 * share, 1.2)
            case let .retrying(delay, attempt, total):
                let share = Double(attempt) / Double(max(total, 1))
                return (0.7 + 0.22 * share, max(Double(delay), 1) / 2)
            }
        case .succeeded:
            return (1, 0.12)
        case .failed:
            return (1, 0.2)
        }
    }

    /// The same position without animation, for Reduce Motion.
    static func step(for phase: RemoteCommandTracker.Phase) -> Double {
        switch phase {
        case .idle: 0
        case .sending: 0.15
        case let .waiting(step):
            switch step {
            case .commandSent: 0.4
            case .checking: 0.7
            case .retrying: 0.8
            }
        case .succeeded, .failed: 1
        }
    }
}

// MARK: - Border

private struct CommandProgressBorder: View {
    let tracker: RemoteCommandTracker
    let shape: CommandOutline
    let tint: Color
    let successTint: Color
    let lineWidth: CGFloat
    let showsWash: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pacer = ProgressPacer()
    @State private var flash: Double = 0

    private var phase: RemoteCommandTracker.Phase { tracker.phase }

    private var strokeColor: Color {
        switch phase {
        case .succeeded: successTint
        case .failed: .red
        default: tint
        }
    }

    var body: some View {
        let outline = shape.inset(by: lineWidth / 2)

        ZStack {
            if showsWash {
                shape.fill(strokeColor.opacity(washOpacity))
            }

            outline.stroke(strokeColor.opacity(0.18), lineWidth: lineWidth)

            if reduceMotion {
                outline
                    .trim(from: 0, to: ProgressPacer.step(for: phase))
                    .stroke(strokeColor, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            } else {
                TimelineView(.animation(paused: phase == .idle)) { context in
                    animatedStroke(outline, at: context.date)
                }
            }

            // Success / failure flash: the whole outline glows, then fades.
            outline
                .stroke(strokeColor, lineWidth: lineWidth * 2)
                .blur(radius: lineWidth * 2)
                .opacity(flash)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear {
            pacer = ProgressPacer()
            retarget(for: phase)
        }
        .onChange(of: tracker.runID) { _, _ in
            pacer = ProgressPacer()
            retarget(for: phase)
        }
        .onChange(of: phase) { _, newPhase in
            retarget(for: newPhase)
            if newPhase == .succeeded || newPhase.isFailure {
                flash = reduceMotion ? 0.5 : 0.9
                withAnimation(.easeOut(duration: reduceMotion ? 0.6 : 1.1)) { flash = 0 }
            }
        }
    }

    private var washOpacity: Double {
        switch phase {
        case .idle: 0
        case .sending, .waiting: 0.07
        case .succeeded: 0.12
        case .failed: 0.12
        }
    }

    private func retarget(for phase: RemoteCommandTracker.Phase) {
        let target = ProgressPacer.target(for: phase)
        pacer.retarget(cap: target.cap, tau: target.tau)
    }

    @ViewBuilder
    private func animatedStroke(_ outline: CommandOutline, at date: Date) -> some View {
        let progress = min(max(pacer.value(at: date), 0), 1)
        let time = date.timeIntervalSinceReferenceDate
        let breathing = 0.6 + 0.4 * (0.5 + 0.5 * sin(time * 5))

        ZStack {
            // A faint comet orbits the track so the wait always reads as
            // live, even while the traced progress is creeping.
            if tracker.isRunning {
                comet(outline, at: time)
            }

            outline
                .trim(from: 0, to: progress)
                .stroke(
                    strokeColor,
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round)
                )

            // Glowing head at the tip of the traced stroke.
            if tracker.isRunning, progress > 0.01 {
                outline
                    .trim(from: max(0, progress - 0.035), to: progress)
                    .stroke(
                        strokeColor,
                        style: StrokeStyle(lineWidth: lineWidth * 2.2, lineCap: .round)
                    )
                    .blur(radius: lineWidth * 1.2)
                    .opacity(breathing)
            }
        }
    }

    /// A short bright segment that loops the outline every 2.4 s. Drawn as
    /// two trims when it wraps past the start of the path.
    @ViewBuilder
    private func comet(_ outline: CommandOutline, at time: TimeInterval) -> some View {
        let length = 0.14
        let head = (time / 2.4).truncatingRemainder(dividingBy: 1)
        let tail = head - length
        let style = StrokeStyle(lineWidth: lineWidth, lineCap: .round)

        Group {
            if tail >= 0 {
                outline.trim(from: tail, to: head).stroke(strokeColor.opacity(0.45), style: style)
            } else {
                outline.trim(from: 0, to: head).stroke(strokeColor.opacity(0.45), style: style)
                outline.trim(from: 1 + tail, to: 1).stroke(strokeColor.opacity(0.45), style: style)
            }
        }
    }
}

private extension RemoteCommandTracker.Phase {
    var isFailure: Bool {
        if case .failed = self { return true }
        return false
    }
}

// MARK: - Shake

/// Horizontal shake for a failed command. `shakes` counts failures; each
/// increment plays one shake when animated.
private struct ShakeEffect: GeometryEffect {
    var shakes: CGFloat
    var amplitude: CGFloat = 6

    var animatableData: CGFloat {
        get { shakes }
        set { shakes = newValue }
    }

    func effectValue(size: CGSize) -> ProjectionTransform {
        let offset = amplitude * sin(shakes * .pi * 6)
        return ProjectionTransform(CGAffineTransform(translationX: offset, y: 0))
    }
}

private struct CommandProgressModifier: ViewModifier {
    let tracker: RemoteCommandTracker
    let shape: CommandOutline
    let tint: Color
    let successTint: Color
    let lineWidth: CGFloat
    let showsWash: Bool
    let isActive: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var isVisible: Bool { isActive && tracker.phase != .idle }

    func body(content: Content) -> some View {
        content
            .overlay {
                if isVisible {
                    CommandProgressBorder(
                        tracker: tracker,
                        shape: shape,
                        tint: tint,
                        successTint: successTint,
                        lineWidth: lineWidth,
                        showsWash: showsWash
                    )
                    .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: reduceMotion ? 0.2 : 0.35), value: isVisible)
            .modifier(ShakeEffect(shakes: isActive ? CGFloat(tracker.failureCount) : 0))
            .animation(
                reduceMotion ? nil : .linear(duration: 0.45),
                value: tracker.failureCount
            )
    }
}

extension View {
    /// Draws a remote command's progress on this view's outline: a stroke
    /// traces around it while the vehicle responds, closes and flashes in
    /// `successTint` when it confirms, and turns red with a shake on
    /// failure. `isActive` limits it to one of several buttons sharing a
    /// tracker (Lock vs Unlock). Respects Reduce Motion.
    func commandProgressBorder(
        _ tracker: RemoteCommandTracker,
        shape: CommandOutline,
        tint: Color,
        successTint: Color = .green,
        lineWidth: CGFloat = 2.5,
        showsWash: Bool = true,
        isActive: Bool = true
    ) -> some View {
        modifier(
            CommandProgressModifier(
                tracker: tracker,
                shape: shape,
                tint: tint,
                successTint: successTint,
                lineWidth: lineWidth,
                showsWash: showsWash,
                isActive: isActive
            )
        )
    }
}
