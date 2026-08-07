//
//  ActiveTripFooter.swift
//  GhostBlue
//
//  Replaces the Map tab's parked-vehicle footer while an ABRP trip is
//  active. Drives the leg-by-leg flow: Navigate (Apple Maps handoff) /
//  Arrived while en route, live SoC vs departure target while charging,
//  Continue for the next leg, and a summary when completed.
//

import SwiftUI

struct ActiveTripFooter: View {
    let trip: ABRPTrip
    let vehicle: BBVehicle

    @State private var showingStops = false
    @State private var showingEndConfirmation = false
    @State private var isReplanning = false
    @State private var replanError: String?

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                phaseInfo
                Spacer()
                overflowMenu
            }

            if let replanError {
                Text(replanError)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            actionButtons
        }
        .padding(16)
        .gbGlassCard(radius: GBDesign.cardRadius)
        .padding(.horizontal, GBDesign.screenPadding)
        .padding(.bottom, 8)
        .sheet(isPresented: $showingStops) {
            stopsSheet
        }
        .confirmationDialog("End this trip?", isPresented: $showingEndConfirmation,
                            titleVisibility: .visible) {
            Button("End Trip", role: .destructive) {
                ABRPTripManager.shared.endTrip()
            }
        }
    }

    // MARK: - Phase Header

    @ViewBuilder
    private var phaseInfo: some View {
        VStack(alignment: .leading, spacing: 4) {
            switch trip.phase {
            case .enRoute:
                Text(trip.currentStop.map { "Next: \($0.name)" } ?? trip.destinationName)
                    .font(.headline.weight(.bold))
                    .lineLimit(1)
                if let detail = enRouteDetail {
                    Label(detail, systemImage: trip.currentStop?.isCharger == true
                        ? "bolt.fill" : "flag.checkered")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

            case .atCharger:
                Text("Charging at \(trip.currentStop?.name ?? "stop")")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.green)
                    .lineLimit(1)
                if let detail = chargingDetail {
                    Label(detail.text, systemImage: "battery.75percent")
                        .font(.caption)
                        .foregroundStyle(detail.ready ? .green : .secondary)
                }

            case .completed:
                Text("Arrived at \(trip.destinationName)")
                    .font(.headline.weight(.bold))
                    .lineLimit(1)
                Label(completedDetail, systemImage: "checkmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var enRouteDetail: String? {
        guard let stop = trip.currentStop else { return nil }
        var parts: [String] = []
        if let arrival = stop.arrivalSocPercent {
            parts.append("arrive ~\(Int(arrival))%")
        }
        if let driveSeconds = stop.driveDurationSeconds, driveSeconds > 0 {
            parts.append("\(TripStopCard.durationText(driveSeconds)) drive")
        }
        let remainingChargers = trip.stops[trip.currentStopIndex...].count(where: \.isCharger)
        if remainingChargers > 0 {
            parts.append("\(remainingChargers) stop\(remainingChargers == 1 ? "" : "s") left")
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private var chargingDetail: (text: String, ready: Bool)? {
        guard let target = trip.currentStop?.departureSocPercent else { return nil }
        guard let soc = vehicle.evStatus?.evRange.percentage else {
            return ("charge to \(Int(target))%", false)
        }
        if soc >= target {
            return ("\(Int(soc))% — ready to continue", true)
        }
        return ("\(Int(soc))% → target \(Int(target))%", false)
    }

    private var completedDetail: String {
        let chargers = trip.chargerStopCount
        guard chargers > 0 else { return "No charging stops needed" }
        return "\(chargers) charging stop\(chargers == 1 ? "" : "s") · " +
            "\(TripStopCard.durationText(trip.totalChargeSeconds)) charging"
    }

    // MARK: - Actions

    @ViewBuilder
    private var actionButtons: some View {
        switch trip.phase {
        case .enRoute:
            HStack(spacing: 10) {
                capsuleButton("Navigate", systemImage: "arrow.triangle.turn.up.right.diamond.fill",
                              prominent: true) {
                    ABRPTripManager.shared.navigateToCurrentStop()
                }
                capsuleButton("Arrived", systemImage: "mappin.and.ellipse") {
                    ABRPTripManager.shared.markArrivedAtCurrentStop()
                }
            }

        case .atCharger:
            capsuleButton("Continue to Next Stop", systemImage: "arrow.right", prominent: true) {
                ABRPTripManager.shared.advanceToNextLeg()
            }

        case .completed:
            capsuleButton("Done", systemImage: "checkmark", prominent: true) {
                ABRPTripManager.shared.endTrip()
            }
        }
    }

    private func capsuleButton(
        _ title: String, systemImage: String, prominent: Bool = false,
        action: @escaping () -> Void,
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(prominent ? Color.blue : Color.primary)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .containerShape(Capsule())
                .glassEffect(.regular.interactive(), in: Capsule())
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var overflowMenu: some View {
        Menu {
            Button {
                showingStops = true
            } label: {
                Label("All Stops", systemImage: "list.bullet")
            }

            Button {
                replan()
            } label: {
                Label("Re-plan From Here", systemImage: "arrow.trianglehead.2.clockwise")
            }
            .disabled(isReplanning)

            Button(role: .destructive) {
                showingEndConfirmation = true
            } label: {
                Label("End Trip", systemImage: "xmark.circle")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
                .font(.title3)
                .foregroundStyle(.secondary)
                .frame(width: 32, height: 32)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
    }

    private func replan() {
        isReplanning = true
        replanError = nil
        Task {
            do {
                try await ABRPTripManager.shared.replanFromCurrentState(vehicle: vehicle)
            } catch {
                replanError = error.localizedDescription
            }
            isReplanning = false
        }
    }

    // MARK: - Stops Sheet

    private var stopsSheet: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 12) {
                    ForEach(Array(trip.stops.enumerated()), id: \.element.id) { index, stop in
                        TripStopCard(
                            stop: stop,
                            isNext: index == trip.currentStopIndex && trip.phase != .completed,
                            isCompleted: stop.reachedAt != nil || index < trip.currentStopIndex,
                        )
                    }
                }
                .padding(GBDesign.screenPadding)
            }
            .navigationTitle("Trip to \(trip.destinationName)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { showingStops = false }
                }
            }
        }
    }
}
