//
//  Vehicle.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 9/6/25.
//

import BetterBlueKit
import Foundation
import SwiftData
import SwiftUI
#if canImport(UserNotifications)
import UserNotifications
#endif

/// User preference for charge port type (affects DC plug icon display)
enum ChargePortType: String, Codable, CaseIterable {
    case ccs1 = "CCS1"
    case ccs2 = "CCS2"
    case nacs = "NACS"

    var displayName: String {
        rawValue
    }

    var dcPlugIcon: String {
        switch self {
        case .ccs1: return "ev.plug.dc.ccs1"
        case .ccs2: return "ev.plug.dc.ccs2"
        case .nacs: return "ev.plug.dc.nacs"
        }
    }

    var acPlugIcon: String {
        switch self {
        case .ccs1: return "ev.plug.ac.type.1"
        case .ccs2: return "ev.plug.ac.type.2"
        case .nacs: return "ev.plug.dc.nacs"
        }
    }
}

enum VehicleImageColor: String, Codable, CaseIterable, Identifiable, Sendable {
    case white
    case black
    case grey
    case blue
    case green
    case red
    case silver
    case matteGrey = "matte-grey"
    case metallicGrey = "metallic-grey"
    case orange

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .white: "White"
        case .black: "Black"
        case .grey: "Gray"
        case .blue: "Blue"
        case .green: "Green"
        case .red: "Red"
        case .silver: "Silver"
        case .matteGrey: "Matte Gray"
        case .metallicGrey: "Metallic Gray"
        case .orange: "Orange"
        }
    }

    var swatchColor: Color {
        switch self {
        case .white: Color(red: 0.95, green: 0.95, blue: 0.95)
        case .black: Color(red: 0.15, green: 0.15, blue: 0.15)
        case .grey: Color(red: 0.55, green: 0.55, blue: 0.55)
        case .blue: Color(red: 0.20, green: 0.45, blue: 0.85)
        case .green: Color(red: 0.18, green: 0.68, blue: 0.30)
        case .red: Color(red: 0.85, green: 0.15, blue: 0.15)
        case .silver: Color(red: 0.78, green: 0.78, blue: 0.80)
        case .matteGrey: Color(red: 0.42, green: 0.44, blue: 0.46)
        case .metallicGrey: Color(red: 0.48, green: 0.50, blue: 0.54)
        case .orange: Color(red: 0.95, green: 0.35, blue: 0.08)
        }
    }

    /// The image host provides a different palette for each vehicle render.
    /// Only show variants that actually exist so a user's choice never
    /// silently turns into the white fallback.
    static func availableColors(for modelName: String) -> [VehicleImageColor] {
        let normalized = modelName
            .replacingOccurrences(
                of: #"^\d{4}\s+"#,
                with: "",
                options: .regularExpression
            )
            .lowercased()
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")

        if normalized.contains("ioniq5n") {
            return [.white, .black, .grey, .blue, .matteGrey, .orange]
        }
        if normalized.contains("ioniq5") {
            return [.white, .black, .grey, .blue, .green, .matteGrey]
        }
        if normalized.contains("ioniq6") {
            return [.white, .black, .grey, .blue, .red, .matteGrey, .metallicGrey]
        }
        if normalized.contains("ioniq9") {
            return [.white, .black, .grey, .blue, .green, .red, .matteGrey, .metallicGrey]
        }
        if normalized.contains("ioniq") {
            return [.white, .black, .grey, .blue, .green, .red, .silver]
        }
        if normalized.contains("ev6") {
            return [.white, .black, .grey, .blue, .red, .matteGrey, .metallicGrey]
        }
        if normalized.contains("ev9") {
            return [.white, .black, .grey, .blue, .green, .red, .silver, .metallicGrey]
        }
        if normalized.contains("kona") {
            return [.white, .black, .grey, .red, .metallicGrey]
        }
        if normalized.contains("niro") {
            return [.white, .black, .grey, .blue, .green, .red, .metallicGrey]
        }

        return [.white]
    }
}

struct VehicleAppearanceHint: Codable, Sendable {
    let model: String
    let color: String
}

enum SmartChargingPreference: String, Codable, Sendable {
    case charge
    case pause
}

/// Current SDG&E residential EV super-off-peak periods. Keeping the tariff
/// decision in one pure type makes app, App Intent, and test behavior agree.
/// Times are evaluated in San Diego local time, regardless of the phone's
/// current time zone.
struct SDGESmartChargingSchedule: Sendable {
    static let timeZone = TimeZone(identifier: "America/Los_Angeles")!

    static func calendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar
    }

    static func preference(
        at date: Date,
        calendar suppliedCalendar: Calendar = calendar()
    ) -> SmartChargingPreference {
        let minuteOfDay = suppliedCalendar.component(.hour, from: date) * 60
            + suppliedCalendar.component(.minute, from: date)

        if isWeekendOrHoliday(date, calendar: suppliedCalendar) {
            return minuteOfDay < 14 * 60 ? .charge : .pause
        }

        let isOvernight = minuteOfDay < 6 * 60
        let isMidday = minuteOfDay >= 10 * 60 && minuteOfDay < 14 * 60
        return isOvernight || isMidday ? .charge : .pause
    }

    static func isWeekendOrHoliday(
        _ date: Date,
        calendar suppliedCalendar: Calendar = calendar()
    ) -> Bool {
        let weekday = suppliedCalendar.component(.weekday, from: date)
        if weekday == 1 || weekday == 7 {
            return true
        }
        return isHoliday(date, calendar: suppliedCalendar)
    }

    static func isHoliday(
        _ date: Date,
        calendar suppliedCalendar: Calendar = calendar()
    ) -> Bool {
        let year = suppliedCalendar.component(.year, from: date)
        return holidayDates(in: year, calendar: suppliedCalendar).contains {
            suppliedCalendar.isDate($0, inSameDayAs: date)
        }
    }

    private static func holidayDates(in year: Int, calendar: Calendar) -> [Date] {
        var dates: [Date] = []

        // SDG&E uses the actual fixed-date holiday. When it falls on Sunday,
        // the following Monday is also the legal holiday; Saturday is not
        // shifted to Friday.
        for (month, day) in [(1, 1), (7, 4), (11, 11), (12, 25)] {
            guard let actual = makeDate(year: year, month: month, day: day, calendar: calendar) else {
                continue
            }
            dates.append(actual)
            if calendar.component(.weekday, from: actual) == 1,
               let monday = calendar.date(byAdding: .day, value: 1, to: actual) {
                dates.append(monday)
            }
        }

        // Presidents' Day, Memorial Day, Labor Day, and Thanksgiving.
        if let presidentsDay = nthWeekday(2, weekday: 2, ordinal: 3, year: year, calendar: calendar) {
            dates.append(presidentsDay)
        }
        if let memorialDay = lastWeekday(5, weekday: 2, year: year, calendar: calendar) {
            dates.append(memorialDay)
        }
        if let laborDay = nthWeekday(9, weekday: 2, ordinal: 1, year: year, calendar: calendar) {
            dates.append(laborDay)
        }
        if let thanksgiving = nthWeekday(11, weekday: 5, ordinal: 4, year: year, calendar: calendar) {
            dates.append(thanksgiving)
        }

        return dates
    }

    private static func makeDate(
        year: Int,
        month: Int,
        day: Int,
        calendar: Calendar
    ) -> Date? {
        calendar.date(from: DateComponents(year: year, month: month, day: day))
    }

    private static func nthWeekday(
        _ month: Int,
        weekday: Int,
        ordinal: Int,
        year: Int,
        calendar: Calendar
    ) -> Date? {
        calendar.date(from: DateComponents(
            year: year,
            month: month,
            weekday: weekday,
            weekdayOrdinal: ordinal
        ))
    }

    private static func lastWeekday(
        _ month: Int,
        weekday: Int,
        year: Int,
        calendar: Calendar
    ) -> Date? {
        guard let nextMonth = calendar.date(from: DateComponents(year: year, month: month + 1, day: 1)),
              let lastDay = calendar.date(byAdding: .day, value: -1, to: nextMonth)
        else { return nil }

        let currentWeekday = calendar.component(.weekday, from: lastDay)
        let daysBack = (currentWeekday - weekday + 7) % 7
        return calendar.date(byAdding: .day, value: -daysBack, to: lastDay)
    }
}

