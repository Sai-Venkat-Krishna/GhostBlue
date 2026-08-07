import BetterBlueKit
import Foundation

enum TripDateFilter: String, CaseIterable, Identifiable, Sendable {
    case all
    case sevenDays
    case thirtyDays
    case ninetyDays

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: "All"
        case .sevenDays: "7 days"
        case .thirtyDays: "30 days"
        case .ninetyDays: "90 days"
        }
    }

    var dayCount: Int? {
        switch self {
        case .all: nil
        case .sevenDays: 7
        case .thirtyDays: 30
        case .ninetyDays: 90
        }
    }
}

enum TripTrendPeriod: String, CaseIterable, Identifiable, Sendable {
    case daily
    case weekly
    case monthly

    var id: String { rawValue }
    var title: String {
        switch self {
        case .daily: "Day"
        case .weekly: "Week"
        case .monthly: "Month"
        }
    }

    var pluralTitle: String {
        switch self {
        case .daily: "Days"
        case .weekly: "Weeks"
        case .monthly: "Months"
        }
    }
}

struct TripEfficiencyTrendPoint: Identifiable, Equatable, Sendable {
    let periodStart: Date
    let distanceMiles: Double
    let energyKWh: Double

    var id: Date { periodStart }

    var efficiencyMilesPerKWh: Double {
        guard energyKWh > 0 else { return 0 }
        return distanceMiles / energyKWh
    }
}

struct TripEfficiencyExtremes: Equatable, Sendable {
    let best: EVTripDetail
    let worst: EVTripDetail
}

enum TripAnalytics {
    static func filtered(
        _ trips: [EVTripDetail],
        by filter: TripDateFilter,
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> [EVTripDetail] {
        guard let dayCount = filter.dayCount,
              let cutoff = calendar.date(byAdding: .day, value: -dayCount, to: now)
        else {
            return trips
        }
        return trips.filter { $0.startDate >= cutoff && $0.startDate <= now }
    }

    static func energyKWh(for trip: EVTripDetail) -> Double {
        let reported = Double(max(0, trip.totalEnergyUsed)) / 1000
        guard reported == 0 else { return reported }
        return [
            trip.drivetrainEnergy,
            trip.climateEnergy,
            trip.accessoriesEnergy,
            trip.batteryCareEnergy
        ]
        .reduce(0) { $0 + Double(max(0, $1)) / 1000 }
    }

    static func efficiencyMilesPerKWh(for trip: EVTripDetail) -> Double? {
        let energy = energyKWh(for: trip)
        guard energy > 0, trip.distance > 0 else { return nil }
        return trip.distance / energy
    }

    static func trendPoints(
        from trips: [EVTripDetail],
        period: TripTrendPeriod,
        calendar: Calendar = .current
    ) -> [TripEfficiencyTrendPoint] {
        struct Aggregate {
            var distance: Double = 0
            var energy: Double = 0
        }

        var aggregates: [Date: Aggregate] = [:]

        for trip in trips {
            let energy = energyKWh(for: trip)
            guard energy > 0, trip.distance > 0,
                  let start = periodStart(
                      for: trip.startDate,
                      period: period,
                      calendar: calendar
                  )
            else {
                continue
            }

            var aggregate = aggregates[start, default: Aggregate()]
            aggregate.distance += trip.distance
            aggregate.energy += energy
            aggregates[start] = aggregate
        }

        return aggregates
            .map { start, aggregate in
                TripEfficiencyTrendPoint(
                    periodStart: start,
                    distanceMiles: aggregate.distance,
                    energyKWh: aggregate.energy
                )
            }
            .sorted { $0.periodStart < $1.periodStart }
    }

    static func extremes(in trips: [EVTripDetail]) -> TripEfficiencyExtremes? {
        let eligible = trips.compactMap { trip -> (EVTripDetail, Double)? in
            guard let efficiency = efficiencyMilesPerKWh(for: trip) else {
                return nil
            }
            return (trip, efficiency)
        }
        guard let best = eligible.max(by: { $0.1 < $1.1 }),
              let worst = eligible.min(by: { $0.1 < $1.1 })
        else {
            return nil
        }
        return TripEfficiencyExtremes(best: best.0, worst: worst.0)
    }

    static func electricityCost(
        for trips: [EVTripDetail],
        ratePerKWh: Double?
    ) -> Double? {
        guard let ratePerKWh, ratePerKWh > 0 else { return nil }
        let energy = trips.reduce(0) { $0 + energyKWh(for: $1) }
        guard energy > 0 else { return nil }
        return energy * ratePerKWh
    }

    static func percentageChange(in points: [TripEfficiencyTrendPoint]) -> Double? {
        guard points.count >= 2 else { return nil }
        let previous = points[points.count - 2].efficiencyMilesPerKWh
        let latest = points[points.count - 1].efficiencyMilesPerKWh
        guard previous > 0 else { return nil }
        return ((latest - previous) / previous) * 100
    }

    private static func periodStart(
        for date: Date,
        period: TripTrendPeriod,
        calendar: Calendar
    ) -> Date? {
        switch period {
        case .daily:
            calendar.dateInterval(of: .day, for: date)?.start
        case .weekly:
            calendar.dateInterval(of: .weekOfYear, for: date)?.start
        case .monthly:
            calendar.dateInterval(of: .month, for: date)?.start
        }
    }
}
