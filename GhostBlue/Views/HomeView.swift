//
//  HomeView.swift
//  GhostBlue
//
//  Rivian-inspired home tab: a bold, glanceable hero for the selected
//  vehicle with Liquid Glass controls layered over the vehicle's
//  gradient backdrop. All commands reuse the existing button components
//  so behavior (status waiting, errors, presets) is unchanged.
//

import BetterBlueKit
import Charts
import CoreLocation
import MapKit
import SwiftData
import SwiftUI
import WidgetKit

struct HomeView: View {
    let displayedVehicles: [BBVehicle]
    @Binding var selectedVehicleIndex: Int
    var onOpenLocation: () -> Void = {}
    var onSuccessfulRefresh: (() -> Void)?

    @Environment(\.modelContext) private var modelContext
    @State private var appSettings = AppSettings.shared

    @State private var showingSettings = false
    @State private var showingVehicleInfo = false
    @State private var showingAccountInfo = false
    @State private var showingHTTPLogs = false
    @State private var showingVehicleConfiguration = false
    @State private var showingChargeHistory = false
    @State private var showingHomeLayout = false
    @State private var showingOwnershipHub = false
    @State private var isEditingHome = false
    @Query(sort: \ChargeSession.startDate, order: .reverse)
    private var allChargeSessions: [ChargeSession]

    @State private var isRefreshing = false
    @State private var showRefreshSuccess = false
    @State private var lastManualRefreshDate: Date?
    @State private var showCooldownFeedback = false
    private let manualRefreshCooldown: TimeInterval = 60

    @State private var errorMessage: AttributedString?
    @State private var lastAPIError: APIError?
    @State private var lastActionError: ActionError?
    @State private var showingErrorDetails = false
    @State private var mfaState = MFAFlowState()
    @State private var cardsVisible = false
    @State private var refreshTask: Task<Void, Never>?

    @Namespace private var transition

    private var bbVehicle: BBVehicle? {
        guard selectedVehicleIndex < displayedVehicles.count else { return nil }
        return displayedVehicles[selectedVehicleIndex]
    }

