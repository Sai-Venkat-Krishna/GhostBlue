//
//  AppSettings.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 8/3/25.
//

import BetterBlueKit
import Foundation
import SwiftUI
import WidgetKit

#if canImport(WatchConnectivity)
    import WatchConnectivity
#endif

#if canImport(ActivityKit)
    import ActivityKit
#endif

#if canImport(UserNotifications)
    import UserNotifications
#endif

extension Notification.Name {
    static let companionPreferencesDidChange = Notification.Name(
        "GhostBlue.CompanionPreferencesDidChange"
    )
}

#if canImport(WatchConnectivity)
    final class CompanionPreferenceSync: NSObject, WCSessionDelegate, @unchecked Sendable {
        static let shared = CompanionPreferenceSync()

        private let defaults = UserDefaults(
            suiteName: "group.com.saivenkatkrishna.ghostblue.shared"
        ) ?? .standard
        private let preferenceKeys = [
            "DistanceUnit",
            "TemperatureUnit",
            "HomeSectionOrder",
            "HomeHiddenSections",
            "HomeSectionSizes",
            "HomeDashboardPreset",
            "VehicleHeroSize",
            "VehicleHeroAlignment",
            "AppTheme",
            "AppAccent",
            "ShowShortTrips"
        ]

        func activate() {
            guard WCSession.isSupported(),
                  Bundle.main.bundleURL.pathExtension != "appex"
            else { return }
            let session = WCSession.default
            session.delegate = self
            session.activate()
        }

        func send(key: String, value: String) {
            guard WCSession.isSupported(),
                  Bundle.main.bundleURL.pathExtension != "appex"
            else { return }
            var context = WCSession.default.applicationContext
            var preferences = context["preferences"] as? [String: String] ?? [:]
            preferences[key] = value
            context["preferences"] = preferences
            try? WCSession.default.updateApplicationContext(context)
        }

        func sendVehicleAppearance(vin: String, model: String, color: String) {
            guard WCSession.isSupported(),
                  Bundle.main.bundleURL.pathExtension != "appex"
            else { return }
            var context = WCSession.default.applicationContext
            var appearances = context["vehicleAppearances"]
                as? [String: [String: String]] ?? [:]
            appearances[vin] = ["model": model, "color": color]
            context["vehicleAppearances"] = appearances
            try? WCSession.default.updateApplicationContext(context)
        }

        func session(
            _ session: WCSession,
            activationDidCompleteWith activationState: WCSessionActivationState,
            error: Error?
        ) {
            guard activationState == .activated else { return }
            var context = session.applicationContext
            var preferences = context["preferences"] as? [String: String] ?? [:]
            for key in preferenceKeys {
                if let value = defaults.string(forKey: key) {
                    preferences[key] = value
                }
            }
            context["preferences"] = preferences
            try? session.updateApplicationContext(context)
        }

        #if os(iOS)
            func sessionDidBecomeInactive(_ session: WCSession) {}

            func sessionDidDeactivate(_ session: WCSession) {
                session.activate()
            }
        #endif

        func session(
            _ session: WCSession,
            didReceiveApplicationContext applicationContext: [String: Any]
        ) {
            if let preferences = applicationContext["preferences"] as? [String: String] {
                for (key, value) in preferences {
                    defaults.set(value, forKey: key)
                }
            }

            if let appearances = applicationContext["vehicleAppearances"]
                as? [String: [String: String]] {
                for (vin, appearance) in appearances {
                    guard let model = appearance["model"],
                          let color = appearance["color"] else { continue }
                    VehicleAppearanceStore.save(vin: vin, model: model, color: color)
                }
            }

            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: .companionPreferencesDidChange,
                    object: nil
                )
            }
        }
    }
#endif

enum AppRefreshInterval: Int, CaseIterable {
    case oneMinute = 1
    case twoMinutes = 2
    case fiveMinutes = 5
    case tenMinutes = 10

