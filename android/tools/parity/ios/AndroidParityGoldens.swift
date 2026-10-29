//
//  AndroidParityGoldens.swift
//
//  Not part of the iOS test suite. android/tools/parity/app-parity.sh copies
//  it into a scratch copy of the project and runs it on a simulator, so the
//  golden files the Android port's tests compare against come from the real
//  iOS code: a backup, charge-session runs, the SDG&E schedule, tariff costs,
//  battery defaults and the CSV export. It also imports a backup the Android
//  app wrote and records what iOS made of it.
//

import BetterBlueKit
import SwiftData
import XCTest
@testable import GhostBlue

@MainActor
final class AndroidParityGoldens: XCTestCase {
    private var containers: [ModelContainer] = []

    override func tearDown() {
        containers = []
        super.tearDown()
    }

    private func makeContext() throws -> ModelContext {
        let container = try ModelContainer(
            for: Schema([
                BBAccount.self, BBVehicle.self, BBHTTPLog.self, ClimatePreset.self,
                ChargeSession.self, TripRecord.self, RemoteCommandActivity.self,
                MaintenanceReminder.self, VehicleAutomation.self
            ]),
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        containers.append(container)
        return container.mainContext
    }

    private func outputDirectory() throws -> URL {
        guard let path = ProcessInfo.processInfo.environment["PARITY_DIR"] else {
            throw XCTSkip("PARITY_DIR is not set")
        }
        return URL(fileURLWithPath: path, isDirectory: true)
    }

    private func write(_ object: Any, to name: String) throws {
        let data = try JSONSerialization.data(
            withJSONObject: object,
            options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        )
        try data.write(to: try outputDirectory().appendingPathComponent(name))
    }

    private func t(_ seconds: TimeInterval) -> Date { Date(timeIntervalSince1970: seconds) }

    private func seconds(_ date: Date?) -> Any { date.map { $0.timeIntervalSince1970 as Any } ?? NSNull() }

    private func value(_ number: Double?) -> Any { number.map { $0 as Any } ?? NSNull() }

    func testWriteGoldens() throws {
        _ = try outputDirectory()
        try writeBackupGolden()
        try writeChargeScenarios()
        try writeScheduleGolden()
        try writeTariffGolden()
        try writeCatalogGolden()
        try importAndroidBackup()
    }

    // MARK: - iPhone backup

    private func seedIPhoneStore(_ context: ModelContext) throws {
        let token = AuthToken(accessToken: "access", refreshToken: "refresh", expiresAt: t(1_900_000_000.5))
        let account = BBAccount(username: "Driver@Example.com", password: "s3cret \"quoted\"", pin: "1234",
                                brand: .hyundai, region: .usa, refreshToken: "refresh-token")
        account.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000001")!
        account.dateCreated = t(1_780_000_000)
        account.rememberMeToken = "rm-token"
        account.deviceId = "DEVICE-1"
        account.lastVehiclesFetch = t(1_790_000_100)
        account.serializedAuthToken = String(data: try JSONEncoder().encode(token), encoding: .utf8)
        context.insert(account)

        let tester = BBAccount(username: "testaccount@betterblue.com", password: "betterblue", pin: "",
                               brand: .fake, region: .canada)
        tester.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000002")!
        tester.dateCreated = t(1_780_000_500)
        context.insert(tester)

        let car = BBVehicle(from: Vehicle(vin: "KMHIOS00000000001", regId: "reg-ios", model: "IONIQ 5",
                                          accountId: account.id, fuelType: .electric, generation: 3,
                                          odometer: .init(length: 12_345.6, units: .miles)))
        car.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000011")!
        car.lastUpdated = t(1_790_000_200)
        car.syncDate = t(1_790_000_150)
        car.evStatus = .init(charging: true, chargeSpeed: 10.9,
                             evRange: .init(range: .init(length: 184, units: .miles), percentage: 62.5),
                             plugType: .acCharger, chargeTime: .seconds(3600), targetSocAC: 80, targetSocDC: 90)
        car.location = .init(latitude: 32.7158, longitude: -117.1612)
        car.lockStatus = .locked
        car.climateStatus = .init(defrostOn: true, airControlOn: true, steeringWheelHeatingOn: false,
                                  temperature: .init(value: 72, units: .fahrenheit))
        car.battery12V = 81
        car.doorOpen = .init(frontLeft: true, frontRight: false, backLeft: false, backRight: false)
        car.trunkOpen = false
        car.hoodOpen = false
        car.tirePressureWarning = .init(frontLeft: true, frontRight: false, rearLeft: false, rearRight: false, all: false)
        car.customName = "Blue Ghost"
        car.sortOrder = 3
        car.backgroundColorName = "ocean"
        car.watchBackgroundColorName = "midnight"
        car.vehicleImageColorRaw = "matte-grey"
        car.chargePortTypeRaw = "NACS"
        car.batteryCapacityKWh = 84
        car.electricityRatePerKWh = 0.31
        car.offPeakElectricityRatePerKWh = 0.12
        car.dcFastChargingRatePerKWh = 0.48
        car.peakElectricityRatePerKWh = 0.62
        car.offPeakStartHour = 23
        car.offPeakEndHour = 7
        car.monthlyChargingBudget = 75
        car.homeLatitude = 32.7157
        car.homeLongitude = -117.1611
        car.homePrivacyRadiusMeters = 250
        car.smartChargingEnabled = true
        car.smartChargingHomeOnly = false
        car.smartChargingTargetSOC = 85
        car.notifyUnlocked = true
        car.notifyTirePressure = false
        car.lowEVBatteryThreshold = 15
        car.low12VBatteryThreshold = 40
        car.enableSeatHeatControls = true
        car.showClimateDurationOverride = false
        car.abrpUserToken = "abrp-token"
        car.abrpCarModel = "hyundai:ioniq5:22:77wr"
        car.primaryColorName = "teal"
        car.lockColorName = "indigo"
        car.stopColorName = "pink"
        car.account = account
        context.insert(car)

        let phev = BBVehicle(from: Vehicle(vin: "KMHIOS00000000002", regId: "reg-phev", model: "TUCSON",
                                           accountId: account.id, fuelType: .gas, generation: 2,
                                           odometer: .init(length: 40_210, units: .kilometers)))
        phev.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000012")!
        phev.fuelTypeOverrideRaw = "phev"
        phev.gasRange = .init(range: .init(length: 410, units: .kilometers), percentage: 55)
        phev.lockStatus = .unlocked
        phev.sortOrder = 4
        phev.account = account
        context.insert(phev)

        var warm = ClimateOptions()
        warm.temperature = Temperature(value: 24, units: .celsius)
        warm.defrost = true
        warm.duration = 15
        warm.frontLeftSeat = 3
        warm.frontRightSeat = 1
        warm.steeringWheel = 1
        warm.frontLeftVentilation = true
        warm.rearDefrost = nil
        let morning = ClimatePreset(name: "Morning", iconName: "sun.max", climateOptions: warm, isSelected: true, vehicle: car)
        morning.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000021")!
        morning.sortOrder = 1
        context.insert(morning)
        let cool = ClimatePreset(name: "Cool", iconName: "snowflake", climateOptions: ClimateOptions(), vehicle: car)
        cool.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000022")!
        context.insert(cool)
        let phevPreset = ClimatePreset(name: "Morning", iconName: "fan", climateOptions: ClimateOptions(), vehicle: phev)
        phevPreset.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000023")!
        context.insert(phevPreset)

        let home = ChargeSession(vin: car.vin, startDate: t(1_790_000_000), startPercentage: 31, isDCFast: false,
                                 latitude: 32.7158, longitude: -117.1612)
        home.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000031")!
        home.applyUserEdits(
            startDate: t(1_790_000_000), endDate: t(1_790_021_600), startPercentage: 31, endPercentage: 80,
            energyAddedKWh: 41.2, maxChargeSpeedKW: 10.9, isDCFast: false, locationType: .home,
            electricityRatePerKWh: 0.12, actualCost: nil, manualEnergyKWh: nil,
            tariff: ChargingTariff(standardRate: 0.31, offPeakRate: 0.12, peakRate: 0.62, usesSDGE: true,
                                   offPeakStartHour: 0, offPeakEndHour: 6, timeZoneIdentifier: "America/Los_Angeles")
        )
        home.speedSamples = [
            ChargeSpeedSample(date: t(1_790_003_600), speedKW: 10.9, batteryPercentage: 40),
            ChargeSpeedSample(date: t(1_790_010_800), speedKW: 10.8, batteryPercentage: 58.5)
        ]
        home.locationName = "Home"
        context.insert(home)

        let fast = ChargeSession(vin: car.vin, startDate: t(1_790_219_000), startPercentage: 18, isDCFast: true)
        fast.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000032")!
        fast.applyUserEdits(
            startDate: t(1_790_219_000), endDate: t(1_790_222_600), startPercentage: 18, endPercentage: 72,
            energyAddedKWh: 0, maxChargeSpeedKW: 178, isDCFast: true, locationType: .publicDC,
            electricityRatePerKWh: 0.48, actualCost: 21.37, manualEnergyKWh: 46.1
        )
        fast.locationName = "Electrify America"
        context.insert(fast)

        // Legacy session from before location-aware pricing, re-priced on read.
        let legacy = ChargeSession(vin: car.vin, startDate: t(1_790_400_000.6), startPercentage: 40, isDCFast: false)
        legacy.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000033")!
        legacy.endDate = t(1_790_407_200)
        legacy.lastSampleDate = t(1_790_407_200)
        legacy.endPercentage = 52
        legacy.energyAddedKWh = 9.75
        legacy.electricityRatePerKWh = 0.2
        context.insert(legacy)

        // Still charging.
        let active = ChargeSession(vin: car.vin, startDate: t(1_790_500_000), startPercentage: 55, isDCFast: false)
        active.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000034")!
        active.lastSampleDate = t(1_790_501_800)
        active.endPercentage = 62.5
        active.energyAddedKWh = 5.45
        active.maxChargeSpeedKW = 10.9
        active.rateWasLocationAware = true
        active.locationTypeRaw = "automatic"
        context.insert(active)

        let commute = TripRecord(vin: car.vin, trip: EVTripDetail(
            distance: 28.4, odometer: 12_300.2, accessoriesEnergy: 358, totalEnergyUsed: 8_950, regenEnergy: 1_140,
            climateEnergy: 720, drivetrainEnergy: 6_950, batteryCareEnergy: 179, startDate: t(1_789_900_000),
            durationSeconds: 2_700, avgSpeed: 37.866, maxSpeed: 55.866
        ))
        commute.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000041")!
        commute.savedDate = t(1_789_910_000)
        commute.customName = "Commute"
        commute.isFavorite = true
        commute.startLocationName = "Home"
        commute.endLocationName = "Office"
        commute.startLatitude = 32.7157
        commute.startLongitude = -117.1611
        commute.endLatitude = 32.8801
        commute.endLongitude = -117.2340
        context.insert(commute)
        let errand = TripRecord(vin: car.vin, trip: EVTripDetail(
            distance: 8.3, odometer: 12_308.5, accessoriesEnergy: 108, totalEnergyUsed: 2_710, regenEnergy: 220,
            climateEnergy: 410, drivetrainEnergy: 1_950, batteryCareEnergy: 54, startDate: t(1_789_950_000),
            durationSeconds: 1_080, avgSpeed: 27.666, maxSpeed: 45.666
        ))
        errand.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000042")!
        errand.savedDate = t(1_789_960_000)
        context.insert(errand)

        let lock = RemoteCommandActivity(vin: car.vin, vehicleName: "Blue Ghost", commandName: "Lock", sourceName: "iPhone")
        lock.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000051")!
        lock.startedAt = t(1_790_100_000.7)
        lock.stateRaw = "succeeded"
        lock.completedAt = t(1_790_100_012)
        context.insert(lock)
        let climate = RemoteCommandActivity(vin: car.vin, vehicleName: "Blue Ghost", commandName: "Start climate",
                                            sourceName: "Apple Watch")
        climate.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000052")!
        climate.startedAt = t(1_790_100_100)
        climate.stateRaw = "failed"
        climate.completedAt = t(1_790_100_130)
        climate.errorMessage = "Vehicle did not respond"
        context.insert(climate)

        let tires = MaintenanceReminder(vin: car.vin, title: "Tire rotation", notes: "Front to back",
                                        dueDate: t(1_795_000_000), dueOdometer: 15_000, cost: 49.99)
        tires.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000061")!
        tires.createdAt = t(1_785_000_000)
        context.insert(tires)
        let wipers = MaintenanceReminder(vin: phev.vin, title: "Wipers")
        wipers.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000062")!
        wipers.createdAt = t(1_785_000_100)
        wipers.completedAt = t(1_786_000_000)
        wipers.lastNotifiedAt = t(1_785_500_000)
        context.insert(wipers)

        let low = VehicleAutomation(vin: car.vin, title: "Charge soon", kind: .lowBattery, threshold: 25)
        low.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000071")!
        low.createdAt = t(1_785_000_200)
        low.lastTriggeredAt = t(1_789_000_000)
        context.insert(low)
        let warmUp = VehicleAutomation(vin: car.vin, title: "Warm up", kind: .preconditionReminder, hour: 6, minute: 45)
        warmUp.id = UUID(uuidString: "0A1B2C3D-0000-4000-8000-000000000072")!
        warmUp.createdAt = t(1_785_000_300)
        warmUp.isEnabled = false
        context.insert(warmUp)

        try context.save()
    }

    private func writeBackupGolden() throws {
        let context = try makeContext()
        try seedIPhoneStore(context)
        let data = try BackupArchive.export(from: context, deviceName: "iPhone").encoded()
        try data.write(to: try outputDirectory().appendingPathComponent("ios-backup.ghostbluebackup"))

        let car = try XCTUnwrap(context.fetch(FetchDescriptor<BBVehicle>()).first { $0.vin == "KMHIOS00000000001" })
        let sessions = try context.fetch(FetchDescriptor<ChargeSession>(sortBy: [SortDescriptor(\.startDate)]))
        try write([
            "sessions": sessions.map { session in
                [
                    "id": session.id.uuidString,
                    "energyDelivered": car.energyDelivered(for: session),
                    "chargingCost": value(car.chargingCost(for: session)),
                    "energySource": car.energySource(for: session),
                    "isMeaningful": session.isMeaningful
                ] as [String: Any]
            },
            "csv": ChargingCSVExporter.csv(sessions: sessions, vehicle: car)
        ], to: "ios-backup-expectations.json")
    }

    // MARK: - Android backup on iOS

    private func importAndroidBackup() throws {
        let url = try outputDirectory().appendingPathComponent("android-backup.ghostbluebackup")
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        let context = try makeContext()
        let archive = try BackupTransfer.readArchive(at: url)
        let summary = try BackupArchive.merge(archive, into: context)
        let again = try BackupArchive.merge(archive, into: context)
        try BackupArchive.export(from: context, deviceName: "iPhone").encoded()
            .write(to: try outputDirectory().appendingPathComponent("android-backup-via-ios.ghostbluebackup"))

        let vehicles = try context.fetch(FetchDescriptor<BBVehicle>())
        let sessions = try context.fetch(FetchDescriptor<ChargeSession>(sortBy: [SortDescriptor(\.startDate)]))
        try write([
            "summary": summary.rows.map { [$0.0, $0.1] },
            "orphanedVehicles": summary.orphanedVehicles,
            "addedOnSecondImport": again.totalAdded,
            "sessions": sessions.map { session in
                let car = vehicles.first { $0.vin == session.vin }
                return [
                    "id": session.id.uuidString,
                    "energyDelivered": value(car?.energyDelivered(for: session)),
                    "chargingCost": value(car.flatMap { $0.chargingCost(for: session) })
                ] as [String: Any]
            },
            "tokensDecoded": try context.fetch(FetchDescriptor<BBAccount>()).map { account in
                account.serializedAuthToken.flatMap { $0.data(using: .utf8) }
                    .flatMap { try? JSONDecoder().decode(AuthToken.self, from: $0) }
                    .map { $0.expiresAt.timeIntervalSince1970 as Any } ?? NSNull()
            }
        ], to: "android-backup-import.json")
    }

    // MARK: - Charge sessions

    /// Repeatable pseudo-random numbers, so the scenarios are the same on every run.
    private struct Generator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return state >> 33
        }
        mutating func pick<T>(_ values: [T]) -> T { values[Int(next() % UInt64(values.count))] }
        mutating func chance(_ percent: UInt64) -> Bool { next() % 100 < percent }
    }

    private func configure(_ car: BBVehicle, config: Int) {
        car.notifyChargeComplete = false
        car.notifyChargeInterrupted = false
        car.notifyOpenPanels = false
        car.notifyLowEVBattery = false
        car.notifyLow12VBattery = false
        car.notifyTirePressure = false
        switch config {
        case 1:
            car.smartChargingEnabled = true
            car.smartChargingHomeOnly = false
            car.electricityRatePerKWh = 0.3
            car.offPeakElectricityRatePerKWh = 0.1
            car.peakElectricityRatePerKWh = 0.6
            car.dcFastChargingRatePerKWh = 0.5
            car.homeLatitude = 32.7157
            car.homeLongitude = -117.1611
        case 2:
            car.electricityRatePerKWh = 0.25
            car.dcFastChargingRatePerKWh = 0.45
        default:
            break
        }
    }

    private func randomSteps(seed: UInt64) -> [[String: Any]] {
        var random = Generator(state: seed)
        var steps: [[String: Any]] = []
        var sync = 1_790_000_000.0
        var percentage = random.pick([20.0, 35.5, 50.0, 78.0])
        var odometer = 1_000.0
        var charging = random.chance(70)
        for _ in 0 ..< 18 {
            if random.chance(8) {
                steps.append(["kind": "stopRequested", "at": sync + Double(random.pick([-30, 10, 60]))])
                continue
            }
            sync += Double(random.pick([0, 0, 60, 300, 600, 900, 1_800, 3_600, 7_200, -120]))
            let fetched = sync + Double(random.pick([0, 0, 30, 300, 600, 960]))
            if random.chance(25) { charging.toggle() }
            let plug = random.pick([2, 2, 2, 2, 1, 0])
            if charging && plug != 0 {
                percentage = min(100, percentage + random.pick([0.0, 0.5, 1.0, 2.5, 5.0]))
            } else {
                percentage = max(0, percentage + random.pick([-3.0, -0.5, 0.0, 0.5]))
            }
            if random.chance(10) { odometer += random.pick([0.4, 1.0, 12.0]) }
            let step: [String: Any] = [
                "kind": "status",
                "syncDate": sync,
                "fetchedAt": fetched,
                "hasEV": !random.chance(5),
                "charging": charging,
                "percentage": random.chance(4) ? 0.0 : percentage,
                "plugType": plug,
                "speed": charging ? random.pick([0.0, 3.6, 7.2, 10.9, 50.0, 150.0]) : 0.0,
                "odometer": odometer,
                "targetSocAC": random.pick([80.0, 100.0, -1.0]),
                "targetSocDC": random.pick([90.0, -1.0]),
                "atHome": random.chance(70)
            ]
            steps.append(step)
        }
        return steps
    }

    private func status(vin: String, step: [String: Any]) -> VehicleStatus {
        let percentage = step["percentage"] as! Double
        let targetAC = step["targetSocAC"] as! Double
        let targetDC = step["targetSocDC"] as! Double
        let ev = VehicleStatus.EVStatus(
            charging: step["charging"] as! Bool,
            chargeSpeed: step["speed"] as! Double,
            evRange: .init(range: .init(length: percentage * 3, units: .miles), percentage: percentage),
            plugType: VehicleStatus.PlugType(rawValue: step["plugType"] as! Int)!,
            chargeTime: .seconds(1_800),
            targetSocAC: targetAC < 0 ? nil : targetAC,
            targetSocDC: targetDC < 0 ? nil : targetDC
        )
        var result = VehicleStatus(
            vin: vin,
            evStatus: (step["hasEV"] as! Bool) ? ev : nil,
            location: (step["atHome"] as! Bool)
                ? .init(latitude: 32.7158, longitude: -117.1612) : .init(latitude: 32.80, longitude: -117.25),
            lockStatus: .locked,
            climateStatus: .init(defrostOn: false, airControlOn: false, steeringWheelHeatingOn: false,
                                 temperature: .init(value: 70, units: .fahrenheit)),
            odometer: .init(length: step["odometer"] as! Double, units: .miles),
            syncDate: t(step["syncDate"] as! Double)
        )
        result.lastUpdated = t(step["fetchedAt"] as! Double)
        return result
    }

    private func writeChargeScenarios() throws {
        let models = ["IONIQ 5", "Unknown EV", "EV9"]
        var scenarios: [[String: Any]] = []
        for index in 0 ..< 60 {
            let context = try makeContext()
            let config = index % 3
            let vin = "SCENARIO\(index)"
            let car = BBVehicle(from: Vehicle(vin: vin, regId: "s", model: models[config], accountId: UUID(),
                                              fuelType: .electric, generation: 3,
                                              odometer: .init(length: 1_000, units: .miles)))
            configure(car, config: config)
            context.insert(car)
            let steps = randomSteps(seed: UInt64(index) &* 7_919 &+ 17)
            var results: [[String: Any]] = []
            for step in steps {
                if step["kind"] as? String == "stopRequested" {
                    car.chargeStopRequestedAt = t(step["at"] as! Double)
                    results.append([:])
                    continue
                }
                let applied = status(vin: vin, step: step)
                let accepted = car.updateStatus(with: applied)
                results.append([
                    "acceptedCharging": accepted.evStatus.map { $0.charging as Any } ?? NSNull(),
                    "acceptedSpeed": value(accepted.evStatus?.chargeSpeed),
                    "acceptedPlugType": accepted.evStatus.map { $0.plugType.rawValue as Any } ?? NSNull(),
                    "acceptedSyncDate": seconds(accepted.syncDate),
                    "charging": car.evStatus.map { $0.charging as Any } ?? NSNull(),
                    "pendingChargingStopDate": seconds(car.pendingChargingStopDate),
                    "pendingChargingStopObservedAt": seconds(car.pendingChargingStopObservedAt),
                    "chargeStopRequestedAt": seconds(car.chargeStopRequestedAt),
                    "displayState": car.chargingDisplayState(at: t(step["syncDate"] as! Double)).rawValue
                ])
            }
            let sessions = try context.fetch(FetchDescriptor<ChargeSession>(sortBy: [SortDescriptor(\.startDate)]))
            scenarios.append([
                "config": config,
                "model": models[config],
                "vin": vin,
                "steps": steps,
                "results": results,
                "sessions": sessions.map { session -> [String: Any] in
                    [
                        "startDate": seconds(session.startDate),
                        "endDate": seconds(session.endDate),
                        "startPercentage": session.startPercentage,
                        "endPercentage": session.endPercentage,
                        "energyAddedKWh": session.energyAddedKWh,
                        "maxChargeSpeedKW": session.maxChargeSpeedKW,
                        "isDCFast": session.isDCFast,
                        "lastSampleDate": seconds(session.lastSampleDate),
                        "pendingStopDate": seconds(session.pendingStopDate),
                        "pendingStopObservedAt": seconds(session.pendingStopObservedAt),
                        "pendingStopPercentage": value(session.pendingStopPercentage),
                        "locationTypeRaw": session.locationTypeRaw,
                        "electricityRatePerKWh": value(session.electricityRatePerKWh),
                        "hasTariff": session.tariff != nil,
                        "samples": session.speedSamples.map { [$0.date.timeIntervalSince1970, $0.speedKW, $0.batteryPercentage] },
                        "energyDelivered": car.energyDelivered(for: session),
                        "chargingCost": value(car.chargingCost(for: session)),
                        "energySource": car.energySource(for: session)
                    ]
                }
            ])
        }
        try write(["scenarios": scenarios], to: "charge-scenarios.json")
    }

    // MARK: - SDG&E schedule

    private func writeScheduleGolden() throws {
        let calendar = SDGESmartChargingSchedule.calendar()
        var days: [String: String] = [:]
        var holidays: [String] = []
        var day = calendar.date(from: DateComponents(year: 2025, month: 1, day: 1))!
        let end = calendar.date(from: DateComponents(year: 2029, month: 1, day: 1))!
        while day < end {
            let parts = calendar.dateComponents([.year, .month, .day], from: day)
            let key = String(format: "%04d-%02d-%02d", parts.year!, parts.month!, parts.day!)
            var slots = ""
            for slot in 0 ..< 48 {
                let date = calendar.date(bySettingHour: slot / 2, minute: (slot % 2) * 30, second: 0, of: day)!
                slots += SDGESmartChargingSchedule.preference(at: date) == .charge ? "C" : "P"
            }
            days[key] = slots
            if SDGESmartChargingSchedule.isHoliday(calendar.date(bySettingHour: 12, minute: 0, second: 0, of: day)!) {
                holidays.append(key)
            }
            day = calendar.date(byAdding: .day, value: 1, to: day)!
        }
        var transitions: [[String: Any]] = []
        var probe = t(1_767_225_600) // 2026-01-01
        var random = Generator(state: 99)
        for _ in 0 ..< 120 {
            probe = probe.addingTimeInterval(Double(random.pick([3_600, 7_919, 86_400, 250_000, 1_000_000])))
            transitions.append([
                "after": probe.timeIntervalSince1970,
                "next": SDGESmartChargingSchedule.transitions(after: probe, count: 6).map {
                    [$0.date.timeIntervalSince1970, $0.preference.rawValue]
                },
                "mostRecent": seconds(SDGESmartChargingSchedule.mostRecentTransition(at: probe))
            ])
        }
        try write(["days": days, "holidays": holidays, "transitions": transitions], to: "sdge-schedule.json")
    }

    // MARK: - Tariffs

    private func writeTariffGolden() throws {
        let tariffs = [
            ChargingTariff(standardRate: 0.3, offPeakRate: 0.1, peakRate: 0.6, usesSDGE: true,
                           offPeakStartHour: 0, offPeakEndHour: 6, timeZoneIdentifier: "America/Los_Angeles"),
            ChargingTariff(standardRate: 0.3, offPeakRate: nil, peakRate: nil, usesSDGE: true,
                           offPeakStartHour: 0, offPeakEndHour: 6, timeZoneIdentifier: "America/Los_Angeles"),
            ChargingTariff(standardRate: 0.22, offPeakRate: 0.09, peakRate: nil, usesSDGE: false,
                           offPeakStartHour: 22, offPeakEndHour: 6, timeZoneIdentifier: "America/New_York"),
            ChargingTariff(standardRate: 0.28, offPeakRate: 0.075, peakRate: 0.4, usesSDGE: false,
                           offPeakStartHour: 0, offPeakEndHour: 7, timeZoneIdentifier: "Europe/London"),
            ChargingTariff(standardRate: 8, offPeakRate: 6.5, peakRate: nil, usesSDGE: false,
                           offPeakStartHour: 23, offPeakEndHour: 5, timeZoneIdentifier: "Asia/Kolkata"),
            ChargingTariff(standardRate: 0.3, offPeakRate: 0.1, peakRate: nil, usesSDGE: false,
                           offPeakStartHour: 1, offPeakEndHour: 5, timeZoneIdentifier: "Not/AZone")
        ]
        let starts: [TimeInterval] = [
            1_773_043_200, // 2026-03-09 00:00 PDT-ish, US spring-forward week
            1_772_960_400, // 2026-03-08 09:00 UTC, the US change
            1_774_746_000, // 2026-03-29 01:00 UTC, the EU change
            1_777_222_800.5, 1_790_000_000, 1_793_523_600, // 2026-11-01, US fall-back
            1_792_890_000, // 2026-10-25, EU fall-back
            1_798_761_600 // 2027-01-01
        ]
        let durations: [TimeInterval] = [0, -60, 1, 59.5, 1_800, 3_600, 7_215, 21_600, 30 * 3_600, 3 * 86_400]
        var cases: [[String: Any]] = []
        for (index, tariff) in tariffs.enumerated() {
            for start in starts {
                for duration in durations {
                    let from = t(start)
                    let to = from.addingTimeInterval(duration)
                    cases.append([
                        "tariff": index,
                        "start": start,
                        "end": to.timeIntervalSince1970,
                        "cost": tariff.cost(energyKWh: 17.3, from: from, to: to),
                        "rate": tariff.rate(at: from)
                    ])
                }
            }
        }
        let encoded = try tariffs.map { try JSONSerialization.jsonObject(with: JSONEncoder().encode($0)) }
        try write(["tariffs": encoded, "cases": cases, "energyKWh": 17.3], to: "tariff-costs.json")
    }

    // MARK: - Battery defaults and energy wording

    private func writeCatalogGolden() throws {
        let models = [
            "IONIQ 5", "Hyundai IONIQ 5", "2025 IONIQ 5", "IONIQ 5 N", "ioniq-5n", "IONIQ_6", "IONIQ 9", "Ioniq Electric",
            "EV9", "EV6 GT", "EV3", "EV4", "GV60", "GV70 Electrified", "G80 Electrified", "Kona Electric", "Niro EV",
            "Soul EV", "Niro PHEV", "TUCSON", "Santa Fe Plug-in Hybrid", "santa_fe", "Sorento PHEV", "Sportage PHEV",
            "Mystery EV", "Sonata", ""
        ]
        var capacities: [[String: Any]] = []
        for model in models {
            for fuel in [FuelType.gas, .electric, .phev] {
                capacities.append([
                    "model": model, "fuelType": fuel.rawValue,
                    "kWh": value(BatteryCapacityCatalog.defaultCapacityKWh(model: model, fuelType: fuel))
                ])
            }
        }
        var sources: [[String: Any]] = []
        for capacity in [nil, 0, 77.4, 84, 64.8, 110.3, 99.75, 38.25, 0.05, 0.25, 12.349] as [Double?] {
            for manual in [nil, 12.5] as [Double?] {
                for added in [0.0, 30.0] {
                    let session = ChargeSession(vin: "V", startDate: t(1_790_000_000), startPercentage: 40, isDCFast: false)
                    session.endPercentage = 40 + added
                    session.manualEnergyKWh = manual
                    session.energyAddedKWh = 31.7
                    for modelDefault in [false, true] {
                        sources.append([
                            "capacity": value(capacity), "manual": value(manual), "added": added,
                            "modelDefault": modelDefault,
                            "text": session.energySource(batteryCapacityKWh: capacity, capacityIsModelDefault: modelDefault),
                            "energy": session.resolvedEnergyKWh(batteryCapacityKWh: capacity)
                        ])
                    }
                }
            }
        }
        try write(["capacities": capacities, "energySources": sources], to: "battery-catalog.json")
    }
}