    var body: some View {
        ZStack {
            GBBackground(colors: appSettings.themeColors)

            if let bbVehicle {
                PersistentModelGuard(model: bbVehicle) {
                    content(for: bbVehicle)
                }
            }
        }
        .environment(\.colorScheme, .dark)
        .tint(appSettings.accentColor)
        .sheet(isPresented: $showingSettings) {
            SettingsView()
        }
        .sheet(isPresented: $showingVehicleInfo) {
            if let bbVehicle {
                NavigationView {
                    VehicleInfoView(bbVehicle: bbVehicle)
                        .toolbar {
                            ToolbarItem(placement: .navigationBarLeading) {
                                Button("Done") { showingVehicleInfo = false }
                            }
                        }
                }
            }
        }
        .sheet(isPresented: $showingOwnershipHub) {
            NavigationStack {
                OwnershipHubView(vehicles: displayedVehicles, initialVehicle: bbVehicle)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Done") { showingOwnershipHub = false }
                        }
                    }
            }
        }
        .sheet(isPresented: $showingAccountInfo) {
            if let account = bbVehicle?.account {
                NavigationView {
                    AccountInfoView(account: account)
                        .toolbar {
                            ToolbarItem(placement: .navigationBarLeading) {
                                Button("Done") { showingAccountInfo = false }
                            }
                        }
                }
            }
        }
        .sheet(isPresented: $showingHTTPLogs) {
            if let account = bbVehicle?.account {
                NavigationView {
                    HTTPLogView(accountId: account.id, transition: transition)
                        .toolbar {
                            ToolbarItem(placement: .navigationBarLeading) {
                                Button("Done") { showingHTTPLogs = false }
                            }
                        }
                }
            }
        }
        .sheet(isPresented: $showingVehicleConfiguration) {
            if let bbVehicle, bbVehicle.account?.brandEnum == .fake {
                NavigationView {
                    FakeVehicleDetailView(vehicle: bbVehicle)
                        .navigationTitle("Configure Vehicle")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .navigationBarLeading) {
                                Button("Done") { showingVehicleConfiguration = false }
                            }
                        }
                }
            }
        }
        .sheet(isPresented: $showingChargeHistory) {
            if let bbVehicle {
                ChargeHistoryView(bbVehicle: bbVehicle)
            }
        }
        .sheet(isPresented: $showingHomeLayout) {
            HomeLayoutEditorView(order: $appSettings.homeSectionOrder)
        }
        .sheet(isPresented: $showingErrorDetails) {
            if let lastActionError {
                ErrorDetailsSheet(error: lastActionError) {
                    showingErrorDetails = false
                }
                .presentationDetents([.medium, .large])
            }
        }
        .mfaFlow(state: mfaState)
    }

    // MARK: - Content

    @ViewBuilder
    private func content(for bbVehicle: BBVehicle) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 12) {
                header(for: bbVehicle)
                    .id("home-top")
                    .cardEntry(visible: cardsVisible, delay: 0.0)

                if errorMessage != nil {
                    errorBanner
                        .cardEntry(visible: cardsVisible, delay: 0.02)
                }

                if isRefreshing, bbVehicle.lastUpdated == nil {
                    GBSkeletonCard(height: 210)
                    GBSkeletonCard(height: 72)
                    GBSkeletonCard(height: 72)
                } else {
                    vehicleVisualHero(for: bbVehicle)
                        .cardEntry(visible: cardsVisible, delay: 0.04)

                    LockButton(bbVehicle: bbVehicle, transition: transition)
                        .cardEntry(visible: cardsVisible, delay: 0.10)

                    ClimateButton(bbVehicle: bbVehicle, transition: transition)
                        .cardEntry(visible: cardsVisible, delay: 0.12)

                    ForEach(
                        Array(appSettings.homeSectionOrder.enumerated()),
                        id: \.element.id
                    ) { index, section in
                        if !appSettings.homeHiddenSections.contains(section) || isEditingHome {
                            homeSectionContainer(section, for: bbVehicle)
                                .cardEntry(
                                    visible: cardsVisible,
                                    delay: 0.15 + min(Double(index) * 0.04, 0.16)
                                )
                        }
                    }
                }
                }
                .padding(.horizontal, GBDesign.screenPadding)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .scrollEdgeEffectStyle(.hard, for: [.top, .bottom])
            .refreshable {
                await performRefresh(forced: true)
            }
            .task(id: bbVehicle.vin) {
                await performRefresh(forced: false)
            }
            .onAppear {
                cardsVisible = false
                withAnimation(.spring(response: 0.55, dampingFraction: 0.88)) {
                    cardsVisible = true
                }
            }
            .onChange(of: bbVehicle.vin) { _, _ in
                cardsVisible = false
                withAnimation(.spring(response: 0.55, dampingFraction: 0.88)) {
                    cardsVisible = true
                }
            }
            .onDisappear {
                refreshTask?.cancel()
            }
            .onReceive(NotificationCenter.default.publisher(for: .scrollHomeToTop)) { _ in
                withAnimation(.easeOut(duration: 0.35)) {
                    proxy.scrollTo("home-top", anchor: .top)
                }
            }
        }
    }

    // MARK: - Header

    @ViewBuilder
    private func header(for bbVehicle: BBVehicle) -> some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                vehicleSwitcher(for: bbVehicle)

                if let lastUpdated = bbVehicle.lastUpdated {
                    Text("Updated \(lastUpdated.formatted(.relative(presentation: .named)))")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            Button {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                    isEditingHome.toggle()
                }
            } label: {
                Text(isEditingHome ? "Done" : "Edit")
                    .font(.caption.bold())
                    .frame(minWidth: 44, minHeight: 44)
                    .gbGlassCard(radius: 18, interactive: true)
            }
            .buttonStyle(.plain)

            refreshButton
            moreMenu(for: bbVehicle)
        }
        .padding(.top, 8)
    }

    @ViewBuilder
    private func vehicleSwitcher(for bbVehicle: BBVehicle) -> some View {
        let title = HStack(spacing: 8) {
            Text(bbVehicle.displayName)
                .font(.gbDisplay)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .foregroundStyle(.primary)

            if displayedVehicles.count > 1 {
                Image(systemName: "chevron.down.circle.fill")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
        }

        if displayedVehicles.count > 1 {
            Menu {
                ForEach(Array(displayedVehicles.enumerated()), id: \.element.id) { index, vehicle in
                    Button {
                        withAnimation(.spring(response: 0.45, dampingFraction: 0.85)) {
                            selectedVehicleIndex = index
                        }
                    } label: {
                        if vehicle.id == bbVehicle.id {
                            Label(vehicle.displayName, systemImage: "checkmark")
                        } else {
                            Text(vehicle.displayName)
                        }
                    }
                }
            } label: {
                title
            }
            .buttonStyle(.plain)
        } else {
            title
        }
    }

    private var isOnCooldown: Bool {
        guard let last = lastManualRefreshDate else { return false }
        return Date().timeIntervalSince(last) < manualRefreshCooldown
    }

    @ViewBuilder
    private var refreshButton: some View {
        Button {
            if isOnCooldown {
                showCooldownFeedback = true
                Task {
                    try? await Task.sleep(for: .seconds(2))
                    await MainActor.run { showCooldownFeedback = false }
                }
                return
            }
            lastManualRefreshDate = Date()
            Task { await performRefresh(forced: true) }
        } label: {
            Group {
                if isRefreshing {
                    ProgressView()
                        .scaleEffect(0.8)
                } else if showRefreshSuccess {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.green)
                } else if showCooldownFeedback {
                    Image(systemName: "clock")
                        .foregroundStyle(.orange)
                } else {
                    Image(systemName: "arrow.clockwise")
                        .fontWeight(.semibold)
                }
            }
            .frame(width: 44, height: 44)
            .gbGlassCircle()
        }
        .buttonStyle(.plain)
        .disabled(isRefreshing)
        .animation(.spring(response: 0.4, dampingFraction: 0.6), value: isRefreshing)
        .animation(.spring(response: 0.4, dampingFraction: 0.6), value: showRefreshSuccess)
        .animation(.spring(response: 0.4, dampingFraction: 0.6), value: showCooldownFeedback)
    }

    @ViewBuilder
    private func moreMenu(for bbVehicle: BBVehicle) -> some View {
        Menu {
            if !appSettings.hideVehicleLocation, let location = bbVehicle.location {
                let coordinate = CLLocationCoordinate2D(
                    latitude: location.latitude,
                    longitude: location.longitude
                )
                Menu {
                    NavigationMenuContent(
                        coordinate: coordinate,
                        destinationName: bbVehicle.displayName
                    )
                } label: {
                    Label("Navigate to Vehicle", systemImage: "location")
                }
            }

            Button {
                showingVehicleInfo = true
            } label: {
                Label("Vehicle Info", systemImage: "car.fill")
            }

            Button {
                showingOwnershipHub = true
            } label: {
                Label("Vehicle Center", systemImage: "car.2.fill")
            }

            Button {
                showingAccountInfo = true
            } label: {
                Label("Account Info", systemImage: "person.circle")
            }

            if AppSettings.shared.debugModeEnabled {
                Button {
                    showingHTTPLogs = true
                } label: {
                    Label("HTTP Logs", systemImage: "network")
                }
            }

            if bbVehicle.account?.brandEnum == .fake {
                Button {
                    showingVehicleConfiguration = true
                } label: {
                    Label("Configure Vehicle", systemImage: "wrench.and.screwdriver")
                }
            }

            Button {
                showingSettings = true
            } label: {
                Label("Settings", systemImage: "gearshape.fill")
            }

            Button {
                showingHomeLayout = true
            } label: {
                Label("Customize Home", systemImage: "rectangle.3.group")
            }

            Button {
                withAnimation {
                    isEditingHome = true
                }
            } label: {
                Label("Edit on Home", systemImage: "slider.horizontal.3")
            }
        } label: {
            Image(systemName: "ellipsis")
                .fontWeight(.semibold)
                .frame(width: 44, height: 44)
                .gbGlassCircle()
        }
        .buttonStyle(.plain)
    }

    // MARK: - Vehicle Visual

    private func vehicleVisualHero(for bbVehicle: BBVehicle) -> some View {
        VStack(spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(bbVehicle.model)
                        .font(.headline.weight(.bold))
                        .lineLimit(1)

                    Text(
                        [
                            bbVehicle.account?.brandEnum.displayName,
                            fuelTypeLabel(bbVehicle.fuelType)
                        ]
                        .compactMap { $0 }
                        .joined(separator: " · ")
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }

                Spacer()

                let state = vehicleImageState(for: bbVehicle)
                Label(state.title, systemImage: state.systemImage)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(state.tint)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .background(state.tint.opacity(0.13), in: Capsule())
            }

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 14) {
                    vehicleHeroArtwork(for: bbVehicle)

                    heroEnergySummary(for: bbVehicle)
                        .frame(width: appSettings.vehicleHeroSize == .compact ? 104 : 124)
                }

                VStack(spacing: 8) {
                    vehicleHeroArtwork(for: bbVehicle)

                    heroEnergySummary(for: bbVehicle)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
        }
        .padding(18)
        .background {
            RadialGradient(
                colors: [.cyan.opacity(0.12), .clear],
                center: .center,
                startRadius: 10,
                endRadius: 190
            )
        }
        .gbGlassCard(radius: GBDesign.heroRadius)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(vehicleHeroAccessibilityLabel(for: bbVehicle))
    }

    private func vehicleHeroArtwork(for vehicle: BBVehicle) -> some View {
        ZStack {
            Ellipse()
                .fill(.black.opacity(0.38))
                .frame(maxWidth: 220, maxHeight: 24)
                .blur(radius: 8)
                .offset(y: appSettings.vehicleHeroSize.imageHeight * 0.31)

            HomeVehicleImageView(
                modelName: vehicle.model,
                colorName: vehicle.vehicleImageColor.rawValue
            )
            .frame(
                maxWidth: .infinity,
                alignment: appSettings.vehicleHeroAlignment.alignment
            )
            .frame(height: appSettings.vehicleHeroSize.imageHeight)
            .shadow(
                color: vehicleImageGlow(for: vehicle),
                radius: vehicle.evStatus?.charging == true ? 28 : 18,
                y: 8
            )

            if vehicle.evStatus?.charging == true {
                ChargingFlowIndicator(tint: .green)
                    .frame(maxWidth: 180)
                    .offset(y: appSettings.vehicleHeroSize.imageHeight * 0.34)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func vehicleHeroAccessibilityLabel(for vehicle: BBVehicle) -> String {
        let state = vehicleImageState(for: vehicle)
        if let evStatus = vehicle.evStatus {
            return "\(vehicle.model), \(state.title), battery \(Int(evStatus.evRange.percentage)) percent"
        }
        if let gasRange = vehicle.gasRange {
            return "\(vehicle.model), \(state.title), range \(gasRange.range.length.formatted())"
        }
        return "\(vehicle.model), \(state.title)"
    }

    private func vehicleImageGlow(for vehicle: BBVehicle) -> Color {
        if vehicle.doorOpen?.anyOpen == true
            || vehicle.hoodOpen == true
            || vehicle.trunkOpen == true
            || vehicle.tirePressureWarning?.hasWarning == true {
            return .orange.opacity(0.45)
        }
        if vehicle.evStatus?.charging == true { return .green.opacity(0.48) }
        if vehicle.climateStatus?.airControlOn == true { return .cyan.opacity(0.42) }
        return appSettings.accentColor.opacity(0.25)
    }

    private func vehicleImageState(
        for vehicle: BBVehicle
    ) -> (title: String, systemImage: String, tint: Color) {
        if vehicle.doorOpen?.anyOpen == true
            || vehicle.hoodOpen == true
            || vehicle.trunkOpen == true
            || vehicle.tirePressureWarning?.hasWarning == true {
            return ("Warning", "exclamationmark.triangle.fill", .orange)
        }
        if vehicle.evStatus?.charging == true {
            return ("Charging", "bolt.fill", .green)
        }
        if vehicle.climateStatus?.airControlOn == true {
            return ("Climate on", "fan.fill", .cyan)
        }
        if vehicle.lockStatus == .locked {
            return ("Locked", "lock.fill", .green)
        }
        return ("Vehicle", "car.side.fill", appSettings.accentColor)
    }

    @ViewBuilder
    private func heroEnergySummary(for vehicle: BBVehicle) -> some View {
        if let evStatus = vehicle.evStatus {
            VStack(alignment: .trailing, spacing: 6) {
                Text("\(Int(evStatus.evRange.percentage))%")
                    .font(.title.bold())
                Text(
                    evStatus.evRange.range.units.format(
                        evStatus.evRange.range.length,
                        to: appSettings.preferredDistanceUnit
                    )
                )
                .font(.caption.bold())
                .foregroundStyle(.secondary)

                if evStatus.charging {
                    if evStatus.chargeTime > .seconds(0) {
                        Text(
                            evStatus.chargeTime.formatted(
                                .units(allowed: [.hours, .minutes], width: .abbreviated)
                            )
                        )
                        .font(.caption2)
                        .foregroundStyle(.green)
                    }

                    if let cost = estimatedChargeCompletionCost(
                        vehicle: vehicle,
                        status: evStatus
                    ) {
                        Text(
                            cost.formatted(
                                .currency(code: Locale.current.currency?.identifier ?? "USD")
                            )
                        )
                        .font(.caption.bold())
                        .foregroundStyle(.green)
                    }
                }
            }
        } else if let gasRange = vehicle.gasRange {
            VStack(alignment: .trailing, spacing: 5) {
                Image(systemName: "fuelpump.fill")
                    .foregroundStyle(appSettings.accentColor)
                Text(
                    gasRange.range.units.format(
                        gasRange.range.length,
                        to: appSettings.preferredDistanceUnit
                    )
                )
                .font(.headline.bold())
            }
        }
    }

    private func estimatedChargeCompletionCost(
        vehicle: BBVehicle,
        status: VehicleStatus.EVStatus
    ) -> Double? {
        guard let capacity = vehicle.batteryCapacityKWh,
              let rate = vehicle.electricityRatePerKWh,
              capacity > 0,
              rate > 0
        else { return nil }
        let target = status.currentTargetSOC ?? 100
        let remainingPercent = max(0, target - status.evRange.percentage)
        return capacity * remainingPercent / 100 * rate
    }

    private func fuelTypeLabel(_ fuelType: FuelType) -> String {
        switch fuelType {
        case .electric: "Electric"
        case .phev: "Plug-in hybrid"
        case .gas: "Gas"
        }
    }

    private func vehicleHealth(
        for bbVehicle: BBVehicle
    ) -> (title: String, systemImage: String, tint: Color) {
        let hasOpenPanel = bbVehicle.doorOpen?.anyOpen == true
            || bbVehicle.hoodOpen == true
            || bbVehicle.trunkOpen == true
        let hasTireWarning = bbVehicle.tirePressureWarning?.hasWarning == true

        if hasOpenPanel || hasTireWarning {
            return ("Check vehicle", "exclamationmark.triangle.fill", .orange)
        }
        if bbVehicle.evStatus?.charging == true {
            return ("Charging", "bolt.fill", .green)
        }
        if bbVehicle.lockStatus == .locked, bbVehicle.doorOpen?.anyOpen == false {
            return ("All secure", "checkmark.shield.fill", .green)
        }
        return ("Status ready", "checkmark.circle.fill", .blue)
    }

    private func lockGlance(
        for bbVehicle: BBVehicle
    ) -> (value: String, systemImage: String, tint: Color) {
        switch bbVehicle.lockStatus {
        case .locked:
            return ("Locked", "lock.fill", .green)
        case .unlocked:
            return ("Unlocked", "lock.open.fill", .orange)
        case .unknown, nil:
            return ("Unknown", "lock.questionmark", .secondary)
        }
    }

    private func doorGlance(
        for bbVehicle: BBVehicle
    ) -> (value: String, systemImage: String, tint: Color) {
        guard let doors = bbVehicle.doorOpen else {
            return ("Unknown", "car.top.door.front.left.open", .secondary)
        }
        if doors.anyOpen || bbVehicle.hoodOpen == true || bbVehicle.trunkOpen == true {
            return ("Open", "car.top.door.front.left.open", .orange)
        }
        return ("Closed", "door.left.hand.closed", .green)
    }

    private func energyGlance(
        for bbVehicle: BBVehicle
    ) -> (label: String, value: String, systemImage: String, tint: Color) {
        if let evStatus = bbVehicle.evStatus {
            if evStatus.charging {
                let speed = evStatus.chargeSpeed > 0
                    ? String(format: "%.1f kW", evStatus.chargeSpeed)
                    : "Charging"
                return ("Energy", speed, "bolt.fill", .green)
            }
            if evStatus.pluggedIn {
                return ("Energy", "Plugged in", "powerplug.fill", .blue)
            }
            return ("Energy", "\(Int(evStatus.evRange.percentage))%", "battery.75percent", .blue)
        }

        if let climate = bbVehicle.climateStatus, climate.airControlOn {
            return ("Climate", "On", "fan.fill", .blue)
        }
        return ("Climate", "Off", "fan.slash", .secondary)
    }

    @ViewBuilder
    private func homeSectionContainer(_ section: HomeSection, for bbVehicle: BBVehicle) -> some View {
        let isHidden = appSettings.homeHiddenSections.contains(section)

        VStack(spacing: 8) {
            if isEditingHome {
                homeSectionEditor(section, isHidden: isHidden)
            }

            homeSection(section, size: appSettings.size(for: section), for: bbVehicle)
                .opacity(isHidden ? 0.42 : 1)
        }
    }

    @ViewBuilder
    private func homeSection(
        _ section: HomeSection,
        size: HomeCardSize,
        for bbVehicle: BBVehicle
    ) -> some View {
        switch section {
        case .briefing:
            VStack(spacing: 12) {
                GBSectionHeader(title: "Smart briefing")
                smartBriefingCard(for: bbVehicle, size: size)
            }
        case .charging:
            if bbVehicle.evStatus != nil {
                VStack(spacing: 12) {
                    GBSectionHeader(title: "Charging")
                    ChargingButton(bbVehicle: bbVehicle, transition: transition)
                    if size != .compact {
                        chargingCompletionDetails(for: bbVehicle)
                    }
                }
            }
        case .health:
            VStack(spacing: 12) {
                GBSectionHeader(title: "Vehicle health")
                vehicleHealthCard(for: bbVehicle, size: size)
            }
        case .status:
            VStack(spacing: 12) {
                GBSectionHeader(title: "Vehicle status")
                if size == .compact {
                    compactStatusRow(for: bbVehicle)
                } else {
                    statusGrid(for: bbVehicle)
                }
            }
        case .history:
            chargeHistorySection(for: bbVehicle, limit: size == .compact ? 1 : (size == .expanded ? 4 : 2))
        case .location:
            if bbVehicle.coordinate != nil {
                VStack(spacing: 12) {
                    GBSectionHeader(title: "Location")
                    locationCard(
                        for: bbVehicle,
                        height: size == .compact ? 105 : (size == .expanded ? 230 : 150)
                    )
                }
            }
        }
    }

    private func smartBriefingCard(
        for vehicle: BBVehicle,
        size: HomeCardSize
    ) -> some View {
        let items = vehicleBriefingItems(for: vehicle)
        let limit = size == .compact ? 1 : (size == .expanded ? items.count : min(2, items.count))

        return VStack(spacing: 0) {
            HStack {
                Label("Right now", systemImage: "sparkles")
                    .font(.subheadline.bold())
                Spacer()
                Button {
                    Task { await performRefresh(forced: true) }
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                        .labelStyle(.iconOnly)
                        .frame(width: 38, height: 38)
                        .background(Color.primary.opacity(0.07), in: Circle())
                }
                .buttonStyle(.plain)
                .disabled(isRefreshing)
                .accessibilityLabel("Refresh vehicle briefing")
            }
            .padding(.bottom, 8)

            ForEach(Array(items.prefix(limit).enumerated()), id: \.element.id) { index, item in
                if index > 0 {
                    Divider()
                        .padding(.leading, 38)
                }

                HStack(alignment: .top, spacing: 11) {
                    Image(systemName: item.systemImage)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(item.tint)
                        .frame(width: 26, height: 26)
                        .background(item.tint.opacity(0.12), in: Circle())

                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(item.title)
                                .font(.subheadline.bold())
                            Text(item.category)
                                .font(.system(size: 9, weight: .heavy))
                                .textCase(.uppercase)
                                .foregroundStyle(item.tint)
                        }
                        Text(item.message)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Spacer(minLength: 0)
                }
                .padding(.vertical, 9)
                .accessibilityElement(children: .combine)
            }

            if items.count > limit {
                Text("+\(items.count - limit) more in expanded view")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 6)
            }
        }
        .padding(16)
        .gbGlassCard(radius: GBDesign.tileRadius)
    }

    private func vehicleBriefingItems(for vehicle: BBVehicle) -> [VehicleBriefingItem] {
        var items: [VehicleBriefingItem] = []

        if vehicle.doorOpen?.anyOpen == true
            || vehicle.hoodOpen == true
            || vehicle.trunkOpen == true {
            items.append(
                VehicleBriefingItem(
                    category: "Action",
                    title: "Vehicle is open",
                    message: "A door, the hood, or the trunk is open. Check the Vehicle Health card before leaving.",
                    systemImage: "car.top.door.front.left.open",
                    tint: .orange
                )
            )
        }

        if vehicle.tirePressureWarning?.hasWarning == true {
            items.append(
                VehicleBriefingItem(
                    category: "Safety",
                    title: "Check tire pressure",
                    message: vehicle.tirePressureWarning?.warningDescription ?? "A tire-pressure warning is active.",
                    systemImage: "exclamationmark.triangle.fill",
                    tint: .orange
                )
            )
        }

        if let battery12V = vehicle.battery12V, battery12V < 40 {
            items.append(
                VehicleBriefingItem(
                    category: "Battery",
                    title: "12V battery is low",
                    message: "The 12V battery is at \(battery12V)%. Consider driving or charging soon.",
                    systemImage: "battery.25percent",
                    tint: battery12V < 20 ? .red : .orange
                )
            )
        }

        if vehicle.lockStatus == .unlocked {
            items.append(
                VehicleBriefingItem(
                    category: "Security",
                    title: "Vehicle is unlocked",
                    message: "Use the Lock control above when the vehicle is unattended.",
                    systemImage: "lock.open.fill",
                    tint: .orange
                )
            )
        }

        if let evStatus = vehicle.evStatus {
            if evStatus.charging {
                let finishText = chargeFinishDate(
                    remainingSeconds: evStatus.chargeTime.components.seconds,
                    reportedAt: vehicle.lastUpdated
                ).map(formatChargeFinishTime)
                let target = Int(evStatus.currentTargetSOC ?? 100)
                items.append(
                    VehicleBriefingItem(
                        category: "Charging",
                        title: "Charging to \(target)%",
                        message: finishText.map { "Estimated completion at \($0)." }
                            ?? "Completion time will appear after the vehicle reports an estimate.",
                        systemImage: "bolt.fill",
                        tint: .green
                    )
                )
            } else if evStatus.evRange.percentage <= 20 {
                items.append(
                    VehicleBriefingItem(
                        category: "Range",
                        title: "Plan your next charge",
                        message: "Battery is at \(Int(evStatus.evRange.percentage))% with \(evStatus.evRange.range.units.format(evStatus.evRange.range.length, to: appSettings.preferredDistanceUnit)) estimated range.",
                        systemImage: "battery.25percent",
                        tint: evStatus.evRange.percentage <= 10 ? .red : .orange
                    )
                )
            }
        }

        if let lastUpdated = vehicle.lastUpdated,
           Date().timeIntervalSince(lastUpdated) > 15 * 60 {
            items.append(
                VehicleBriefingItem(
                    category: "Data",
                    title: "Status may be outdated",
                    message: "Last refreshed \(lastUpdated.formatted(.relative(presentation: .named))). Pull down or tap Refresh.",
                    systemImage: "clock.badge.exclamationmark.fill",
                    tint: .orange
                )
            )
        }

        if items.isEmpty {
            items.append(
                VehicleBriefingItem(
                    category: "Ready",
                    title: "Everything looks good",
                    message: "No open-panel, tire, battery, charging, or security alerts need attention.",
                    systemImage: "checkmark.shield.fill",
                    tint: .green
                )
            )
        }

        return items
    }

    private func homeSectionEditor(_ section: HomeSection, isHidden: Bool) -> some View {
        HStack {
            Label(section.title, systemImage: section.systemImage)
                .font(.caption.bold())

            Spacer()

            Button {
                moveHomeSection(section, offset: -1)
            } label: {
                Image(systemName: "arrow.up")
            }
            .disabled(appSettings.homeSectionOrder.first == section)

            Button {
                moveHomeSection(section, offset: 1)
            } label: {
                Image(systemName: "arrow.down")
            }
            .disabled(appSettings.homeSectionOrder.last == section)

            Menu {
                Picker(
                    "Size",
                    selection: Binding(
                        get: { appSettings.size(for: section) },
                        set: { appSettings.setSize($0, for: section) }
                    )
                ) {
                    ForEach(HomeCardSize.allCases) { size in
                        Text(size.title).tag(size)
                    }
                }
            } label: {
                Image(systemName: "rectangle.resize")
            }

            Button {
                if isHidden {
                    appSettings.homeHiddenSections.remove(section)
                } else {
                    appSettings.homeHiddenSections.insert(section)
                }
            } label: {
                Image(systemName: isHidden ? "eye" : "eye.slash")
            }
        }
        .buttonStyle(.borderless)
        .foregroundStyle(appSettings.accentColor)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial, in: Capsule())
    }

    private func moveHomeSection(_ section: HomeSection, offset: Int) {
        guard let index = appSettings.homeSectionOrder.firstIndex(of: section) else { return }
        let destination = min(max(0, index + offset), appSettings.homeSectionOrder.count - 1)
        guard destination != index else { return }
        withAnimation {
            let moved = appSettings.homeSectionOrder.remove(at: index)
            appSettings.homeSectionOrder.insert(moved, at: destination)
        }
    }

    private func compactStatusRow(for vehicle: BBVehicle) -> some View {
        HStack(spacing: 10) {
            let lock = lockGlance(for: vehicle)
            HomeVehicleGlanceItem(
                systemImage: lock.systemImage,
                label: "Lock",
                value: lock.value,
                tint: lock.tint
            )
            let doors = doorGlance(for: vehicle)
            HomeVehicleGlanceItem(
                systemImage: doors.systemImage,
                label: "Access",
                value: doors.value,
                tint: doors.tint
            )
            HomeVehicleGlanceItem(
                systemImage: "minus.plus.batteryblock.fill",
                label: "12V",
                value: vehicle.battery12V.map { "\($0)%" } ?? "--",
                tint: (vehicle.battery12V ?? 100) < 40 ? .orange : .green
            )
        }
        .padding(10)
        .gbGlassCard(radius: GBDesign.tileRadius)
    }

    private func vehicleHealthCard(for vehicle: BBVehicle, size: HomeCardSize) -> some View {
        let health = vehicleHealth(for: vehicle)
        return VStack(spacing: 12) {
            HStack {
                Label(health.title, systemImage: health.systemImage)
                    .font(.headline.bold())
                    .foregroundStyle(health.tint)
                Spacer()
                Text(vehicle.lastUpdated?.formatted(.relative(presentation: .named)) ?? "Not updated")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if size != .compact {
                HStack(spacing: 8) {
                    healthMetric(
                        "12V",
                        vehicle.battery12V.map { "\($0)%" } ?? "--",
                        vehicle.battery12V.map { $0 < 40 ? Color.orange : .green } ?? .secondary
                    )
                    healthMetric(
                        "Tires",
                        vehicle.tirePressureWarning?.hasWarning == true ? "Check" : "OK",
                        vehicle.tirePressureWarning?.hasWarning == true ? .orange : .green
                    )
                    healthMetric(
                        "Doors",
                        vehicle.doorOpen?.anyOpen == true ? "Open" : "Closed",
                        vehicle.doorOpen?.anyOpen == true ? .orange : .green
                    )
                }
            }
        }
        .padding(16)
        .gbGlassCard(radius: GBDesign.tileRadius)
    }

    private func healthMetric(_ title: String, _ value: String, _ tint: Color) -> some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.caption.bold())
                .foregroundStyle(tint)
            Text(title.uppercased())
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func chargingCompletionDetails(for vehicle: BBVehicle) -> some View {
        if let status = vehicle.evStatus, status.charging {
            let current = min(max(status.evRange.percentage, 0), 100)
            let target = min(max(status.currentTargetSOC ?? 100, current), 100)
            let progress = target > 0 ? min(current / target, 1) : 0

            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 16) {
                    ZStack {
                        Circle()
                            .stroke(.white.opacity(0.10), lineWidth: 10)
                        Circle()
                            .trim(from: 0, to: progress)
                            .stroke(
                                AngularGradient(colors: [.cyan, .green], center: .center),
                                style: StrokeStyle(lineWidth: 10, lineCap: .round)
                            )
                            .rotationEffect(.degrees(-90))

                        VStack(spacing: 1) {
                            Text("\(Int(current))%")
                                .font(.title2.bold())
                            Text("of \(Int(target))%")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .frame(width: 104, height: 104)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Battery charge progress")
                    .accessibilityValue("\(Int(current)) percent of \(Int(target)) percent target")

                    LazyVGrid(
                        columns: [
                            GridItem(.flexible(), alignment: .leading),
                            GridItem(.flexible(), alignment: .leading)
                        ],
                        spacing: 12
                    ) {
                        chargingMetric(
                            "Remaining",
                            status.chargeTime > .seconds(0)
                                ? status.chargeTime.formatted(
                                    .units(allowed: [.hours, .minutes], width: .abbreviated)
                                )
                                : "Calculating",
                            systemImage: "clock.fill"
                        )
                        chargingMetric(
                            "Speed",
                            status.chargeSpeed > 0
                                ? "\(status.chargeSpeed.formatted(.number.precision(.fractionLength(1)))) kW"
                                : "--",
                            systemImage: "bolt.fill"
                        )
                        chargingMetric(
                            "Energy left",
                            estimatedRemainingChargeEnergy(vehicle: vehicle, status: status)
                                .map { "\($0.formatted(.number.precision(.fractionLength(1)))) kWh" }
                                ?? "--",
                            systemImage: "battery.75percent"
                        )
                        chargingMetric(
                            "Est. cost",
                            estimatedChargeCompletionCost(vehicle: vehicle, status: status)?
                                .formatted(
                                    .currency(
                                        code: Locale.current.currency?.identifier ?? "USD"
                                    )
                                ) ?? "--",
                            systemImage: "dollarsign.circle.fill"
                        )
                    }
                }

                if let warning = chargingWarning(for: vehicle, status: status) {
                    Label(warning.message, systemImage: warning.systemImage)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(warning.tint)
                        .padding(10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(warning.tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                }

                if let session = activeChargeSession(for: vehicle) {
                    chargingSpeedChart(session)
                }

                if vehicle.batteryCapacityKWh == nil || vehicle.electricityRatePerKWh == nil {
                    Label(
                        "Add usable battery capacity and electricity rate in Charging settings for energy and cost estimates.",
                        systemImage: "info.circle"
                    )
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                }
            }
            .padding(16)
            .gbGlassCard(radius: GBDesign.tileRadius)
            .accessibilityElement(children: .contain)
        }
    }

    private func chargingMetric(
        _ title: String,
        _ value: String,
        systemImage: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Label(title, systemImage: systemImage)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.caption.bold())
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .accessibilityElement(children: .combine)
    }

    private func estimatedRemainingChargeEnergy(
        vehicle: BBVehicle,
        status: VehicleStatus.EVStatus
    ) -> Double? {
        guard let capacity = vehicle.batteryCapacityKWh, capacity > 0 else { return nil }
        let target = status.currentTargetSOC ?? 100
        return capacity * max(0, target - status.evRange.percentage) / 100
    }

    private func chargingWarning(
        for vehicle: BBVehicle,
        status: VehicleStatus.EVStatus
    ) -> (message: String, systemImage: String, tint: Color)? {
        if status.chargeSpeed <= 0 {
            return (
                "Charging may be paused. Check the cable or station.",
                "exclamationmark.bolt.fill",
                .orange
            )
        }
        if status.plugType != .dcCharger, status.chargeSpeed < 2 {
            return (
                "Charging is slower than expected for an AC connection.",
                "tortoise.fill",
                .yellow
            )
        }
        if let lastUpdated = vehicle.lastUpdated,
           Date().timeIntervalSince(lastUpdated) > 600 {
            return (
                "Charging status is more than 10 minutes old.",
                "clock.badge.exclamationmark.fill",
                .orange
            )
        }
        return nil
    }

    private func activeChargeSession(for vehicle: BBVehicle) -> ChargeSession? {
        allChargeSessions.first { $0.vin == vehicle.vin && $0.isActive }
    }

    @ViewBuilder
    private func chargingSpeedChart(_ session: ChargeSession) -> some View {
        let samples = Array(session.speedSamples.suffix(24))

        if samples.count > 1 {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Label("Charging speed", systemImage: "waveform.path.ecg")
                        .font(.caption.bold())
                    Spacer()
                    Text("Current session")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Chart(samples, id: \.date) { sample in
                    AreaMark(
                        x: .value("Time", sample.date),
                        y: .value("Speed", sample.speedKW)
                    )
                    .foregroundStyle(
                        LinearGradient(
                            colors: [.green.opacity(0.30), .green.opacity(0.02)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )

                    LineMark(
                        x: .value("Time", sample.date),
                        y: .value("Speed", sample.speedKW)
                    )
                    .foregroundStyle(.green)
                    .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .accessibilityLabel(sample.date.formatted(date: .omitted, time: .shortened))
                    .accessibilityValue(
                        "\(sample.speedKW.formatted(.number.precision(.fractionLength(1)))) kilowatts"
                    )
                }
                .chartYAxis {
                    AxisMarks(position: .leading) {
                        AxisGridLine()
                        AxisValueLabel()
                    }
                }
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: 3)) {
                        AxisValueLabel(format: .dateTime.hour().minute())
                    }
                }
                .frame(height: 105)
                .accessibilityLabel("Current charging speed history")
            }
        } else {
            Label(
                "Charging-speed history will appear after the next status refresh.",
                systemImage: "chart.xyaxis.line"
            )
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
    }

    // MARK: - Error Banner

    @ViewBuilder
    private var errorBanner: some View {
        if let errorMessage {
            Button {
                if let apiError = lastAPIError, apiError.errorType == .requiresMFA {
                    handleMFAError(apiError)
                } else if lastActionError != nil {
                    showingErrorDetails = true
                }
            } label: {
                HStack {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .tint(.blue)
                        .multilineTextAlignment(.leading)
                    Spacer()
                    if lastAPIError?.errorType == .requiresMFA || lastActionError != nil {
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(.red.opacity(0.7))
                    }
                }
                .padding()
            }
            .buttonStyle(.plain)
            .gbGlassCard(radius: GBDesign.tileRadius, interactive: true)
        }
    }

    // MARK: - Range Hero

    @ViewBuilder
    private func rangeHero(for bbVehicle: BBVehicle) -> some View {
        if let evStatus = bbVehicle.evStatus {
            EVRangeHero(evStatus: evStatus, statusDate: bbVehicle.lastUpdated)
        } else if let gasRange = bbVehicle.gasRange {
            FuelRangeHero(gasRange: gasRange)
        }
    }

    // MARK: - Status Grid

    @ViewBuilder
    private func statusGrid(for bbVehicle: BBVehicle) -> some View {
        let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

        LazyVGrid(columns: columns, spacing: 10) {
            GBStatTile(
                systemImage: "gauge.with.needle",
                label: "Odometer",
                value: bbVehicle.odometer.units.format(
                    bbVehicle.odometer.length,
                    to: appSettings.preferredDistanceUnit
                )
            )

            if let battery12V = bbVehicle.battery12V {
                GBStatTile(
                    systemImage: "minus.plus.batteryblock.fill",
                    label: "12V Battery",
                    value: "\(battery12V)%",
                    tint: battery12V < 30 ? .red : (battery12V < 50 ? .orange : .primary)
                )
            }

            if let doorOpen = bbVehicle.doorOpen {
                GBStatTile(
                    systemImage: doorOpen.anyOpen ? "car.top.door.front.left.open" : "door.left.hand.closed",
                    label: "Doors",
                    value: doorOpen.anyOpen ? doorOpen.openDoorsDescription : "Closed",
                    tint: doorOpen.anyOpen ? .orange : .primary
                )
            }

            let hoodOpen = bbVehicle.hoodOpen ?? false
            let trunkOpen = bbVehicle.trunkOpen ?? false
            GBStatTile(
                systemImage: hoodOpen ? "car.side.front.open" : (trunkOpen ? "car.side.rear.open" : "car.side"),
                label: "Hood / Trunk",
                value: hoodTrunkText(hoodOpen: hoodOpen, trunkOpen: trunkOpen),
                tint: (hoodOpen || trunkOpen) ? .orange : .primary
            )

            if let tirePressure = bbVehicle.tirePressureWarning {
                GBStatTile(
                    systemImage: tirePressure.hasWarning ? "exclamationmark.tirepressure" : "tirepressure",
                    label: "Tires",
                    value: tirePressure.hasWarning ? tirePressure.warningDescription : "OK",
                    tint: tirePressure.hasWarning ? .orange : .primary
                )
            }

            if let climateStatus = bbVehicle.climateStatus {
                GBStatTile(
                    systemImage: climateStatus.airControlOn ? "fan.fill" : "fan.slash",
                    label: "Climate",
                    value: climateTileText(for: climateStatus),
                    tint: climateStatus.airControlOn ? .blue : .primary
                )
            }

            // PHEV: fuel level lives in the grid since the hero shows battery
            if bbVehicle.evStatus != nil, let gasRange = bbVehicle.gasRange {
                GBStatTile(
                    systemImage: "fuelpump.fill",
                    label: "Fuel",
                    value: gasRange.range.units.format(
                        gasRange.range.length,
                        to: appSettings.preferredDistanceUnit
                    )
                )
            }
        }
    }

    private func hoodTrunkText(hoodOpen: Bool, trunkOpen: Bool) -> String {
        switch (hoodOpen, trunkOpen) {
        case (true, true): "Both open"
        case (true, false): "Hood open"
        case (false, true): "Trunk open"
        case (false, false): "Closed"
        }
    }

    private func climateTileText(for climateStatus: VehicleStatus.ClimateStatus) -> String {
        guard climateStatus.airControlOn else { return "Off" }
        let temperature = climateStatus.temperature
        guard temperature.isPlausibleForDisplay else { return "On" }
        return temperature.units.format(
            temperature.value,
            to: appSettings.preferredTemperatureUnit
        )
    }

    // MARK: - Charge History Section

    private func chargeSessions(for bbVehicle: BBVehicle) -> [ChargeSession] {
        allChargeSessions.filter { $0.vin == bbVehicle.vin && $0.isMeaningful }
    }

    @ViewBuilder
    private func chargeHistorySection(for bbVehicle: BBVehicle, limit: Int = 2) -> some View {
        let sessions = chargeSessions(for: bbVehicle)

        if !sessions.isEmpty {
            GBSectionHeader(title: "Charging history")

            ForEach(sessions.prefix(limit)) { session in
                ChargeSessionCard(
                    session: session,
                    batteryCapacityKWh: bbVehicle.batteryCapacityKWh,
                    electricityRatePerKWh: bbVehicle.electricityRatePerKWh
                )
            }

            if sessions.count > limit {
                Button {
                    showingChargeHistory = true
                } label: {
                    HStack {
                        Text("View all \(sessions.count) sessions")
                            .font(.subheadline.weight(.semibold))
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(14)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .gbGlassCard(radius: GBDesign.tileRadius, interactive: true)
            }
        }
    }

    // MARK: - Location Card

    @ViewBuilder
    private func locationCard(for bbVehicle: BBVehicle, height: CGFloat = 150) -> some View {
        if appSettings.hideVehicleLocation {
            VStack(spacing: 8) {
                Image(systemName: "location.slash.fill")
                    .font(.title2)
                    .foregroundStyle(appSettings.accentColor)
                Text("Exact location hidden")
                    .font(.headline)
                Text("Change this in Vehicle Center → Privacy & Security.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .gbGlassCard()
        } else if let coordinate = bbVehicle.coordinate {
            Button(action: onOpenLocation) {
                ZStack(alignment: .bottomLeading) {
                    Map(
                        initialPosition: .region(
                            MKCoordinateRegion(
                                center: coordinate,
                                span: MKCoordinateSpan(latitudeDelta: 0.006, longitudeDelta: 0.006)
                            )
                        )
                    ) {
                        Annotation("", coordinate: coordinate) {
                            Image(systemName: "car.fill")
                                .font(.callout)
                                .foregroundStyle(.white)
                                .padding(8)
                                .background(Circle().fill(.blue))
                        }
                    }
                    .frame(height: height)
                    .allowsHitTesting(false)
                    .id("\(coordinate.latitude),\(coordinate.longitude)")

                    HStack {
                        Label("Parked", systemImage: "parkingsign.circle.fill")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                        Spacer()
                        Text("View map")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(12)
                    .background(.thinMaterial)
                }
            }
            .buttonStyle(.plain)
            .gbGlassCard()
        }
    }

    // MARK: - Refresh + Errors

    private func handleMFAError(_ error: APIError) {
        guard let account = bbVehicle?.account else { return }
        mfaState.start(from: error, account: account) { [self] in
            errorMessage = nil
            lastAPIError = nil
            lastActionError = nil
            await performRefresh(forced: true)
        }
    }

    @MainActor
    private func performRefresh(forced: Bool) async {
        guard let bbVehicle else { return }

        // Deduplicate: reuse the in-flight refresh if one is already running
        if let task = refreshTask {
            return await task.value
        }
        let task = Task {
            await executeRefresh(for: bbVehicle, forced: forced)
        }
        refreshTask = task
        await task.value
        refreshTask = nil
    }

    @MainActor
    private func executeRefresh(for bbVehicle: BBVehicle, forced: Bool) async {
        await bbVehicle.clearPendingStatusWaiters()

        isRefreshing = true
        showRefreshSuccess = false
        errorMessage = nil
        lastAPIError = nil
        lastActionError = nil

        do {
            guard let account = bbVehicle.account else {
                throw APIError(message: "Account not found for vehicle")
            }

            try await account.fetchAndUpdateVehicleStatus(
                for: bbVehicle,
                modelContext: modelContext,
                cached: !forced
            )

            if Task.isCancelled {
                isRefreshing = false
                return
            }

            try? modelContext.save()

            isRefreshing = false
            showRefreshSuccess = true
            WidgetCenter.shared.reloadAllTimelines()
            onSuccessfulRefresh?()

            Task {
                try? await Task.sleep(for: .seconds(2))
                await MainActor.run { showRefreshSuccess = false }
            }
        } catch {
            isRefreshing = false
            showRefreshSuccess = false

            if !Task.isCancelled, !(error is CancellationError) {
                BBLogger.error(.app, "HomeView: Error fetching status for \(bbVehicle.vin): \(error)")
                handleError(error, for: bbVehicle)
            }
        }
    }

    private func handleError(_ error: Error, for bbVehicle: BBVehicle) {
        if let apiError = error as? APIError {
            lastAPIError = apiError
            let message = friendlyErrorMessage(for: apiError)
            errorMessage = (try? AttributedString(markdown: message)) ?? AttributedString(message)
        } else {
            lastAPIError = nil
            errorMessage = AttributedString("Failed to refresh: \(error.localizedDescription)")
        }

        // MFA gets its own flow; everything else surfaces the details sheet
        if lastAPIError?.errorType == .requiresMFA {
            lastActionError = nil
        } else {
            lastActionError = ActionError(
                action: "Refresh \(bbVehicle.displayName)",
                error: error,
                accountId: bbVehicle.account?.id
            )
        }
    }

    private func friendlyErrorMessage(for error: APIError) -> String {
        switch error.errorType {
        case .invalidCredentials:
            "Login expired - please check account settings"
        case .invalidPin:
            "PIN validation failed - check account settings"
        case .invalidVehicleSession:
            "Vehicle session expired - trying to reconnect"
        case .serverError:
            "Server temporarily unavailable - try again later"
        case .concurrentRequest:
            "Another request in progress - please wait and try again"
        case .failedRetryLogin:
            "Unable to reconnect - check account settings"
        case .requiresMFA:
            "\(error.message) - Tap to verify"
        case .general:
            friendlyGeneralErrorMessage(for: error)
        case .kiaInvalidRequest:
            error.message
        case .regionNotSupported:
            "This region is not yet supported"
        }
    }

    private func friendlyGeneralErrorMessage(for error: APIError) -> String {
        if error.message.contains("timeout") || error.message.contains("timed out") {
            "Vehicle not responding - try again later"
        } else if error.message.lowercased().contains("network") {
            "Network connection issue - check your internet"
        } else if error.message.contains("404") {
            "Vehicle not found on server"
        } else if error.message.contains("500") || error.message.contains("502") || error.message.contains("503") {
            "Server temporarily unavailable - try again later"
        } else if let code = error.code, code >= 400 {
            "Server error (\(code)) - try again later"
        } else {
            "Unable to refresh - check connection and try again"
        }
    }
}

private struct HomeVehicleGlanceItem: View {
    let systemImage: String
    let label: String
    let value: String
    let tint: Color

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(tint)

            Text(value)
                .font(.caption.weight(.bold))
                .lineLimit(1)
                .minimumScaleFactor(0.7)

            Text(label.uppercased())
                .font(.system(size: 9, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .padding(.horizontal, 6)
        .background(Color.primary.opacity(0.055), in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label), \(value)")
    }
}

// MARK: - EV Range Hero

/// Bold battery hero: giant state of charge, range, capsule gauge, and
/// live charging details when plugged in.
struct EVRangeHero: View {
    let evStatus: VehicleStatus.EVStatus
    var statusDate: Date?
    @State private var appSettings = AppSettings.shared

    private var percentage: Double { evStatus.evRange.percentage }

    private var tint: Color {
        gbBatteryColor(percentage: percentage, charging: evStatus.charging)
    }

    private var formattedRange: String {
        guard evStatus.evRange.range.length > 0 else { return "--" }
        return evStatus.evRange.range.units.format(
            evStatus.evRange.range.length,
            to: appSettings.preferredDistanceUnit
        )
    }

    private var chargeTimeRemaining: String? {
        guard evStatus.charging, evStatus.chargeTime > .seconds(0) else { return nil }
        let formatted = evStatus.chargeTime.formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
        if let targetSOC = evStatus.currentTargetSOC {
            return "\(formatted) to \(Int(targetSOC))%"
        }
        return formatted
    }

    private var chargeFinishText: String? {
        guard evStatus.charging,
              let finish = chargeFinishDate(
                  remainingSeconds: evStatus.chargeTime.components.seconds,
                  reportedAt: statusDate
              ) else { return nil }
        return formatChargeFinishTime(finish)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Label("Battery", systemImage: "bolt.fill")
                    .font(.system(size: 12, weight: .heavy))
                    .textCase(.uppercase)
                    .foregroundStyle(.secondary)

                Spacer()

                if evStatus.charging {
                    HStack(spacing: 5) {
                        Image(systemName: "bolt.fill")
                            .symbolEffect(.pulse, options: .repeating)
                        Text(evStatus.chargeSpeed > 0
                            ? String(format: "Charging · %.1f kW", evStatus.chargeSpeed)
                            : "Charging")
                    }
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.green)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(Color.green.opacity(0.18)))
                } else if evStatus.pluggedIn {
                    Label("Plugged in", systemImage: "powerplug.fill")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.secondary)
                }
            }

            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text("\(Int(percentage))")
                    .font(.gbHero)
                    .contentTransition(.numericText())
                Text("%")
                    .font(.gbHeroUnit)
                    .foregroundStyle(.secondary)

                Spacer()

                VStack(alignment: .trailing, spacing: 2) {
                    Text(formattedRange)
                        .font(.title2.weight(.bold))
                    Text("est. range")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            GBBatteryGauge(
                percentage: percentage,
                charging: evStatus.charging,
                tint: tint,
                targetPercentage: evStatus.charging ? evStatus.currentTargetSOC : nil
            )

            if let chargeTimeRemaining {
                HStack(spacing: 6) {
                    Image(systemName: "clock.fill")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(chargeTimeRemaining)
                        .font(.footnote.weight(.semibold))
                    if let chargeFinishText {
                        Text("· \(chargeFinishText)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }
            }
        }
        .padding(18)
        .gbGlassCard(radius: GBDesign.heroRadius)
        .animation(.spring(response: 0.6, dampingFraction: 0.85), value: Int(percentage))
    }
}

private struct VehicleBriefingItem: Identifiable {
    let id = UUID()
    let category: String
    let title: String
    let message: String
    let systemImage: String
    let tint: Color
}

private struct ChargingFlowIndicator: View {
    let tint: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var flowOffset: CGFloat = -58

    var body: some View {
        ZStack {
            Capsule()
                .stroke(tint.opacity(0.26), style: StrokeStyle(lineWidth: 2, dash: [4, 6]))

            Image(systemName: "bolt.fill")
                .font(.caption.bold())
                .foregroundStyle(tint)
                .shadow(color: tint.opacity(0.7), radius: 6)
                .offset(x: reduceMotion ? 0 : flowOffset)
        }
        .frame(height: 22)
        .clipShape(Capsule())
        .accessibilityHidden(true)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.linear(duration: 1.45).repeatForever(autoreverses: false)) {
                flowOffset = 58
            }
        }
        .onChange(of: reduceMotion) { _, enabled in
            flowOffset = enabled ? 0 : -58
            guard !enabled else { return }
            withAnimation(.linear(duration: 1.45).repeatForever(autoreverses: false)) {
                flowOffset = 58
            }
        }
    }
}

// MARK: - Fuel Range Hero

/// Bold fuel hero for combustion vehicles: fuel level and range.
struct FuelRangeHero: View {
    let gasRange: VehicleStatus.FuelRange
    @State private var appSettings = AppSettings.shared

    private var percentage: Double { gasRange.percentage }

    private var tint: Color {
        if percentage <= 15 { return .red }
        if percentage <= 30 { return .orange }
        return .blue
    }

    private var formattedRange: String {
        guard gasRange.range.length > 0 else { return "--" }
        return gasRange.range.units.format(
            gasRange.range.length,
            to: appSettings.preferredDistanceUnit
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Fuel", systemImage: "fuelpump.fill")
                .font(.system(size: 12, weight: .heavy))
                .textCase(.uppercase)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text("\(Int(percentage))")
                    .font(.gbHero)
                    .contentTransition(.numericText())
                Text("%")
                    .font(.gbHeroUnit)
                    .foregroundStyle(.secondary)

                Spacer()

                VStack(alignment: .trailing, spacing: 2) {
                    Text(formattedRange)
                        .font(.title2.weight(.bold))
                    Text("est. range")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            GBBatteryGauge(percentage: percentage, tint: tint)
        }
        .padding(18)
        .gbGlassCard(radius: GBDesign.heroRadius)
    }
}