/// A tiny app-group mirror used by Live Activities, which cannot receive a
/// mutable ActivityAttributes value after an activity starts.
enum VehicleAppearanceStore {
    private static let appGroupID = "group.com.saivenkatkrishna.ghostblue.shared"
    private static let storageKey = "VehicleAppearanceHints"

    static func save(vin: String, model: String, color: String) {
        let defaults = UserDefaults(suiteName: appGroupID) ?? .standard
        var hints = allHints(from: defaults)
        hints[vin] = VehicleAppearanceHint(model: model, color: color)
        guard let data = try? JSONEncoder().encode(hints) else { return }
        defaults.set(data, forKey: storageKey)
    }

    static func hint(for vin: String) -> VehicleAppearanceHint? {
        let defaults = UserDefaults(suiteName: appGroupID) ?? .standard
        return allHints(from: defaults)[vin]
    }

    private static func allHints(from defaults: UserDefaults) -> [String: VehicleAppearanceHint] {
        guard let data = defaults.data(forKey: storageKey),
              let hints = try? JSONDecoder().decode(
                  [String: VehicleAppearanceHint].self,
                  from: data
              )
        else {
            return [:]
        }
        return hints
    }
}

@Model
class BBVehicle {
    var id: UUID = UUID()
    var vin: String = ""

    // Vehicle fields (all required)
    var regId: String = ""
    var model: String = ""
    var accountId: UUID = UUID()
    var fuelTypeRaw: String = FuelType.gas.rawValue
    var generation: Int = 0
    var odometer: Distance = Distance(length: 0, units: .miles)

    // VehicleStatus fields (all optional since status might not be fetched)
    var lastUpdated: Date?
    var syncDate: Date?
    var gasRange: VehicleStatus.FuelRange?
    var evStatus: VehicleStatus.EVStatus?
    var location: VehicleStatus.Location?
    var lockStatus: VehicleStatus.LockStatus?
    var climateStatus: VehicleStatus.ClimateStatus?

    // Additional status fields
    var battery12V: Int?
    var doorOpen: VehicleStatus.DoorStatus?
    var trunkOpen: Bool?
    var hoodOpen: Bool?
    var tirePressureWarning: VehicleStatus.TirePressureWarning?

    // Custom name and visibility (kept separate for easier queries)
    var customName: String?
    var isHidden: Bool = false
    var sortOrder: Int = 0
    var backgroundColorName: String = "default"
    var watchBackgroundColorName: String = "charcoal"
    var vehicleImageColorRaw: String = VehicleImageColor.white.rawValue
    var chargePortTypeRaw: String = ChargePortType.ccs1.rawValue
    /// Usable battery capacity used to convert charge percentage into a
    /// reliable energy figure for charging history. Nil uses sampled kWh.
    var batteryCapacityKWh: Double?
    /// Local electricity price per kWh. Nil hides estimated charging cost.
    var electricityRatePerKWh: Double?
    /// Optional reduced tariff used inside the user's preferred off-peak window.
    var offPeakElectricityRatePerKWh: Double?
    var offPeakStartHour: Int = 0
    var offPeakEndHour: Int = 6
    var monthlyChargingBudget: Double?
    /// A private home geofence used only on-device for charge classification and
    /// export/map redaction.
    var homeLatitude: Double?
    var homeLongitude: Double?
    var homePrivacyRadiusMeters: Double = 300

    // SDG&E smart charging supplements the vehicle's own 12 AM-2 PM
    // schedule. The Hyundai schedule remains the fail-safe; GhostBlue only
    // needs to pause weekday charging from 6-10 AM and can reconcile the
    // other boundaries when invoked from Shortcuts or while the app is open.
    var smartChargingEnabled: Bool = false
    var smartChargingHomeOnly: Bool = true
    var smartChargingTargetSOC: Int = 80
    var smartChargingLastRunAt: Date?
    var smartChargingLastResult: String?

    // Smart alert rules. These are per vehicle so each car can have a
    // different notification policy.
    var notifyChargeComplete: Bool = true
    var notifyChargeInterrupted: Bool = true
    var notifyUnlocked: Bool = false
    var notifyOpenPanels: Bool = true
    var notifyLowEVBattery: Bool = true
    var notifyLow12VBattery: Bool = true
    var notifyTirePressure: Bool = true
    var lowEVBatteryThreshold: Int = 20
    var low12VBatteryThreshold: Int = 35
    private var alertSnapshotData: Data?
    /// Most recent background attempt to archive the API's rolling trip
    /// window. This keeps the archive current without issuing a trip request
    /// for every status refresh or command poll.
    var lastTripArchiveAttemptDate: Date?
    var debugConfiguration: BBDebugConfiguration?
    var debugLiveActivity: Bool = false

    /// Override to show seat heat controls on older vehicles (generation < 3)
    /// Ignored for generation 3+ vehicles where seat heat controls are always shown
    var enableSeatHeatControls: Bool = false

