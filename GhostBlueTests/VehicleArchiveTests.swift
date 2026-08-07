import BetterBlueKit
import SwiftData
import XCTest
@testable import GhostBlue

@MainActor
final class VehicleArchiveTests: XCTestCase {
    private func makeTrip(
        startDate: Date = Date(timeIntervalSince1970: 1_700_000_000),
        odometer: Double = 12_345,
        distance: Double = 42.5
    ) -> EVTripDetail {
        EVTripDetail(distance: distance, odometer: odometer, accessoriesEnergy: 220, totalEnergyUsed: 7_400, regenEnergy: 1_100, climateEnergy: 700, drivetrainEnergy: 5_900, batteryCareEnergy: 100, startDate: startDate, durationSeconds: 3_600, avgSpeed: 38, maxSpeed: 72)
    }

    func testTripRecordPreservesEveryTripField() {
        let trip = makeTrip()
        let record = TripRecord(vin: "TESTVIN", trip: trip)

        XCTAssertEqual(record.vin, "TESTVIN")
        XCTAssertEqual(record.distance, trip.distance)
        XCTAssertEqual(record.odometer, trip.odometer)
        XCTAssertEqual(record.totalEnergyUsed, trip.totalEnergyUsed)
        XCTAssertEqual(record.regenEnergy, trip.regenEnergy)
        XCTAssertEqual(record.climateEnergy, trip.climateEnergy)
        XCTAssertEqual(record.drivetrainEnergy, trip.drivetrainEnergy)
        XCTAssertEqual(record.durationSeconds, trip.durationSeconds)
        XCTAssertEqual(record.tripDetail.startDate, trip.startDate)
    }

    func testTripKeyDeduplicatesTheSameVehicleAndTrip() {
        let trip = makeTrip()

        XCTAssertEqual(TripRecord.key(vin: "TESTVIN", trip: trip), TripRecord.key(vin: "TESTVIN", trip: trip))
        XCTAssertNotEqual(TripRecord.key(vin: "TESTVIN", trip: trip), TripRecord.key(vin: "OTHERVIN", trip: trip))
        XCTAssertNotEqual(TripRecord.key(vin: "TESTVIN", trip: trip), TripRecord.key(vin: "TESTVIN", trip: makeTrip(odometer: trip.odometer + 1)))
    }

