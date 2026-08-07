//
//  ABRPTelemetryManager.swift
//  GhostBlue
//
//  Pushes live vehicle data (SoC, charging state, location) to ABRP's
//  telemetry API whenever a status refresh lands, so the user's ABRP account
//  always knows the real battery level. Fire-and-forget: never blocks the
//  status update path; failures are logged only.
//

import BetterBlueKit
import Foundation
import SwiftData

@MainActor
final class ABRPTelemetryManager {
    static let shared = ABRPTelemetryManager()

    /// Never send more often than this per vehicle.
    private static let minInterval: TimeInterval = 60
    /// Resend unchanged data at most this often, so ABRP still sees a heartbeat.
    private static let heartbeatInterval: TimeInterval = 1800

    private var modelContainer: ModelContainer?
    private var observer: NSObjectProtocol?
    private var lastSent: [String: (date: Date, signature: Int)] = [:]

    private init() {}

    func configure(with container: ModelContainer) {
        modelContainer = container
    }

    func start() {
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(
            forName: .bbVehicleStatusUpdated, object: nil, queue: .main,
        ) { notification in
            guard let vin = notification.object as? String else { return }
            Task { @MainActor in
                ABRPTelemetryManager.shared.pushIfNeeded(vin: vin)
            }
        }
    }

    private func pushIfNeeded(vin: String) {
        let settings = AppSettings.shared
        guard settings.abrpTelemetryEnabled, !settings.abrpTelemetryApiKey.isEmpty,
              let modelContainer else { return }

        let descriptor = FetchDescriptor<BBVehicle>(predicate: #Predicate { $0.vin == vin })
        guard let vehicle = try? modelContainer.mainContext.fetch(descriptor).first,
              let token = vehicle.abrpUserToken, !token.isEmpty,
              let evStatus = vehicle.evStatus else { return }

        let odometerKm = vehicle.odometer.units.convert(vehicle.odometer.length, to: .kilometers)
        let signature = Self.signature(
            evStatus: evStatus, location: vehicle.location, odometerKm: odometerKm)

        let now = Date()
        if let last = lastSent[vin] {
            if now.timeIntervalSince(last.date) < Self.minInterval { return }
            if last.signature == signature,
               now.timeIntervalSince(last.date) < Self.heartbeatInterval { return }
        }
        lastSent[vin] = (now, signature)

        var fields: [String: Any] = [
            // Sample time, not send time — status data can be minutes old.
            "utc": Int((vehicle.syncDate ?? vehicle.lastUpdated ?? now).timeIntervalSince1970),
            "soc": evStatus.evRange.percentage,
            "is_charging": evStatus.charging ? 1 : 0,
        ]
        if evStatus.charging {
            // ABRP convention: power input (charging) is negative kW.
            fields["power"] = -evStatus.chargeSpeed
            fields["is_dcfc"] = evStatus.plugType == .dcCharger ? 1 : 0
        }
        if let location = vehicle.location {
            fields["lat"] = location.latitude
            fields["lon"] = location.longitude
        }
        if odometerKm > 0 {
            fields["odometer"] = odometerKm
        }
        if let carModel = vehicle.abrpCarModel, !carModel.isEmpty {
            fields["car_model"] = carModel
        }

        guard let tlmData = try? JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys]),
              let tlmJSON = String(data: tlmData, encoding: .utf8) else { return }

        let apiKey = settings.abrpTelemetryApiKey
        let logSink = HTTPLogSinkManager.shared.createLogSink()
        Task {
            do {
                try await ABRPClient.sendTelemetry(
                    tlmJSON: tlmJSON, apiKey: apiKey, userToken: token,
                    vin: vin, logSink: logSink)
                BBLogger.debug(.app, "ABRP: Sent telemetry for \(vin)")
            } catch {
                BBLogger.warning(.app, "ABRP: Telemetry send failed for \(vin): \(error)")
            }
        }
    }

    private static func signature(
        evStatus: VehicleStatus.EVStatus,
        location: VehicleStatus.Location?,
        odometerKm: Double,
    ) -> Int {
        var hasher = Hasher()
        hasher.combine(Int(evStatus.evRange.percentage.rounded()))
        hasher.combine(evStatus.charging)
        hasher.combine(Int(evStatus.chargeSpeed.rounded()))
        hasher.combine(Int(odometerKm.rounded()))
        if let location {
            hasher.combine(Int((location.latitude * 10000).rounded()))
            hasher.combine(Int((location.longitude * 10000).rounded()))
        }
        return hasher.finalize()
    }
}
