//
//  EVChargingProgressView.swift
//  GhostBlue
//
//  Shared view for EV charging progress display
//  Used by both EVRangeChargingCard and Live Activity
//

import SwiftUI

/// Shared view for displaying EV charging progress
/// Used by EVRangeChargingCard in the main app and VehicleActivityWidget for Live Activities
struct EVChargingProgressView: View {
    let icon: Image?
    let formattedRange: String
    let batteryPercentage: Int
    let isCharging: Bool
    let chargeSpeed: String?
    let chargeTimeRemaining: String?
    let chargeFinishText: String?
    let targetSOC: Double?
    let showHeader: Bool

    @State private var shimmerOffset: CGFloat = -1.0

    init(
        icon: Image? = nil,
        formattedRange: String = "",
        batteryPercentage: Int,
        isCharging: Bool,
        chargeSpeed: String?,
        chargeTimeRemaining: String?,
        chargeFinishText: String? = nil,
        targetSOC: Double?,
        showHeader: Bool = true
    ) {
        self.icon = icon
        self.formattedRange = formattedRange
        self.batteryPercentage = batteryPercentage
        self.isCharging = isCharging
        self.chargeSpeed = chargeSpeed
        self.chargeTimeRemaining = chargeTimeRemaining
        self.chargeFinishText = chargeFinishText
        self.targetSOC = targetSOC
        self.showHeader = showHeader
    }

    private var batteryBarColor: Color {
        switch batteryPercentage {
        case 70...: return Color(red: 0.20, green: 0.82, blue: 0.45)
        case 40...: return .yellow
        case 20...: return .orange
        default:    return .red
        }
    }

    var body: some View {
        VStack(spacing: 12) {
            if showHeader {
                // Top row: Icon (optional), Range, and Battery percentage
                HStack(spacing: 12) {
                    if let icon {
                        icon
                            .font(.title2)
                            .foregroundColor(isCharging ? .green : .primary)
                            .symbolEffect(.pulse, isActive: isCharging)
                            .frame(width: 28)
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text("EV Range")
                            .font(.caption)
                        Text(formattedRange)
                            .font(.title3)
                            .fontWeight(.semibold)
                            .contentTransition(.numericText(countsDown: false))
                    }

                    Spacer()

                    VStack(alignment: .trailing, spacing: 4) {
                        Text("Battery")
                            .font(.caption)
                        Text("\(batteryPercentage)%")
                            .font(.title3)
                            .fontWeight(.semibold)
                            .contentTransition(.numericText(countsDown: false))
                    }
                }
                .foregroundColor(.primary)
                .animation(.spring(response: 0.4, dampingFraction: 0.85), value: batteryPercentage)
                .animation(.spring(response: 0.4, dampingFraction: 0.85), value: formattedRange)
            }

            // Progress bar
            if isCharging {
                chargingProgressBar
            } else {
                notChargingProgressBar
            }

            // Estimated finish clock time, e.g. "Done at 3:41 PM"
            if isCharging, let chargeFinishText {
                HStack(spacing: 4) {
                    Image(systemName: "bolt.badge.checkmark.fill")
                        .font(.caption2)
                        .foregroundColor(.green)
                    Text("Done at \(chargeFinishText)")
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(.secondary)
                    Spacer()
                }
            }
        }
        .onAppear { startShimmerIfNeeded() }
        .onChange(of: isCharging) { _, charging in
            if charging { startShimmerIfNeeded() }
        }
    }

    // MARK: - Charging bar with shimmer

    private var chargingProgressBar: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                // Background
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.gray.opacity(0.2))
                    .frame(height: 32)

                // Foreground progress
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.green)
                    .frame(
                        width: geometry.size.width * (Double(batteryPercentage) / 100.0),
                        height: 32
                    )
                    .overlay(
                        // Shimmer sweep — only while charging
                        LinearGradient(
                            stops: [
                                .init(color: .clear, location: 0.0),
                                .init(color: .white.opacity(0.28), location: 0.5),
                                .init(color: .clear, location: 1.0),
                            ],
                            startPoint: UnitPoint(x: shimmerOffset - 0.4, y: 0.5),
                            endPoint:   UnitPoint(x: shimmerOffset + 0.4, y: 0.5)
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    )

                // Target SOC indicator — a thin dashed vertical line.
                // Hidden at 100% since it would sit flush with the bar edge.
                if let targetSOC, targetSOC < 100 {
                    Line()
                        .stroke(style: StrokeStyle(lineWidth: 2, dash: [3, 3]))
                        .foregroundColor(.white.opacity(0.8))
                        .frame(width: 2, height: 32)
                        .offset(x: geometry.size.width * (targetSOC / 100.0))
                }

                // Text overlay - charge speed on left
                HStack {
                    if let speed = chargeSpeed {
                        Text(speed)
                            .font(.caption)
                            .fontWeight(.semibold)
                            .foregroundColor(.white)
                            .shadow(color: .black.opacity(0.5), radius: 2, x: 0, y: 1)
                            .padding(.leading, 12)
                    }
                    Spacer()
                }
                .frame(height: 32)

                // Time remaining - positioned within target SOC area
                if let timeRemaining = chargeTimeRemaining {
                    HStack {
                        Spacer()
                        Text(timeRemaining)
                            .font(.caption)
                            .fontWeight(.semibold)
                            .foregroundColor(.white)
                            .shadow(color: .black.opacity(0.5), radius: 2, x: 0, y: 1)
                            .padding(.trailing, 12)
                    }
                    .frame(
                        width: targetSOC != nil
                            ? geometry.size.width * ((targetSOC ?? 100) / 100.0)
                            : geometry.size.width,
                        height: 32
                    )
                }
            }
        }
        .frame(height: 32)
    }

    // MARK: - Idle battery bar (color-coded + animated)

    private var notChargingProgressBar: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.primary.opacity(0.08))
                    .frame(height: 10)

                Capsule()
                    .fill(batteryBarColor)
                    .frame(
                        width: max(10, geometry.size.width * (Double(batteryPercentage) / 100.0)),
                        height: 10
                    )
                    .animation(.spring(response: 0.7, dampingFraction: 0.85), value: batteryPercentage)
            }
        }
        .frame(height: 10)
    }

    // MARK: - Shimmer control

    private func startShimmerIfNeeded() {
        guard isCharging else { return }
        shimmerOffset = -1.0
        withAnimation(.linear(duration: 1.4).repeatForever(autoreverses: false)) {
            shimmerOffset = 1.0
        }
    }
}