    var displayName: String {
        switch self {
        case .oneMinute:   return "1 minute"
        case .twoMinutes:  return "2 minutes"
        case .fiveMinutes: return "5 minutes"
        case .tenMinutes:  return "10 minutes"
        }
    }

    var timeInterval: TimeInterval {
        return TimeInterval(rawValue * 60)
    }
}

enum WidgetRefreshInterval: Int, CaseIterable {
    case oneHour = 1
    case twoHours = 2
    case threeHours = 3
    case fourHours = 4
    case sixHours = 6
    case twelveHours = 12

    var displayName: String {
        switch self {
        case .oneHour:
            return "1 hour"
        case .twoHours:
            return "2 hours"
        case .threeHours:
            return "3 hours"
        case .fourHours:
            return "4 hours"
        case .sixHours:
            return "6 hours"
        case .twelveHours:
            return "12 hours"
        }
    }

    var timeInterval: TimeInterval {
        return TimeInterval(rawValue * 3600) // Convert hours to seconds
    }
}

enum HomeSection: String, CaseIterable, Codable, Identifiable, Sendable {
    case briefing
    case charging
    case health
    case status
    case location

    var id: String { rawValue }

    var title: String {
        switch self {
        case .briefing: "Smart Briefing"
        case .charging: "Charging"
        case .health: "Vehicle Health"
        case .status: "Vehicle Status"
        case .location: "Location"
        }
    }

    var systemImage: String {
        switch self {
        case .briefing: "sparkles"
        case .charging: "bolt.fill"
        case .health: "heart.text.square.fill"
        case .status: "gauge.with.needle"
        case .location: "map.fill"
        }
    }

    static func normalizedOrder(from rawValues: [String]) -> [HomeSection] {
        var seen = Set<HomeSection>()
        var result: [HomeSection] = []

        for rawValue in rawValues {
            guard let section = HomeSection(rawValue: rawValue),
                  seen.insert(section).inserted
            else {
                continue
            }
            result.append(section)
        }

        for section in allCases where seen.insert(section).inserted {
            result.append(section)
        }
        return result
    }
}

enum HomeCardSize: String, CaseIterable, Codable, Identifiable, Sendable {
    case compact
    case standard
    case expanded

    var id: String { rawValue }
    var title: String { rawValue.capitalized }
}

enum HomeDashboardPreset: String, CaseIterable, Codable, Identifiable, Sendable {
    case essential
    case charging
    case roadTrip
    case detailed

    var id: String { rawValue }

    var title: String {
        switch self {
        case .essential: "Essential"
        case .charging: "Charging"
        case .roadTrip: "Road Trip"
        case .detailed: "Detailed"
        }
    }
}

enum VehicleHeroSize: String, CaseIterable, Codable, Identifiable, Sendable {
    case compact
    case standard
    case large

    var id: String { rawValue }
    var title: String { rawValue.capitalized }

    var imageHeight: CGFloat {
        switch self {
        case .compact: 104
        case .standard: 144
        case .large: 180
        }
    }
}

enum VehicleHeroAlignment: String, CaseIterable, Codable, Identifiable, Sendable {
    case leading
    case center
    case trailing

    var id: String { rawValue }
    var title: String { rawValue.capitalized }

    var alignment: Alignment {
        switch self {
        case .leading: .leading
        case .center: .center
        case .trailing: .trailing
        }
    }
}

enum AppTheme: String, CaseIterable, Codable, Identifiable, Sendable {
    case midnight
    case hyundaiBlue
    case aurora
    case minimal

    var id: String { rawValue }

    var title: String {
        switch self {
        case .midnight: "Midnight"
        case .hyundaiBlue: "Hyundai Blue"
        case .aurora: "Aurora"
        case .minimal: "Minimal"
        }
    }

