//
//  BackupArchive.swift
//  GhostBlue
//
//  A portable snapshot of everything the app stores locally, and a
//  merge that folds one snapshot into another store without destroying
//  anything already there.
//
//  Two installs that share a bundle identifier and app group share a
//  single store, so this exists for the cases where they genuinely do
//  not: moving to a new phone, recovering from a failed migration, or
//  pulling simulator data onto a device.
//
//  HTTP logs are deliberately excluded. They are diagnostic chatter,
//  they dwarf everything else on disk, and nothing is lost by dropping
//  them.
//

import BetterBlueKit
import Foundation
import SwiftData

struct BackupArchive: Codable {
    static let currentVersion = 1

    var version: Int
    var createdAt: Date
    var sourceDevice: String

    var accounts: [AccountRow]
    var vehicles: [VehicleRow]
    var climatePresets: [ClimatePresetRow]
    var chargeSessions: [ChargeSessionRow]
    var trips: [TripRow]
    var remoteCommands: [RemoteCommandRow]
    var maintenanceReminders: [MaintenanceRow]
    var automations: [AutomationRow]

    // MARK: - Rows

    struct AccountRow: Codable {
        var id: UUID
        var username: String
        var password: String
        var refreshToken: String
        var pin: String
        var brand: String
        var region: String
        var dateCreated: Date
        var rememberMeToken: String?
        var serializedAuthToken: String?
        var deviceId: String?
        var lastVehiclesFetch: Date?
    }

    /// Carries the owning account's username and brand as well as its id:
    /// the same account can have a different UUID in each store, so the
    /// merge re-attaches by the pair that is actually stable.
    struct VehicleRow: Codable {
        var id: UUID
        var vin: String
        var regId: String
        var model: String
        var accountId: UUID
        var accountUsername: String?
        var accountBrand: String?
        var fuelTypeRaw: String
        var generation: Int
        var odometer: Distance
        var lastUpdated: Date?
        var syncDate: Date?
        var gasRange: VehicleStatus.FuelRange?
        var evStatus: VehicleStatus.EVStatus?
        var location: VehicleStatus.Location?
        var lockStatus: VehicleStatus.LockStatus?
        var climateStatus: VehicleStatus.ClimateStatus?
        var battery12V: Int?
        var doorOpen: VehicleStatus.DoorStatus?
        var trunkOpen: Bool?
        var hoodOpen: Bool?
        var tirePressureWarning: VehicleStatus.TirePressureWarning?

        // User-authored settings — the part worth merging.
        var customName: String?
        var isHidden: Bool
        var sortOrder: Int
        var backgroundColorName: String
        var watchBackgroundColorName: String
        var vehicleImageColorRaw: String
        var chargePortTypeRaw: String
        var batteryCapacityKWh: Double?
        var electricityRatePerKWh: Double?
        var offPeakElectricityRatePerKWh: Double?
        var dcFastChargingRatePerKWh: Double?
        var peakElectricityRatePerKWh: Double?
        var offPeakStartHour: Int
        var offPeakEndHour: Int
        var monthlyChargingBudget: Double?
        var homeLatitude: Double?
        var homeLongitude: Double?
        var homePrivacyRadiusMeters: Double
        var smartChargingEnabled: Bool
        var smartChargingHomeOnly: Bool
        var smartChargingTargetSOC: Int
        var notifyChargeComplete: Bool
        var notifyChargeInterrupted: Bool
        var notifyUnlocked: Bool
        var notifyOpenPanels: Bool
        var notifyLowEVBattery: Bool
        var notifyLow12VBattery: Bool
        var notifyTirePressure: Bool
        var lowEVBatteryThreshold: Int
        var low12VBatteryThreshold: Int
        var enableSeatHeatControls: Bool
        var showClimateDurationOverride: Bool?
        var fuelTypeOverrideRaw: String?
        var abrpUserToken: String?
        var abrpCarModel: String?
        var primaryColorName: String?
        var chargingColorName: String?
        var gasColorName: String?
        var lockColorName: String?
        var unlockColorName: String?
        var startClimateColorName: String?
        var stopColorName: String?
    }

