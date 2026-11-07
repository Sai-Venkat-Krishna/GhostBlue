//
//  RemoteCommandCard.swift
//  GhostBlue
//
//  The card shared by LockButton, ClimateButton and ChargingButton: the
//  state icon, the state title, one status line, and the command border.
//
//  While a command runs, the card names what it is doing ("Locking…"),
//  greys the icon and traces the border. When the vehicle confirms, the
//  title rolls to the new state, the icon swaps and bounces, and the border
//  closes and flashes in the new state's colour. A failure turns the border
//  red, shakes the card and badges the icon until it settles.
//

import SwiftUI

struct RemoteCommandCard: View {
    let tracker: RemoteCommandTracker
    /// The vehicle's confirmed state, e.g. "Locked".
    let stateTitle: String
    /// What the running command is doing, e.g. "Locking…".
    let pendingTitle: String
    /// The line under the title when no command is running.
    let idleDetail: String
    /// The icon for the confirmed state.
    let icon: Image
    /// The icon for the state the running command heads to.
    let pendingIcon: Image
    /// Colour of the confirmed state; the border flashes it on success.
    let tint: Color
    var spinsIcon = false
    var pulsesIcon = false
    var isEnabled = true
    let action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.gbExpressiveShapes) private var expressiveShapes
    @State private var appSettings = AppSettings.shared

    private var isRunning: Bool { tracker.isRunning }
    private var title: String { isRunning ? pendingTitle : stateTitle }

    /// The status line: the error, the confirmation, the wait, or the hint.
    private var detail: String {
        if let error = tracker.errorMessage { return error }
        if tracker.didSucceed { return "Confirmed by vehicle" }
        if isRunning { return tracker.statusText ?? "" }
        return idleDetail
    }

    private var titleTransition: AnyTransition {
        reduceMotion
            ? .opacity
            : .asymmetric(
                insertion: .move(edge: .bottom).combined(with: .opacity),
                removal: .move(edge: .top).combined(with: .opacity)
            )
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                iconView
                    .frame(width: 52, height: 52)

                VStack(alignment: .leading, spacing: 3) {
                    ZStack(alignment: .leading) {
                        Text(title)
                            .font(.subheadline)
                            .fontWeight(.semibold)
                            .foregroundStyle(.primary)
                            .id(title)
                            .transition(titleTransition)
                    }
                    .clipped()

                    statusLine
                }

                Spacer(minLength: 8)

                trailingIndicator
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            .opacity(isEnabled ? 1 : 0.5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled && !isRunning)
        .vehicleCardGlassEffect()
        .commandProgressBorder(
            tracker,
            // Same radius as `vehicleCardGlassEffect()` under this theme.
            shape: .rounded(gbCardRadius(12, expressive: expressiveShapes)),
            tint: appSettings.accentColor,
            successTint: tint
        )
        .bouncePress()
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.42, dampingFraction: 0.78), value: title)
        .animation(.easeInOut(duration: 0.25), value: detail)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(detail)
        .accessibilityHint(isRunning ? "Double tap to cancel" : "")
        .accessibilityAddTraits(.isButton)
    }

    // MARK: Icon

    private var iconView: some View {
        ZStack {
            (isRunning ? pendingIcon : icon)
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(isRunning ? Color.secondary : tint)
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.pulse, options: .repeating, isActive: isRunning && !reduceMotion)
                .symbolEffect(.bounce, options: .nonRepeating, value: reduceMotion ? 0 : tracker.successCount)
                .spin(spinsIcon && !isRunning && !reduceMotion)
                .pulse(pulsesIcon && !isRunning && !reduceMotion)
        }
        .overlay(alignment: .bottomTrailing) {
            if tracker.errorMessage != nil {
                Image(systemName: "exclamationmark.circle.fill")
                    .font(.system(size: 16, weight: .bold))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, .red)
                    .offset(x: 2, y: 2)
                    .transition(reduceMotion ? .opacity : .scale.combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.35, dampingFraction: 0.6), value: tracker.errorMessage != nil)
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: isRunning)
    }

    // MARK: Status line

    @ViewBuilder
    private var statusLine: some View {
        if let error = tracker.errorMessage {
            Label {
                Text(error).lineLimit(1)
            } icon: {
                Image(systemName: "xmark.octagon.fill")
            }
            .font(.caption.weight(.medium))
            .foregroundStyle(.red)
            .labelStyle(.titleAndIcon)
            .transition(.opacity)
        } else if tracker.didSucceed {
            Label(detail, systemImage: "checkmark.circle.fill")
                .font(.caption.weight(.medium))
                .foregroundStyle(tint)
                .transition(.opacity)
        } else {
            Text(detail)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .contentTransition(.opacity)
        }
    }

    // MARK: Trailing

    @ViewBuilder
    private var trailingIndicator: some View {
        ZStack {
            if isRunning {
                Image(systemName: "xmark")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 22, height: 22)
                    .background(Color.primary.opacity(0.08), in: Circle())
                    .transition(.scale.combined(with: .opacity))
                    .accessibilityHidden(true)
            } else if tracker.didSucceed {
                Image(systemName: "checkmark")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(tint)
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.4).combined(with: .opacity))
            } else {
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .transition(.opacity)
            }
        }
        .frame(width: 22)
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.35, dampingFraction: 0.7), value: tracker.phase)
    }
}
