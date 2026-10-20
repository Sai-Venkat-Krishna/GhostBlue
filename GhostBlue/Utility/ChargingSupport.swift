import Foundation
import BetterBlueKit

enum VehicleRefreshPolicy {
    static func interval(requested: TimeInterval, pluggedIn: Bool) -> TimeInterval {
        max(60, pluggedIn ? min(requested, 600) : requested)
    }

    static func shouldRefresh(lastFetch: Date?, now: Date = Date(), interval: TimeInterval) -> Bool {
        guard let lastFetch else { return true }
        return now.timeIntervalSince(lastFetch) >= interval
    }

    /// How old data may be and still skip a fetch when a timeline reloads
    /// every `reloadInterval`. It must be shorter than the interval: the
    /// fetch time is stamped after the network call, so an on-time reload
    /// sees data slightly younger than the interval and would otherwise skip,
    /// leaving widgets nearly two intervals stale.
    static func freshnessWindow(forReloadInterval reloadInterval: TimeInterval) -> TimeInterval {
        min(reloadInterval * 0.75, 30 * 60)
    }

    /// Cancellation must end the loop; swallowing a cancelled sleep creates a busy loop.
    @MainActor
    static func run(
        interval: @escaping () -> TimeInterval,
        action: @escaping () async -> Void
    ) async {
        while !Task.isCancelled {
            do { try await Task.sleep(for: .seconds(max(0.01, interval()))) }
            catch { return }
            guard !Task.isCancelled else { return }
            await action()
        }
    }
}

enum ChargingDisplayState: String, Codable, Sendable {
    case charging, confirmingStop, scheduledPause, completed, pluggedIn, unplugged, outdated, unknown

    var title: String {
        switch self {
        case .charging: "Charging"
        case .confirmingStop: "Waiting for confirmation"
        case .scheduledPause: "Scheduled pause"
        case .completed: "Completed"
        case .pluggedIn: "Plugged in · not charging"
        case .unplugged: "Unplugged"
        case .outdated: "Status outdated"
        case .unknown: "Charging status unavailable"
        }
    }
}

enum SmartChargingOverride: String, Codable, Sendable {
    case chargeNow, skipWindow

    var title: String { self == .chargeNow ? "Charge now" : "Skip this window" }
    var preference: SmartChargingPreference { self == .chargeNow ? .charge : .pause }
}

struct SmartChargingTransition: Identifiable, Sendable {
    let date: Date
    let preference: SmartChargingPreference
    var id: Date { date }
    var title: String { preference == .charge ? "Charging window opens" : "Charging window closes" }
}

extension SDGESmartChargingSchedule {
    static func transitions(after date: Date, count: Int = 4) -> [SmartChargingTransition] {
        guard count > 0 else { return [] }
        let cal = calendar()
        let start = cal.startOfDay(for: date)
        var result: [SmartChargingTransition] = []
        for offset in 0 ... max(7, count) {
            guard let day = cal.date(byAdding: .day, value: offset, to: start) else { continue }
            for hour in [0, 6, 10, 14] {
                guard let boundary = cal.date(bySettingHour: hour, minute: 0, second: 0, of: day),
                      boundary > date else { continue }
                let next = preference(at: boundary)
                guard next != preference(at: boundary.addingTimeInterval(-1)) else { continue }
                result.append(.init(date: boundary, preference: next))
                if result.count == count { return result }
            }
        }
        return result
    }

    static func mostRecentTransition(at date: Date) -> Date? {
        let cal = calendar()
        guard let start = cal.date(byAdding: .day, value: -2, to: date) else { return nil }
        return transitions(after: start, count: 16).last(where: { $0.date <= date })?.date
    }

    static func formattedTime(_ date: Date) -> String {
        var format = Date.FormatStyle(date: .abbreviated, time: .shortened)
        format.timeZone = timeZone
        return date.formatted(format) + " " + (timeZone.abbreviation(for: date) ?? "PT")
    }
}

extension BBVehicle {
    var vehicleSampleDate: Date? { syncDate ?? lastUpdated }

    func activeChargingOverride(at date: Date = Date()) -> SmartChargingOverride? {
        guard let raw = smartChargingOverrideRaw, let expiry = smartChargingOverrideExpiresAt,
              date < expiry else { return nil }
        return SmartChargingOverride(rawValue: raw)
    }

    func chargingPreference(at date: Date = Date()) -> SmartChargingPreference {
        activeChargingOverride(at: date)?.preference ?? SDGESmartChargingSchedule.preference(at: date)
    }

    func setChargingOverride(_ override: SmartChargingOverride?, at date: Date = Date()) {
        smartChargingOverrideRaw = override?.rawValue
        smartChargingOverrideExpiresAt = override == nil ? nil
            : SDGESmartChargingSchedule.transitions(after: date, count: 1).first?.date
    }

