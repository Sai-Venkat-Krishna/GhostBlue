//
//  ABRPClient.swift
//  GhostBlue
//
//  Client for the ABRP (Iternio) API: route planning, car model list, and
//  live telemetry. Deliberately not a BetterBlueKit APIClientProtocol —
//  different auth model (API key, no account/session) and no VIN semantics.
//  All requests are logged through HTTPLogSink so raw responses can be
//  inspected in Settings > HTTP Logs.
//

import BetterBlueKit
import CoreLocation
import Foundation

struct ABRPClient: Sendable {
    static let baseURL = URL(string: "https://api.iternio.com/1")!

    /// Stable id used to group ABRP entries in HTTP Logs (there is no real account).
    static let logAccountId = UUID(uuidString: "00000000-0000-4000-8000-0000000000AB")!

    let apiKey: String
    let logSink: HTTPLogSink?

    // MARK: - Plan

    func fetchPlan(
        origin: CLLocationCoordinate2D,
        destination: CLLocationCoordinate2D,
        carModel: String,
        initialSocPercent: Double,
        vin: String? = nil,
    ) async throws -> ABRPPlanResult {
        guard !apiKey.isEmpty else { throw ABRPError.missingAPIKey }
        guard !carModel.isEmpty else { throw ABRPError.missingCarModel }

        #if DEBUG
            // Offline fixture for UI testing without an Iternio key: enter "demo"
            // as the plan API key. Runs through the normal decode path.
            if apiKey == "demo" {
                return try Self.decodePlanResult(from: Data(
                    Self.demoPlanJSON(origin: origin, destination: destination).utf8))
            }
        #endif

        let destinationsJSON = """
        [{"lat":\(origin.latitude),"lon":\(origin.longitude)},\
        {"lat":\(destination.latitude),"lon":\(destination.longitude)}]
        """
        var components = URLComponents(
            url: Self.baseURL.appendingPathComponent("plan"), resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "destinations", value: destinationsJSON),
            URLQueryItem(name: "car_model", value: carModel),
            URLQueryItem(name: "initial_soc_perc", value: String(Int(initialSocPercent.rounded()))),
        ]
        var request = URLRequest(url: components.url!)
        request.setValue("APIKEY \(apiKey)", forHTTPHeaderField: "Authorization")