    struct ClimatePresetRow: Codable {
        var id: UUID
        var vehicleVin: String?
        var name: String
        var iconName: String
        var climateOptions: ClimateOptions
        var isSelected: Bool
        var sortOrder: Int
    }

    struct ChargeSessionRow: Codable {
        var id: UUID
        var vin: String
        var startDate: Date
        var endDate: Date?
        var startPercentage: Double
        var endPercentage: Double
        var energyAddedKWh: Double
        var maxChargeSpeedKW: Double
        var isDCFast: Bool
        var lastSampleDate: Date
        var latitude: Double?
        var longitude: Double?
        var locationTypeRaw: String
        var locationName: String?
        var electricityRatePerKWh: Double?
        var actualCost: Double?
        var manualEnergyKWh: Double?
        var rateWasLocationAware: Bool
        var tariff: ChargingTariff?
        var speedSamples: [ChargeSpeedSample]
    }

    struct TripRow: Codable {
        var id: UUID
        var vin: String
        var tripKey: String
        var distance: Double
        var odometer: Double
        var accessoriesEnergy: Int
        var totalEnergyUsed: Int
        var regenEnergy: Int
        var climateEnergy: Int
        var drivetrainEnergy: Int
        var batteryCareEnergy: Int
        var startDate: Date
        var durationSeconds: Int
        var avgSpeed: Double
        var maxSpeed: Double
        var savedDate: Date
        var customName: String?
        var isFavorite: Bool
        var startLocationName: String?
        var endLocationName: String?
        var startLatitude: Double?
        var startLongitude: Double?
        var endLatitude: Double?
        var endLongitude: Double?
    }

    struct RemoteCommandRow: Codable {
        var id: UUID
        var vin: String
        var vehicleName: String
        var commandName: String
        var sourceName: String
        var stateRaw: String
        var startedAt: Date
        var completedAt: Date?
        var errorMessage: String?
    }

    struct MaintenanceRow: Codable {
        var id: UUID
        var vin: String
        var title: String
        var notes: String
        var dueDate: Date?
        var dueOdometer: Double?
        var cost: Double?
        var createdAt: Date
        var completedAt: Date?
        var lastNotifiedAt: Date?
    }

    struct AutomationRow: Codable {
        var id: UUID
        var vin: String
        var title: String
        var kindRaw: String
        var isEnabled: Bool
        var threshold: Int
        var hour: Int
        var minute: Int
        var lastTriggeredAt: Date?
        var createdAt: Date
    }
}

// MARK: - Export