    var colors: [Color] {
        switch self {
        case .midnight:
            [Color(red: 0.035, green: 0.055, blue: 0.16), Color(red: 0.06, green: 0.11, blue: 0.30)]
        case .hyundaiBlue:
            [Color(red: 0.02, green: 0.13, blue: 0.30), Color(red: 0.00, green: 0.36, blue: 0.66)]
        case .aurora:
            [Color(red: 0.06, green: 0.12, blue: 0.24), Color(red: 0.06, green: 0.40, blue: 0.35)]
        case .minimal:
            [Color(red: 0.10, green: 0.11, blue: 0.14), Color(red: 0.17, green: 0.18, blue: 0.22)]
        }
    }
}

enum AppAccent: String, CaseIterable, Codable, Identifiable, Sendable {
    case cyan
    case blue
    case green
    case orange
    case purple

    var id: String { rawValue }
    var title: String { rawValue.capitalized }

    var color: Color {
        switch self {
        case .cyan: .cyan
        case .blue: .blue
        case .green: .green
        case .orange: .orange
        case .purple: .purple
        }
    }
}

/// Protocol to abstract storage for cross-device sync (iCloud on device, shared file in simulator)
private protocol SyncStore {
    func string(forKey key: String) -> String?
    func setString(_ value: String, forKey key: String)
    func performSync()
}

extension NSUbiquitousKeyValueStore: SyncStore {
    func setString(_ value: String, forKey key: String) {
        set(value as Any, forKey: key)
    }

    func performSync() {
        synchronize()
    }
}

/// UserDefaults-backed sync store for real devices when iCloud is unavailable (free developer account)
private final class UserDefaultsSyncStore: SyncStore {
    private let defaults: UserDefaults

    init(_ defaults: UserDefaults) {
        self.defaults = defaults
    }

    func string(forKey key: String) -> String? {
        defaults.string(forKey: key)
    }

    func setString(_ value: String, forKey key: String) {
        defaults.set(value, forKey: key)
    }

    func performSync() {
        defaults.synchronize()
    }
}

/// Shared UserDefaults-based sync store for simulator (uses /tmp/GhostBlue_Shared)
private final class SimulatorSyncStore: SyncStore {
    private let defaults: UserDefaults

    init() {
        let sharedPath = "/tmp/GhostBlue_Shared"
        try? FileManager.default.createDirectory(
            atPath: sharedPath,
            withIntermediateDirectories: true,
            attributes: nil
        )
        // Use a UserDefaults with a custom suite that points to the shared location
        defaults = UserDefaults(suiteName: sharedPath) ?? UserDefaults.standard
    }

    func string(forKey key: String) -> String? {
        defaults.string(forKey: key)
    }

    func setString(_ value: String, forKey key: String) {
        defaults.set(value, forKey: key)
    }

    func performSync() {
        defaults.synchronize()
    }
}

@MainActor @Observable
class AppSettings {
    static let shared = AppSettings()

    private let userDefaults = UserDefaults(suiteName: "group.com.saivenkatkrishna.ghostblue.shared")!
    private let syncStore: SyncStore
    private let isSimulator: Bool
    private let distanceUnitKey = "DistanceUnit"
    private let temperatureUnitKey = "TemperatureUnit"
    private let notificationsEnabledKey = "NotificationsEnabled"
    private let widgetRefreshIntervalKey = "WidgetRefreshInterval"
    private let appRefreshIntervalKey = "AppRefreshInterval"
    private let debugModeEnabledKey = "DebugModeEnabled"
    private let liveActivitiesEnabledKey = "LiveActivitiesEnabled"
    private let abrpPlanApiKeyKey = "ABRPPlanAPIKey"
    private let abrpTelemetryApiKeyKey = "ABRPTelemetryAPIKey"
    private let abrpTelemetryEnabledKey = "ABRPTelemetryEnabled"
    private let homeSectionOrderKey = "HomeSectionOrder"
    private let homeHiddenSectionsKey = "HomeHiddenSections"
    private let homeSectionSizesKey = "HomeSectionSizes"
    private let homeDashboardPresetKey = "HomeDashboardPreset"
    private let vehicleHeroSizeKey = "VehicleHeroSize"
    private let vehicleHeroAlignmentKey = "VehicleHeroAlignment"
    private let appThemeKey = "AppTheme"
    private let appAccentKey = "AppAccent"
    private let showShortTripsKey = "ShowShortTrips"
    private let lastViewedTripsDateKey = "LastViewedTripsDate"
    private let requireBiometricUnlockKey = "RequireBiometricUnlock"
    private let hideVehicleLocationKey = "HideVehicleLocation"
    private let redactPrivateLocationsKey = "RedactPrivateLocations"
    private let quietHoursEnabledKey = "QuietHoursEnabled"
    private let quietHoursStartKey = "QuietHoursStart"
    private let quietHoursEndKey = "QuietHoursEnd"
    private let hasCompletedOnboardingKey = "HasCompletedOwnershipOnboarding"

