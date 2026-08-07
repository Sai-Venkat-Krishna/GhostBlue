import BetterBlueKit
import SwiftData
import XCTest
@testable import GhostBlue

@MainActor
final class OwnershipFeatureTests: XCTestCase {
    private func sdgeDate(
        _ year: Int,
        _ month: Int,
        _ day: Int,
        _ hour: Int,
        minute: Int = 0
    ) throws -> Date {
        try XCTUnwrap(SDGESmartChargingSchedule.calendar().date(from: DateComponents(
            year: year,
            month: month,
            day: day,
            hour: hour,
            minute: minute
        )))
    }

    func testSDGEWeekdayChargingWindowsCoverEveryBoundary() throws {
        // Monday, August 3, 2026 is a normal weekday.
        XCTAssertEqual(
            SDGESmartChargingSchedule.preference(at: try sdgeDate(2026, 8, 3, 0)),
            .charge
        )
        XCTAssertEqual(
            SDGESmartChargingSchedule.preference(at: try sdgeDate(2026, 8, 3, 5, minute: 59)),
            .charge
        )
        XCTAssertEqual(
            SDGESmartChargingSchedule.preference(at: try sdgeDate(2026, 8, 3, 6)),
            .pause
        )
        XCTAssertEqual(
            SDGESmartChargingSchedule.preference(at: try sdgeDate(2026, 8, 3, 9, minute: 59)),
            .pause
        )
        XCTAssertEqual(
            SDGESmartChargingSchedule.preference(at: try sdgeDate(2026, 8, 3, 10)),
            .charge
        )
        XCTAssertEqual(
            SDGESmartChargingSchedule.preference(at: try sdgeDate(2026, 8, 3, 13, minute: 59)),
            .charge
        )
        XCTAssertEqual(
            SDGESmartChargingSchedule.preference(at: try sdgeDate(2026, 8, 3, 14)),
            .pause
        )
    }

    func testSDGEWeekendAndHolidayRemainContinuousUntilTwoPM() throws {
        // Saturday and Thanksgiving (Thursday) both use the weekend window.
        XCTAssertEqual(
            SDGESmartChargingSchedule.preference(at: try sdgeDate(2026, 8, 8, 8)),
            .charge
        )
        XCTAssertEqual(
            SDGESmartChargingSchedule.preference(at: try sdgeDate(2026, 8, 8, 14)),
            .pause
        )
        XCTAssertTrue(
            SDGESmartChargingSchedule.isHoliday(try sdgeDate(2026, 11, 26, 8))
        )
        XCTAssertEqual(
            SDGESmartChargingSchedule.preference(at: try sdgeDate(2026, 11, 26, 8)),
            .charge
        )
    }

    func testSDGESundayHolidayObservesMondayButSaturdayDoesNotShiftFriday() throws {
        // July 4, 2027 is Sunday, so Monday July 5 is the legal holiday.
        XCTAssertTrue(SDGESmartChargingSchedule.isHoliday(try sdgeDate(2027, 7, 5, 8)))
        // July 4, 2026 is Saturday; Friday July 3 is a normal weekday.
        XCTAssertFalse(SDGESmartChargingSchedule.isHoliday(try sdgeDate(2026, 7, 3, 8)))
        XCTAssertEqual(
            SDGESmartChargingSchedule.preference(at: try sdgeDate(2026, 7, 3, 8)),
            .pause
        )
    }