extension BackupArchive {
    @MainActor
    static func export(from context: ModelContext, deviceName: String) -> BackupArchive {
        func all<T: PersistentModel>(_: T.Type) -> [T] {
            (try? context.fetch(FetchDescriptor<T>())) ?? []
        }

        let accounts = all(BBAccount.self).map { account in
            AccountRow(
                id: account.id,
                username: account.username,
                password: account.password,
                refreshToken: account.refreshToken,
                pin: account.pin,
                brand: account.brand,
                region: account.region,
                dateCreated: account.dateCreated,
                rememberMeToken: account.rememberMeToken,
                serializedAuthToken: account.serializedAuthToken,
                deviceId: account.deviceId,
                lastVehiclesFetch: account.lastVehiclesFetch
            )
        }

        let vehicles = all(BBVehicle.self).map { vehicle in
            VehicleRow(
                id: vehicle.id,
                vin: vehicle.vin,
                regId: vehicle.regId,
                model: vehicle.model,
                accountId: vehicle.accountId,
                accountUsername: vehicle.account?.username,
                accountBrand: vehicle.account?.brand,
                fuelTypeRaw: vehicle.fuelTypeRaw,
                generation: vehicle.generation,
                odometer: vehicle.odometer,
                lastUpdated: vehicle.lastUpdated,
                syncDate: vehicle.syncDate,
                gasRange: vehicle.gasRange,
                evStatus: vehicle.evStatus,
                location: vehicle.location,
                lockStatus: vehicle.lockStatus,
                climateStatus: vehicle.climateStatus,
                battery12V: vehicle.battery12V,
                doorOpen: vehicle.doorOpen,
                trunkOpen: vehicle.trunkOpen,
                hoodOpen: vehicle.hoodOpen,
                tirePressureWarning: vehicle.tirePressureWarning,
                customName: vehicle.customName,
                isHidden: vehicle.isHidden,
                sortOrder: vehicle.sortOrder,
                backgroundColorName: vehicle.backgroundColorName,
                watchBackgroundColorName: vehicle.watchBackgroundColorName,
                vehicleImageColorRaw: vehicle.vehicleImageColorRaw,
                chargePortTypeRaw: vehicle.chargePortTypeRaw,
                batteryCapacityKWh: vehicle.batteryCapacityKWh,
                electricityRatePerKWh: vehicle.electricityRatePerKWh,
                offPeakElectricityRatePerKWh: vehicle.offPeakElectricityRatePerKWh,
                dcFastChargingRatePerKWh: vehicle.dcFastChargingRatePerKWh,
                peakElectricityRatePerKWh: vehicle.peakElectricityRatePerKWh,
                offPeakStartHour: vehicle.offPeakStartHour,
                offPeakEndHour: vehicle.offPeakEndHour,
                monthlyChargingBudget: vehicle.monthlyChargingBudget,
                homeLatitude: vehicle.homeLatitude,
                homeLongitude: vehicle.homeLongitude,
                homePrivacyRadiusMeters: vehicle.homePrivacyRadiusMeters,
                smartChargingEnabled: vehicle.smartChargingEnabled,
                smartChargingHomeOnly: vehicle.smartChargingHomeOnly,
                smartChargingTargetSOC: vehicle.smartChargingTargetSOC,
                notifyChargeComplete: vehicle.notifyChargeComplete,
                notifyChargeInterrupted: vehicle.notifyChargeInterrupted,
                notifyUnlocked: vehicle.notifyUnlocked,
                notifyOpenPanels: vehicle.notifyOpenPanels,
                notifyLowEVBattery: vehicle.notifyLowEVBattery,
                notifyLow12VBattery: vehicle.notifyLow12VBattery,
                notifyTirePressure: vehicle.notifyTirePressure,
                lowEVBatteryThreshold: vehicle.lowEVBatteryThreshold,
                low12VBatteryThreshold: vehicle.low12VBatteryThreshold,
                enableSeatHeatControls: vehicle.enableSeatHeatControls,
                showClimateDurationOverride: vehicle.showClimateDurationOverride,
                fuelTypeOverrideRaw: vehicle.fuelTypeOverrideRaw,
                abrpUserToken: vehicle.abrpUserToken,
                abrpCarModel: vehicle.abrpCarModel,
                primaryColorName: vehicle.primaryColorName,
                chargingColorName: vehicle.chargingColorName,
                gasColorName: vehicle.gasColorName,
                lockColorName: vehicle.lockColorName,
                unlockColorName: vehicle.unlockColorName,
                startClimateColorName: vehicle.startClimateColorName,
                stopColorName: vehicle.stopColorName
            )
        }

        let presets = all(ClimatePreset.self).map { preset in
            ClimatePresetRow(
                id: preset.id,
                vehicleVin: preset.vehicle?.vin,
                name: preset.name,
                iconName: preset.iconName,
                climateOptions: preset.climateOptions,
                isSelected: preset.isSelected,
                sortOrder: preset.sortOrder
            )
        }

        let sessions = all(ChargeSession.self).map { session in
            ChargeSessionRow(
                id: session.id,
                vin: session.vin,
                startDate: session.startDate,
                endDate: session.endDate,
                startPercentage: session.startPercentage,
                endPercentage: session.endPercentage,
                energyAddedKWh: session.energyAddedKWh,
                maxChargeSpeedKW: session.maxChargeSpeedKW,
                isDCFast: session.isDCFast,
                lastSampleDate: session.lastSampleDate,
                latitude: session.latitude,
                longitude: session.longitude,
                locationTypeRaw: session.locationTypeRaw,
                locationName: session.locationName,
                electricityRatePerKWh: session.electricityRatePerKWh,
                actualCost: session.actualCost,
                manualEnergyKWh: session.manualEnergyKWh,
                rateWasLocationAware: session.rateWasLocationAware,
                tariff: session.tariff,
                speedSamples: session.speedSamples
            )
        }

        let trips = all(TripRecord.self).map { trip in
            TripRow(
                id: trip.id,
                vin: trip.vin,
                tripKey: trip.tripKey,
                distance: trip.distance,
                odometer: trip.odometer,
                accessoriesEnergy: trip.accessoriesEnergy,
                totalEnergyUsed: trip.totalEnergyUsed,
                regenEnergy: trip.regenEnergy,
                climateEnergy: trip.climateEnergy,
                drivetrainEnergy: trip.drivetrainEnergy,
                batteryCareEnergy: trip.batteryCareEnergy,
                startDate: trip.startDate,
                durationSeconds: trip.durationSeconds,
                avgSpeed: trip.avgSpeed,
                maxSpeed: trip.maxSpeed,
                savedDate: trip.savedDate,
                customName: trip.customName,
                isFavorite: trip.isFavorite,
                startLocationName: trip.startLocationName,
                endLocationName: trip.endLocationName,
                startLatitude: trip.startLatitude,
                startLongitude: trip.startLongitude,
                endLatitude: trip.endLatitude,
                endLongitude: trip.endLongitude
            )
        }

        let commands = all(RemoteCommandActivity.self).map { activity in
            RemoteCommandRow(
                id: activity.id,
                vin: activity.vin,
                vehicleName: activity.vehicleName,
                commandName: activity.commandName,
                sourceName: activity.sourceName,
                stateRaw: activity.stateRaw,
                startedAt: activity.startedAt,
                completedAt: activity.completedAt,
                errorMessage: activity.errorMessage
            )
        }

        let reminders = all(MaintenanceReminder.self).map { reminder in
            MaintenanceRow(
                id: reminder.id,
                vin: reminder.vin,
                title: reminder.title,
                notes: reminder.notes,
                dueDate: reminder.dueDate,
                dueOdometer: reminder.dueOdometer,
                cost: reminder.cost,
                createdAt: reminder.createdAt,
                completedAt: reminder.completedAt,
                lastNotifiedAt: reminder.lastNotifiedAt
            )
        }

        let automations = all(VehicleAutomation.self).map { automation in
            AutomationRow(
                id: automation.id,
                vin: automation.vin,
                title: automation.title,
                kindRaw: automation.kindRaw,
                isEnabled: automation.isEnabled,
                threshold: automation.threshold,
                hour: automation.hour,
                minute: automation.minute,
                lastTriggeredAt: automation.lastTriggeredAt,
                createdAt: automation.createdAt
            )
        }

        return BackupArchive(
            version: currentVersion,
            createdAt: Date(),
            sourceDevice: deviceName,
            accounts: accounts,
            vehicles: vehicles,
            climatePresets: presets,
            chargeSessions: sessions,
            trips: trips,
            remoteCommands: commands,
            maintenanceReminders: reminders,
            automations: automations
        )
    }

