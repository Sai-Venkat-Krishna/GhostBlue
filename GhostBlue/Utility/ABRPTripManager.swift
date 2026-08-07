//
//  ABRPTripManager.swift
//  GhostBlue
//
//  State machine for the active ABRP trip. Navigation is handed off to
//  Apple Maps leg-by-leg (there is no multi-stop directions API), so this
//  tracks which stop is next: enRoute(stop N) → atCharger(stop N) →
//  enRoute(stop N+1) → ... → completed. The trip is persisted as JSON in the
//  app-group UserDefaults so it survives app restarts mid-trip.
//

import BetterBlueKit
import CoreLocation
import Foundation
import SwiftData

@MainActor @Observable
final class ABRPTripManager {
    static let shared = ABRPTripManager()

    private static let storageKey = "ABRPActiveTrip"
    /// Auto-arrival: vehicle within this range of the current stop while charging.
    private static let arrivalRadiusMeters: CLLocationDistance = 500

    private let defaults = UserDefaults(suiteName: "group.com.saivenkatkrishna.ghostblue.shared")!

    @ObservationIgnored private var modelContainer: ModelContainer?
    @ObservationIgnored private var observer: NSObjectProtocol?

    private(set) var activeTrip: ABRPTrip?

    private init() {
        if let data = defaults.data(forKey: Self.storageKey) {
            activeTrip = try? JSONDecoder().decode(ABRPTrip.self, from: data)
        }
    }

    /// Hooks up status-update observation for arrival auto-detection.
    func start(container: ModelContainer) {
        modelContainer = container
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(
            forName: .bbVehicleStatusUpdated, object: nil, queue: .main,
        ) { notification in
            guard let vin = notification.object as? String else { return }
            Task { @MainActor in
                ABRPTripManager.shared.handleStatusUpdate(vin: vin)
            }
        }
    }

    func trip(for vin: String) -> ABRPTrip? {
        activeTrip?.vin == vin ? activeTrip : nil
    }

    // MARK: - State Transitions

    func startTrip(_ trip: ABRPTrip) {
        activeTrip = trip
        persist()
        BBLogger.info(.app, "ABRP: Started trip to \(trip.destinationName) " +
            "with \(trip.chargerStopCount) charging stops")
        navigateToCurrentStop()
    }

    /// Opens Apple Maps turn-by-turn directions for the current leg.
    func navigateToCurrentStop() {
        guard let stop = activeTrip?.currentStop else { return }
        NavigationHelper.openAppleMapsDirections(to: stop.coordinate, destinationName: stop.name)
    }

    /// enRoute → atCharger, or completed when this was the final destination.
    func markArrivedAtCurrentStop() {
        guard var trip = activeTrip, trip.phase == .enRoute,
              trip.stops.indices.contains(trip.currentStopIndex) else { return }
        trip.stops[trip.currentStopIndex].reachedAt = Date()
        trip.phase = trip.isOnLastLeg ? .completed : .atCharger
        activeTrip = trip
        persist()
        BBLogger.info(.app, "ABRP: Arrived at \(trip.currentStop?.name ?? "stop") " +
            "(\(trip.phase.rawValue))")
    }

    /// atCharger → enRoute for the next stop, with immediate Apple Maps handoff.
    func advanceToNextLeg() {
        guard var trip = activeTrip, trip.phase == .atCharger, !trip.isOnLastLeg else { return }
        trip.currentStopIndex += 1
        trip.phase = .enRoute
        activeTrip = trip
        persist()
        navigateToCurrentStop()
    }

    func endTrip() {
        activeTrip = nil
        persist()
    }

    /// Re-requests the plan from the vehicle's current position and SoC to the
    /// original final destination, replacing the remaining stops.
    func replanFromCurrentState(vehicle: BBVehicle) async throws {
        guard let trip = activeTrip else { return }
        guard let origin = vehicle.coordinate else { throw ABRPError.missingVehicleLocation }
        guard let destination = trip.stops.last?.coordinate else { return }
        guard let soc = vehicle.evStatus?.evRange.percentage else {
            throw ABRPError.missingVehicleLocation
        }

        let client = ABRPClient(
            apiKey: AppSettings.shared.abrpPlanApiKey,
            logSink: HTTPLogSinkManager.shared.createLogSink())
        let result = try await client.fetchPlan(
            origin: origin, destination: destination,
            carModel: trip.carModel, initialSocPercent: soc, vin: trip.vin)

        guard let route = result.routes?.first,
              var newTrip = ABRPTrip(route: route, vin: trip.vin, carModel: trip.carModel,
                                     destinationName: trip.destinationName,
                                     planUuid: result.planUuid) else {
            throw ABRPError.decoding("no route in re-plan response")
        }
        newTrip.id = trip.id
        newTrip.createdAt = trip.createdAt
        activeTrip = newTrip
        persist()
        BBLogger.info(.app, "ABRP: Re-planned trip to \(trip.destinationName), " +
            "now \(newTrip.chargerStopCount) charging stops")
    }

    // MARK: - Arrival Auto-Detection

    /// Marks arrival when the vehicle reports charging near the current stop.
    /// Never auto-advances legs, so passing a charger can't derail the trip.
    private func handleStatusUpdate(vin: String) {
        guard let trip = activeTrip, trip.vin == vin, trip.phase == .enRoute,
              let stop = trip.currentStop, stop.isCharger else { return }
        guard let modelContainer else {
            BBLogger.warning(.app, "ABRP: arrival check skipped — no model container")
            return
        }

        let descriptor = FetchDescriptor<BBVehicle>(predicate: #Predicate { $0.vin == vin })
        guard let vehicle = try? modelContainer.mainContext.fetch(descriptor).first else {
            BBLogger.debug(.app, "ABRP: arrival check skipped — vehicle \(vin) not found")
            return
        }
        guard vehicle.evStatus?.charging == true, let coordinate = vehicle.coordinate else {
            BBLogger.debug(.app, "ABRP: arrival check — not charging or no location " +
                "(charging: \(vehicle.evStatus?.charging ?? false))")
            return
        }

        let vehicleLocation = CLLocation(latitude: coordinate.latitude,
                                         longitude: coordinate.longitude)
        let stopLocation = CLLocation(latitude: stop.latitude, longitude: stop.longitude)
        let distance = vehicleLocation.distance(from: stopLocation)
        BBLogger.debug(.app, "ABRP: arrival check — \(Int(distance)) m from \(stop.name)")
        if distance <= Self.arrivalRadiusMeters {
            markArrivedAtCurrentStop()
        }
    }

    // MARK: - Persistence

    private func persist() {
        if let trip = activeTrip, let data = try? JSONEncoder().encode(trip) {
            defaults.set(data, forKey: Self.storageKey)
        } else {
            defaults.removeObject(forKey: Self.storageKey)
        }
    }
}
