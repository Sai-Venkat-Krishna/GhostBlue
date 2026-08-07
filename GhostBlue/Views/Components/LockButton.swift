//
//  LockButton.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 8/25/25.
//

import BetterBlueKit
import SwiftData
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

struct LockButton: View {
    let bbVehicle: BBVehicle
    var transition: Namespace.ID?
    @Environment(\.modelContext) private var modelContext

    @State private var iconDegrees: Double = 0
    @State private var inProgress = false
    @State private var statusText = ""
    @State private var errorText: String?
    @State private var currentTask: Task<Void, Never>?

    var isLocked: Bool {
        guard bbVehicle.modelContext != nil else { return false }
        return bbVehicle.lockStatus == .locked
    }

    private var lockColor: Color { isLocked ? .red : .green }

    private var displayStatusText: String {
        if let e = errorText { return e }
        if inProgress { return statusText.isEmpty ? "Sending command..." : statusText }
        return isLocked ? "Tap to unlock" : "Tap to lock"
    }

    var body: some View {
        Button { handleTap() } label: {
            HStack(spacing: 14) {
                ZStack {
                    // Pulsing ring during in-progress
                    Circle()
                        .stroke(lockColor.opacity(0.5), lineWidth: 2)
                        .scaleEffect(inProgress ? 1.55 : 1.0)
                        .opacity(inProgress ? 0.0 : 0.35)
                        .animation(
                            inProgress
                                ? .easeInOut(duration: 0.85).repeatForever(autoreverses: true)
                                : .default,
                            value: inProgress
                        )

                    Image(systemName: isLocked ? "lock.fill" : "lock.open.fill")
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(inProgress ? Color.secondary : lockColor)
                        .rotation3DEffect(.degrees(iconDegrees), axis: (x: 0, y: 1, z: 0))
                        .animation(.spring(response: 0.45, dampingFraction: 0.65), value: iconDegrees)
                }
                .frame(width: 52, height: 52)

                VStack(alignment: .leading, spacing: 3) {
                    Text(isLocked ? "Locked" : "Unlocked")
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundStyle(.primary)
                        .animation(.spring(response: 0.3), value: isLocked)

                    Text(displayStatusText)
                        .font(.caption)
                        .foregroundStyle(errorText != nil ? Color.red : Color.secondary)
                        .lineLimit(1)
                        .animation(.easeInOut(duration: 0.2), value: displayStatusText)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .opacity(inProgress ? 0 : 1)
                    .animation(.easeInOut(duration: 0.15), value: inProgress)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .vehicleCardGlassEffect()
        .bouncePress()
        .onChange(of: bbVehicle.lockStatus) { _, _ in
            withAnimation(.spring(response: 0.45, dampingFraction: 0.65)) {
                iconDegrees += 180
            }
        }
    }

    private func handleTap() {
        if inProgress {
            currentTask?.cancel()
            return
        }
        let target = !isLocked
        currentTask = Task {
            await performLock(target)
        }
    }

    @MainActor
    private func performLock(_ shouldLock: Bool) async {
        inProgress = true
        statusText = ""
        errorText = nil
        defer {
            inProgress = false
            currentTask = nil
        }

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
                statusMessageUpdater: { msg in
                    Task { @MainActor in statusText = msg }
                }
            )

            #if canImport(UIKit)
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            #endif
        } catch is CancellationError {
            // cancelled by user tap — nothing to do
        } catch {
            let msg = (error as? APIError)?.message ?? error.localizedDescription
            errorText = msg
            Task {
                try? await Task.sleep(for: .seconds(4))
                await MainActor.run { errorText = nil }
            }
        }
    }
}