    func chargingDisplayState(at date: Date = Date()) -> ChargingDisplayState {
        guard let evStatus else { return .unknown }
        if let sampleDate = vehicleSampleDate,
           date.timeIntervalSince(sampleDate) > (evStatus.pluggedIn || evStatus.charging ? 600 : 1800) {
            return .outdated
        }
        if pendingChargingStopDate != nil { return .confirmingStop }
        if evStatus.charging { return .charging }
        if !evStatus.pluggedIn { return .unplugged }
        if let target = evStatus.currentTargetSOC, evStatus.evRange.percentage >= target - 1 {
            return .completed
        }
        if smartChargingEnabled, (!smartChargingHomeOnly || isAtConfiguredHome),
           isSmartChargingControllableAC(evStatus), chargingPreference(at: date) == .pause {
            return .scheduledPause
        }
        return .pluggedIn
    }

    func smartChargingWarning(at date: Date = Date()) -> String? {
        guard smartChargingEnabled, evStatus?.pluggedIn == true,
              !smartChargingHomeOnly || isAtConfiguredHome else { return nil }
        guard let lastConfirmed = smartChargingLastConfirmedAt else {
            return "No successful schedule check recorded. Run a check and verify your Shortcuts automations."
        }
        guard let boundary = SDGESmartChargingSchedule.mostRecentTransition(at: date),
              date.timeIntervalSince(boundary) > 300, lastConfirmed < boundary else { return nil }
        return "No confirmed check since \(SDGESmartChargingSchedule.formattedTime(boundary)). A scheduled run may have been missed."
    }

    func chargingTariff(locationType: ChargingLocationType, isDCFast: Bool) -> ChargingTariff? {
        guard !isDCFast, locationType != .publicDC,
              locationType == .home || (locationType == .automatic && smartChargingEnabled),
              let standard = electricityRatePerKWh, standard.isFinite, standard >= 0 else { return nil }
        return ChargingTariff(
            standardRate: standard,
            offPeakRate: offPeakElectricityRatePerKWh,
            peakRate: peakElectricityRatePerKWh,
            usesSDGE: smartChargingEnabled,
            offPeakStartHour: offPeakStartHour,
            offPeakEndHour: offPeakEndHour,
            timeZoneIdentifier: smartChargingEnabled
                ? SDGESmartChargingSchedule.timeZone.identifier : TimeZone.current.identifier
        )
    }
}

/// Rates and time zone are snapshotted with a session, so later tariff edits
/// cannot rewrite its bill estimate. Energy is allocated by elapsed time;
/// a meter reading or receipt remains the authoritative measurement.
struct ChargingTariff: Codable, Equatable, Sendable {
    var standardRate: Double
    var offPeakRate: Double?
    var peakRate: Double?
    var usesSDGE: Bool
    var offPeakStartHour: Int
    var offPeakEndHour: Int
    var timeZoneIdentifier: String

    func rate(at date: Date) -> Double {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: timeZoneIdentifier) ?? SDGESmartChargingSchedule.timeZone
        let hour = cal.component(.hour, from: date)
        if usesSDGE {
            if SDGESmartChargingSchedule.preference(at: date, calendar: cal) == .charge {
                return offPeakRate ?? standardRate
            }
            if (16 ..< 21).contains(hour) { return peakRate ?? standardRate }
        } else {
            let offPeak = offPeakStartHour <= offPeakEndHour
                ? hour >= offPeakStartHour && hour < offPeakEndHour
                : hour >= offPeakStartHour || hour < offPeakEndHour
            if offPeak { return offPeakRate ?? standardRate }
        }
        return standardRate
    }

    func cost(energyKWh: Double, from start: Date, to end: Date) -> Double {
        guard energyKWh.isFinite, energyKWh >= 0 else { return 0 }
        let duration = end.timeIntervalSince(start)
        guard duration > 0 else { return energyKWh * rate(at: start) }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: timeZoneIdentifier) ?? SDGESmartChargingSchedule.timeZone
        var cursor = start
        var weightedRate = 0.0
        while cursor < end {
            // Absolute hour intervals also handle skipped/repeated DST hours.
            let hourEnd = cal.dateInterval(of: .hour, for: cursor)?.end ?? cursor.addingTimeInterval(3600)
            let next = min(end, max(hourEnd, cursor.addingTimeInterval(1)))
            weightedRate += rate(at: cursor) * next.timeIntervalSince(cursor) / duration
            cursor = next
        }
        return energyKWh * weightedRate
    }
}

enum ChargingCSVExporter {
    static func csv(sessions: [ChargeSession], vehicle: BBVehicle) -> String {
        let formatter = ISO8601DateFormatter()
        let header = "Started (UTC),Ended (UTC),State,Start SOC (%),End SOC (%),Energy (kWh),Energy source,Cost,Cost source,Location,Charger"
        let rows = sessions.map { session in
            [
                formatter.string(from: session.startDate),
                session.endDate.map(formatter.string) ?? "",
                session.isActive ? "In progress" : "Completed",
                String(session.startPercentage), String(session.endPercentage),
                String(vehicle.energyDelivered(for: session)),
                vehicle.energySource(for: session),
                vehicle.chargingCost(for: session).map { String($0) } ?? "",
                session.actualCost == nil ? "Estimated" : "Receipt",
                session.locationType.title,
                session.isDCFast ? "DC" : "AC"
            ].map(escape).joined(separator: ",")
        }
        return ([header] + rows).joined(separator: "\r\n") + "\r\n"
    }

    private static func escape(_ value: String) -> String {
        "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }
}