    /// Per-vehicle toggle for the climate duration picker. `nil` = use the regional default
    /// (non-USA shows the picker, USA hides it because the API ignores the duration field
    /// there); `true`/`false` pin the visibility regardless of region.
    var showClimateDurationOverride: Bool?

    /// User override for the inferred powertrain. `nil` means "use whatever the API decided".
    /// Setting it to a non-nil value pins `fuelType` to that choice.
    var fuelTypeOverrideRaw: String?

    /// ABRP Live Data user token (ABRP app > Settings > Live Data). Per-vehicle.
    /// Deliberately excluded from the debug-export `encode(to:)`.
    var abrpUserToken: String?

    /// ABRP car model typecode for this vehicle, e.g. "hyundai:ioniq5:22:77wr".
    var abrpCarModel: String?

    // Per-action accent colors. All optional → `nil` uses the palette default resolved by
    // `CustomColors.color(forName:default:)`. Stored as palette name (String) because
    // SwiftUI `Color` isn't natively persistable.
    var primaryColorName: String?
    var chargingColorName: String?
    var gasColorName: String?
    var lockColorName: String?
    var unlockColorName: String?
    var startClimateColorName: String?
    var stopColorName: String?

    // Resolved-`Color` accessors used throughout the UI. The persisted `*Name` field is the
    // source of truth; these getters fall back to a sensible palette default when nil so
    // existing rows render correctly without a migration.
    //
    // The palette resolver is inlined here (rather than calling into `CustomColor.color(...)`)
    // because this file is also compiled by the Watch and Widget targets, which don't
    // include `CustomColors.swift`. Keeping the lookup self-contained means the file
    // compiles cleanly in every target.
    var primaryColor: Color { Self.resolveColor(primaryColorName, default: .blue) }
    var chargingColor: Color { Self.resolveColor(chargingColorName, default: .green) }
    var gasColor: Color { Self.resolveColor(gasColorName, default: .orange) }
    var lockColor: Color { Self.resolveColor(lockColorName, default: .red) }
    var unlockColor: Color { Self.resolveColor(unlockColorName, default: .green) }
    var startClimateColor: Color { Self.resolveColor(startClimateColorName, default: .blue) }
    var stopColor: Color { Self.resolveColor(stopColorName, default: .red) }

    private static func resolveColor(_ name: String?, default fallback: Color) -> Color {
        switch name {
        case "blue":   return .blue
        case "green":  return .green
        case "red":    return .red
        case "orange": return .orange
        case "yellow": return .yellow
        case "pink":   return .pink
        case "purple": return .purple
        case "indigo": return .indigo
        case "teal":   return .teal
        case "cyan":   return .cyan
        case "mint":   return .mint
        case "brown":  return .brown
        case "gray":   return .gray
        default:       return fallback
        }
    }

    var chargePortType: ChargePortType {
        get { ChargePortType(rawValue: chargePortTypeRaw) ?? .ccs1 }
        set { chargePortTypeRaw = newValue.rawValue }
    }

    var vehicleImageColor: VehicleImageColor {
        get {
            if vehicleImageColorRaw == "gray" {
                return .grey
            }
            return VehicleImageColor(rawValue: vehicleImageColorRaw) ?? .white
        }
        set {
            vehicleImageColorRaw = newValue.rawValue
            VehicleAppearanceStore.save(
                vin: vin,
                model: model,
                color: newValue.rawValue
            )
        }
    }

    var fuelTypeOverride: FuelType? {
        get {
            guard let raw = fuelTypeOverrideRaw else { return nil }
            return FuelType(rawValue: raw)
        }
        set { fuelTypeOverrideRaw = newValue?.rawValue }
    }

    var fuelType: FuelType {
        get {
            if let override = fuelTypeOverride { return override }
            return FuelType(rawValue: fuelTypeRaw) ?? .gas
        }
        set { fuelTypeRaw = newValue.rawValue }
    }

    /// Effective "show climate duration picker" decision. Reads the override when pinned,
    /// otherwise infers from generation + region: generation-3+ vehicles always honor the
    /// duration field, and non-USA vehicles of any generation also honor it. Pre-gen-3 USA
    /// vehicles default off because the API ignores duration there.
    var showClimateDuration: Bool {
        if let override = showClimateDurationOverride { return override }
        if generation >= 3 { return true }
        return account?.regionEnum != .usa
    }

    // Optional vehicle key for Kia vehicles
    @Transient var vehicleKey: String?

    @Relationship(inverse: \BBAccount.vehicles) var account: BBAccount?
    @Relationship(deleteRule: .cascade) var climatePresets: [ClimatePreset]? = []

    var safeClimatePresets: [ClimatePreset] {
        climatePresets ?? []
    }

    init(from vehicle: Vehicle, backgroundColorName: String? = nil) {
        id = UUID()
        vin = vehicle.vin
        regId = vehicle.regId
        model = vehicle.model
        accountId = vehicle.accountId
        fuelType = vehicle.fuelType
        generation = vehicle.generation
        odometer = vehicle.odometer

        // Initialize status fields as nil
        lastUpdated = nil
        syncDate = nil
        gasRange = nil
        evStatus = nil
        location = nil
        lockStatus = nil
        climateStatus = nil
        battery12V = nil
        doorOpen = nil
        trunkOpen = nil
        hoodOpen = nil
        tirePressureWarning = nil

        customName = nil
        isHidden = false
        vehicleImageColorRaw = VehicleImageColor.white.rawValue
        vehicleKey = vehicle.vehicleKey
        if let color = backgroundColorName {
            self.backgroundColorName = color
        }
    }
}

// MARK: - Status Management

extension BBVehicle {
    @MainActor
    func updateStatus(with status: VehicleStatus) {
        // Wake up any waiting status change tasks (cancel them so they can restart immediately)
        wakeUpStatusWaiters()

        // Update all fields with the merged status
        lastUpdated = status.lastUpdated
        syncDate = status.syncDate

        // Update gas range and EV status, keeping existing values if new ones aren't provided
        // PHEVs can have both gasRange and evStatus simultaneously
        if let gasRange = status.gasRange {
            self.gasRange = gasRange
        }
        if let evStatus = status.evStatus {
            self.evStatus = evStatus
        }
        // Only clear gas range for pure EVs (PHEVs retain both gas and EV range)
        if fuelType == .electric && status.evStatus != nil && status.gasRange == nil {
            gasRange = nil
        }
        location = status.location
        lockStatus = status.lockStatus
        climateStatus = status.climateStatus
        if let odometer = status.odometer {
            self.odometer = odometer
        }

        // Update additional status fields
        battery12V = status.battery12V
        doorOpen = status.doorOpen
        trunkOpen = status.trunkOpen
        hoodOpen = status.hoodOpen
        tirePressureWarning = status.tirePressureWarning

        // Fold this sample into the charge-session log
        recordChargingActivity(with: status)
        evaluateSmartAlerts(with: status)

        // Single hook covering every refresh path (periodic loop, manual
        // refresh, command polling, fake vehicles). Observers: ABRP telemetry
        // push and trip arrival detection. Foundation-only, so targets
        // without an observer are unaffected.
        NotificationCenter.default.post(name: .bbVehicleStatusUpdated, object: vin)
    }

