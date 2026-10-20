//
//  VehicleTimelineProvider.swift
//  GhostBlueWidget
//
//  Created by Mark Schmidt on 8/29/25.
//

import BetterBlueKit
import Foundation
import SwiftData
import SwiftUI
import UIKit
import WidgetKit

struct VehicleWidgetEntry: TimelineEntry {
    let date: Date
    let vehicle: VehicleEntity?
    let configuration: VehicleWidgetIntent
}

struct VehicleTimelineProvider: AppIntentTimelineProvider {
    func placeholder(in _: Context) -> VehicleWidgetEntry {
        VehicleWidgetEntry(date: Date(), vehicle: nil, configuration: VehicleWidgetIntent())
    }

    func snapshot(for configuration: VehicleWidgetIntent, in _: Context) async -> VehicleWidgetEntry {
        if let vehicle = configuration.vehicle {
            return VehicleWidgetEntry(date: Date(), vehicle: vehicle, configuration: configuration)
        }

        // Try to get the first available vehicle
        do {
            let vehicles = try await VehicleQuery().suggestedEntities()
            let firstVehicle = vehicles.first
            return VehicleWidgetEntry(date: Date(), vehicle: firstVehicle, configuration: configuration)
        } catch {
            return VehicleWidgetEntry(date: Date(), vehicle: nil, configuration: configuration)
        }
    }

    func timeline(for configuration: VehicleWidgetIntent, in _: Context) async -> Timeline<VehicleWidgetEntry> {
        let currentDate = Date()
        let refreshInterval = await MainActor.run {
            AppSettings.shared.widgetRefreshInterval.timeInterval
        }

        // Try to refresh vehicle data
        let updatedVehicle = await refreshVehicleData(for: configuration)

        // Create timeline entries
        var entries: [VehicleWidgetEntry] = []

        // Add current entry
        entries.append(VehicleWidgetEntry(
            date: currentDate,
            vehicle: updatedVehicle,
            configuration: configuration
        ))

        // While plugged in (charging or completed), poll every 10 min so
        // disconnection and charge-complete transitions appear promptly.
        let isPlugged = updatedVehicle?.isCharging == true || updatedVehicle?.isPluggedIn == true
        let effectiveInterval = VehicleRefreshPolicy.interval(requested: refreshInterval, pluggedIn: isPlugged)

        let nextRefreshDate = currentDate.addingTimeInterval(effectiveInterval)
        entries.append(VehicleWidgetEntry(
            date: nextRefreshDate,
            vehicle: updatedVehicle,
            configuration: configuration
        ))

        return Timeline(entries: entries, policy: .atEnd)
    }

    // MARK: - Car image (replicates Scriptable egmp-bluelink getCarImage logic)

    /// Maps model names to the keys used at bluelink.andyfase.com/app-assets/car-images
    private static let modelImageKeys: [(match: String, key: String)] = [
        ("ioniq5n",  "ioniq5n"),
        ("ioniq5",   "ioniq5"),
        ("ioniq6",   "ioniq6"),
        ("ioniq9",   "ioniq9"),
        ("ioniq",    "ioniq"),
        ("ev6",      "ev6"),
        ("ev9",      "ev9"),
        ("kona",     "kona"),
        ("niro",     "niro"),
    ]
    private static let defaultImageKey = "ioniq5"

