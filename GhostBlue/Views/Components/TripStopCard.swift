//
//  TripStopCard.swift
//  GhostBlue
//
//  One ABRP trip stop (charging stop or final destination) as a glass card,
//  visually matching ChargeSessionCard. Used in the route planner preview
//  and the active trip's stop list.
//

import BetterBlueKit
import SwiftUI

struct TripStopCard: View {
    let stop: ABRPTripStop
    var isNext: Bool = false
    var isCompleted: Bool = false

    private var tint: Color {
        if isCompleted { return .secondary }
        if isNext { return .green }
        return stop.isCharger ? .blue : .primary
    }

    private var icon: String {
        if isCompleted { return "checkmark" }
        return stop.isCharger ? "bolt.fill" : "flag.checkered"
    }

    private var socLine: String? {
        var parts: [String] = []
        if let arrival = stop.arrivalSocPercent {
            if let departure = stop.departureSocPercent, stop.isCharger {
                parts.append("\(Int(arrival))% → \(Int(departure))%")
            } else {
                parts.append("arrive \(Int(arrival))%")
            }
        }
        if let chargeSeconds = stop.chargeDurationSeconds, chargeSeconds > 0 {
            parts.append(Self.durationText(chargeSeconds) + " charge")
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .stroke(tint.opacity(0.35), lineWidth: 2)
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(tint)
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(stop.name)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(isCompleted ? Color.secondary : Color.primary)
                        .lineLimit(1)

                    if isNext {
                        Text("NEXT")
                            .font(.system(size: 9, weight: .heavy))
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1.5)
                            .background(Capsule().fill(Color.green.opacity(0.25)))
                            .foregroundStyle(.green)
                    }
                }

                if let socLine {
                    Text(socLine)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if let network = stop.chargerNetwork {
                    Text(network)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 3) {
                if let driveSeconds = stop.driveDurationSeconds, driveSeconds > 0 {
                    Text(Self.durationText(driveSeconds))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let distanceText {
                    Text(distanceText)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }
        }
        .padding(14)
        .gbGlassCard(radius: GBDesign.tileRadius)
    }

    private var distanceText: String? {
        guard let meters = stop.driveDistanceMeters, meters > 0 else { return nil }
        let unit = AppSettings.shared.preferredDistanceUnit
        let value = Distance.Units.kilometers.convert(meters / 1000, to: unit)
        return String(format: "%.0f %@", value, unit == .miles ? "mi" : "km")
    }

    static func durationText(_ seconds: Int) -> String {
        Duration.seconds(max(60, seconds))
            .formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
    }
}
