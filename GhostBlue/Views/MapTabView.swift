//
//  MapTabView.swift
//  GhostBlue
//
//  Location tab: a full-screen interactive map of where the selected
//  vehicle is parked, with a Liquid Glass footer card for details and
//  turn-by-turn handoff to the user's preferred maps app.
//

import BetterBlueKit
import CoreLocation
import MapKit
import SwiftUI

/// Tappable vehicle pin: opens a menu of installed maps apps for
/// navigation hand-off.
struct VehicleMapMarker: View {
    let vehicle: BBVehicle
    let coordinate: CLLocationCoordinate2D

    var body: some View {
        Menu {
            NavigationMenuContent(
                coordinate: coordinate,
                destinationName: vehicle.displayName,
            )
        } label: {
            Circle()
                .fill(Color.blue)
                .overlay(
                    Circle()
                        .stroke(Color.white, lineWidth: 3),
                )
                .overlay(
                    Image(systemName: "car.fill")
                        .foregroundColor(.white)
                        .font(.title2),
                )
                .frame(width: 50, height: 50)
                .contentShape(Circle())
                .padding(4)
        }
    }
}

/// Small pin for an ABRP trip stop: bolt for chargers, flag for the
/// destination; green when it's the next stop, gray once passed.
struct TripStopMarker: View {
    let stop: ABRPTripStop
    let isNext: Bool
    let isCompleted: Bool

    private var fill: Color {
        if isCompleted { return .gray }
        return isNext ? .green : .blue
    }

    var body: some View {
        Circle()
            .fill(fill)
            .overlay(
                Circle()
                    .stroke(Color.white, lineWidth: 2),
            )
            .overlay(
                Image(systemName: stop.isCharger ? "bolt.fill" : "flag.checkered")
                    .foregroundColor(.white)
                    .font(.caption.weight(.semibold)),
            )
            .frame(width: 32, height: 32)
    }
}

struct MapTabView: View {
    let displayedVehicles: [BBVehicle]
    @Binding var selectedVehicleIndex: Int

    @State private var position: MapCameraPosition = .automatic
    @State private var showingRoutePlanner = UITestFlags.openRoutePlanner
    @State private var tripManager = ABRPTripManager.shared
    @State private var routeCoordinates: [CLLocationCoordinate2D] = []
    @State private var appSettings = AppSettings.shared

    private var bbVehicle: BBVehicle? {
        guard selectedVehicleIndex < displayedVehicles.count else { return nil }
        return displayedVehicles[selectedVehicleIndex]
    }