    // MARK: - Status Change Waiting

    // Simple actor to manage wake-up continuations
    private actor StatusWaitingManager {
        private var wakeUpContinuations: [UUID: CheckedContinuation<Void, Never>] = [:]

        func setWakeUpContinuation(
            for vehicleId: UUID,
            continuation: CheckedContinuation<Void, Never>,
        ) {
            wakeUpContinuations[vehicleId] = continuation
        }

        func wakeUp(vehicleId: UUID) -> Bool {
            if let continuation = wakeUpContinuations.removeValue(forKey: vehicleId) {
                continuation.resume()
                return true
            }
            return false
        }

        func clearWakeUp(for vehicleId: UUID) {
            if let continuation = wakeUpContinuations.removeValue(forKey: vehicleId) {
                // Resume the continuation to prevent leak
                continuation.resume()
            }
        }
    }

    private static let statusWaitingManager = StatusWaitingManager()

    @MainActor
    func waitForStatusChange(
        modelContext: ModelContext,
        condition: @escaping @Sendable (VehicleStatus) -> Bool,
        statusMessageUpdater: (@Sendable (String) -> Void)? = nil,
        maxAttempts: Int = 3,
        initialDelaySeconds: Int = 10,
        retryDelaySeconds: Int = 10,
    ) async throws {
        // Initial delay to allow command to process
        statusMessageUpdater?("Command sent")
        try await interruptibleSleep(seconds: initialDelaySeconds)

        var currentAttempt = 0

        while currentAttempt < maxAttempts {
            try Task.checkCancellation()

            guard let account else {
                throw APIError(message: "Account not found for vehicle")
            }

            // Post-command verification needs to reflect the vehicle's
            // actual state, not the backend's cached snapshot.
            let updatedStatus = try await account.fetchVehicleStatus(
                for: self,
                modelContext: modelContext,
                cached: false
            )

            // Update the vehicle's status
            updateStatus(with: updatedStatus)

            if condition(updatedStatus) {
                print(
                    "✅ [BBVehicle] Status condition met for vehicle \(displayName)",
                )
                return
            }

            currentAttempt += 1
            if currentAttempt < maxAttempts {
                statusMessageUpdater?(
                    "Waiting for vehicle (\(currentAttempt)/\(maxAttempts))"
                )
                try await interruptibleSleep(seconds: retryDelaySeconds)
            }
        }

        throw APIError(
            message: "Status change condition not met after \(maxAttempts) attempts",
        )
    }

    @MainActor
    private func interruptibleSleep(seconds: Int) async throws {
        let vehicleId = id

        // Ensure any existing continuation is cleared before setting up a new one
        await Self.statusWaitingManager.clearWakeUp(for: vehicleId)

        try await withThrowingTaskGroup(of: Bool.self) { group in
            // Add timer task
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds) * 1_000_000_000)
                return false // Timer completed normally
            }

            // Add wake-up task
            group.addTask {
                await withCheckedContinuation { continuation in
                    Task {
                        await Self.statusWaitingManager.setWakeUpContinuation(
                            for: vehicleId,
                            continuation: continuation,
                        )
                    }
                }
                return true // Wake-up was triggered
            }

            // Wait for the first task to complete
            let wasWakeUp = try await group.next() ?? false

            // Cancel remaining tasks and ensure continuation cleanup
            group.cancelAll()
            await Self.statusWaitingManager.clearWakeUp(for: vehicleId)

            if wasWakeUp {
                print(
                    "⏰ [BBVehicle] Sleep interrupted by wake-up for vehicle \(self.displayName)",
                )
            }
        }
    }

    @MainActor
    func wakeUpStatusWaiters() {
        Task {
            let wasAwakened = await Self.statusWaitingManager.wakeUp(
                vehicleId: self.id,
            )
            if wasAwakened {
                print(
                    "🔔 [BBVehicle] Waking up status waiter for vehicle \(self.displayName)",
                )
            }
        }
    }

    @MainActor
    func clearPendingStatusWaiters() async {
        await Self.statusWaitingManager.clearWakeUp(for: id)
        print(
            "🧹 [BBVehicle] Cleared pending status waiters for vehicle \(displayName)",
        )
    }
}

// MARK: - UI and Display

extension BBVehicle {
    var displayName: String {
        customName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false ?
            customName! : model
    }

    /// Returns the appropriate plug icon based on current charging state and user's port type preference
    func plugIcon(for plugType: VehicleStatus.PlugType?) -> Image {
        guard let plugType else {
            return Image("custom.powerplug.portrait.slash")
        }

        switch plugType {
        case .unplugged:
            return Image("custom.powerplug.portrait.slash")
        case .acCharger:
            return Image(systemName: chargePortType.acPlugIcon)
        case .dcCharger:
            return Image(systemName: chargePortType.dcPlugIcon)
        }
    }
}

// MARK: - Codable Conformance for Export

extension BBVehicle: Encodable {
    enum CodingKeys: String, CodingKey {
        case id, vin, regId, model, accountId, fuelTypeRaw, generation, odometer
        case lastUpdated, syncDate, gasRange, evStatus, location, lockStatus, climateStatus
        case battery12V, doorOpen, trunkOpen, hoodOpen, tirePressureWarning
        case customName, isHidden, sortOrder, backgroundColorName, watchBackgroundColorName
        case vehicleImageColorRaw
        case chargePortTypeRaw, batteryCapacityKWh, electricityRatePerKWh, lastTripArchiveAttemptDate
        case smartChargingEnabled, smartChargingHomeOnly, smartChargingTargetSOC
        case smartChargingLastRunAt, smartChargingLastResult
        case debugConfiguration, debugLiveActivity, enableSeatHeatControls
        case fuelTypeOverrideRaw
        case showClimateDurationOverride
        case climatePresets
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)