    var preferredDistanceUnit: Distance.Units {
        didSet {
            // Write to both sync store (for cross-device sync) and UserDefaults (for widgets)
            syncStore.setString(preferredDistanceUnit.rawValue, forKey: distanceUnitKey)
            userDefaults.set(preferredDistanceUnit.rawValue, forKey: distanceUnitKey)
            syncStore.performSync()
            sendCompanionPreference(key: distanceUnitKey, value: preferredDistanceUnit.rawValue)
            refreshWidgetsAndLiveActivities()
        }
    }

    var preferredTemperatureUnit: Temperature.Units {
        didSet {
            // Write to both sync store (for cross-device sync) and UserDefaults (for widgets)
            syncStore.setString(preferredTemperatureUnit.rawValue, forKey: temperatureUnitKey)
            userDefaults.set(preferredTemperatureUnit.rawValue, forKey: temperatureUnitKey)
            syncStore.performSync()
            sendCompanionPreference(key: temperatureUnitKey, value: preferredTemperatureUnit.rawValue)
            refreshWidgetsAndLiveActivities()
        }
    }

    var notificationsEnabled: Bool {
        didSet {
            userDefaults.set(notificationsEnabled, forKey: notificationsEnabledKey)
            if notificationsEnabled {
                #if canImport(UserNotifications)
                    Task {
                        await requestNotificationPermission()
                    }
                #endif
            }
        }
    }

    var widgetRefreshInterval: WidgetRefreshInterval {
        didSet {
            userDefaults.set(widgetRefreshInterval.rawValue, forKey: widgetRefreshIntervalKey)
        }
    }

    var appRefreshInterval: AppRefreshInterval {
        didSet {
            userDefaults.set(appRefreshInterval.rawValue, forKey: appRefreshIntervalKey)
        }
    }

    var debugModeEnabled: Bool {
        didSet {
            userDefaults.set(debugModeEnabled, forKey: debugModeEnabledKey)
        }
    }

    var liveActivitiesEnabled: Bool {
        didSet {
            userDefaults.set(liveActivitiesEnabled, forKey: liveActivitiesEnabledKey)
        }
    }

    /// Iternio plan API key (requested from Iternio). Empty string = unset.
    var abrpPlanApiKey: String {
        didSet {
            userDefaults.set(abrpPlanApiKey, forKey: abrpPlanApiKeyKey)
        }
    }

    /// Iternio telemetry API key (free, telemetry-only). Empty string = unset.
    var abrpTelemetryApiKey: String {
        didSet {
            userDefaults.set(abrpTelemetryApiKey, forKey: abrpTelemetryApiKeyKey)
        }
    }

    var abrpTelemetryEnabled: Bool {
        didSet {
            userDefaults.set(abrpTelemetryEnabled, forKey: abrpTelemetryEnabledKey)
        }
    }

    var homeSectionOrder: [HomeSection] {
        didSet {
            let value = homeSectionOrder.map(\.rawValue).joined(separator: ",")
            userDefaults.set(homeSectionOrder.map(\.rawValue), forKey: homeSectionOrderKey)
            syncStore.setString(value, forKey: homeSectionOrderKey)
            syncStore.performSync()
            sendCompanionPreference(key: homeSectionOrderKey, value: value)
        }
    }