    func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(self)
    }

    static func decoded(from data: Data) throws -> BackupArchive {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(BackupArchive.self, from: data)
    }
}

// MARK: - Merge

/// What a merge did, per model. Nothing is ever deleted and no existing
/// row is rewritten, so `skipped` always means "this already existed
/// here" rather than "this was dropped".
struct BackupMergeSummary {
    var accountsAdded = 0
    var accountsSkipped = 0
    var vehiclesAdded = 0
    var vehiclesUpdated = 0
    var vehiclesSkipped = 0
    var presetsAdded = 0
    var presetsSkipped = 0
    var chargeSessionsAdded = 0
    var chargeSessionsSkipped = 0
    var tripsAdded = 0
    var tripsSkipped = 0
    var remoteCommandsAdded = 0
    var remoteCommandsSkipped = 0
    var remindersAdded = 0
    var remindersSkipped = 0
    var automationsAdded = 0
    var automationsSkipped = 0
    var orphanedVehicles: [String] = []

    var totalAdded: Int {
        accountsAdded + vehiclesAdded + presetsAdded + chargeSessionsAdded
            + tripsAdded + remoteCommandsAdded + remindersAdded + automationsAdded
    }

    var rows: [(String, String)] {
        [
            ("Accounts", "\(accountsAdded) added, \(accountsSkipped) already here"),
            ("Vehicles", "\(vehiclesAdded) added, \(vehiclesUpdated) filled in, \(vehiclesSkipped) unchanged"),
            ("Climate Presets", "\(presetsAdded) added, \(presetsSkipped) already here"),
            ("Charge Sessions", "\(chargeSessionsAdded) added, \(chargeSessionsSkipped) already here"),
            ("Trips", "\(tripsAdded) added, \(tripsSkipped) already here"),
            ("Remote Commands", "\(remoteCommandsAdded) added, \(remoteCommandsSkipped) already here"),
            ("Maintenance", "\(remindersAdded) added, \(remindersSkipped) already here"),
            ("Automations", "\(automationsAdded) added, \(automationsSkipped) already here")
        ]
    }
}

