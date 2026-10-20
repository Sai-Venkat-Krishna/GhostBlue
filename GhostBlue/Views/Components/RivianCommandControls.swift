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
    var onMore: () -> Void = {}
    @Environment(\.modelContext) private var modelContext

    @State private var inProgress = false
    @State private var errorText: String?
    @State private var currentTask: Task<Void, Never>?

    private var isLocked: Bool {
        guard bbVehicle.modelContext != nil else { return false }
        return bbVehicle.lockStatus == .locked
    }

    var body: some View {
        VStack(spacing: RV.Space.m) {
            HStack(spacing: 24) {
                RVCircleButton(title: "Lock", systemImage: "lock.fill",
                               active: isLocked, dimmed: inProgress) { send(lock: true) }
                RVCircleButton(title: "Unlock", systemImage: "lock.open.fill",
                               active: !isLocked, dimmed: inProgress) { send(lock: false) }
                RVCircleButton(title: "Controls", systemImage: "square.grid.2x2.fill", action: onMore)
            }
            if inProgress || errorText != nil {
                Text(errorText ?? "Sending command…")
                    .font(.caption)
                    .foregroundStyle(errorText != nil ? RV.danger : RV.inkSecondary)
                    .lineLimit(1)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: inProgress)
    }

    private func send(lock shouldLock: Bool) {
        guard !inProgress else { return }
        currentTask = Task { await perform(shouldLock) }
    }

    @MainActor
    private func perform(_ shouldLock: Bool) async {
        inProgress = true
        errorText = nil
        defer { inProgress = false; currentTask = nil }
        do {
            guard let account = bbVehicle.account else {
                throw APIError(message: "Account not found for vehicle")
            }
            let context = modelContext
            if shouldLock {
                try await account.lockVehicle(bbVehicle, modelContext: context)
            } else {
                try await account.unlockVehicle(bbVehicle, modelContext: context)
            }
            let targetStatus: VehicleStatus.LockStatus = shouldLock ? .locked : .unlocked
            try await bbVehicle.waitForStatusChange(
                modelContext: context,
                condition: { status in status.lockStatus == targetStatus },
                statusMessageUpdater: { _ in }
            )
            #if canImport(UIKit)
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            #endif
        } catch is CancellationError {
            // user navigated away
        } catch {
            errorText = (error as? APIError)?.message ?? error.localizedDescription
            Task {
                try? await Task.sleep(for: .seconds(4))
                await MainActor.run { errorText = nil }
            }
        }
    }
}