    private func remoteImageKey(for modelName: String) -> String {
        let stripped = modelName
            .replacingOccurrences(of: #"^\d{4}\s+"#, with: "", options: .regularExpression)
        let lower = stripped.lowercased().replacingOccurrences(of: " ", with: "")
        return Self.modelImageKeys.first { lower.contains($0.match) }?.key ?? Self.defaultImageKey
    }

    private func carImageCacheURL(modelKey: String, color: String) -> URL? {
        let fm = FileManager.default
        let base: URL
        if let group = fm.containerURL(forSecurityApplicationGroupIdentifier: "group.com.saivenkatkrishna.ghostblue.shared") {
            base = group.appendingPathComponent("CarImages", isDirectory: true)
        } else {
            base = fm.temporaryDirectory.appendingPathComponent("CarImages", isDirectory: true)
        }
        try? fm.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("\(modelKey)_\(color).png")
    }

    /// Fetches one color variant from bluelink.andyfase.com.
    /// Falls back to white if the requested color is unavailable (mirrors Scriptable getCarImage).
    private func fetchAndCacheCarImage(modelName: String, color: String = "white") async -> String? {
        let key = remoteImageKey(for: modelName)
        guard let cachePath = carImageCacheURL(modelKey: key, color: color) else { return nil }

        // Serve from disk cache if already downloaded
        if FileManager.default.fileExists(atPath: cachePath.path) {
            return cachePath.path
        }

        // URL mirrors the Scriptable script exactly (double-slash is intentional)
        let urlString = "https://bluelink.andyfase.com/app-assets/car-images//\(key)/\(color).png"
        guard let url = URL(string: urlString) else { return nil }

        do {
            // A widget must always return a timeline promptly. A car render
            // is decorative, so cap its network work and fall back to the
            // vector silhouette rather than letting a slow CDN block status.
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = 8
            configuration.timeoutIntervalForResource = 10
            let (data, response) = try await URLSession(configuration: configuration).data(from: url)
            if let http = response as? HTTPURLResponse, http.statusCode == 200,
               UIImage(data: data) != nil {
                try data.write(to: cachePath, options: .atomic)
                BBLogger.info(.app, "Widget: Car image cached for \(key)/\(color)")
                return cachePath.path
            }
            // Color not found on CDN — fall back to white (same logic as Scriptable)
            if color != "white" {
                BBLogger.info(.app, "Widget: \(color) not available for \(key), falling back to white")
                return await fetchAndCacheCarImage(modelName: modelName, color: "white")
            }
            return nil
        } catch {
            BBLogger.error(.app, "Widget: Car image fetch failed for \(key)/\(color): \(error)")
            if color != "white" {
                return await fetchAndCacheCarImage(modelName: modelName, color: "white")
            }
            return nil
        }
    }

    @MainActor
    private func refreshVehicleData(for configuration: VehicleWidgetIntent) async -> VehicleEntity? {
        do {
            let modelContainer = try createSharedModelContainer(enableCloudKit: false)

            // Configure the HTTP log sink manager for widget
            await MainActor.run {
                HTTPLogSinkManager.shared.configure(with: modelContainer, deviceType: .widget)
            }

            let context = ModelContext(modelContainer)

            // Get the target vehicle
            let targetVehicle: BBVehicle?
            if let configVehicle = configuration.vehicle {
                let vin = configVehicle.vin
                var descriptor = FetchDescriptor<BBVehicle>(predicate: #Predicate { $0.vin == vin })
                descriptor.fetchLimit = 1
                targetVehicle = try context.fetch(descriptor).first
            } else {
                var descriptor = FetchDescriptor<BBVehicle>(
                    predicate: #Predicate { !$0.isHidden },
                    sortBy: [SortDescriptor(\.sortOrder)]
                )
                descriptor.fetchLimit = 1
                let vehicles = try context.fetch(descriptor)
                targetVehicle = vehicles.first
            }

            guard let bbVehicle = targetVehicle,
                  let account = bbVehicle.account
            else {
                BBLogger.info(.app, "Widget: No vehicle or account found for refresh")
                return await getVehicleEntityFromContext(for: configuration, context: context)
            }

            let vehicleName = bbVehicle.displayName
            let lastUpdated = bbVehicle.lastUpdated ?? Date.distantPast
            let timeSinceLastUpdate = Date().timeIntervalSince(lastUpdated)
            let refreshInterval = VehicleRefreshPolicy.interval(
                requested: AppSettings.shared.widgetRefreshInterval.timeInterval,
                pluggedIn: bbVehicle.evStatus?.pluggedIn == true || bbVehicle.evStatus?.charging == true
            )

            if !VehicleRefreshPolicy.shouldRefresh(
                lastFetch: bbVehicle.lastUpdated,
                interval: VehicleRefreshPolicy.freshnessWindow(forReloadInterval: refreshInterval)
            ) {
                BBLogger.info(.app, "Widget: Using fresh data for \(vehicleName) (updated \(Int(timeSinceLastUpdate / 60))m ago)")
            } else {
                BBLogger.info(.app, "Widget: Refreshing stale vehicle status for \(vehicleName) (last updated \(Int(timeSinceLastUpdate / 60))m ago)")

                // Refresh vehicle status only if data is stale
                try await account.fetchAndUpdateVehicleStatus(for: bbVehicle, modelContext: context)

                BBLogger.info(.app, "Widget: Successfully refreshed \(vehicleName)")
            }

            // Create vehicle entity on the main actor to avoid capture issues
            let unit = await MainActor.run { AppSettings.shared.preferredDistanceUnit }
            let allPresets = bbVehicle.safeClimatePresets.map { ClimatePresetEntity(from: $0) }
            var vehicleEntity = VehicleEntity(from: bbVehicle, with: unit, allPresets: allPresets)
            vehicleEntity.cachedImagePath = await fetchAndCacheCarImage(
                modelName: bbVehicle.model,
                color: bbVehicle.vehicleImageColor.rawValue
            )
            return vehicleEntity

        } catch {
            BBLogger.error(.app, "Widget: Failed to refresh vehicle data: \(error)")

            // Fall back to cached data
            do {
                let modelContainer = try createSharedModelContainer(enableCloudKit: false)

                // Configure the HTTP log sink manager for widget fallback
                await MainActor.run {
                    HTTPLogSinkManager.shared.configure(with: modelContainer, deviceType: .widget)
                }

                let context = ModelContext(modelContainer)
                return await getVehicleEntityFromContext(for: configuration, context: context)
            } catch {
                BBLogger.error(.app, "Widget: Failed to get cached vehicle data: \(error)")
                return nil
            }
        }
    }

    @MainActor
    private func getVehicleEntityFromContext(
        for configuration: VehicleWidgetIntent,
        context: ModelContext
    ) async -> VehicleEntity? {
        do {
            let targetVehicle: BBVehicle?
            if let configVehicle = configuration.vehicle {
                let vin = configVehicle.vin
                var descriptor = FetchDescriptor<BBVehicle>(predicate: #Predicate { $0.vin == vin })
                descriptor.fetchLimit = 1
                targetVehicle = try context.fetch(descriptor).first
            } else {
                var descriptor = FetchDescriptor<BBVehicle>(
                    predicate: #Predicate { !$0.isHidden },
                    sortBy: [SortDescriptor(\.sortOrder)]
                )
                descriptor.fetchLimit = 1
                let vehicles = try context.fetch(descriptor)
                targetVehicle = vehicles.first
            }

            guard let bbVehicle = targetVehicle else {
                return nil
            }

            let unit = await MainActor.run { AppSettings.shared.preferredDistanceUnit }
            let allPresets = bbVehicle.safeClimatePresets.map { ClimatePresetEntity(from: $0) }
            var vehicleEntity = VehicleEntity(from: bbVehicle, with: unit, allPresets: allPresets)
            vehicleEntity.cachedImagePath = await fetchAndCacheCarImage(
                modelName: bbVehicle.model,
                color: bbVehicle.vehicleImageColor.rawValue
            )
            return vehicleEntity

        } catch {
            BBLogger.error(.app, "Widget: Failed to fetch vehicles from context: \(error)")
            return nil
        }
    }
}
