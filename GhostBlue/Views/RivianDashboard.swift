//
//  RivianDashboard.swift
//  GhostBlue
//
//  Rivian Mobile 3.0–style home dashboard (original SwiftUI) built on the RV
//  design system, using only features this app has:
//   • a vehicle hero,
//   • circular Lock · Unlock · Controls commands,
//   • colour-coded tiles: Climate, Energy, Vehicle Center, Location,
//   • Service and Account in the top bar.
//  Adapts to light and dark appearance. Shown when the Rivian theme is active.
//

import BetterBlueKit
import SwiftUI

struct RivianDashboard: View {
    let bbVehicle: BBVehicle
    var transition: Namespace.ID?
    /// Every displayed vehicle, so the title can switch between them.
    var vehicleOptions: [BBVehicle] = []
    var onSelectVehicle: ((Int) -> Void)?
    /// Last refresh error from HomeView; tapping it opens details or MFA.
    var errorMessage: AttributedString?
    var onTapError: () -> Void = {}
    var onOpenSettings: () -> Void = {}
    var onOpenLocation: () -> Void = {}
    var onOpenCharging: () -> Void = {}
    var onOpenVehicleCenter: () -> Void = {}

    @State private var showingControls = false
    @State private var showingClimate = false
    @State private var showingService = false
    @State private var screenSize: CGSize = .zero

    /// Wide screens (the iPhone Duo's inner display) put the car and its
    /// commands beside the tiles, so everything fits without scrolling.
    private var isWide: Bool { AdaptiveLayout.isWide(screenSize.width) }

    /// The hero shrinks on short screens (the Duo's outer display) so the
    /// tiles stay above the fold.
    private var heroHeight: CGFloat {
        if isWide { return 250 }
        return AdaptiveLayout.isShort(screenSize.height) ? 170 : 220
    }

    private var batteryPercent: Int? {
        guard let ev = bbVehicle.evStatus else { return nil }
        return Int(ev.evRange.percentage)
    }
    private var isCharging: Bool { bbVehicle.evStatus?.charging == true }
    private var isLocked: Bool { bbVehicle.lockStatus == .locked }
    private var climateOn: Bool { bbVehicle.climateStatus?.airControlOn == true }

    var body: some View {
        ScrollView {
            VStack(spacing: RV.Space.xl) {
                topBar
                if let errorMessage {
                    errorBanner(errorMessage)
                }
                AdaptiveColumns(isWide: isWide, spacing: RV.Space.xl) {
                    heroCard
                    RivianCommandControls(bbVehicle: bbVehicle, onMore: { showingControls = true })
                } trailing: {
                    tileGrid
                }
            }
            .padding(.horizontal, RV.Space.l)
            .padding(.bottom, RV.Space.xxl)
        }
        .readSize(into: $screenSize)
        .rivianStatusBarBackdrop()
        .background(RVBackground())
        .sheet(isPresented: $showingControls) {
            RivianControls(bbVehicle: bbVehicle, transition: transition,
                           onClose: { showingControls = false })
        }
        .sheet(isPresented: $showingClimate) {
            RivianClimateView(bbVehicle: bbVehicle, transition: transition,
                              onClose: { showingClimate = false })
        }
        .sheet(isPresented: $showingService) {
            RivianService(bbVehicle: bbVehicle, onClose: { showingService = false })
        }
    }

    // MARK: - Top bar (Service + Account at the top, per Rivian 3.0)

    private var topBar: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                vehicleTitle
                Text(statusLine)
                    .font(.subheadline)
                    .foregroundStyle(RV.inkSecondary)
            }
            Spacer()
            HStack(spacing: 18) {
                Button { showingService = true } label: {
                    Image(systemName: "wrench.and.screwdriver.fill")
                        .font(.system(size: 20))
                        .foregroundStyle(RV.ink)
                }
                .accessibilityLabel("Service")
                Button(action: onOpenSettings) {
                    Image(systemName: "person.crop.circle")
                        .font(.system(size: 28))
                        .foregroundStyle(RV.ink)
                }
                .accessibilityLabel("Account and settings")
            }
        }
        .padding(.top, RV.Space.s)
    }

    private var titleText: String {
        bbVehicle.displayName.isEmpty ? "Your Vehicle" : bbVehicle.displayName
    }

    @ViewBuilder
    private var vehicleTitle: some View {
        if vehicleOptions.count > 1, let onSelectVehicle {
            Menu {
                ForEach(Array(vehicleOptions.enumerated()), id: \.element.id) { index, vehicle in
                    Button {
                        onSelectVehicle(index)
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
                    Text(titleText)
                        .font(RV.Font.title)
                        .foregroundStyle(RV.ink)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Image(systemName: "chevron.down")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(RV.inkSecondary)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Vehicle: \(titleText). Switch vehicle")
        } else {
            Text(titleText)
                .font(RV.Font.title)
                .foregroundStyle(RV.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
    }

    private func errorBanner(_ message: AttributedString) -> some View {
        Button(action: onTapError) {
            HStack(spacing: RV.Space.s) {
                Image(systemName: "exclamationmark.triangle.fill")
                Text(message)
                    .font(.caption)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.caption)
            }
            .foregroundStyle(.red)
            .padding(RV.Space.m)
            .background(
                RoundedRectangle(cornerRadius: RV.Radius.card, style: .continuous)
                    .fill(Color.red.opacity(0.1))
            )
        }
        .buttonStyle(.plain)
    }

    private var statusLine: String {
        if let p = batteryPercent {
            return isCharging ? "Charging · \(p)%" : "\(p)% battery"
        }
        return isLocked ? "Locked" : "Unlocked"
    }

    // MARK: - Vehicle hero

    private var heroCard: some View {
        ZStack {
            RoundedRectangle(cornerRadius: RV.Radius.hero, style: .continuous)
                .fill(RV.surface)
            HomeVehicleImageView(
                modelName: bbVehicle.model,
                colorName: bbVehicle.vehicleImageColor.rawValue
            )
                .frame(maxWidth: .infinity)
                .padding(24)
        }
        .frame(height: heroHeight)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(bbVehicle.displayName) vehicle image")
    }

    // MARK: - Category tiles

    private var tileGrid: some View {
        LazyVGrid(
            columns: [GridItem(.flexible(), spacing: RV.Space.m),
                      GridItem(.flexible(), spacing: RV.Space.m)],
            spacing: RV.Space.m
        ) {
            RVTile(title: "Climate", systemImage: "fan.fill",
                   detail: climateOn ? "On" : "Off", color: RV.climate) { showingClimate = true }
            RVTile(title: "Energy", systemImage: "bolt.fill",
                   detail: batteryPercent.map { "\($0)%" } ?? "—", color: RV.energy, action: onOpenCharging)
            RVTile(title: "Vehicle Center", systemImage: "car.fill",
                   detail: "Details", color: RV.vehicleCenter, action: onOpenVehicleCenter)
            RVTile(title: "Location", systemImage: "location.fill",
                   detail: "Map", color: RV.location, action: onOpenLocation)
        }
    }
}