        let (data, _) = try await perform(request, requestType: .abrpPlan, vin: vin)
        return try Self.decodePlanResult(from: data)
    }

    static func decodePlanResult(from data: Data) throws -> ABRPPlanResult {
        // Error responses carry a string/other payload in "result", so check
        // the status envelope before decoding the full plan shape.
        struct Envelope: Decodable { let status: String? }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase

        let envelope = try? decoder.decode(Envelope.self, from: data)
        guard envelope?.status == "ok" else {
            throw ABRPError.apiError(Self.errorMessage(from: data))
        }
        do {
            let response = try decoder.decode(ABRPPlanResponse.self, from: data)
            guard let result = response.result else {
                throw ABRPError.decoding("empty result")
            }
            return result
        } catch let error as ABRPError {
            throw error
        } catch {
            throw ABRPError.decoding(String(describing: error))
        }
    }

    private static func errorMessage(from data: Data) -> String {
        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            for key in ["result", "error", "message"] {
                if let message = object[key] as? String { return message }
            }
        }
        return String(data: data.prefix(200), encoding: .utf8) ?? "unknown error"
    }

    // MARK: - Car Models

    /// Fetches the list of ABRP car model typecodes. The response shape is a
    /// nested name → typecode mapping, so string leaves are collected
    /// recursively rather than decoded against a fixed schema.
    func fetchCarModels() async throws -> [ABRPCarModel] {
        var request = URLRequest(url: Self.baseURL.appendingPathComponent("get_carmodels_list"))
        if !apiKey.isEmpty, apiKey != "demo" {
            request.setValue("APIKEY \(apiKey)", forHTTPHeaderField: "Authorization")
        }

        let (data, _) = try await perform(request, requestType: .abrpCarModels, vin: nil)
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let result = object["result"] else {
            throw ABRPError.decoding(Self.errorMessage(from: data))
        }

        var models: [ABRPCarModel] = []
        Self.collectCarModels(from: result, into: &models)
        guard !models.isEmpty else {
            throw ABRPError.decoding("no car models in response")
        }
        return models.sorted { $0.displayName < $1.displayName }
    }

    private static func collectCarModels(from value: Any, into models: inout [ABRPCarModel]) {
        if let dictionary = value as? [String: Any] {
            for (key, nested) in dictionary {
                if let typecode = nested as? String {
                    // Names come back semicolon-delimited, e.g. "Hyundai;Ioniq 5;..."
                    models.append(ABRPCarModel(
                        typecode: typecode,
                        displayName: key.replacingOccurrences(of: ";", with: " ")))
                } else {
                    collectCarModels(from: nested, into: &models)
                }
            }
        } else if let array = value as? [Any] {
            for element in array {
                collectCarModels(from: element, into: &models)
            }
        }
    }

    // MARK: - Telemetry

    /// Sends a live-data sample to ABRP. `tlmJSON` is a serialized object
    /// following the Iternio tlm schema (utc, soc, is_charging, lat, lon,
    /// power, odometer, ...) — include only fields with known values.
    static func sendTelemetry(
        tlmJSON: String,
        apiKey: String,
        userToken: String,
        vin: String?,
        logSink: HTTPLogSink?,
    ) async throws {
        var components = URLComponents()
        components.queryItems = [
            URLQueryItem(name: "token", value: userToken),
            URLQueryItem(name: "tlm", value: tlmJSON),
        ]
        var request = URLRequest(url: baseURL.appendingPathComponent("tlm/send"))
        request.httpMethod = "POST"
        request.setValue("APIKEY \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = components.percentEncodedQuery.map { Data($0.utf8) }

        let client = ABRPClient(apiKey: apiKey, logSink: logSink)
        let (data, _) = try await client.perform(request, requestType: .abrpTelemetry, vin: vin)

        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let status = object["status"] as? String, status != "ok" {
            throw ABRPError.apiError(errorMessage(from: data))
        }
    }

    // MARK: - Request Execution + Logging

    private func perform(
        _ request: URLRequest,
        requestType: HTTPRequestType,
        vin: String?,
    ) async throws -> (Data, HTTPURLResponse) {
        let start = Date()
        var responseData: Data?
        var httpResponse: HTTPURLResponse?
        var caughtError: Error?

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            responseData = data
            httpResponse = response as? HTTPURLResponse
        } catch {
            caughtError = error
        }

        log(request: request, requestType: requestType, vin: vin,
            response: httpResponse, data: responseData,
            error: caughtError, duration: Date().timeIntervalSince(start))

        if let caughtError { throw caughtError }
        guard let data = responseData, let response = httpResponse else {
            throw ABRPError.badResponse(status: nil)
        }
        guard (200 ... 299).contains(response.statusCode) else {
            throw ABRPError.badResponse(status: response.statusCode)
        }
        return (data, response)
    }

    private func log(
        request: URLRequest,
        requestType: HTTPRequestType,
        vin: String?,
        response: HTTPURLResponse?,
        data: Data?,
        error: Error?,
        duration: TimeInterval,
    ) {
        guard let logSink else { return }

        var requestHeaders = request.allHTTPHeaderFields ?? [:]
        if requestHeaders["Authorization"] != nil {
            requestHeaders["Authorization"] = "APIKEY <redacted>"
        }
        let responseHeaders = (response?.allHeaderFields as? [String: String]) ?? [:]

        logSink(HTTPLog(
            timestamp: Date(),
            accountId: Self.logAccountId,
            requestType: requestType,
            method: request.httpMethod ?? "GET",
            url: request.url?.absoluteString ?? "",
            requestHeaders: requestHeaders,
            requestBody: request.httpBody.flatMap { String(data: $0, encoding: .utf8) },
            responseStatus: response?.statusCode,
            responseHeaders: responseHeaders,
            responseBody: data.flatMap { String(data: $0, encoding: .utf8) },
            error: error.map(String.init(describing:)),
            duration: duration,
            vin: vin,
        ))
    }
}

// MARK: - Demo Fixture

#if DEBUG
    extension ABRPClient {
        /// A plan with two charging stops interpolated between origin and
        /// destination. No polyline — exercises the dashed fallback path.
        static func demoPlanJSON(
            origin: CLLocationCoordinate2D,
            destination: CLLocationCoordinate2D,
        ) -> String {
            func point(_ fraction: Double) -> (lat: Double, lon: Double) {
                (origin.latitude + (destination.latitude - origin.latitude) * fraction,
                 origin.longitude + (destination.longitude - origin.longitude) * fraction)
            }
            let stop1 = point(0.35), stop2 = point(0.7)

            return """
            {"status":"ok","result":{"plan_uuid":"demo-plan","routes":[{"steps":[
            {"name":"Origin","lat":\(origin.latitude),"lon":\(origin.longitude),\
            "is_charger":false,"departure_perc":80,"drive_duration":0,"drive_dist":0},
            {"name":"Demo Supercharger","lat":\(stop1.lat),"lon":\(stop1.lon),\
            "is_charger":true,"arrival_perc":24,"departure_perc":72,"charge_duration":1440,\
            "drive_duration":6900,"drive_dist":175000,\
            "charger":{"name":"Demo Supercharger","network_name":"Demo Network"}},
            {"name":"Demo Ionity","lat":\(stop2.lat),"lon":\(stop2.lon),\
            "is_charger":true,"arrival_perc":18,"departure_perc":65,"charge_duration":1260,\
            "drive_duration":6300,"drive_dist":160000,\
            "charger":{"name":"Demo Ionity","network_name":"Demo Network"}},
            {"name":"Destination","lat":\(destination.latitude),"lon":\(destination.longitude),\
            "is_charger":false,"is_end_station":true,"arrival_perc":22,\
            "drive_duration":5400,"drive_dist":140000}
            ]}]}}
            """
        }
    }
#endif