    var homeHiddenSections: Set<HomeSection> {
        didSet {
            let value = homeHiddenSections.map(\.rawValue).sorted().joined(separator: ",")
            userDefaults.set(Array(homeHiddenSections.map(\.rawValue)), forKey: homeHiddenSectionsKey)
            syncStore.setString(value, forKey: homeHiddenSectionsKey)
            syncStore.performSync()
            sendCompanionPreference(key: homeHiddenSectionsKey, value: value)
        }
    }

    var homeSectionSizes: [HomeSection: HomeCardSize] {
        didSet {
            let raw = Dictionary(uniqueKeysWithValues: homeSectionSizes.map { ($0.key.rawValue, $0.value.rawValue) })
            if let data = try? JSONEncoder().encode(raw),
               let value = String(data: data, encoding: .utf8) {
                userDefaults.set(value, forKey: homeSectionSizesKey)
                syncStore.setString(value, forKey: homeSectionSizesKey)
                syncStore.performSync()
                sendCompanionPreference(key: homeSectionSizesKey, value: value)
            }
        }
    }

    var homeDashboardPreset: HomeDashboardPreset {
        didSet { persistSynced(homeDashboardPreset.rawValue, key: homeDashboardPresetKey) }
    }

    var vehicleHeroSize: VehicleHeroSize {
        didSet { persistSynced(vehicleHeroSize.rawValue, key: vehicleHeroSizeKey) }
    }

    var vehicleHeroAlignment: VehicleHeroAlignment {
        didSet { persistSynced(vehicleHeroAlignment.rawValue, key: vehicleHeroAlignmentKey) }
    }

    var appTheme: AppTheme {
        didSet {
            persistSynced(appTheme.rawValue, key: appThemeKey)
            refreshWidgetsAndLiveActivities()
        }
    }

    var appAccent: AppAccent {
        didSet {
            persistSynced(appAccent.rawValue, key: appAccentKey)
            refreshWidgetsAndLiveActivities()
        }
    }

    var showShortTrips: Bool {
        didSet { persistSynced(showShortTrips ? "true" : "false", key: showShortTripsKey) }
    }

    var lastViewedTripsDate: Date {
        didSet {
            userDefaults.set(lastViewedTripsDate.timeIntervalSince1970, forKey: lastViewedTripsDateKey)
        }
    }

    var requireBiometricUnlock: Bool {
        didSet { userDefaults.set(requireBiometricUnlock, forKey: requireBiometricUnlockKey) }
    }

    var hideVehicleLocation: Bool {
        didSet { userDefaults.set(hideVehicleLocation, forKey: hideVehicleLocationKey) }
    }

    var redactPrivateLocations: Bool {
        didSet { userDefaults.set(redactPrivateLocations, forKey: redactPrivateLocationsKey) }
    }

    var quietHoursEnabled: Bool {
        didSet { userDefaults.set(quietHoursEnabled, forKey: quietHoursEnabledKey) }
    }

    var quietHoursStart: Int {
        didSet { userDefaults.set(quietHoursStart, forKey: quietHoursStartKey) }
    }

    var quietHoursEnd: Int {
        didSet { userDefaults.set(quietHoursEnd, forKey: quietHoursEndKey) }
    }

    var hasCompletedOwnershipOnboarding: Bool {
        didSet { userDefaults.set(hasCompletedOwnershipOnboarding, forKey: hasCompletedOnboardingKey) }
    }

    var isInNotificationQuietHours: Bool {
        guard quietHoursEnabled else { return false }
        let hour = Calendar.current.component(.hour, from: Date())
        if quietHoursStart <= quietHoursEnd {
            return hour >= quietHoursStart && hour < quietHoursEnd
        }
        return hour >= quietHoursStart || hour < quietHoursEnd
    }

