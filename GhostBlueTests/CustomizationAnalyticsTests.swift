import BetterBlueKit
@testable import GhostBlue
import XCTest

final class CustomizationAnalyticsTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        calendar.firstWeekday = 2
        return calendar
    }

    private func date(_ year: Int, _ month: Int, _ day: Int) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    private func trip(
        date: Date,
        distance: Double,
        energyWh: Int,
        odometer: Double
    ) -> EVTripDetail {
        EVTripDetail(
            distance: distance,
            odometer: odometer,
            accessoriesEnergy: 0,
            totalEnergyUsed: energyWh,
            regenEnergy: 0,
            climateEnergy: 0,
            drivetrainEnergy: energyWh,
            batteryCareEnergy: 0,
            startDate: date,
            durationSeconds: 1_800,
            avgSpeed: 35,
            maxSpeed: 60
        )
    }

    func testHomeOrderRemovesDuplicatesAndRestoresMissingSections() {
        let order = HomeSection.normalizedOrder(
            from: ["location", "status", "location", "unknown"]
        )

        XCTAssertEqual(order, [.location, .status, .briefing, .charging, .health])
    }

    func testVehicleImageColorPersistsAsRawValue() {
        let vehicle = BBVehicle(
            from: Vehicle(
                vin: "COLORVIN",
                regId: "REG",
                model: "IONIQ 5",
                accountId: UUID(),
                fuelType: .electric,
                generation: 3,
                odometer: Distance(length: 0, units: .miles)
            )
        )

        vehicle.vehicleImageColor = .blue

        XCTAssertEqual(vehicle.vehicleImageColorRaw, "blue")
        XCTAssertEqual(vehicle.vehicleImageColor, .blue)
    }

    func testVehicleImageColorsMatchAvailableModelRenders() {
        XCTAssertEqual(
            VehicleImageColor.availableColors(for: "2025 IONIQ 5 N"),
            [.white, .black, .grey, .blue, .matteGrey, .orange]
        )
        XCTAssertEqual(
            VehicleImageColor.availableColors(for: "KONA Electric"),
            [.white, .black, .grey, .red, .metallicGrey]
        )
        XCTAssertEqual(
            VehicleImageColor.availableColors(for: "Unknown Vehicle"),
            [.white]
        )
    }

    func testLegacyGrayVehicleImageValueMigratesInMemory() {
        let vehicle = BBVehicle(
            from: Vehicle(
                vin: "LEGACYCOLORVIN",
                regId: "REG",
                model: "IONIQ 5",
                accountId: UUID(),
                fuelType: .electric,
                generation: 3,
                odometer: Distance(length: 0, units: .miles)
            )
        )

        vehicle.vehicleImageColorRaw = "gray"

        XCTAssertEqual(vehicle.vehicleImageColor, .grey)
    }

    func testTripDateFilterUsesRequestedRollingWindow() {
        let now = date(2026, 7, 22)
        let trips = [
            trip(date: date(2026, 7, 21), distance: 10, energyWh: 2_000, odometer: 3),
            trip(date: date(2026, 7, 10), distance: 10, energyWh: 2_000, odometer: 2),
            trip(date: date(2026, 5, 1), distance: 10, energyWh: 2_000, odometer: 1)
        ]

        XCTAssertEqual(
            TripAnalytics.filtered(
                trips,
                by: .sevenDays,
                now: now,
                calendar: calendar
            ).count,
            1
        )
        XCTAssertEqual(
            TripAnalytics.filtered(
                trips,
                by: .ninetyDays,
                now: now,
                calendar: calendar
            ).count,
            3
        )
    }

    func testWeeklyTrendUsesWeightedDistanceAndEnergy() {
        let trips = [
            trip(date: date(2026, 7, 20), distance: 20, energyWh: 5_000, odometer: 2),
            trip(date: date(2026, 7, 21), distance: 30, energyWh: 5_000, odometer: 3)
        ]

        let points = TripAnalytics.trendPoints(
            from: trips,
            period: .weekly,
            calendar: calendar
        )

        XCTAssertEqual(points.count, 1)
        XCTAssertEqual(points[0].distanceMiles, 50, accuracy: 0.001)
        XCTAssertEqual(points[0].energyKWh, 10, accuracy: 0.001)
        XCTAssertEqual(points[0].efficiencyMilesPerKWh, 5, accuracy: 0.001)
    }

    func testDailyTrendSeparatesCalendarDays() {
        let trips = [
            trip(date: date(2026, 7, 20), distance: 20, energyWh: 5_000, odometer: 1),
            trip(date: date(2026, 7, 21), distance: 30, energyWh: 5_000, odometer: 2)
        ]

        let points = TripAnalytics.trendPoints(
            from: trips,
            period: .daily,
            calendar: calendar
        )

        XCTAssertEqual(points.count, 2)
        XCTAssertEqual(points.map(\.efficiencyMilesPerKWh), [4, 6])
        XCTAssertEqual(TripTrendPeriod.daily.pluralTitle, "Days")
    }

    func testMonthlyTrendSeparatesMonthsAndCalculatesChange() {
        let trips = [
            trip(date: date(2026, 6, 20), distance: 20, energyWh: 5_000, odometer: 1),
            trip(date: date(2026, 7, 20), distance: 30, energyWh: 5_000, odometer: 2)
        ]

        let points = TripAnalytics.trendPoints(
            from: trips,
            period: .monthly,
            calendar: calendar
        )

        XCTAssertEqual(points.count, 2)
        XCTAssertEqual(points[0].efficiencyMilesPerKWh, 4, accuracy: 0.001)
        XCTAssertEqual(points[1].efficiencyMilesPerKWh, 6, accuracy: 0.001)
        XCTAssertEqual(
            TripAnalytics.percentageChange(in: points) ?? -1,
            50,
            accuracy: 0.001
        )
    }

    func testBestWorstAndElectricityCostInsights() {
        let efficient = trip(
            date: date(2026, 7, 21),
            distance: 30,
            energyWh: 5_000,
            odometer: 2
        )
        let inefficient = trip(
            date: date(2026, 7, 20),
            distance: 10,
            energyWh: 5_000,
            odometer: 1
        )

        let extremes = TripAnalytics.extremes(in: [inefficient, efficient])
        let cost = TripAnalytics.electricityCost(
            for: [inefficient, efficient],
            ratePerKWh: 0.25
        )

        XCTAssertEqual(extremes?.best.id, efficient.id)
        XCTAssertEqual(extremes?.worst.id, inefficient.id)
        XCTAssertEqual(cost ?? -1, 2.5, accuracy: 0.001)
    }
}
