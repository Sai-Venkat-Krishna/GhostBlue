//
//  RivianCommandControls.swift
//  GhostBlue
//
//  Rivian 3.0–style trio of circular primary commands: Lock, Unlock, Controls,
//  built on RVCircleButton (active state = solid ink, adapts to light/dark).
//  Lock/Unlock perform the real remote command using the same path as
//  LockButton (account.lock/unlock + waitForStatusChange), so status-waiting,
//  errors and haptics are preserved. Original UI; no Rivian assets.
//

import BetterBlueKit
import SwiftData
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

struct RivianCommandControls: View {
    let bbVehicle: BBVehicle
    /// Lets the Controls sheet zoom out of its button.
    var zoomNamespace: Namespace.ID?
    var onMore: () -> Void = {}
    @Environment(\.modelContext) private var modelContext
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var tracker: RemoteCommandTracker { RemoteCommands.tracker(.lock, for: bbVehicle) }

    private var isLocked: Bool {
        guard bbVehicle.modelContext != nil else { return false }
        return bbVehicle.lockStatus == .locked
    }

    var body: some View {
        VStack(spacing: RV.Space.m) {
            HStack(spacing: 24) {
                // Neither button is filled while a command runs: the tapped
                // one traces its outline, and the fill lands on the state
                // the vehicle confirms.
                RVCircleButton(
                    title: "Lock",
                    systemImage: "lock.fill",
                    active: isLocked && !tracker.isRunning,
                    dimmed: tracker.isRunning,
                    command: tracker,
                    commandTag: "lock"
                ) { send(lock: true) }
                RVCircleButton(
                    title: "Unlock",
                    systemImage: "lock.open.fill",
                    active: !isLocked && !tracker.isRunning,
                    dimmed: tracker.isRunning,
                    command: tracker,
                    commandTag: "unlock"
                ) { send(lock: false) }
                RVCircleButton(title: "Controls", systemImage: "square.grid.2x2.fill", action: onMore)
                    .gbZoomSource("rivian-controls", in: zoomNamespace)
            }
            if let status = tracker.statusText {
                Label {
                    Text(status).lineLimit(1)
                } icon: {
                    if tracker.errorMessage != nil {
                        Image(systemName: "xmark.octagon.fill")
                    } else if tracker.didSucceed {
                        Image(systemName: "checkmark.circle.fill")
                    }
                }
                .font(.caption)
                .foregroundStyle(tracker.errorMessage != nil ? RV.danger : RV.inkSecondary)
                .contentTransition(.opacity)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.4, dampingFraction: 0.85), value: tracker.phase)
    }

    private func send(lock shouldLock: Bool) {
        RemoteCommands.setLocked(shouldLock, vehicle: bbVehicle, modelContext: modelContext)
    }
}