    var accentColor: Color { appAccent.color }
    var themeColors: [Color] { appTheme.colors }

    private init() {
        #if targetEnvironment(simulator)
            isSimulator = true
            syncStore = SimulatorSyncStore()
        #else
            isSimulator = false
            syncStore = UserDefaultsSyncStore(UserDefaults(suiteName: "group.com.saivenkatkrishna.ghostblue.shared") ?? .standard)
        #endif

        // Initialize unit preferences from sync store (iCloud on device, shared file in simulator)
        let savedDistanceUnit = syncStore.string(forKey: distanceUnitKey)
            ?? userDefaults.string(forKey: distanceUnitKey)
            ?? Distance.Units.miles.rawValue
        preferredDistanceUnit = Distance.Units(rawValue: savedDistanceUnit) ?? .miles

        let savedTemperatureUnit = syncStore.string(forKey: temperatureUnitKey)
            ?? userDefaults.string(forKey: temperatureUnitKey)
            ?? Temperature.Units.fahrenheit.rawValue
        preferredTemperatureUnit = Temperature.Units(rawValue: savedTemperatureUnit) ?? .fahrenheit

        notificationsEnabled = userDefaults.bool(forKey: notificationsEnabledKey)

        let savedRefreshInterval = userDefaults.integer(forKey: widgetRefreshIntervalKey)
        widgetRefreshInterval = WidgetRefreshInterval(rawValue: savedRefreshInterval) ?? .fourHours

        let savedAppRefreshInterval = userDefaults.integer(forKey: appRefreshIntervalKey)
        appRefreshInterval = AppRefreshInterval(rawValue: savedAppRefreshInterval) ?? .twoMinutes

        if userDefaults.object(forKey: debugModeEnabledKey) == nil {
            #if DEBUG
                debugModeEnabled = true
            #else
                debugModeEnabled = false
            #endif
        } else {
            debugModeEnabled = userDefaults.bool(forKey: debugModeEnabledKey)
        }

        // Live Activities is a beta feature, disabled by default
        liveActivitiesEnabled = userDefaults.bool(forKey: liveActivitiesEnabledKey)

        abrpPlanApiKey = userDefaults.string(forKey: abrpPlanApiKeyKey) ?? ""
        abrpTelemetryApiKey = userDefaults.string(forKey: abrpTelemetryApiKeyKey) ?? ""
        abrpTelemetryEnabled = userDefaults.bool(forKey: abrpTelemetryEnabledKey)
        let syncedOrder = syncStore.string(forKey: homeSectionOrderKey)?
            .split(separator: ",").map(String.init)
        homeSectionOrder = HomeSection.normalizedOrder(
            from: syncedOrder ?? userDefaults.stringArray(forKey: homeSectionOrderKey) ?? []
        )
        let hiddenRaw = syncStore.string(forKey: homeHiddenSectionsKey)?
            .split(separator: ",").map(String.init)
            ?? userDefaults.stringArray(forKey: homeHiddenSectionsKey)
            ?? []
        homeHiddenSections = Set(hiddenRaw.compactMap(HomeSection.init(rawValue:)))

        let sizesString = syncStore.string(forKey: homeSectionSizesKey)
            ?? userDefaults.string(forKey: homeSectionSizesKey)
        if let sizesString,
           let data = sizesString.data(using: .utf8),
           let raw = try? JSONDecoder().decode([String: String].self, from: data) {
            homeSectionSizes = Dictionary(
                uniqueKeysWithValues: raw.compactMap { key, value in
                    guard let section = HomeSection(rawValue: key),
                          let size = HomeCardSize(rawValue: value) else { return nil }
                    return (section, size)
                }
            )
        } else {
            homeSectionSizes = [:]
        }
        homeDashboardPreset = HomeDashboardPreset(
            rawValue: syncStore.string(forKey: homeDashboardPresetKey)
                ?? userDefaults.string(forKey: homeDashboardPresetKey)
                ?? ""
        ) ?? .detailed
        vehicleHeroSize = VehicleHeroSize(
            rawValue: syncStore.string(forKey: vehicleHeroSizeKey)
                ?? userDefaults.string(forKey: vehicleHeroSizeKey)
                ?? ""
        ) ?? .standard
        vehicleHeroAlignment = VehicleHeroAlignment(
            rawValue: syncStore.string(forKey: vehicleHeroAlignmentKey)
                ?? userDefaults.string(forKey: vehicleHeroAlignmentKey)
                ?? ""
        ) ?? .center
        appTheme = AppTheme(
            rawValue: syncStore.string(forKey: appThemeKey)
                ?? userDefaults.string(forKey: appThemeKey)
                ?? ""
        ) ?? .midnight
        appAccent = AppAccent(
            rawValue: syncStore.string(forKey: appAccentKey)
                ?? userDefaults.string(forKey: appAccentKey)
                ?? ""
        ) ?? .cyan
        showShortTrips = (syncStore.string(forKey: showShortTripsKey)
            ?? userDefaults.string(forKey: showShortTripsKey)) == "true"
        lastViewedTripsDate = Date(
            timeIntervalSince1970: userDefaults.double(forKey: lastViewedTripsDateKey)
        )
        requireBiometricUnlock = userDefaults.bool(forKey: requireBiometricUnlockKey)
        hideVehicleLocation = userDefaults.bool(forKey: hideVehicleLocationKey)
        if userDefaults.object(forKey: redactPrivateLocationsKey) == nil {
            redactPrivateLocations = true
        } else {
            redactPrivateLocations = userDefaults.bool(forKey: redactPrivateLocationsKey)
        }
        quietHoursEnabled = userDefaults.bool(forKey: quietHoursEnabledKey)
        quietHoursStart = userDefaults.object(forKey: quietHoursStartKey) == nil
            ? 22
            : userDefaults.integer(forKey: quietHoursStartKey)
        quietHoursEnd = userDefaults.object(forKey: quietHoursEndKey) == nil
            ? 7
            : userDefaults.integer(forKey: quietHoursEndKey)
        hasCompletedOwnershipOnboarding = userDefaults.bool(forKey: hasCompletedOnboardingKey)

        // Start sync store
        syncStore.performSync()
        #if canImport(WatchConnectivity)
            CompanionPreferenceSync.shared.activate()
            NotificationCenter.default.addObserver(
                forName: .companionPreferencesDidChange,
                object: nil,
                queue: .main
            ) { _ in
                MainActor.assumeIsolated {
                    AppSettings.shared.reloadCompanionPreferences()
                }
            }
        #endif
    }

