//
//  EVRangeDisplayCard.swift
//  GhostBlue
//
//  Display-only card for EV range and battery info
//

import BetterBlueKit
import SwiftUI

struct EVRangeDisplayCard: View {
    let evStatus: VehicleStatus.EVStatus
    /// When the status was reported — anchors the charge finish estimate.
    var statusDate: Date?
    @State private var appSettings = AppSettings.shared

    var formattedRange: String {
        guard evStatus.evRange.range.length > 0 else {
            return "--"
        }
        return evStatus.evRange.range.units.format(
            evStatus.evRange.range.length,
            to: appSettings.preferredDistanceUnit
        )
    }

    var batteryPercentage: Int {
        Int(evStatus.evRange.percentage)
    }

    var isCharging: Bool {
        evStatus.charging
    }

    var chargeSpeed: String? {
        guard isCharging, evStatus.chargeSpeed > 0 else {
            return nil
        }
        return String(format: "%.1f kW", evStatus.chargeSpeed)
    }

    var chargeTimeRemaining: String? {
        guard isCharging else {
            return nil
        }
        let duration = evStatus.chargeTime
        guard duration > .seconds(0) else {
            return nil
        }
        let formattedTime = duration.formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))

        // If there's a target SOC, append "to X%"
        if let targetSOC = evStatus.currentTargetSOC {
            return "\(formattedTime) to \(Int(targetSOC))%"
        }
        return formattedTime
    }

    var chargeFinishText: String? {
        guard isCharging,
              let finish = chargeFinishDate(
                  remainingSeconds: evStatus.chargeTime.components.seconds,
                  reportedAt: statusDate
              ) else {
            return nil
        }
        return formatChargeFinishTime(finish)
    }

    var body: some View {
        EVChargingProgressView(
            formattedRange: formattedRange,
            batteryPercentage: batteryPercentage,
            isCharging: isCharging,
            chargeSpeed: chargeSpeed,
            chargeTimeRemaining: chargeTimeRemaining,
            chargeFinishText: chargeFinishText,
            targetSOC: evStatus.currentTargetSOC
        )
        .padding()
        .vehicleCardGlassEffect()
    }
}
