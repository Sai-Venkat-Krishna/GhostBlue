//
//  VehicleWidgetIntent.swift
//  GhostBlueWidget
//
//  Created by Mark Schmidt on 8/29/25.
//

import AppIntents
import BetterBlueKit
import SwiftData
import SwiftUI
import WidgetKit

// MARK: - Accent colour picker (from widget design spec)

enum WidgetAccentOption: String, AppEnum, CaseIterable {
    case mint   = "mint"
    case violet = "violet"
    case amber  = "amber"
    case coral  = "coral"

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Accent Color"
    static var caseDisplayRepresentations: [WidgetAccentOption: DisplayRepresentation] = [
        .mint:   "Mint",
        .violet: "Violet",
        .amber:  "Amber",
        .coral:  "Coral",
    ]

    var color: Color {
        switch self {
        case .mint:   return Color(red: 0.204, green: 0.898, blue: 0.769) // #34e5c4
        case .violet: return Color(red: 0.478, green: 0.498, blue: 1.000) // #7a7fff
        case .amber:  return Color(red: 1.000, green: 0.702, blue: 0.278) // #ffb347
        case .coral:  return Color(red: 1.000, green: 0.420, blue: 0.420) // #ff6b6b
        }
    }
}

// MARK: - Widget configuration intent

struct VehicleWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource =
        "Vehicle Widget Configuration"
    static var description = IntentDescription("Choose a vehicle and accent color. Vehicle image color is managed in GhostBlue.")

    @Parameter(
        title: "Vehicle",
        description: "Select which vehicle this widget controls",
    )
    var vehicle: VehicleEntity?

    @Parameter(
        title: "Accent Color",
        description: "Accent color used for the wordmark, battery and buttons",
        default: .mint
    )
    var accentColor: WidgetAccentOption

    init(
        vehicle: VehicleEntity? = nil,
        accentColor: WidgetAccentOption = .mint
    ) {
        self.vehicle = vehicle
        self.accentColor = accentColor
    }

    init() {}
}