        // Core vehicle fields
        try container.encode(id, forKey: .id)
        try container.encode(vin, forKey: .vin)
        try container.encode(regId, forKey: .regId)
        try container.encode(model, forKey: .model)
        try container.encode(accountId, forKey: .accountId)
        try container.encode(fuelTypeRaw, forKey: .fuelTypeRaw)
        try container.encode(generation, forKey: .generation)
        try container.encode(odometer, forKey: .odometer)

        // Status fields
        try container.encodeIfPresent(lastUpdated, forKey: .lastUpdated)
        try container.encodeIfPresent(syncDate, forKey: .syncDate)
        try container.encodeIfPresent(gasRange, forKey: .gasRange)
        try container.encodeIfPresent(evStatus, forKey: .evStatus)
        try container.encodeIfPresent(location, forKey: .location)
        try container.encodeIfPresent(lockStatus, forKey: .lockStatus)
        try container.encodeIfPresent(climateStatus, forKey: .climateStatus)
        try container.encodeIfPresent(battery12V, forKey: .battery12V)
        try container.encodeIfPresent(doorOpen, forKey: .doorOpen)
        try container.encodeIfPresent(trunkOpen, forKey: .trunkOpen)
        try container.encodeIfPresent(hoodOpen, forKey: .hoodOpen)
        try container.encodeIfPresent(tirePressureWarning, forKey: .tirePressureWarning)

        // UI/Settings fields
        try container.encodeIfPresent(customName, forKey: .customName)
        try container.encode(isHidden, forKey: .isHidden)
        try container.encode(sortOrder, forKey: .sortOrder)
        try container.encode(backgroundColorName, forKey: .backgroundColorName)
        try container.encode(watchBackgroundColorName, forKey: .watchBackgroundColorName)
        try container.encode(vehicleImageColorRaw, forKey: .vehicleImageColorRaw)
        try container.encode(chargePortTypeRaw, forKey: .chargePortTypeRaw)
        try container.encodeIfPresent(batteryCapacityKWh, forKey: .batteryCapacityKWh)
        try container.encodeIfPresent(electricityRatePerKWh, forKey: .electricityRatePerKWh)
        try container.encodeIfPresent(lastTripArchiveAttemptDate, forKey: .lastTripArchiveAttemptDate)
        try container.encode(smartChargingEnabled, forKey: .smartChargingEnabled)
        try container.encode(smartChargingHomeOnly, forKey: .smartChargingHomeOnly)
        try container.encode(smartChargingTargetSOC, forKey: .smartChargingTargetSOC)
        try container.encodeIfPresent(smartChargingLastRunAt, forKey: .smartChargingLastRunAt)
        try container.encodeIfPresent(smartChargingLastResult, forKey: .smartChargingLastResult)
        try container.encodeIfPresent(debugConfiguration, forKey: .debugConfiguration)
        try container.encode(debugLiveActivity, forKey: .debugLiveActivity)
        try container.encode(enableSeatHeatControls, forKey: .enableSeatHeatControls)
        try container.encodeIfPresent(fuelTypeOverrideRaw, forKey: .fuelTypeOverrideRaw)
        try container.encodeIfPresent(showClimateDurationOverride, forKey: .showClimateDurationOverride)

        // Climate presets (relationship)
        try container.encode(safeClimatePresets, forKey: .climatePresets)
    }
}

// MARK: - Charge Session

struct ChargeSpeedSample: Codable, Equatable, Sendable {
    let date: Date
    let speedKW: Double
    let batteryPercentage: Double
}

/// One charging session, auto-detected from status polling (see
/// `BBVehicle.recordChargingActivity`). Lives in this file rather than its
/// own because Vehicle.swift is already compiled into every target that
/// builds the shared model container (app, widgets, watch) — a new file
/// would need per-target wiring in project.pbxproj.
@Model
final class ChargeSession {
    var id: UUID = UUID()
    var vin: String = ""
    var startDate: Date = Date()
    /// nil while the session is still in progress
    var endDate: Date?
    var startPercentage: Double = 0
    var endPercentage: Double = 0
    /// Integrated from charge-speed samples between polls; a lower bound
    /// when polling is sparse
    var energyAddedKWh: Double = 0
    var maxChargeSpeedKW: Double = 0
    var isDCFast: Bool = false
    /// Timestamp of the newest status sample folded into this session —
    /// used to deduplicate stale/cached statuses across targets
    var lastSampleDate: Date = Date()
    var latitude: Double?
    var longitude: Double?
    /// User-correctable classification. Empty values are inferred from plug
    /// type and the optional private home geofence.
    var locationTypeRaw: String = ChargingLocationType.automatic.rawValue
    var locationName: String?
    /// Rate copied when the session starts so historical totals do not change
    /// when the user's tariff changes later.
    var electricityRatePerKWh: Double?
    private var speedSamplesData: Data?

    var isActive: Bool { endDate == nil }

    var locationType: ChargingLocationType {
        get { ChargingLocationType(rawValue: locationTypeRaw) ?? .automatic }
        set { locationTypeRaw = newValue.rawValue }
    }

    var percentAdded: Double { max(0, endPercentage - startPercentage) }

    var duration: TimeInterval {
        (endDate ?? lastSampleDate).timeIntervalSince(startDate)
    }

    /// True for blips not worth showing (single sample, no charge gained)
    var isMeaningful: Bool {
        isActive || percentAdded >= 1 || duration >= 300
    }

    var speedSamples: [ChargeSpeedSample] {
        get {
            guard let speedSamplesData else { return [] }
            return (try? JSONDecoder().decode(
                [ChargeSpeedSample].self,
                from: speedSamplesData
            )) ?? []
        }
        set {
            speedSamplesData = try? JSONEncoder().encode(Array(newValue.suffix(120)))
        }
    }

    func appendSpeedSample(
        date: Date,
        speedKW: Double,
        batteryPercentage: Double
    ) {
        var samples = speedSamples
        guard samples.last?.date != date else { return }
        samples.append(
            ChargeSpeedSample(
                date: date,
                speedKW: max(0, speedKW),
                batteryPercentage: batteryPercentage
            )
        )
        speedSamples = samples
    }

    init(
        vin: String,
        startDate: Date,
        startPercentage: Double,
        isDCFast: Bool,
        latitude: Double? = nil,
        longitude: Double? = nil
    ) {
        self.vin = vin
        self.startDate = startDate
        self.startPercentage = startPercentage
        endPercentage = startPercentage
        self.isDCFast = isDCFast
        lastSampleDate = startDate
        self.latitude = latitude
        self.longitude = longitude
    }
}

enum ChargingLocationType: String, CaseIterable, Codable, Identifiable {
    case automatic
    case home
    case publicAC
    case publicDC
    case other

    var id: String { rawValue }

