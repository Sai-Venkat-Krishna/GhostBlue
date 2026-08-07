//
//  VehicleRangeViews.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 9/8/25.
//

import BetterBlueKit
import SwiftUI

struct EVRangeCardView: View {
    let evStatus: VehicleStatus.EVStatus
    @State private var appSettings = AppSettings.shared

    var formattedRange: String {
        guard evStatus.evRange.range.length > 0 else {
            return "--"
        }
        return evStatus.evRange.range.units.format(
            evStatus.evRange.range.length,
            to: appSettings.preferredDistanceUnit,
        )
    }

    var batteryPercentage: Int { Int(evStatus.evRange.percentage) }

    private var batteryBarColor: Color {
        switch batteryPercentage {
        case 70...: return Color(red: 0.20, green: 0.82, blue: 0.45)
        case 40...: return .yellow
        case 20...: return .orange
        default:    return .red
        }
    }

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                VStack(alignment: .leading) {
                    Text("EV Range")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(formattedRange)
                        .font(.title3)
                        .fontWeight(.semibold)
                        .contentTransition(.numericText(countsDown: false))
                }
                Spacer()
                VStack(alignment: .trailing) {
                    Text("Battery")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text("\(batteryPercentage)%")
                        .font(.title3)
                        .fontWeight(.semibold)
                        .contentTransition(.numericText(countsDown: false))
                }
            }
            .animation(.spring(response: 0.4, dampingFraction: 0.85), value: batteryPercentage)

            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color.primary.opacity(0.08))
                        .frame(height: 10)
                    Capsule()
                        .fill(batteryBarColor)
                        .frame(
                            width: max(10, geo.size.width * Double(batteryPercentage) / 100.0),
                            height: 10
                        )
                        .animation(.spring(response: 0.7, dampingFraction: 0.85), value: batteryPercentage)
                }
            }
            .frame(height: 10)
        }
        .padding()
        .vehicleCardGlassEffect()
    }
}

struct GasRangeCardView: View {
    let gasRange: VehicleStatus.FuelRange
    @State private var appSettings = AppSettings.shared

    var formattedRange: String {
        guard gasRange.range.length > 0 else {
            return "--"
        }
        return gasRange.range.units.format(
            gasRange.range.length,
            to: appSettings.preferredDistanceUnit,
        )
    }

    var fuelPercentage: Int { Int(gasRange.percentage) }

    private var fuelBarColor: Color {
        switch fuelPercentage {
        case 70...: return Color(red: 0.20, green: 0.82, blue: 0.45)
        case 40...: return .yellow
        case 20...: return .orange
        default:    return .red
        }
    }

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                VStack(alignment: .leading) {
                    Text("Gas Range")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(formattedRange)
                        .font(.title3)
                        .fontWeight(.semibold)
                        .contentTransition(.numericText(countsDown: false))
                }
                Spacer()
                if gasRange.percentage >= 0.0 {
                    VStack(alignment: .trailing) {
                        Text("Fuel")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text("\(fuelPercentage)%")
                            .font(.title3)
                            .fontWeight(.semibold)
                            .contentTransition(.numericText(countsDown: false))
                    }
                }
            }
            .animation(.spring(response: 0.4, dampingFraction: 0.85), value: fuelPercentage)

            if gasRange.percentage >= 0.0 {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(Color.primary.opacity(0.08))
                            .frame(height: 10)
                        Capsule()
                            .fill(fuelBarColor)
                            .frame(
                                width: max(10, geo.size.width * Double(fuelPercentage) / 100.0),
                                height: 10
                            )
                            .animation(.spring(response: 0.7, dampingFraction: 0.85), value: fuelPercentage)
                    }
                }
                .frame(height: 10)
            }
        }
        .padding()
        .vehicleCardGlassEffect()
    }
}