    private var activeTrip: ABRPTrip? {
        guard let vin = bbVehicle?.vin else { return nil }
        return tripManager.trip(for: vin)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            Map(position: $position) {
                if let bbVehicle, let coordinate = bbVehicle.coordinate {
                    Annotation(bbVehicle.displayName, coordinate: coordinate) {
                        VehicleMapMarker(vehicle: bbVehicle, coordinate: coordinate)
                    }
                }

                if let trip = activeTrip {
                    if !routeCoordinates.isEmpty {
                        // Dashed when ABRP returned no polyline (straight-line
                        // fallback through the stops).
                        MapPolyline(coordinates: routeCoordinates)
                            .stroke(.blue, style: StrokeStyle(
                                lineWidth: 5, lineCap: .round,
                                dash: trip.encodedPolyline == nil ? [8, 6] : []))
                    }

                    ForEach(Array(trip.stops.enumerated()), id: \.element.id) { index, stop in
                        Annotation(stop.name, coordinate: stop.coordinate) {
                            TripStopMarker(
                                stop: stop,
                                isNext: index == trip.currentStopIndex && trip.phase != .completed,
                                isCompleted: stop.reachedAt != nil || index < trip.currentStopIndex,
                            )
                        }
                    }
                }
            }
            .mapStyle(.standard(elevation: .realistic))
            .ignoresSafeArea(edges: .top)

            if let bbVehicle {
                PersistentModelGuard(model: bbVehicle) {
                    if let trip = activeTrip {
                        ActiveTripFooter(trip: trip, vehicle: bbVehicle)
                    } else {
                        footerCard(for: bbVehicle)
                    }
                }
            }
        }
        .sheet(isPresented: $showingRoutePlanner) {
            if let bbVehicle {
                RoutePlannerView(bbVehicle: bbVehicle)
            }
        }
        .overlay(alignment: .topTrailing) {
            recenterButton
                .padding(.trailing, GBDesign.screenPadding)
                .padding(.top, 8)
        }
        .onAppear {
            if activeTrip != nil {
                updateRouteOverlay()
                frameTrip(animated: false)
            } else {
                centerOnVehicle(animated: false)
            }
        }
        .onChange(of: bbVehicle?.location) { _, _ in
            // Don't fight the route framing while a trip is active
            guard activeTrip == nil else { return }
            centerOnVehicle(animated: true)
        }
        .onChange(of: selectedVehicleIndex) { _, _ in
            updateRouteOverlay()
            if activeTrip != nil {
                frameTrip(animated: true)
            } else {
                centerOnVehicle(animated: true)
            }
        }
        .onChange(of: activeTrip?.id) { _, _ in
            updateRouteOverlay()
            if activeTrip != nil {
                frameTrip(animated: true)
            } else {
                centerOnVehicle(animated: true)
            }
        }
        .onChange(of: activeTrip?.encodedPolyline) { _, _ in
            // Re-plan swaps the polyline while keeping the trip id
            updateRouteOverlay()
        }
        .onReceive(NotificationCenter.default.publisher(for: .recenterLocationTab)) { _ in
            if activeTrip != nil {
                frameTrip(animated: true)
            } else {
                centerOnVehicle(animated: true)
            }
        }
        .overlay {
            if appSettings.hideVehicleLocation {
                ZStack {
                    GBBackground(colors: appSettings.themeColors)
                        .ignoresSafeArea()
                    VStack(spacing: 14) {
                        Image(systemName: "location.slash.circle.fill")
                            .font(.system(size: 52))
                            .foregroundStyle(appSettings.accentColor)
                        Text("Vehicle Location Hidden")
                            .font(.title2.bold())
                        Text("Exact map content is protected by your privacy setting.")
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                        Button("Show location") {
                            appSettings.hideVehicleLocation = false
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding(30)
                }
                .ghostBlueColorScheme()
            }
        }
        .ghostBlueColorScheme(classic: nil)
    }

    // MARK: - Footer Card

    @ViewBuilder
    private func footerCard(for bbVehicle: BBVehicle) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                vehiclePicker(for: bbVehicle)

                if bbVehicle.coordinate != nil {
                    if let lastUpdated = bbVehicle.lastUpdated {
                        Label(
                            "Parked · \(lastUpdated.formatted(.relative(presentation: .named)))",
                            systemImage: "parkingsign.circle.fill"
                        )
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    } else {
                        Label("Parked", systemImage: "parkingsign.circle.fill")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Label("No location reported yet", systemImage: "location.slash")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            if AppSettings.shared.appTheme == .rivian {
                rivianFooterButtons(for: bbVehicle)
            }

            if AppSettings.shared.appTheme != .rivian,
               bbVehicle.fuelType.hasElectricCapability, bbVehicle.evStatus != nil {
                Button {
                    showingRoutePlanner = true
                } label: {
                    Image(systemName: "bolt.car.fill")
                        .font(.subheadline.weight(.bold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .containerShape(Capsule())
                        .glassEffect(.regular.interactive(), in: Capsule())
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Plan Trip")
            }

            if AppSettings.shared.appTheme != .rivian, let coordinate = bbVehicle.coordinate {
                Menu {
                    NavigationMenuContent(
                        coordinate: coordinate,
                        destinationName: bbVehicle.displayName
                    )
                } label: {
                    Label("Navigate", systemImage: "arrow.triangle.turn.up.right.diamond.fill")
                        .font(.subheadline.weight(.bold))
                        .fixedSize()
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .containerShape(Capsule())
                        .glassEffect(.regular.interactive(), in: Capsule())
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(16)
        .gbGlassCard(radius: GBDesign.cardRadius)
        .padding(.horizontal, GBDesign.screenPadding)
        .padding(.bottom, 8)
    }

    /// Rivian theme: compact circular Plan Trip and Navigate buttons (Navigate in
    /// solid ink, like the dashboard commands) so the name and status fit.
    @ViewBuilder
    private func rivianFooterButtons(for bbVehicle: BBVehicle) -> some View {
        if bbVehicle.fuelType.hasElectricCapability, bbVehicle.evStatus != nil {
            Button {
                showingRoutePlanner = true
            } label: {
                Image(systemName: "bolt.car.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(RV.commandInactiveIcon)
                    .frame(width: 46, height: 46)
                    .background(Circle().fill(RV.commandInactiveFill))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Plan Trip")
        }

        if let coordinate = bbVehicle.coordinate {
            Menu {
                NavigationMenuContent(
                    coordinate: coordinate,
                    destinationName: bbVehicle.displayName
                )
            } label: {
                Image(systemName: "arrow.triangle.turn.up.right.diamond.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(RV.commandActiveIcon)
                    .frame(width: 46, height: 46)
                    .background(Circle().fill(RV.commandActiveFill))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Navigate")
        }
    }

    @ViewBuilder
    private func vehiclePicker(for bbVehicle: BBVehicle) -> some View {
        if displayedVehicles.count > 1 {
            Menu {
                ForEach(Array(displayedVehicles.enumerated()), id: \.element.id) { index, vehicle in
                    Button {
                        selectedVehicleIndex = index
                    } label: {
                        if vehicle.id == bbVehicle.id {
                            Label(vehicle.displayName, systemImage: "checkmark")
                        } else {
                            Text(vehicle.displayName)
                        }
                    }
                }
            } label: {
                HStack(spacing: 6) {
                    Text(bbVehicle.displayName)
                        .font(.headline.weight(.bold))
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.secondary)
                }
            }
            .buttonStyle(.plain)
        } else {
            Text(bbVehicle.displayName)
                .font(.headline.weight(.bold))
                .lineLimit(1)
        }
    }

    private var recenterButton: some View {
        Button {
            centerOnVehicle(animated: true)
        } label: {
            Image(systemName: "location.fill")
                .fontWeight(.semibold)
                .frame(width: 44, height: 44)
                .gbGlassCircle()
        }
        .buttonStyle(.plain)
        .disabled(bbVehicle?.coordinate == nil)
    }

    // MARK: - Trip Overlay

    private func updateRouteOverlay() {
        guard let trip = activeTrip else {
            routeCoordinates = []
            return
        }
        if let polyline = trip.encodedPolyline {
            routeCoordinates = GooglePolyline.decode(polyline)
        } else {
            // Straight-line fallback through the stops (rendered dashed)
            var coordinates = bbVehicle?.coordinate.map { [$0] } ?? []
            coordinates.append(contentsOf: trip.stops.map(\.coordinate))
            routeCoordinates = coordinates
        }
    }

    private func frameTrip(animated: Bool) {
        var coordinates = routeCoordinates
        if let vehicleCoordinate = bbVehicle?.coordinate {
            coordinates.append(vehicleCoordinate)
        }
        guard coordinates.count > 1 else {
            centerOnVehicle(animated: animated)
            return
        }

        var rect = MKMapRect.null
        for coordinate in coordinates {
            rect = rect.union(MKMapRect(origin: MKMapPoint(coordinate), size: MKMapSize()))
        }
        let padded = rect.insetBy(dx: -rect.size.width * 0.2, dy: -rect.size.height * 0.2)

        if animated {
            withAnimation(.easeInOut(duration: 0.8)) {
                position = .rect(padded)
            }
        } else {
            position = .rect(padded)
        }
    }

    // MARK: - Centering

    private func centerOnVehicle(animated: Bool) {
        guard let coordinate = bbVehicle?.coordinate else { return }

        let region = MKCoordinateRegion(
            center: coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
        )

        if animated {
            withAnimation(.easeInOut(duration: 0.8)) {
                position = .region(region)
            }
        } else {
            position = .region(region)
        }
    }
}