extension BackupArchive {
    private static func stamp(_ date: Date) -> Int { Int(date.timeIntervalSince1970.rounded()) }

    /// Folds this archive into `context`. Add-only: a row whose natural key
    /// already exists is left exactly as it is, so importing the same file
    /// twice is a no-op and importing in either direction converges on the
    /// union of both stores.
    @MainActor
    static func merge(_ archive: BackupArchive, into context: ModelContext) throws -> BackupMergeSummary {
        var summary = BackupMergeSummary()

        func all<T: PersistentModel>(_: T.Type) -> [T] {
            (try? context.fetch(FetchDescriptor<T>())) ?? []
        }

        // Accounts — keyed by username + brand, which survives differing UUIDs.
        func accountKey(_ username: String, _ brand: String) -> String {
            "\(username.lowercased())|\(brand.lowercased())"
        }
        var accountsByKey: [String: BBAccount] = [:]
        for account in all(BBAccount.self) {
            accountsByKey[accountKey(account.username, account.brand)] = account
        }

        for row in archive.accounts {
            let key = accountKey(row.username, row.brand)
            if accountsByKey[key] != nil {
                summary.accountsSkipped += 1
                continue
            }
            guard let brand = Brand(rawValue: row.brand), let region = Region(rawValue: row.region) else {
                summary.accountsSkipped += 1
                continue
            }
            let account = BBAccount(
                username: row.username,
                password: row.password,
                pin: row.pin,
                brand: brand,
                region: region,
                refreshToken: row.refreshToken
            )
            account.dateCreated = row.dateCreated
            account.rememberMeToken = row.rememberMeToken
            account.serializedAuthToken = row.serializedAuthToken
            account.deviceId = row.deviceId
            account.lastVehiclesFetch = row.lastVehiclesFetch
            context.insert(account)
            accountsByKey[key] = account
            summary.accountsAdded += 1
        }

        // Vehicles — keyed by VIN.
        var vehiclesByVIN: [String: BBVehicle] = [:]
        for vehicle in all(BBVehicle.self) {
            vehiclesByVIN[vehicle.vin] = vehicle
        }

        for row in archive.vehicles {
            if let existing = vehiclesByVIN[row.vin] {
                if fillBlanks(on: existing, from: row) {
                    summary.vehiclesUpdated += 1
                } else {
                    summary.vehiclesSkipped += 1
                }
                continue
            }

            let owner: BBAccount?
            if let username = row.accountUsername, let brand = row.accountBrand {
                owner = accountsByKey[accountKey(username, brand)]
            } else {
                owner = all(BBAccount.self).first { $0.id == row.accountId }
            }
            guard let owner else {
                summary.orphanedVehicles.append(row.vin)
                continue
            }

            let seed = Vehicle(
                vin: row.vin,
                regId: row.regId,
                model: row.model,
                accountId: owner.id,
                fuelType: FuelType(rawValue: row.fuelTypeRaw) ?? .gas,
                generation: row.generation,
                odometer: row.odometer
            )
            let vehicle = BBVehicle(from: seed)
            apply(row, to: vehicle)
            vehicle.account = owner
            context.insert(vehicle)
            vehiclesByVIN[row.vin] = vehicle
            summary.vehiclesAdded += 1
        }

        // Climate presets — keyed by owning VIN + name.
        var presetKeys = Set<String>()
        for preset in all(ClimatePreset.self) {
            presetKeys.insert("\(preset.vehicle?.vin ?? "")|\(preset.name.lowercased())")
        }
        for row in archive.climatePresets {
            let key = "\(row.vehicleVin ?? "")|\(row.name.lowercased())"
            guard !presetKeys.contains(key) else {
                summary.presetsSkipped += 1
                continue
            }
            guard let vin = row.vehicleVin, let vehicle = vehiclesByVIN[vin] else {
                summary.presetsSkipped += 1
                continue
            }
            let preset = ClimatePreset(
                name: row.name,
                iconName: row.iconName,
                climateOptions: row.climateOptions,
                isSelected: row.isSelected,
                vehicle: vehicle
            )
            preset.sortOrder = row.sortOrder
            context.insert(preset)
            presetKeys.insert(key)
            summary.presetsAdded += 1
        }

        // Charge sessions — keyed by VIN + start instant.
        var sessionKeys = Set<String>()
        for session in all(ChargeSession.self) {
            sessionKeys.insert("\(session.vin)|\(stamp(session.startDate))")
        }
        for row in archive.chargeSessions {
            let key = "\(row.vin)|\(stamp(row.startDate))"
            guard !sessionKeys.contains(key) else {
                summary.chargeSessionsSkipped += 1
                continue
            }
            let session = ChargeSession(
                vin: row.vin,
                startDate: row.startDate,
                startPercentage: row.startPercentage,
                isDCFast: row.isDCFast,
                latitude: row.latitude,
                longitude: row.longitude
            )
            session.id = row.id
            session.endDate = row.endDate
            session.endPercentage = row.endPercentage
            session.energyAddedKWh = row.energyAddedKWh
            session.maxChargeSpeedKW = row.maxChargeSpeedKW
            session.lastSampleDate = row.lastSampleDate
            session.locationTypeRaw = row.locationTypeRaw
            session.locationName = row.locationName
            session.electricityRatePerKWh = row.electricityRatePerKWh
            session.actualCost = row.actualCost
            session.manualEnergyKWh = row.manualEnergyKWh
            session.rateWasLocationAware = row.rateWasLocationAware
            session.tariff = row.tariff
            session.speedSamples = row.speedSamples
            context.insert(session)
            sessionKeys.insert(key)
            summary.chargeSessionsAdded += 1
        }

        // Trips — keyed by the tripKey the app already derives.
        var tripKeys = Set<String>()
        for trip in all(TripRecord.self) {
            tripKeys.insert("\(trip.vin)|\(trip.tripKey)")
        }
        for row in archive.trips {
            let key = "\(row.vin)|\(row.tripKey)"
            guard !tripKeys.contains(key) else {
                summary.tripsSkipped += 1
                continue
            }
            let detail = EVTripDetail(
                distance: row.distance,
                odometer: row.odometer,
                accessoriesEnergy: row.accessoriesEnergy,
                totalEnergyUsed: row.totalEnergyUsed,
                regenEnergy: row.regenEnergy,
                climateEnergy: row.climateEnergy,
                drivetrainEnergy: row.drivetrainEnergy,
                batteryCareEnergy: row.batteryCareEnergy,
                startDate: row.startDate,
                durationSeconds: row.durationSeconds,
                avgSpeed: row.avgSpeed,
                maxSpeed: row.maxSpeed
            )
            let trip = TripRecord(vin: row.vin, trip: detail)
            trip.id = row.id
            trip.tripKey = row.tripKey
            trip.savedDate = row.savedDate
            trip.customName = row.customName
            trip.isFavorite = row.isFavorite
            trip.startLocationName = row.startLocationName
            trip.endLocationName = row.endLocationName
            trip.startLatitude = row.startLatitude
            trip.startLongitude = row.startLongitude
            trip.endLatitude = row.endLatitude
            trip.endLongitude = row.endLongitude
            context.insert(trip)
            tripKeys.insert(key)
            summary.tripsAdded += 1
        }

        // Remote command history.
        var commandKeys = Set<String>()
        for activity in all(RemoteCommandActivity.self) {
            commandKeys.insert("\(activity.vin)|\(activity.commandName)|\(stamp(activity.startedAt))")
        }
        for row in archive.remoteCommands {
            let key = "\(row.vin)|\(row.commandName)|\(stamp(row.startedAt))"
            guard !commandKeys.contains(key) else {
                summary.remoteCommandsSkipped += 1
                continue
            }
            let activity = RemoteCommandActivity(
                vin: row.vin,
                vehicleName: row.vehicleName,
                commandName: row.commandName,
                sourceName: row.sourceName
            )
            activity.id = row.id
            activity.stateRaw = row.stateRaw
            activity.startedAt = row.startedAt
            activity.completedAt = row.completedAt
            activity.errorMessage = row.errorMessage
            context.insert(activity)
            commandKeys.insert(key)
            summary.remoteCommandsAdded += 1
        }

        // Maintenance reminders.
        var reminderKeys = Set<String>()
        for reminder in all(MaintenanceReminder.self) {
            reminderKeys.insert("\(reminder.vin)|\(reminder.title.lowercased())|\(stamp(reminder.createdAt))")
        }
        for row in archive.maintenanceReminders {
            let key = "\(row.vin)|\(row.title.lowercased())|\(stamp(row.createdAt))"
            guard !reminderKeys.contains(key) else {
                summary.remindersSkipped += 1
                continue
            }
            let reminder = MaintenanceReminder(
                vin: row.vin,
                title: row.title,
                notes: row.notes,
                dueDate: row.dueDate,
                dueOdometer: row.dueOdometer,
                cost: row.cost
            )
            reminder.id = row.id
            reminder.createdAt = row.createdAt
            reminder.completedAt = row.completedAt
            reminder.lastNotifiedAt = row.lastNotifiedAt
            context.insert(reminder)
            reminderKeys.insert(key)
            summary.remindersAdded += 1
        }

        // Automations.
        var automationKeys = Set<String>()
        for automation in all(VehicleAutomation.self) {
            automationKeys.insert("\(automation.vin)|\(automation.kindRaw)|\(automation.title.lowercased())")
        }
        for row in archive.automations {
            let key = "\(row.vin)|\(row.kindRaw)|\(row.title.lowercased())"
            guard !automationKeys.contains(key) else {
                summary.automationsSkipped += 1
                continue
            }
            guard let kind = VehicleAutomationKind(rawValue: row.kindRaw) else {
                summary.automationsSkipped += 1
                continue
            }
            let automation = VehicleAutomation(
                vin: row.vin,
                title: row.title,
                kind: kind,
                threshold: row.threshold,
                hour: row.hour,
                minute: row.minute
            )
            automation.id = row.id
            automation.isEnabled = row.isEnabled
            automation.lastTriggeredAt = row.lastTriggeredAt
            automation.createdAt = row.createdAt
            context.insert(automation)
            automationKeys.insert(key)
            summary.automationsAdded += 1
        }

        try context.save()
        return summary
    }

