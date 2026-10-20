//
//  WatchComplications.swift
//  WatchWidget
//
//  Created by Mark Schmidt on 1/25/26.
//

import BetterBlueKit
import SwiftData
import SwiftUI
import WidgetKit

// MARK: - Timeline Entry and Provider

struct WatchComplicationEntry: TimelineEntry {
    let date: Date
    let vehicleName: String?
    let rangeText: String?
    let batteryPercentage: Double?
}

struct WatchComplicationProvider: TimelineProvider {
    func placeholder(in context: Context) -> WatchComplicationEntry {
        WatchComplicationEntry(
            date: Date(),
            vehicleName: "Vehicle",
            rangeText: "-- mi",
            batteryPercentage: 75
        )
    }

    func getSnapshot(in context: Context, completion: @escaping (WatchComplicationEntry) -> Void) {
        Task { @MainActor in
            let entry = await fetchEntry()
            completion(entry)
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WatchComplicationEntry>) -> Void) {
        Task { @MainActor in
            let entry = await fetchEntry()
            let nextUpdate = Calendar.current.date(byAdding: .minute, value: 15, to: Date())!
            let timeline = Timeline(entries: [entry], policy: .after(nextUpdate))
            completion(timeline)
        }
    }

    @MainActor
    private func fetchEntry() async -> WatchComplicationEntry {
        do {
            let modelContainer = try createSharedModelContainer(enableCloudKit: false)
            let context = ModelContext(modelContainer)
            let vehicles = try context.fetch(FetchDescriptor<BBVehicle>(
                predicate: #Predicate { !$0.isHidden },
                sortBy: [SortDescriptor(\.sortOrder)]
            ))

            guard let vehicle = vehicles.first else {
                return WatchComplicationEntry(date: Date(), vehicleName: nil, rangeText: nil, batteryPercentage: nil)
            }

            let settings = AppSettings.shared
            var rangeText: String?
            var percentage: Double?

            if vehicle.fuelType.hasElectricCapability, let evStatus = vehicle.evStatus {
                percentage = evStatus.evRange.percentage
                if evStatus.evRange.range.length > 0 {
                    rangeText = evStatus.evRange.range.units.format(
                        evStatus.evRange.range.length,
                        to: settings.preferredDistanceUnit
                    )
                }
            } else if let gasRange = vehicle.gasRange {
                percentage = gasRange.percentage
                if gasRange.range.length > 0 {
                    rangeText = gasRange.range.units.format(
                        gasRange.range.length,
                        to: settings.preferredDistanceUnit
                    )
                }
            }

            return WatchComplicationEntry(
                date: Date(),
                vehicleName: vehicle.displayName,
                rangeText: rangeText,
                batteryPercentage: percentage
            )
        } catch {
            BBLogger.error(.app, "WatchComplicationProvider: \(error)")
            return WatchComplicationEntry(date: Date(), vehicleName: nil, rangeText: nil, batteryPercentage: nil)
        }
    }
}

// MARK: - Vehicle Status Complication

struct VehicleStatusComplication: Widget {
    let kind = "com.ghostblue.watch.status"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: WatchComplicationProvider()) { entry in
            VehicleStatusComplicationView(entry: entry)
        }
        .configurationDisplayName("GhostBlue")
        .description("View vehicle battery and range")
        .supportedFamilies([
            .accessoryCircular,
            .accessoryCorner,
            .accessoryRectangular,
            .accessoryInline
        ])
    }
}

struct VehicleStatusComplicationView: View {
    let entry: WatchComplicationEntry
    @Environment(\.widgetFamily) var family

    var body: some View {
        if AppTheme.persisted == .rivian {
            RivianComplicationView(entry: entry, family: family)
        } else {
            classicBody
        }
    }

    @ViewBuilder
    private var classicBody: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                if let percentage = entry.batteryPercentage {
                    Gauge(value: percentage, in: 0...100) {
                        Image(systemName: "car.fill")
                    } currentValueLabel: {
                        Text("\(Int(percentage))")
                            .font(.system(.body, design: .rounded))
                    }
                    .gaugeStyle(.accessoryCircular)
                } else {
                    AccessoryWidgetBackground()
                    Image(systemName: "car.fill")
                        .font(.title2)
                }
            }
        case .accessoryCorner:
            Image(systemName: "car.fill")
                .font(.title2)
                .widgetLabel {
                    if let range = entry.rangeText {
                        Text(range)
                    } else if let name = entry.vehicleName {
                        Text(name)
                    } else {
                        Text("GhostBlue")
                    }
                }
        case .accessoryRectangular:
            HStack {
                Image(systemName: "car.fill")
                    .font(.title2)
                VStack(alignment: .leading) {
                    Text(entry.vehicleName ?? "GhostBlue")
                        .font(.headline)
                    if let range = entry.rangeText {
                        Text(range)
                            .font(.caption)
                    }
                }
                Spacer()
            }
        case .accessoryInline:
            if let range = entry.rangeText {
                Label(range, systemImage: "car.fill")
            } else {
                Label("GhostBlue", systemImage: "car.fill")
            }
        default:
            Image(systemName: "car.fill")
        }
    }
}

/// Rivian theme: green charge gauges and a gold accent on full-colour faces.
private struct RivianComplicationView: View {
    let entry: WatchComplicationEntry
    let family: WidgetFamily

    var body: some View {
        switch family {
        case .accessoryCircular:
            if let percentage = entry.batteryPercentage {
                Gauge(value: percentage, in: 0...100) {
                    Image(systemName: "bolt.fill")
                } currentValueLabel: {
                    Text("\(Int(percentage))")
                        .font(.system(.body, design: .rounded))
                }
                .gaugeStyle(.accessoryCircular)
                .tint(RVTokens.battery(percentage))
            } else {
                ZStack {
                    AccessoryWidgetBackground()
                    Image(systemName: "car.fill")
                        .font(.title2)
                }
            }
        case .accessoryCorner:
            Image(systemName: "bolt.car.fill")
                .font(.title2)
                .foregroundStyle(RVTokens.accent)
                .widgetLabel {
                    if let percentage = entry.batteryPercentage {
                        Gauge(value: percentage, in: 0...100) {
                            Text(entry.rangeText ?? "")
                        } currentValueLabel: {
                            Text("\(Int(percentage))%")
                        }
                        .tint(RVTokens.battery(percentage))
                    } else {
                        Text(entry.rangeText ?? entry.vehicleName ?? "GhostBlue")
                    }
                }
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(entry.vehicleName ?? "GhostBlue")
                        .font(.headline)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Image(systemName: "bolt.fill")
                        .foregroundStyle(RVTokens.accent)
                        .widgetAccentable()
                }
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    if let percentage = entry.batteryPercentage {
                        Text("\(Int(percentage))%")
                            .font(.system(.title3, design: .rounded).weight(.bold))
                    }
                    if let range = entry.rangeText {
                        Text(range)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                if let percentage = entry.batteryPercentage {
                    Gauge(value: percentage, in: 0...100) { EmptyView() }
                        .gaugeStyle(.accessoryLinearCapacity)
                        .tint(RVTokens.battery(percentage))
                }
            }
        case .accessoryInline:
            if let percentage = entry.batteryPercentage, let range = entry.rangeText {
                Label("\(Int(percentage))% · \(range)", systemImage: "bolt.car.fill")
            } else {
                Label(entry.rangeText ?? "GhostBlue", systemImage: "car.fill")
            }
        default:
            Image(systemName: "car.fill")
        }
    }
}