    var title: String {
        switch self {
        case .automatic: "Automatic"
        case .home: "Home"
        case .publicAC: "Public AC"
        case .publicDC: "Public DC"
        case .other: "Other"
        }
    }

    var systemImage: String {
        switch self {
        case .automatic: "wand.and.stars"
        case .home: "house.fill"
        case .publicAC: "bolt.circle"
        case .publicDC: "bolt.fill"
        case .other: "mappin.circle"
        }
    }
}

// MARK: - Ownership Records

@Model
final class RemoteCommandActivity {
    var id: UUID = UUID()
    var vin: String = ""
    var vehicleName: String = ""
    var commandName: String = ""
    var sourceName: String = ""
    var stateRaw: String = CommandActivityState.sending.rawValue
    var startedAt: Date = Date()
    var completedAt: Date?
    var errorMessage: String?

    var state: CommandActivityState {
        get { CommandActivityState(rawValue: stateRaw) ?? .sending }
        set { stateRaw = newValue.rawValue }
    }

    init(vin: String, vehicleName: String, commandName: String, sourceName: String) {
        self.vin = vin
        self.vehicleName = vehicleName
        self.commandName = commandName
        self.sourceName = sourceName
    }

    func finish(_ state: CommandActivityState, error: Error? = nil) {
        self.state = state
        completedAt = Date()
        errorMessage = error?.localizedDescription
    }
}

enum CommandActivityState: String, Codable {
    case sending
    case succeeded
    case failed

    var title: String {
        switch self {
        case .sending: "Sending"
        case .succeeded: "Completed"
        case .failed: "Failed"
        }
    }

    var systemImage: String {
        switch self {
        case .sending: "arrow.triangle.2.circlepath"
        case .succeeded: "checkmark.circle.fill"
        case .failed: "exclamationmark.circle.fill"
        }
    }
}

@Model
final class MaintenanceReminder {
    var id: UUID = UUID()
    var vin: String = ""
    var title: String = ""
    var notes: String = ""
    var dueDate: Date?
    var dueOdometer: Double?
    var cost: Double?
    var createdAt: Date = Date()
    var completedAt: Date?
    var lastNotifiedAt: Date?

    var isCompleted: Bool { completedAt != nil }

    init(
        vin: String,
        title: String,
        notes: String = "",
        dueDate: Date? = nil,
        dueOdometer: Double? = nil,
        cost: Double? = nil
    ) {
        self.vin = vin
        self.title = title
        self.notes = notes
        self.dueDate = dueDate
        self.dueOdometer = dueOdometer
        self.cost = cost
    }
}

@Model
final class VehicleAutomation {
    var id: UUID = UUID()
    var vin: String = ""
    var title: String = ""
    var kindRaw: String = VehicleAutomationKind.lowBattery.rawValue
    var isEnabled: Bool = true
    var threshold: Int = 20
    var hour: Int = 7
    var minute: Int = 30
    var lastTriggeredAt: Date?
    var createdAt: Date = Date()

    var kind: VehicleAutomationKind {
        get { VehicleAutomationKind(rawValue: kindRaw) ?? .lowBattery }
        set { kindRaw = newValue.rawValue }
    }

    init(
        vin: String,
        title: String,
        kind: VehicleAutomationKind,
        threshold: Int = 20,
        hour: Int = 7,
        minute: Int = 30
    ) {
        self.vin = vin
        self.title = title
        kindRaw = kind.rawValue
        self.threshold = threshold
        self.hour = hour
        self.minute = minute
    }
}

enum VehicleAutomationKind: String, CaseIterable, Codable, Identifiable {
    case lowBattery
    case unplugged
    case preconditionReminder

    var id: String { rawValue }

    var title: String {
        switch self {
        case .lowBattery: "Low battery reminder"
        case .unplugged: "Unplugged reminder"
        case .preconditionReminder: "Precondition reminder"
        }
    }

    var systemImage: String {
        switch self {
        case .lowBattery: "battery.25percent"
        case .unplugged: "powerplug"
        case .preconditionReminder: "fan"
        }
    }
}

// MARK: - Trip History Archive

/// A durable copy of an API trip. Vehicle APIs usually expose only a short
/// recent window, so this archive lets GhostBlue build a complete history
/// over time and keeps past drives available offline.
@Model
final class TripRecord {
    var id: UUID = UUID()
    var vin: String = ""
    /// Stable deduplication key: a trip start timestamp and ending odometer.
    var tripKey: String = ""
    var distance: Double = 0
    var odometer: Double = 0
    var accessoriesEnergy: Int = 0
    var totalEnergyUsed: Int = 0
    var regenEnergy: Int = 0
    var climateEnergy: Int = 0
    var drivetrainEnergy: Int = 0
    var batteryCareEnergy: Int = 0
    var startDate: Date = Date()
    var durationSeconds: Int = 0
    var avgSpeed: Double = 0
    var maxSpeed: Double = 0
    var savedDate: Date = Date()
    /// User-owned metadata remains intact when the rolling API window is
    /// archived again.
    var customName: String?
    var isFavorite: Bool = false
    var startLocationName: String?
    var endLocationName: String?
    var startLatitude: Double?
    var startLongitude: Double?
    var endLatitude: Double?
    var endLongitude: Double?

    init(vin: String, trip: EVTripDetail) {
        self.vin = vin
        tripKey = Self.key(vin: vin, trip: trip)
        distance = trip.distance
        odometer = trip.odometer
        accessoriesEnergy = trip.accessoriesEnergy
        totalEnergyUsed = trip.totalEnergyUsed
        regenEnergy = trip.regenEnergy
        climateEnergy = trip.climateEnergy
        drivetrainEnergy = trip.drivetrainEnergy
        batteryCareEnergy = trip.batteryCareEnergy
        startDate = trip.startDate
        durationSeconds = trip.durationSeconds
        avgSpeed = trip.avgSpeed
        maxSpeed = trip.maxSpeed
    }

    static func key(vin: String, trip: EVTripDetail) -> String {
        "\(vin)-\(Int(trip.startDate.timeIntervalSince1970))-\(trip.odometer)"
    }

    var tripDetail: EVTripDetail {
        EVTripDetail(
            distance: distance,
            odometer: odometer,
            accessoriesEnergy: accessoriesEnergy,
            totalEnergyUsed: totalEnergyUsed,
            regenEnergy: regenEnergy,
            climateEnergy: climateEnergy,
            drivetrainEnergy: drivetrainEnergy,
            batteryCareEnergy: batteryCareEnergy,
            startDate: startDate,
            durationSeconds: durationSeconds,
            avgSpeed: avgSpeed,
            maxSpeed: maxSpeed
        )
    }
}

