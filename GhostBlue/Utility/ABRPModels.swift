//
//  ABRPModels.swift
//  GhostBlue
//
//  Models for the ABRP (A Better Route Planner / Iternio) integration:
//  Codable API response types plus the persisted active-trip domain model.
//

import CoreLocation
import Foundation

// MARK: - Errors

enum ABRPError: LocalizedError {
    case missingAPIKey
    case missingCarModel
    case missingVehicleLocation
    case apiError(String)
    case badResponse(status: Int?)
    case decoding(String)

    var errorDescription: String? {
        switch self {
        case .missingAPIKey:
            "No ABRP API key configured. Add your Iternio API key in Settings."
        case .missingCarModel:
            "No ABRP vehicle model selected. Choose one in the vehicle's settings."
        case .missingVehicleLocation:
            "The vehicle hasn't reported a location yet."
        case let .apiError(message):
            "ABRP error: \(message)"
        case let .badResponse(status):
            "ABRP request failed (HTTP \(status.map(String.init) ?? "no response"))."
        case let .decoding(message):
            "Couldn't read the ABRP response: \(message)"
        }
    }
}

// MARK: - API Response Models

// Field names follow the Iternio plan API with .convertFromSnakeCase decoding.
// Everything is optional (and numeric fields are Double) so undocumented
// response variations degrade gracefully instead of failing the whole decode.
// Raw responses are inspectable in Settings > HTTP Logs.

struct ABRPPlanResponse: Decodable {
    let status: String?
    let result: ABRPPlanResult?
}

struct ABRPPlanResult: Decodable {
    let routes: [ABRPRoute]?
    let planUuid: String?
}

struct ABRPRoute: Decodable {
    let steps: [ABRPStep]?
    let totalDriveDuration: Double?
    let totalDist: Double?
    let averageConsumption: Double?
    let polyline: String?
}

struct ABRPStep: Decodable {
    let name: String?
    let lat: Double?
    let lon: Double?
    let isCharger: Bool?
    let isEndStation: Bool?
    let isWaypoint: Bool?
    let arrivalPerc: Double?
    let departurePerc: Double?
    let chargeDuration: Double?
    let driveDuration: Double?
    let driveDist: Double?
    let waitDuration: Double?
    let charger: ABRPCharger?
    let path: String?
}

struct ABRPCharger: Decodable {
    let name: String?
    let networkName: String?
}

struct ABRPCarModel: Identifiable, Hashable {
    let typecode: String
    let displayName: String

    var id: String { typecode }
}

// MARK: - Active Trip (persisted as JSON in app-group UserDefaults)

struct ABRPTripStop: Codable, Equatable, Identifiable {
    var id: UUID = UUID()
    var name: String
    var latitude: Double
    var longitude: Double
    var isCharger: Bool
    var chargerNetwork: String?
    var arrivalSocPercent: Double?
    var departureSocPercent: Double?
    var chargeDurationSeconds: Int?
    /// Duration and distance of the leg leading to this stop.
    var driveDurationSeconds: Int?
    var driveDistanceMeters: Double?
    var reachedAt: Date?

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

struct ABRPTrip: Codable, Equatable {
    enum Phase: String, Codable {
        case enRoute, atCharger, completed
    }

    var id: UUID = UUID()
    var planUuid: String?
    var vin: String
    var carModel: String
    var createdAt: Date = Date()
    var destinationName: String
    var encodedPolyline: String?
    /// Charging stops plus the final destination, in driving order.
    var stops: [ABRPTripStop]
    var currentStopIndex: Int = 0
    var phase: Phase = .enRoute

    var currentStop: ABRPTripStop? {
        stops.indices.contains(currentStopIndex) ? stops[currentStopIndex] : nil
    }

    var isOnLastLeg: Bool {
        currentStopIndex >= stops.count - 1
    }

    var chargerStopCount: Int {
        stops.count(where: \.isCharger)
    }

    var totalChargeSeconds: Int {
        stops.compactMap(\.chargeDurationSeconds).reduce(0, +)
    }

    var totalDriveSeconds: Int {
        stops.compactMap(\.driveDurationSeconds).reduce(0, +)
    }

    var arrivalSocPercent: Double? {
        stops.last?.arrivalSocPercent
    }
}

extension ABRPTrip {
    /// Builds a trip from an ABRP route, keeping charging stops and the final
    /// destination. The first step (origin) is dropped; steps without
    /// coordinates are skipped.
    init?(route: ABRPRoute, vin: String, carModel: String,
          destinationName: String, planUuid: String?) {
        guard let steps = route.steps, steps.count >= 2 else { return nil }

        var stops: [ABRPTripStop] = []
        for (index, step) in steps.enumerated() {
            guard index > 0, let lat = step.lat, let lon = step.lon else { continue }
            let isCharger = step.isCharger ?? false
            let isDestination = index == steps.count - 1
            guard isCharger || isDestination else { continue }

            stops.append(ABRPTripStop(
                name: step.name ?? step.charger?.name ??
                    (isDestination ? destinationName : "Charger"),
                latitude: lat,
                longitude: lon,
                isCharger: isCharger && !isDestination,
                chargerNetwork: step.charger?.networkName,
                arrivalSocPercent: step.arrivalPerc,
                departureSocPercent: step.departurePerc,
                chargeDurationSeconds: step.chargeDuration.map { Int($0) },
                driveDurationSeconds: step.driveDuration.map { Int($0) },
                driveDistanceMeters: step.driveDist,
            ))
        }
        guard !stops.isEmpty else { return nil }

        self.init(
            planUuid: planUuid,
            vin: vin,
            carModel: carModel,
            destinationName: destinationName,
            encodedPolyline: route.polyline,
            stops: stops,
        )
    }
}