    private func requestNotificationPermission() async {
        #if canImport(UserNotifications)
            do {
                let granted = try await UNUserNotificationCenter.current()
                    .requestAuthorization(options: [.alert, .sound, .badge])
                if granted {
                    BBLogger.info(.push, "Notifications: Permission granted")
                } else {
                    BBLogger.warning(.push, "Notifications: Permission denied")
                    await MainActor.run {
                        notificationsEnabled = false
                    }
                }
            } catch {
                BBLogger.error(.push, "Notifications: Permission request failed: \(error)")
                await MainActor.run {
                    notificationsEnabled = false
                }
            }
        #endif
    }

    func size(for section: HomeSection) -> HomeCardSize {
        homeSectionSizes[section] ?? .standard
    }

    func setSize(_ size: HomeCardSize, for section: HomeSection) {
        homeSectionSizes[section] = size
    }

    func applyDashboardPreset(_ preset: HomeDashboardPreset) {
        homeDashboardPreset = preset
        switch preset {
        case .essential:
            homeSectionOrder = [.briefing, .health, .status, .charging, .location]
            homeHiddenSections = [.location]
            homeSectionSizes = [
                .briefing: .standard,
                .health: .compact,
                .status: .compact,
                .charging: .standard
            ]
        case .charging:
            homeSectionOrder = [.briefing, .charging, .health, .status, .location]
            homeHiddenSections = [.location]
            homeSectionSizes = [
                .briefing: .compact,
                .charging: .expanded,
                .health: .compact
            ]
        case .roadTrip:
            homeSectionOrder = [.briefing, .health, .location, .status, .charging]
            homeHiddenSections = []
            homeSectionSizes = [
                .briefing: .standard,
                .location: .expanded,
                .health: .standard,
                .status: .compact
            ]
        case .detailed:
            homeSectionOrder = HomeSection.allCases
            homeHiddenSections = []
            homeSectionSizes = Dictionary(uniqueKeysWithValues: HomeSection.allCases.map { ($0, .standard) })
        }
    }

