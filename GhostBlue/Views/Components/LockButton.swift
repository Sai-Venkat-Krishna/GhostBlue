//
//  LockButton.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 8/25/25.
//

import BetterBlueKit
import SwiftData
import SwiftUI

struct LockButton: View {
    let bbVehicle: BBVehicle
    var transition: Namespace.ID?
    @Environment(\.modelContext) private var modelContext

    private var tracker: RemoteCommandTracker { RemoteCommands.tracker(.lock, for: bbVehicle) }

    var isLocked: Bool {
        guard bbVehicle.modelContext != nil else { return false }
        return bbVehicle.lockStatus == .locked
    }

    /// Where the running command is heading: the tapped direction, not the
    /// reported state, which only changes once the vehicle confirms.
    private var pendingLock: Bool { tracker.tag == "lock" }

    var body: some View {
        RemoteCommandCard(
            tracker: tracker,
            stateTitle: isLocked ? "Locked" : "Unlocked",
            pendingTitle: pendingLock ? "Locking…" : "Unlocking…",
            idleDetail: isLocked ? "Tap to unlock" : "Tap to lock",
            icon: Image(systemName: isLocked ? "lock.fill" : "lock.open.fill"),
            pendingIcon: Image(systemName: pendingLock ? "lock.fill" : "lock.open.fill"),
            tint: isLocked ? .green : .orange
        ) {
            RemoteCommands.toggleLock(bbVehicle, modelContext: modelContext)
        }
    }
}