struct VehicleEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation =
        "Vehicle"
    static var defaultQuery = VehicleQuery()

    var id: UUID
    var displayName: String
    var vin: String
    var fuelType: FuelType
    var rangeText: String
    var batteryPercentage: Double?
    var backgroundColorName: String
    var timestamp: Date
    var presets: [ClimatePresetEntity] = []
    var odometer: String = ""
    var model: String = ""
    var cachedImagePath: String? = nil

    // Charging state
    var isCharging: Bool = false
    var chargingState: ChargingDisplayState = .unknown
    var isPluggedIn: Bool = false
    var chargeRemainingSeconds: Int64 = 0   // 0 = unknown
    var chargeSpeedKW: Double = 0           // 0 = unknown
    var targetSOC: Double? = nil
    var plugTypeLabel: String? = nil        // "AC" | "DC" | nil

    // Lock state (nil = unknown)
    var isLocked: Bool? = nil
    /// Current HVAC state, used to expose the one relevant climate action.
    var isClimateOn: Bool = false

    var selectedPreset: ClimatePresetEntity? {
        presets.first(where: \.isSelected)
    }

    /// True when the vehicle is physically plugged in but the charge cycle is finished.
    var isChargeComplete: Bool {
        isPluggedIn && !isCharging && targetSOC.map { (batteryPercentage ?? 0) >= $0 - 1 } == true
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(displayName)")
    }

    // Get the gradient colors for the selected background
    var backgroundGradient: [Color] {
        guard let background = BBVehicle.availableBackgrounds.first(where: {
            $0.name == backgroundColorName
        }) else {
            return BBVehicle.availableBackgrounds[0].gradient
        }
        return background.gradient
    }

    /// Normalised image asset key, e.g. "2025 IONIQ 5" → "ioniq5"
    var carImageKey: String {
        let stripped = model.replacingOccurrences(
            of: #"^\d{4}\s+"#, with: "", options: .regularExpression
        )
        return stripped
            .lowercased()
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")
    }

    init(
        id: UUID,
        displayName: String,
        vin: String,
        fuelType: FuelType,
        rangeText: String,
        batteryPercentage: Double?,
        timestamp: Date,
        backgroundColorName: String = "default",
        presets: [ClimatePresetEntity] = [],
        odometer: String = "",
        model: String = "",
        cachedImagePath: String? = nil
    ) {
        self.id = id
        self.displayName = displayName
        self.vin = vin
        self.fuelType = fuelType
        self.rangeText = rangeText
        self.batteryPercentage = batteryPercentage
        self.timestamp = timestamp
        self.backgroundColorName = backgroundColorName
        self.presets = presets
        self.odometer = odometer
        self.model = model
        self.cachedImagePath = cachedImagePath
    }

    init(from bbVehicle: BBVehicle, with unit: Distance.Units, allPresets: [ClimatePresetEntity]) {
        id = bbVehicle.id
        displayName = bbVehicle.displayName
        vin = bbVehicle.vin
        fuelType = bbVehicle.fuelType
        backgroundColorName = bbVehicle.backgroundColorName
        // A vehicle that has never been refreshed has no sample date;
        // .distantPast would render as "2025 years ago".
        timestamp = bbVehicle.vehicleSampleDate ?? Date()
        chargingState = bbVehicle.chargingDisplayState()
        model = bbVehicle.model

        // Format odometer
        let odomLen = bbVehicle.odometer.length
        odometer = odomLen > 0 ? bbVehicle.odometer.units.format(odomLen, to: unit) : ""

        // Use safe property accessors to prevent context detachment
        if bbVehicle.fuelType.hasElectricCapability {
            if bbVehicle.modelContext != nil, let evStatus = bbVehicle.evStatus {
                batteryPercentage = evStatus.evRange.percentage
                let range = evStatus.evRange.range.length > 0 ?
                    evStatus.evRange.range.units.format(
                        evStatus.evRange.range.length,
                        to: unit,
                    ) : "--"
                rangeText = range
            } else {
                batteryPercentage = nil
                rangeText = "No EV data"
            }
        } else {
            if bbVehicle.modelContext != nil, let gasRange = bbVehicle.gasRange {
                batteryPercentage = gasRange.percentage
                let range = gasRange.range.length > 0 ?
                    gasRange.range.units.format(
                        gasRange.range.length,
                        to: unit,
                    ) : "--"
                rangeText = range
            } else {
                batteryPercentage = nil
                rangeText = "No fuel data"
            }
        }
        self.presets = allPresets.filter { preset in preset.vehicleVin == vin}

        // Charging state (EV / PHEV only)
        if bbVehicle.fuelType.hasElectricCapability, let ev = bbVehicle.evStatus {
            isCharging = ev.charging
            isPluggedIn = ev.pluggedIn
            chargeRemainingSeconds = ev.chargeTime.components.seconds
            chargeSpeedKW = ev.chargeSpeed
            targetSOC = ev.currentTargetSOC
            plugTypeLabel = ev.plugType == .acCharger ? "AC" :
                            ev.plugType == .dcCharger ? "DC" : nil
        }

        // Lock state
        if bbVehicle.modelContext != nil {
            switch bbVehicle.lockStatus {
            case .locked: isLocked = true
            case .unlocked: isLocked = false
            default: isLocked = nil
            }
            isClimateOn = bbVehicle.climateStatus?.airControlOn ?? false
        }
    }
}

struct VehicleQuery: EntityQuery {
    func entities(
        for identifiers: [UUID],
    ) async throws -> [VehicleEntity] {
        let presets = try await ClimatePresetEntity.defaultQuery.suggestedEntities()
        return try await MainActor.run {
            let modelContainer = try createSharedModelContainer(enableCloudKit: false)
            let context = ModelContext(modelContainer)

            let vehicles = try context.fetch(FetchDescriptor<BBVehicle>())
            let settings = AppSettings.shared

            return vehicles
                .filter { identifiers.contains($0.id) }
                .map { VehicleEntity(from: $0, with: settings.preferredDistanceUnit, allPresets: presets) }
        }
    }

    func suggestedEntities() async throws -> [VehicleEntity] {
        let presets = try await ClimatePresetEntity.defaultQuery.suggestedEntities()
        return try await MainActor.run {
            let modelContainer = try createSharedModelContainer(enableCloudKit: false)
            let context = ModelContext(modelContainer)

            let descriptor = FetchDescriptor<BBVehicle>(
                predicate: #Predicate { !$0.isHidden },
                sortBy: [SortDescriptor(\.sortOrder)],
            )

            let vehicles = try context.fetch(descriptor)
            let settings = AppSettings.shared

            return vehicles.map { VehicleEntity(from: $0, with: settings.preferredDistanceUnit, allPresets: presets) }
        }
    }
}