    private func persistSynced(_ value: String, key: String) {
        userDefaults.set(value, forKey: key)
        syncStore.setString(value, forKey: key)
        syncStore.performSync()
        sendCompanionPreference(key: key, value: value)
    }

    private func sendCompanionPreference(key: String, value: String) {
        #if canImport(WatchConnectivity)
            CompanionPreferenceSync.shared.send(key: key, value: value)
        #endif
    }

    private func reloadCompanionPreferences() {
        if let raw = userDefaults.string(forKey: appThemeKey),
           let value = AppTheme(rawValue: raw), value != appTheme {
            appTheme = value
        }
        if let raw = userDefaults.string(forKey: appAccentKey),
           let value = AppAccent(rawValue: raw), value != appAccent {
            appAccent = value
        }
        if let raw = userDefaults.string(forKey: homeDashboardPresetKey),
           let value = HomeDashboardPreset(rawValue: raw), value != homeDashboardPreset {
            homeDashboardPreset = value
        }
        if let raw = userDefaults.string(forKey: vehicleHeroSizeKey),
           let value = VehicleHeroSize(rawValue: raw), value != vehicleHeroSize {
            vehicleHeroSize = value
        }
        if let raw = userDefaults.string(forKey: vehicleHeroAlignmentKey),
           let value = VehicleHeroAlignment(rawValue: raw), value != vehicleHeroAlignment {
            vehicleHeroAlignment = value
        }
        if let raw = userDefaults.string(forKey: showShortTripsKey) {
            let value = raw == "true"
            if value != showShortTrips {
                showShortTrips = value
            }
        }
        if let raw = userDefaults.string(forKey: homeSectionOrderKey) {
            let value = HomeSection.normalizedOrder(
                from: raw.split(separator: ",").map(String.init)
            )
            if value != homeSectionOrder {
                homeSectionOrder = value
            }
        }
        if let raw = userDefaults.string(forKey: homeHiddenSectionsKey) {
            let value = Set(
                raw.split(separator: ",")
                    .compactMap { HomeSection(rawValue: String($0)) }
            )
            if value != homeHiddenSections {
                homeHiddenSections = value
            }
        }
        if let raw = userDefaults.string(forKey: homeSectionSizesKey),
           let data = raw.data(using: .utf8),
           let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
            let value: [HomeSection: HomeCardSize] = Dictionary(
                uniqueKeysWithValues: decoded.compactMap { key, value in
                    guard let section = HomeSection(rawValue: key),
                          let size = HomeCardSize(rawValue: value) else { return nil }
                    return (section, size)
                }
            )
            if value != homeSectionSizes {
                homeSectionSizes = value
            }
        }
    }

    private func refreshWidgetsAndLiveActivities() {
        
        // Refresh all widgets to pick up the new unit settings
        BBLogger.info(.app, "Refreshing widget and live activities")
        WidgetCenter.shared.reloadAllTimelines()

        // Refresh all live activities to pick up the new unit settings
        #if canImport(ActivityKit)
            Task { @MainActor in
                for activity in Activity<VehicleActivityAttributes>.activities {
                    let currentState = activity.content.state
                    await activity.update(ActivityContent(state: currentState, staleDate: nil))
                }
            }
        #endif
    }
}