    /// Copies every field onto a vehicle that did not exist here before.
    @MainActor
    private static func apply(_ row: VehicleRow, to vehicle: BBVehicle) {
        vehicle.id = row.id
        vehicle.lastUpdated = row.lastUpdated
        vehicle.syncDate = row.syncDate
        vehicle.gasRange = row.gasRange
        vehicle.evStatus = row.evStatus
        vehicle.location = row.location
        vehicle.lockStatus = row.lockStatus
        vehicle.climateStatus = row.climateStatus
        vehicle.battery12V = row.battery12V
        vehicle.doorOpen = row.doorOpen
        vehicle.trunkOpen = row.trunkOpen
        vehicle.hoodOpen = row.hoodOpen
        vehicle.tirePressureWarning = row.tirePressureWarning
        vehicle.customName = row.customName
        vehicle.isHidden = row.isHidden
        vehicle.sortOrder = row.sortOrder
        vehicle.backgroundColorName = row.backgroundColorName
        vehicle.watchBackgroundColorName = row.watchBackgroundColorName
        vehicle.vehicleImageColorRaw = row.vehicleImageColorRaw
        vehicle.chargePortTypeRaw = row.chargePortTypeRaw
        vehicle.batteryCapacityKWh = row.batteryCapacityKWh
        vehicle.electricityRatePerKWh = row.electricityRatePerKWh
        vehicle.offPeakElectricityRatePerKWh = row.offPeakElectricityRatePerKWh
        vehicle.dcFastChargingRatePerKWh = row.dcFastChargingRatePerKWh
        vehicle.peakElectricityRatePerKWh = row.peakElectricityRatePerKWh
        vehicle.offPeakStartHour = row.offPeakStartHour
        vehicle.offPeakEndHour = row.offPeakEndHour
        vehicle.monthlyChargingBudget = row.monthlyChargingBudget
        vehicle.homeLatitude = row.homeLatitude
        vehicle.homeLongitude = row.homeLongitude
        vehicle.homePrivacyRadiusMeters = row.homePrivacyRadiusMeters
        vehicle.smartChargingEnabled = row.smartChargingEnabled
        vehicle.smartChargingHomeOnly = row.smartChargingHomeOnly
        vehicle.smartChargingTargetSOC = row.smartChargingTargetSOC
        vehicle.notifyChargeComplete = row.notifyChargeComplete
        vehicle.notifyChargeInterrupted = row.notifyChargeInterrupted
        vehicle.notifyUnlocked = row.notifyUnlocked
        vehicle.notifyOpenPanels = row.notifyOpenPanels
        vehicle.notifyLowEVBattery = row.notifyLowEVBattery
        vehicle.notifyLow12VBattery = row.notifyLow12VBattery
        vehicle.notifyTirePressure = row.notifyTirePressure
        vehicle.lowEVBatteryThreshold = row.lowEVBatteryThreshold
        vehicle.low12VBatteryThreshold = row.low12VBatteryThreshold
        vehicle.enableSeatHeatControls = row.enableSeatHeatControls
        vehicle.showClimateDurationOverride = row.showClimateDurationOverride
        vehicle.fuelTypeOverrideRaw = row.fuelTypeOverrideRaw
        vehicle.abrpUserToken = row.abrpUserToken
        vehicle.abrpCarModel = row.abrpCarModel
        vehicle.primaryColorName = row.primaryColorName
        vehicle.chargingColorName = row.chargingColorName
        vehicle.gasColorName = row.gasColorName
        vehicle.lockColorName = row.lockColorName
        vehicle.unlockColorName = row.unlockColorName
        vehicle.startClimateColorName = row.startClimateColorName
        vehicle.stopColorName = row.stopColorName
    }