    func testRemoteCommandActivityPersistsSuccessAndFailureDetails() throws {
        let container = try ModelContainer(
            for: Schema([RemoteCommandActivity.self]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let context = container.mainContext
        let succeeded = RemoteCommandActivity(
            vin: "VIN-1",
            vehicleName: "Ioniq",
            commandName: "Lock",
            sourceName: "Apple Watch"
        )
        succeeded.finish(.succeeded)
        let failed = RemoteCommandActivity(
            vin: "VIN-1",
            vehicleName: "Ioniq",
            commandName: "Start climate",
            sourceName: "Widget or Shortcut"
        )
        failed.finish(.failed, error: NSError(
            domain: "OwnershipFeatureTests",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "Vehicle did not respond"]
        ))
        context.insert(succeeded)
        context.insert(failed)
        try context.save()

        let saved = try context.fetch(FetchDescriptor<RemoteCommandActivity>())
        XCTAssertEqual(saved.count, 2)
        XCTAssertEqual(saved.first(where: { $0.commandName == "Lock" })?.state, .succeeded)
        XCTAssertEqual(
            saved.first(where: { $0.commandName == "Start climate" })?.errorMessage,
            "Vehicle did not respond"
        )
    }

    func testChargingSessionUsesHomeClassificationAndOffPeakSnapshotRate() throws {
        let vehicle = BBVehicle(from: Vehicle(
            vin: "VIN-CHARGE",
            regId: "registration",
            model: "Ioniq 5",
            accountId: UUID(),
            fuelType: .electric,
            generation: 3,
            odometer: Distance(length: 12_000, units: .miles)
        ))
        vehicle.homeLatitude = 37.3349
        vehicle.homeLongitude = -122.0090
        vehicle.homePrivacyRadiusMeters = 300
        vehicle.electricityRatePerKWh = 0.30
        vehicle.offPeakElectricityRatePerKWh = 0.10
        vehicle.offPeakStartHour = 22
        vehicle.offPeakEndHour = 6

        let sampleDate = try XCTUnwrap(
            Calendar.current.date(
                from: DateComponents(
                    year: 2026,
                    month: 1,
                    day: 2,
                    hour: 1
                )
            )
        )
        XCTAssertEqual(
            vehicle.inferredChargingLocationType(
                latitude: 37.3349,
                longitude: -122.0090,
                isDCFast: false
            ),
            .home
        )
        XCTAssertEqual(vehicle.effectiveElectricityRate(at: sampleDate), 0.10)

        let session = ChargeSession(
            vin: vehicle.vin,
            startDate: sampleDate,
            startPercentage: 40,
            isDCFast: false
        )
        session.locationType = vehicle.inferredChargingLocationType(
            latitude: 37.3349,
            longitude: -122.0090,
            isDCFast: false
        )
        session.electricityRatePerKWh = vehicle.effectiveElectricityRate(at: sampleDate)
        XCTAssertEqual(session.locationType, .home)
        XCTAssertEqual(session.electricityRatePerKWh, 0.10)
    }

    func testSmartChargingTariffUsesBothWeekdayWindows() throws {
        let vehicle = BBVehicle(from: Vehicle(
            vin: "VIN-SDGE",
            regId: "registration",
            model: "Ioniq 5",
            accountId: UUID(),
            fuelType: .electric,
            generation: 3,
            odometer: Distance(length: 0, units: .miles)
        ))
        vehicle.electricityRatePerKWh = 0.50
        vehicle.offPeakElectricityRatePerKWh = 0.13
        vehicle.smartChargingEnabled = true

        XCTAssertEqual(
            vehicle.effectiveElectricityRate(at: try sdgeDate(2026, 8, 3, 5)),
            0.13
        )
        XCTAssertEqual(
            vehicle.effectiveElectricityRate(at: try sdgeDate(2026, 8, 3, 8)),
            0.50
        )
        XCTAssertEqual(
            vehicle.effectiveElectricityRate(at: try sdgeDate(2026, 8, 3, 11)),
            0.13
        )
    }

    func testSmartChargingToleratesPausedHomeACReportedAsDC() {
        let vehicle = BBVehicle(from: Vehicle(
            vin: "VIN-HOME-AC",
            regId: "registration",
            model: "Ioniq 5",
            accountId: UUID(),
            fuelType: .electric,
            generation: 3,
            odometer: Distance(length: 0, units: .miles)
        ))
        vehicle.smartChargingHomeOnly = true
        vehicle.homeLatitude = 32.7157
        vehicle.homeLongitude = -117.1611
        vehicle.location = VehicleStatus.Location(latitude: 32.7157, longitude: -117.1611)

        let pausedHomeACReportedAsDC = VehicleStatus.EVStatus(
            charging: false,
            chargeSpeed: 0,
            evRange: .init(range: Distance(length: 200, units: .miles), percentage: 50),
            plugType: .dcCharger,
            chargeTime: .seconds(0)
        )
        XCTAssertTrue(vehicle.isSmartChargingControllableAC(pausedHomeACReportedAsDC))

        let genuineFastDC = VehicleStatus.EVStatus(
            charging: true,
            chargeSpeed: 150,
            evRange: .init(range: Distance(length: 200, units: .miles), percentage: 50),
            plugType: .dcCharger,
            chargeTime: .seconds(0)
        )
        XCTAssertFalse(vehicle.isSmartChargingControllableAC(genuineFastDC))

        vehicle.location = VehicleStatus.Location(latitude: 33.7157, longitude: -118.1611)
        XCTAssertFalse(vehicle.isSmartChargingControllableAC(pausedHomeACReportedAsDC))
    }

    func testMaintenanceAndAutomationRecordsRemainVehicleScoped() throws {
        let container = try ModelContainer(
            for: Schema([MaintenanceReminder.self, VehicleAutomation.self]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let context = container.mainContext
        context.insert(MaintenanceReminder(
            vin: "VIN-A",
            title: "Tire rotation",
            dueOdometer: 20_000
        ))
        context.insert(MaintenanceReminder(
            vin: "VIN-B",
            title: "Annual inspection",
            dueDate: Date()
        ))
        context.insert(VehicleAutomation(
            vin: "VIN-A",
            title: "Charge soon",
            kind: .lowBattery,
            threshold: 25
        ))
        try context.save()

        let vin = "VIN-A"
        let reminders = try context.fetch(
            FetchDescriptor<MaintenanceReminder>(predicate: #Predicate { $0.vin == vin })
        )
        let automations = try context.fetch(
            FetchDescriptor<VehicleAutomation>(predicate: #Predicate { $0.vin == vin })
        )
        XCTAssertEqual(reminders.map(\.title), ["Tire rotation"])
        XCTAssertEqual(automations.first?.threshold, 25)
        XCTAssertEqual(automations.first?.kind, .lowBattery)
    }

    func testOdometerMaintenanceReminderIsMarkedWhenDue() throws {
        let container = try ModelContainer(
            for: Schema([
                BBAccount.self,
                BBVehicle.self,
                ClimatePreset.self,
                MaintenanceReminder.self
            ]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let context = container.mainContext
        let vehicle = BBVehicle(from: Vehicle(
            vin: "VIN-DUE",
            regId: "registration",
            model: "Test Vehicle",
            accountId: UUID(),
            fuelType: .gas,
            generation: 1,
            odometer: Distance(length: 20_500, units: .miles)
        ))
        let reminder = MaintenanceReminder(
            vin: vehicle.vin,
            title: "Tire rotation",
            dueOdometer: 20_000
        )
        context.insert(vehicle)
        context.insert(reminder)
        try context.save()

        vehicle.evaluateMaintenanceReminders()

        XCTAssertNotNil(reminder.lastNotifiedAt)
    }

    func testTripMetadataRedactionPreservesNamesAndFavoriteState() {
        let trip = EVTripDetail(
            distance: 10,
            odometer: 1_000,
            accessoriesEnergy: 10,
            totalEnergyUsed: 2_000,
            regenEnergy: 100,
            climateEnergy: 200,
            drivetrainEnergy: 1_700,
            batteryCareEnergy: 0,
            startDate: Date(),
            durationSeconds: 900,
            avgSpeed: 30,
            maxSpeed: 50
        )
        let record = TripRecord(vin: "VIN", trip: trip)
        record.customName = "Commute"
        record.isFavorite = true
        record.startLocationName = "Home"
        record.endLocationName = "Office"

        let redacted = TripCSVMetadata(record: record, redactLocations: true)

        XCTAssertEqual(redacted.customName, "Commute")
        XCTAssertTrue(redacted.isFavorite)
        XCTAssertEqual(redacted.startLocationName, "Private location")
        XCTAssertEqual(redacted.endLocationName, "Private location")
    }
}
