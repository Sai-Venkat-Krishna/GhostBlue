//
//  RoutePlannerView.swift
//  GhostBlue
//
//  EV trip planner sheet: search a destination, fetch charging stops from
//  the ABRP (Iternio) plan API using the vehicle's live SoC and location,
//  preview the stops, and start the trip (leg-by-leg Apple Maps handoff via
//  ABRPTripManager).
//

import BetterBlueKit
import CoreLocation
import MapKit
import SwiftUI

/// Wraps MKLocalSearchCompleter for SwiftUI. Delegate callbacks arrive on
/// the main queue.
@Observable
final class DestinationSearchCompleter: NSObject, MKLocalSearchCompleterDelegate {
    var completions: [MKLocalSearchCompletion] = []
    private let completer = MKLocalSearchCompleter()

    override init() {
        super.init()
        completer.delegate = self
        completer.resultTypes = [.address, .pointOfInterest]
    }

    func search(_ query: String, near coordinate: CLLocationCoordinate2D?) {
        if let coordinate {
            completer.region = MKCoordinateRegion(
                center: coordinate,
                span: MKCoordinateSpan(latitudeDelta: 3, longitudeDelta: 3))
        }
        if query.isEmpty {
            completions = []
            completer.cancel()
        } else {
            completer.queryFragment = query
        }
    }

    func completerDidUpdateResults(_ completer: MKLocalSearchCompleter) {
        completions = completer.results
    }

    func completer(_ completer: MKLocalSearchCompleter, didFailWithError error: Error) {
        completions = []
    }
}

struct RoutePlannerView: View {
    let bbVehicle: BBVehicle

    private struct Destination {
        let name: String
        let coordinate: CLLocationCoordinate2D
    }

    @Environment(\.dismiss) private var dismiss
    @State private var appSettings = AppSettings.shared
    @State private var searchCompleter = DestinationSearchCompleter()
    @State private var searchText = ""
    @State private var destination: Destination?
    @State private var previewTrip: ABRPTrip?
    @State private var isPlanning = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationView {
            content
                .navigationTitle("Plan Trip")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                }
        }
    }

    @ViewBuilder
    private var content: some View {
        if appSettings.abrpPlanApiKey.isEmpty {
            missingKeyView
        } else if bbVehicle.abrpCarModel?.isEmpty != false {
            missingCarModelView
        } else if bbVehicle.coordinate == nil {
            ContentUnavailableView(
                "No Vehicle Location",
                systemImage: "location.slash",
                description: Text("The vehicle hasn't reported a location yet. " +
                    "Refresh its status and try again."))
        } else if let previewTrip {
            planPreview(previewTrip)
        } else {
            searchView
        }
    }

    // MARK: - Preconditions

    private var missingKeyView: some View {
        ContentUnavailableView {
            Label("ABRP API Key Needed", systemImage: "key")
        } description: {
            Text("Trip planning uses the ABRP (Iternio) plan API. Request a key by " +
                "emailing contact@iternio.com, then paste it in Settings > ABRP Route Planning.")
        }
    }

    private var missingCarModelView: some View {
        ContentUnavailableView {
            Label("Choose an ABRP Vehicle Model", systemImage: "car")
        } description: {
            Text("ABRP needs to know the car's consumption model to place charging stops.")
        } actions: {
            NavigationLink("Select Model") {
                ABRPCarModelPicker(bbVehicle: bbVehicle)
            }
            .buttonStyle(.borderedProminent)
        }
    }

    // MARK: - Destination Search

    private var searchView: some View {
        List {
            Section {
                TextField("Where to?", text: $searchText)
                    .textInputAutocapitalization(.words)
                    .onChange(of: searchText) { _, newValue in
                        searchCompleter.search(newValue, near: bbVehicle.coordinate)
                    }
            } footer: {
                if let soc = bbVehicle.evStatus?.evRange.percentage {
                    Text("Planning from the car's current position at \(Int(soc))% charge.")
                }
            }

            if isPlanning {
                Section {
                    HStack {
                        BrandProgressView()
                        Text("Asking ABRP for charging stops…")
                            .foregroundStyle(.secondary)
                    }
                }
            } else if let errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            if !searchCompleter.completions.isEmpty {
                Section("Results") {
                    ForEach(searchCompleter.completions, id: \.self) { completion in
                        Button {
                            select(completion)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(completion.title)
                                    .foregroundColor(.primary)
                                if !completion.subtitle.isEmpty {
                                    Text(completion.subtitle)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private func select(_ completion: MKLocalSearchCompletion) {
        guard !isPlanning else { return }
        isPlanning = true
        errorMessage = nil
        Task {
            do {
                let search = MKLocalSearch(request: MKLocalSearch.Request(completion: completion))
                let response = try await search.start()
                guard let item = response.mapItems.first else {
                    throw ABRPError.apiError("Couldn't resolve that destination.")
                }
                destination = Destination(
                    name: completion.title,
                    coordinate: item.location.coordinate)
                try await plan()
            } catch {
                errorMessage = error.localizedDescription
            }
            isPlanning = false
        }
    }

    // MARK: - Planning

    private func plan() async throws {
        guard let destination else { return }
        guard let origin = bbVehicle.coordinate else { throw ABRPError.missingVehicleLocation }
        guard let carModel = bbVehicle.abrpCarModel else { throw ABRPError.missingCarModel }
        let soc = bbVehicle.evStatus?.evRange.percentage ?? 100

        let client = ABRPClient(
            apiKey: appSettings.abrpPlanApiKey,
            logSink: HTTPLogSinkManager.shared.createLogSink())
        let result = try await client.fetchPlan(
            origin: origin, destination: destination.coordinate,
            carModel: carModel, initialSocPercent: soc, vin: bbVehicle.vin)

        guard let route = result.routes?.first,
              let trip = ABRPTrip(route: route, vin: bbVehicle.vin, carModel: carModel,
                                  destinationName: destination.name,
                                  planUuid: result.planUuid) else {
            throw ABRPError.decoding("ABRP returned no usable route")
        }
        previewTrip = trip
    }

    // MARK: - Plan Preview

    private func planPreview(_ trip: ABRPTrip) -> some View {
        ScrollView {
            VStack(spacing: 12) {
                summaryCard(trip)

                ForEach(trip.stops) { stop in
                    TripStopCard(stop: stop)
                }

                Button {
                    ABRPTripManager.shared.startTrip(trip)
                    dismiss()
                } label: {
                    Label("Start Trip", systemImage: "arrow.triangle.turn.up.right.diamond.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                }
                .buttonStyle(.borderedProminent)

                Button("Choose a Different Destination") {
                    previewTrip = nil
                    destination = nil
                }
                .font(.subheadline)
            }
            .padding(GBDesign.screenPadding)
        }
    }

    private func summaryCard(_ trip: ABRPTrip) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(trip.destinationName)
                .font(.headline.weight(.bold))

            HStack(spacing: 14) {
                Label(TripStopCard.durationText(trip.totalDriveSeconds),
                      systemImage: "car.fill")
                if trip.chargerStopCount > 0 {
                    Label("\(trip.chargerStopCount)", systemImage: "bolt.fill")
                    Label(TripStopCard.durationText(trip.totalChargeSeconds),
                          systemImage: "clock")
                }
                if let arrival = trip.arrivalSocPercent {
                    Label("\(Int(arrival))%", systemImage: "battery.50percent")
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .gbGlassCard(radius: GBDesign.cardRadius)
    }
}