extension BBVehicle {
    /// Saves unseen API trips without deleting older local history. The API
    /// may return only its latest few drives; this method intentionally never
    /// treats an omitted trip as a delete.
    @MainActor
    func archiveTrips(_ trips: [EVTripDetail]) {
        guard let context = modelContext, !trips.isEmpty else { return }

        let vehicleVIN = vin
        let descriptor = FetchDescriptor<TripRecord>(predicate: #Predicate { $0.vin == vehicleVIN })
        let existing = (try? context.fetch(descriptor)) ?? []
        var existingKeys = Set(existing.map(\.tripKey))

        var inserted = false
        for trip in trips {
            let key = TripRecord.key(vin: vehicleVIN, trip: trip)
            // `existingKeys` is updated immediately so a duplicate inside
            // one provider response cannot be inserted twice.
            guard existingKeys.insert(key).inserted else { continue }
            context.insert(TripRecord(vin: vehicleVIN, trip: trip))
            inserted = true
        }

        if inserted {
            do {
                try context.save()
                BBLogger.info(.app, "Archived \(trips.count) fetched trips for \(displayName)")
            } catch {
                BBLogger.error(.app, "Failed to archive trips: \(error)")
            }
        }
    }
}

// MARK: - Charge Session Detection

extension BBVehicle {
    /// If a charging session appears to have been abandoned (no samples for
    /// this long while "charging"), close it at its last sample rather than
    /// stretching it across the gap.
    private static let staleSessionGap: TimeInterval = 3 * 3600

    /// Folds a fresh status into the charge-session log: opens a session on
    /// the first charging sample, integrates energy on subsequent ones, and
    /// closes the open session when charging stops. Called from
    /// `updateStatus(with:)`, so every refresh path (app, widget, watch,
    /// command polling) feeds the same log in the shared store.
    func recordChargingActivity(with status: VehicleStatus) {
        guard let context = modelContext, let evStatus = status.evStatus else { return }

        let sampleDate = status.syncDate ?? status.lastUpdated
        let percentage = evStatus.evRange.percentage

        let vin = self.vin
        var descriptor = FetchDescriptor<ChargeSession>(
            predicate: #Predicate { $0.vin == vin && $0.endDate == nil },
            sortBy: [SortDescriptor(\.startDate, order: .reverse)]
        )
        descriptor.fetchLimit = 1
        let openSession = (try? context.fetch(descriptor))?.first

        if evStatus.charging {
            guard let session = openSession else {
                startChargeSession(at: sampleDate, percentage: percentage, evStatus: evStatus, context: context)
                return
            }

            // Ignore replays of statuses we've already folded in
            guard sampleDate > session.lastSampleDate else { return }

            let gap = sampleDate.timeIntervalSince(session.lastSampleDate)
            if gap > Self.staleSessionGap {
                session.endDate = session.lastSampleDate
                startChargeSession(at: sampleDate, percentage: percentage, evStatus: evStatus, context: context)
                return
            }

            if evStatus.chargeSpeed > 0 {
                session.energyAddedKWh += evStatus.chargeSpeed * gap / 3600
            }
            session.appendSpeedSample(
                date: sampleDate,
                speedKW: evStatus.chargeSpeed,
                batteryPercentage: percentage
            )
            session.maxChargeSpeedKW = max(session.maxChargeSpeedKW, evStatus.chargeSpeed)
            session.isDCFast = session.isDCFast || evStatus.plugType == .dcCharger
            session.endPercentage = percentage
            session.lastSampleDate = sampleDate
            try? context.save()
        } else if let session = openSession {
            session.endPercentage = max(session.endPercentage, percentage)
            session.endDate = max(session.lastSampleDate, sampleDate)
            session.lastSampleDate = max(session.lastSampleDate, sampleDate)
            try? context.save()
        }
    }

    private func startChargeSession(
        at date: Date,
        percentage: Double,
        evStatus: VehicleStatus.EVStatus,
        context: ModelContext
    ) {
        let session = ChargeSession(
            vin: vin,
            startDate: date,
            startPercentage: percentage,
            isDCFast: evStatus.plugType == .dcCharger,
            latitude: location?.latitude,
            longitude: location?.longitude
        )
        session.locationType = inferredChargingLocationType(
            latitude: location?.latitude,
            longitude: location?.longitude,
            isDCFast: evStatus.plugType == .dcCharger
        )
        session.electricityRatePerKWh = effectiveElectricityRate(at: date)
        session.maxChargeSpeedKW = evStatus.chargeSpeed
        session.appendSpeedSample(
            date: date,
            speedKW: evStatus.chargeSpeed,
            batteryPercentage: percentage
        )
        context.insert(session)
        try? context.save()
    }

    func inferredChargingLocationType(
        latitude: Double?,
        longitude: Double?,
        isDCFast: Bool
    ) -> ChargingLocationType {
        guard let latitude,
              let longitude,
              let homeLatitude,
              let homeLongitude
        else {
            return isDCFast ? .publicDC : .automatic
        }

        let earthRadius = 6_371_000.0
        let latitudeDelta = (latitude - homeLatitude) * .pi / 180
        let longitudeDelta = (longitude - homeLongitude) * .pi / 180
        let a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2)
            + cos(homeLatitude * .pi / 180) * cos(latitude * .pi / 180)
            * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        let distance = earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
        if distance <= homePrivacyRadiusMeters {
            return .home
        }
        return isDCFast ? .publicDC : .publicAC
    }

    func effectiveElectricityRate(at date: Date) -> Double? {
        guard let baseRate = electricityRatePerKWh else { return nil }
        guard let offPeakElectricityRatePerKWh else { return baseRate }

        if smartChargingEnabled {
            return SDGESmartChargingSchedule.preference(at: date) == .charge
                ? offPeakElectricityRatePerKWh
                : baseRate
        }

        let hour = Calendar.current.component(.hour, from: date)
        let isOffPeak: Bool
        if offPeakStartHour <= offPeakEndHour {
            isOffPeak = hour >= offPeakStartHour && hour < offPeakEndHour
        } else {
            isOffPeak = hour >= offPeakStartHour || hour < offPeakEndHour
        }
        return isOffPeak ? offPeakElectricityRatePerKWh : baseRate
    }

    var isAtConfiguredHome: Bool {
        guard let location else { return false }
        return inferredChargingLocationType(
            latitude: location.latitude,
            longitude: location.longitude,
            isDCFast: false
        ) == .home
    }

