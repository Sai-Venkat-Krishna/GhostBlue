//
//  RivianEnergyHero.swift
//  GhostBlue
//
//  Rivian-style Energy hero for the Charging (Energy) tab: large state of
//  charge, estimated range, a battery bar, live charging state and the
//  functional start/stop charging control. Original UI; no Rivian assets.
//

import BetterBlueKit
import SwiftUI

struct RivianEnergyHero: View {
    let bbVehicle: BBVehicle
    var transition: Namespace.ID?
    @State private var appSettings = AppSettings.shared

    var body: some View {
        RVCard(padding: RV.Space.xl) {
            VStack(alignment: .leading, spacing: RV.Space.l) {
                if let ev = bbVehicle.evStatus {
                    HStack(alignment: .firstTextBaseline) {
                        HStack(alignment: .firstTextBaseline, spacing: 2) {
                            Text("\(Int(ev.evRange.percentage))")
                                .font(RV.Font.metric)
                                .foregroundStyle(RV.ink)
                            Text("%")
                                .font(RV.Font.headline)
                                .foregroundStyle(RV.inkSecondary)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(ev.evRange.range.units.format(
                                ev.evRange.range.length,
                                to: appSettings.preferredDistanceUnit
                            ))
                            .font(RV.Font.headline)
                            .foregroundStyle(RV.ink)
                            Text("Estimated range")
                                .font(.caption)
                                .foregroundStyle(RV.inkSecondary)
                        }
                    }
                    batteryBar(fraction: ev.evRange.percentage / 100, charging: ev.charging)
                    if ev.charging, ev.chargeTime > .seconds(0) {
                        Label(
                            "\(ev.chargeTime.formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))) remaining",
                            systemImage: "bolt.fill"
                        )
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(RV.energy)
                    }
                } else {
                    Text("Battery data unavailable")
                        .foregroundStyle(RV.inkSecondary)
                }
                ChargingStatusSummary(vehicle: bbVehicle, activeTint: RV.energy)
                ChargingButton(bbVehicle: bbVehicle, transition: transition)
            }
        }
    }

    private func batteryBar(fraction: Double, charging: Bool) -> some View {
        let clamped = min(max(fraction, 0), 1)
        return GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(RV.surfaceMuted)
                Capsule()
                    .fill(charging ? RV.energy : RV.ink)
                    .frame(width: max(12, geo.size.width * clamped))
            }
        }
        .frame(height: 12)
        .accessibilityElement()
        .accessibilityLabel("Battery \(Int(clamped * 100)) percent")
    }
}
