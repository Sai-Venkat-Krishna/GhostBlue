import BetterBlueKit
@testable import GhostBlue
import XCTest

final class TripChartDataTests: XCTestCase {
    private func trip(
        totalEnergy: Int = 7_400,
        drivetrain: Int = 5_900,
        climate: Int = 700,
        accessories: Int = 220,
        batteryCare: Int = 100,
        date: Date = Date(),
        odometer: Double = 12_345
    ) -> EVTripDetail {
        EVTripDetail(
            distance: 42.5,
            odometer: odometer,
            accessoriesEnergy: accessories,
            totalEnergyUsed: totalEnergy,
            regenEnergy: 1_100,
            climateEnergy: climate,
            drivetrainEnergy: drivetrain,
            batteryCareEnergy: batteryCare,
            startDate: date,
            durationSeconds: 3_600,
            avgSpeed: 38,
            maxSpeed: 72
        )
    }

    func testReportedTotalEnergyIsPreferred() {
        let value = TripChartData.totalEnergyKWh(
            for: trip(
                totalEnergy: 8_250,
                drivetrain: 99_000,
                climate: 99_000,
                accessories: 99_000,
                batteryCare: 99_000
            )
        )

        XCTAssertEqual(value, 8.25, accuracy: 0.001)
    }

    func testMissingTotalFallsBackToPositiveComponentEnergy() {
        let value = TripChartData.totalEnergyKWh(
            for: trip(
                totalEnergy: 0,
                drivetrain: 5_000,
                climate: 800,
                accessories: -200,
                batteryCare: 200
            )
        )

        XCTAssertEqual(value, 6.0, accuracy: 0.001)
    }

    func testChartKeepsLatestTripsAndOrdersThemOldestFirst() {
        let baseDate = Date(timeIntervalSince1970: 1_700_000_000)
        let newestFirst = (0..<20).map { offset in
            trip(
                date: baseDate.addingTimeInterval(TimeInterval(-offset * 60)),
                odometer: 20_000 - Double(offset)
            )
        }

        let indexed = TripChartData.indexedTrips(from: newestFirst)

        XCTAssertEqual(indexed.count, TripChartData.maximumTripCount)
        XCTAssertEqual(indexed.first?.trip.odometer, 19_989)
        XCTAssertEqual(indexed.last?.trip.odometer, 20_000)
    }

    func testBreakdownDropsZeroAndNegativeSegments() {
        let points = TripChartData.breakdownPoints(
            from: [
                trip(
                    totalEnergy: 0,
                    drivetrain: 5_000,
                    climate: 0,
                    accessories: -50,
                    batteryCare: 250
                )
            ]
        )

        XCTAssertEqual(points.map(\.category), ["Drivetrain", "Battery Care"])
        XCTAssertEqual(points.map(\.energy), [5.0, 0.25])
    }

    func testAxisLabelsStaySparseButIncludeFirstAndLastTrip() {
        XCTAssertEqual(TripChartData.axisIndices(for: 12), [0, 3, 6, 9, 11])
        XCTAssertEqual(TripChartData.axisIndices(for: 3), [0, 1, 2])
        XCTAssertEqual(TripChartData.axisIndices(for: 0), [])
        XCTAssertEqual(TripChartData.axisSlots(for: 12), ["0", "3", "6", "9", "11"])
    }

    func testChartUsesCategoricalSlotsSoBarsHaveVisibleWidth() {
        let points = TripChartData.totalPoints(
            from: [
                trip(totalEnergy: 1_600, odometer: 2),
                trip(totalEnergy: 900, odometer: 1)
            ]
        )

        XCTAssertEqual(points.map(\.slot), ["0", "1"])
        XCTAssertEqual(
            TripChartData.breakdownPoints(from: [trip()]).map(\.slot),
            ["0", "0", "0", "0"]
        )
    }

    func testCSVExportIncludesMetadataCostAndEscapesCommas() {
        let exportedTrip = trip(
            totalEnergy: 7_400,
            date: Date(timeIntervalSince1970: 1_700_000_000),
            odometer: 12_345
        )
        let vin = "TESTVIN"
        let key = TripRecord.key(vin: vin, trip: exportedTrip)

        let csv = TripCSVExporter.csv(
            trips: [exportedTrip],
            vin: vin,
            metadata: [
                key: TripCSVMetadata(
                    customName: "Office, morning",
                    isFavorite: true,
                    startLocationName: "Home",
                    endLocationName: "Office, HQ"
                )
            ],
            ratePerKWh: 0.20
        )

        XCTAssertTrue(csv.hasPrefix("Date,Name,Favorite"))
        XCTAssertTrue(csv.contains("\"Office, morning\""))
        XCTAssertTrue(csv.contains("\"Office, HQ\""))
        XCTAssertTrue(csv.contains(",Yes,"))
        XCTAssertTrue(csv.contains(",1.48\n"))
    }
}