    /// Hyundai can temporarily report `batteryPlugin == 1` (DC) while a
    /// scheduled home AC session is paused. Only relax that report when the
    /// user requires home-only control, the refreshed vehicle location is
    /// inside the saved geofence, and the reported power is below the range
    /// expected from DC fast charging. Real DC sessions remain untouched.
    func isSmartChargingControllableAC(_ status: VehicleStatus.EVStatus) -> Bool {
        switch status.plugType {
        case .acCharger:
            return true
        case .dcCharger:
            return smartChargingHomeOnly
                && isAtConfiguredHome
                && status.chargeSpeed < 25
        case .unplugged:
            return false
        }
    }
}

// MARK: - Status Update Notification

extension Notification.Name {
    /// Posted (object = VIN) after `BBVehicle.updateStatus(with:)` applies a
    /// status sample, on every refresh path.
    static let bbVehicleStatusUpdated = Notification.Name("BBVehicleStatusUpdated")
}

// MARK: - Smart Alert Evaluation

private struct VehicleAlertSnapshot: Codable {
    let charging: Bool
    let pluggedIn: Bool
    let batteryPercentage: Int?
    let locked: Bool
    let hasOpenPanel: Bool
    let battery12V: Int?
    let tireWarning: Bool
}

extension BBVehicle {
    @MainActor
    private func evaluateSmartAlerts(with status: VehicleStatus) {
        let snapshot = VehicleAlertSnapshot(
            charging: status.evStatus?.charging ?? false,
            pluggedIn: status.evStatus?.pluggedIn ?? false,
            batteryPercentage: status.evStatus.map { Int($0.evRange.percentage.rounded()) },
            locked: status.lockStatus == .locked,
            hasOpenPanel: (status.doorOpen?.anyOpen ?? false)
                || (status.trunkOpen ?? false)
                || (status.hoodOpen ?? false),
            battery12V: status.battery12V,
            tireWarning: status.tirePressureWarning?.hasWarning ?? false
        )

        let previous = alertSnapshotData.flatMap {
            try? JSONDecoder().decode(VehicleAlertSnapshot.self, from: $0)
        }
        alertSnapshotData = try? JSONEncoder().encode(snapshot)
        evaluateMaintenanceReminders()
        guard let previous else { return }

        var alerts: [(String, String)] = []
        let target = status.evStatus?.currentTargetSOC.map { Int($0.rounded()) }
        let percentage = snapshot.batteryPercentage ?? 100

        if notifyChargeComplete,
           previous.charging,
           !snapshot.charging,
           (target.map { percentage >= $0 - 1 } ?? (percentage >= 99)) {
            alerts.append(("Charging complete", "\(displayName) reached \(percentage)%."))
        } else if notifyChargeInterrupted,
                  previous.charging,
                  !snapshot.charging,
                  snapshot.pluggedIn,
                  target.map({ percentage < $0 - 1 }) == true {
            alerts.append(("Charging interrupted", "\(displayName) stopped charging at \(percentage)%."))
        }

        if notifyUnlocked, previous.locked, !snapshot.locked {
            alerts.append(("Vehicle unlocked", "\(displayName) is now unlocked."))
        }
        if notifyOpenPanels, !previous.hasOpenPanel, snapshot.hasOpenPanel {
            alerts.append(("Door or panel open", "\(displayName) reports an open door, hood, or trunk."))
        }
        if notifyLowEVBattery,
           let oldLevel = previous.batteryPercentage,
           oldLevel > lowEVBatteryThreshold,
           percentage <= lowEVBatteryThreshold {
            alerts.append(("Low EV battery", "\(displayName) is at \(percentage)%."))
        }
        if notifyLow12VBattery,
           let oldLevel = previous.battery12V,
           let newLevel = snapshot.battery12V,
           oldLevel > low12VBatteryThreshold,
           newLevel <= low12VBatteryThreshold {
            alerts.append(("Low 12V battery", "\(displayName)'s 12V battery is at \(newLevel)%."))
        }
        if notifyTirePressure, !previous.tireWarning, snapshot.tireWarning {
            alerts.append(("Tire pressure warning", "\(displayName) reports a tire pressure warning."))
        }

        evaluateStatusAutomations(snapshot: snapshot, previous: previous, alerts: &alerts)
        for alert in alerts {
            scheduleVehicleNotification(title: alert.0, body: alert.1)
        }
    }

    @MainActor
    func evaluateMaintenanceReminders() {
        guard let context = modelContext else { return }
        let vehicleVIN = vin
        let descriptor = FetchDescriptor<MaintenanceReminder>(
            predicate: #Predicate {
                $0.vin == vehicleVIN
                    && $0.completedAt == nil
                    && $0.lastNotifiedAt == nil
            }
        )
        guard let reminders = try? context.fetch(descriptor) else { return }
        var changed = false
        for reminder in reminders {
            guard let dueOdometer = reminder.dueOdometer,
                  odometer.length >= dueOdometer
            else { continue }
            scheduleVehicleNotification(
                title: "Maintenance due",
                body: "\(reminder.title) is due for \(displayName)."
            )
            reminder.lastNotifiedAt = Date()
            changed = true
        }
        if changed {
            try? context.save()
        }
    }

    @MainActor
    private func evaluateStatusAutomations(
        snapshot: VehicleAlertSnapshot,
        previous: VehicleAlertSnapshot,
        alerts: inout [(String, String)]
    ) {
        guard let context = modelContext else { return }
        let vehicleVIN = vin
        let descriptor = FetchDescriptor<VehicleAutomation>(
            predicate: #Predicate { $0.vin == vehicleVIN && $0.isEnabled }
        )
        guard let rules = try? context.fetch(descriptor) else { return }
        for rule in rules {
            let shouldTrigger: Bool
            switch rule.kind {
            case .lowBattery:
                let old = previous.batteryPercentage ?? 100
                let new = snapshot.batteryPercentage ?? 100
                shouldTrigger = old > rule.threshold && new <= rule.threshold
            case .unplugged:
                shouldTrigger = previous.pluggedIn && !snapshot.pluggedIn
            case .preconditionReminder:
                shouldTrigger = false
            }
            guard shouldTrigger else { continue }
            rule.lastTriggeredAt = Date()
            alerts.append((rule.title, "\(displayName) matched your automation rule."))
        }
        try? context.save()
    }

    @MainActor
    private func scheduleVehicleNotification(title: String, body: String) {
        #if canImport(UserNotifications)
        guard AppSettings.shared.notificationsEnabled,
              !AppSettings.shared.isInNotificationQuietHours
        else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.userInfo = ["vin": vin]
        let request = UNNotificationRequest(
            identifier: "vehicle-alert-\(vin)-\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        Task {
            try? await UNUserNotificationCenter.current().add(request)
        }
        #endif
    }
}