    func testTripRecordPersistsAndFetchesFromAnInMemoryStore() throws {
        let container = try ModelContainer(for: Schema([TripRecord.self]), configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = container.mainContext
        context.insert(TripRecord(vin: "TESTVIN", trip: makeTrip()))
        try context.save()

        let vin = "TESTVIN"
        let savedTrips = try context.fetch(FetchDescriptor<TripRecord>(predicate: #Predicate { $0.vin == vin }))

        XCTAssertEqual(savedTrips.count, 1)
        XCTAssertEqual(savedTrips.first?.tripDetail.distance, 42.5)
    }

    func testTripUserMetadataPersistsWithoutChangingAPITrip() throws {
        let container = try ModelContainer(
            for: Schema([TripRecord.self]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let context = container.mainContext
        let record = TripRecord(vin: "TESTVIN", trip: makeTrip())
        record.customName = "Morning commute"
        record.isFavorite = true
        record.startLocationName = "Home"
        record.endLocationName = "Office"
        record.startLatitude = 37.3349
        record.startLongitude = -122.0090
        record.endLatitude = 37.7749
        record.endLongitude = -122.4194
        context.insert(record)
        try context.save()

        let saved = try XCTUnwrap(context.fetch(FetchDescriptor<TripRecord>()).first)
        XCTAssertEqual(saved.customName, "Morning commute")
        XCTAssertTrue(saved.isFavorite)
        XCTAssertEqual(saved.startLocationName, "Home")
        XCTAssertEqual(saved.endLocationName, "Office")
        XCTAssertEqual(saved.startLatitude, 37.3349)
        XCTAssertEqual(saved.startLongitude, -122.0090)
        XCTAssertEqual(saved.endLatitude, 37.7749)
        XCTAssertEqual(saved.endLongitude, -122.4194)
        XCTAssertEqual(saved.tripDetail.distance, 42.5)
    }

    func testArchiveKeepsOldTripsAndDeduplicatesTheRollingAPIWindow() throws {
        let container = try ModelContainer(
            for: Schema([BBAccount.self, BBVehicle.self, ClimatePreset.self, ChargeSession.self, TripRecord.self]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let context = container.mainContext
        let vehicle = BBVehicle(from: Vehicle(
            vin: "TESTVIN",
            regId: "test-registration",
            model: "Test EV",
            accountId: UUID(),
            fuelType: .electric,
            generation: 3,
            odometer: Distance(length: 0, units: .miles)
        ))
        context.insert(vehicle)
        try context.save()

        let firstTrip = makeTrip()
        let laterTrip = makeTrip(
            startDate: firstTrip.startDate.addingTimeInterval(3_600),
            odometer: firstTrip.odometer + 28,
            distance: 28
        )
        vehicle.archiveTrips([firstTrip, firstTrip])
        vehicle.archiveTrips([firstTrip, laterTrip])

        let vin = "TESTVIN"
        let records = try context.fetch(FetchDescriptor<TripRecord>(predicate: #Predicate { $0.vin == vin }))
        XCTAssertEqual(records.count, 2, "Overlapping API windows must never duplicate saved trips")
        XCTAssertEqual(Set(records.map(\.tripKey)).count, 2)
    }

    func testChargeSessionMeaningfulRulesAndPercentGain() {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let session = ChargeSession(vin: "TESTVIN", startDate: start, startPercentage: 50, isDCFast: false)

        XCTAssertTrue(session.isActive)
        XCTAssertTrue(session.isMeaningful)

        session.endDate = start.addingTimeInterval(120)
        session.lastSampleDate = start.addingTimeInterval(120)
        XCTAssertFalse(session.isActive)
        XCTAssertFalse(session.isMeaningful)

        session.endPercentage = 53
        XCTAssertEqual(session.percentAdded, 3)
        XCTAssertTrue(session.isMeaningful)

        session.endPercentage = 45
        XCTAssertEqual(session.percentAdded, 0, "A stale status must never create negative added charge")
    }

    func testChargeSessionPreservesExactStartEndAndUpdateTimes() throws {
        let container = try ModelContainer(
            for: Schema([BBAccount.self, BBVehicle.self, ClimatePreset.self, ChargeSession.self]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let context = container.mainContext
        let vehicle = BBVehicle(from: Vehicle(
            vin: "TESTVIN",
            regId: "test-registration",
            model: "Test EV",
            accountId: UUID(),
            fuelType: .electric,
            generation: 3,
            odometer: Distance(length: 0, units: .miles)
        ))
        context.insert(vehicle)
        try context.save()

        let startedAt = Date(timeIntervalSince1970: 1_700_000_000.125)
        let updatedAt = Date(timeIntervalSince1970: 1_700_000_125.500)
        let endedAt = Date(timeIntervalSince1970: 1_700_000_360.875)

        vehicle.updateStatus(with: makeVehicleStatus(
            charging: true,
            percentage: 20,
            syncDate: startedAt
        ))
        vehicle.updateStatus(with: makeVehicleStatus(
            charging: true,
            percentage: 25,
            syncDate: updatedAt
        ))

        var session = try XCTUnwrap(context.fetch(FetchDescriptor<ChargeSession>()).first)
        XCTAssertEqual(session.startDate, startedAt)
        XCTAssertNil(session.endDate)
        XCTAssertEqual(session.lastSampleDate, updatedAt)

        vehicle.updateStatus(with: makeVehicleStatus(
            charging: false,
            percentage: 30,
            syncDate: endedAt
        ))

        session = try XCTUnwrap(context.fetch(FetchDescriptor<ChargeSession>()).first)
        XCTAssertEqual(session.startDate, startedAt)
        XCTAssertEqual(session.endDate, endedAt)
        XCTAssertEqual(session.lastSampleDate, endedAt)
    }

    private func makeVehicleStatus(
        charging: Bool,
        percentage: Double,
        syncDate: Date
    ) -> VehicleStatus {
        VehicleStatus(
            vin: "TESTVIN",
            evStatus: VehicleStatus.EVStatus(
                charging: charging,
                chargeSpeed: charging ? 7.2 : 0,
                evRange: VehicleStatus.FuelRange(
                    range: Distance(length: 150, units: .miles),
                    percentage: percentage
                ),
                plugType: charging ? .acCharger : .unplugged,
                chargeTime: .seconds(3_600)
            ),
            location: VehicleStatus.Location(latitude: 37.3349, longitude: -122.0090),
            lockStatus: .locked,
            climateStatus: VehicleStatus.ClimateStatus(
                defrostOn: false,
                airControlOn: false,
                steeringWheelHeatingOn: false,
                temperature: Temperature(value: 70, units: .fahrenheit)
            ),
            syncDate: syncDate
        )
    }

    func testChargeSpeedHistoryIsPersistedAndBounded() throws {
        let container = try ModelContainer(
            for: Schema([ChargeSession.self]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let context = container.mainContext
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let session = ChargeSession(
            vin: "TESTVIN",
            startDate: start,
            startPercentage: 20,
            isDCFast: true
        )

        for index in 0..<125 {
            session.appendSpeedSample(
                date: start.addingTimeInterval(Double(index * 60)),
                speedKW: Double(index),
                batteryPercentage: 20 + Double(index) / 10
            )
        }
        context.insert(session)
        try context.save()

        let saved = try XCTUnwrap(context.fetch(FetchDescriptor<ChargeSession>()).first)
        XCTAssertEqual(saved.speedSamples.count, 120)
        XCTAssertEqual(saved.speedSamples.first?.speedKW, 5)
        XCTAssertEqual(saved.speedSamples.last?.speedKW, 124)
    }
}