    /// For a vehicle that already exists here, copies only the user-authored
    /// settings this store has no value for. Live status is left alone — the
    /// local copy is the fresher one — and nothing already set is replaced.
    /// Returns whether anything changed.
    @MainActor
    private static func fillBlanks(on vehicle: BBVehicle, from row: VehicleRow) -> Bool {
        var changed = false

        func fill<T>(_ keyPath: ReferenceWritableKeyPath<BBVehicle, T?>, _ value: T?) {
            guard vehicle[keyPath: keyPath] == nil, let value else { return }
            vehicle[keyPath: keyPath] = value
            changed = true
        }

        fill(\.customName, row.customName)
        fill(\.batteryCapacityKWh, row.batteryCapacityKWh)
        fill(\.electricityRatePerKWh, row.electricityRatePerKWh)
        fill(\.offPeakElectricityRatePerKWh, row.offPeakElectricityRatePerKWh)
        fill(\.dcFastChargingRatePerKWh, row.dcFastChargingRatePerKWh)
        fill(\.peakElectricityRatePerKWh, row.peakElectricityRatePerKWh)
        fill(\.monthlyChargingBudget, row.monthlyChargingBudget)
        fill(\.homeLatitude, row.homeLatitude)
        fill(\.homeLongitude, row.homeLongitude)
        fill(\.abrpUserToken, row.abrpUserToken)
        fill(\.abrpCarModel, row.abrpCarModel)
        fill(\.fuelTypeOverrideRaw, row.fuelTypeOverrideRaw)
        fill(\.showClimateDurationOverride, row.showClimateDurationOverride)
        fill(\.primaryColorName, row.primaryColorName)
        fill(\.chargingColorName, row.chargingColorName)
        fill(\.gasColorName, row.gasColorName)
        fill(\.lockColorName, row.lockColorName)
        fill(\.unlockColorName, row.unlockColorName)
        fill(\.startClimateColorName, row.startClimateColorName)
        fill(\.stopColorName, row.stopColorName)

        return changed
    }
}
