//
//  TripsView.swift
//  GhostBlue
//
//  Trips tab: bold drive-history dashboard with summary stats, an
//  energy chart, and expandable Liquid Glass trip cards. Data comes
//  from BetterBlueKit's EV trip details endpoint.
//

import BetterBlueKit
import Charts
import MapKit
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

struct TripsView: View {
    let displayedVehicles: [BBVehicle]
    @Binding var selectedVehicleIndex: Int

    @Environment(\.modelContext) private var modelContext
    @Query(sort: \TripRecord.startDate, order: .reverse)
    private var archivedTripRecords: [TripRecord]

    init(displayedVehicles: [BBVehicle], selectedVehicleIndex: Binding<Int>) {
        self.displayedVehicles = displayedVehicles
        _selectedVehicleIndex = selectedVehicleIndex
        let index = selectedVehicleIndex.wrappedValue
        let vin = displayedVehicles.indices.contains(index) ? displayedVehicles[index].vin : ""
        _archivedTripRecords = Query(filter: #Predicate<TripRecord> { $0.vin == vin },
                                     sort: \TripRecord.startDate, order: .reverse)
    }

    @State private var recordsByKey: [String: TripRecord] = [:]
    @State private var appSettings = AppSettings.shared
    @State private var trips: [EVTripDetail] = []
    @State private var isLoading = false
    @State private var loadError: ActionError?
    @State private var showEnergyBreakdown = false
    @State private var expandedTripId: String?
    @State private var contentVisible = false
    @State private var selectedFilter: TripDateFilter = .all
    @State private var trendPeriod: TripTrendPeriod = .weekly
    @State private var showingCostSettings = false
    @State private var tripSearchText = ""
    @State private var tripSort: TripSortOption = .newest
    @State private var recordToRename: TripRecord?
    @State private var renameText = ""
    @State private var selectedEnergySlot: String?
    @State private var selectedTrendDate: Date?
    @State private var showEfficiencyTrend = false
    @State private var showEnergyUsage = false
    @State private var pendingDeletedRecord: TripRecord?
    @State private var deletionTask: Task<Void, Never>?
    @State private var cachedDataNotice: String?
    @State private var comparisonMode = false
    @State private var comparedTripIDs: [String] = []
    @State private var showingTripComparison = false
    @State private var exportingTrips = false
    @State private var exportErrorMessage: String?
    @AppStorage(
        "TripsDetailedMode",
        store: UserDefaults(suiteName: "group.com.saivenkatkrishna.ghostblue.shared")
    ) private var detailedMode = false

    private var bbVehicle: BBVehicle? {
        guard selectedVehicleIndex < displayedVehicles.count else { return nil }
        return displayedVehicles[selectedVehicleIndex]
    }

    private var supportsTrips: Bool {
        guard let bbVehicle else { return false }
        return bbVehicle.fuelType.hasElectricCapability
            && (bbVehicle.account?.supportsEVTripDetails == true || hasArchivedTrips(for: bbVehicle))
    }

    private func hasArchivedTrips(for vehicle: BBVehicle) -> Bool {
        archivedTripRecords.contains { $0.vin == vehicle.vin }
    }

    private func archivedTrips(for vehicle: BBVehicle) -> [EVTripDetail] {
        archivedTripRecords
            .filter { $0.vin == vehicle.vin }
            .map(\.tripDetail)
    }

    private var filteredTrips: [EVTripDetail] {
        TripAnalytics.filtered(trips, by: selectedFilter)
            .filter { appSettings.showShortTrips || $0.distance >= 0.1 }
    }

    private var savedTrips: [EVTripDetail] {
        let records = recordsByKey
        let vin = bbVehicle?.vin ?? ""
        let query = tripSearchText.localizedLowercase
        let searched = filteredTrips.filter { trip in
            guard !tripSearchText.isEmpty else { return true }
            let record = records[TripRecord.key(vin: vin, trip: trip)]
            return [
                record?.customName,
                record?.startLocationName,
                record?.endLocationName,
                trip.startDate.formatted(date: .long, time: .shortened)
            ]
            .compactMap { $0?.localizedLowercase }
            .contains { $0.contains(query) }
        }

        return searched.sorted { lhs, rhs in
            let lhsFavorite = records[TripRecord.key(vin: vin, trip: lhs)]?.isFavorite == true
            let rhsFavorite = records[TripRecord.key(vin: vin, trip: rhs)]?.isFavorite == true
            if lhsFavorite != rhsFavorite {
                return lhsFavorite && !rhsFavorite
            }
            switch tripSort {
            case .newest: return lhs.startDate > rhs.startDate
            case .oldest: return lhs.startDate < rhs.startDate
            case .distance: return lhs.distance > rhs.distance
            case .efficiency:
                return (TripAnalytics.efficiencyMilesPerKWh(for: lhs) ?? 0)
                    > (TripAnalytics.efficiencyMilesPerKWh(for: rhs) ?? 0)
            case .energy:
                return TripAnalytics.energyKWh(for: lhs) > TripAnalytics.energyKWh(for: rhs)
            }
        }
    }

    private var savedTripGroups: [TripHistoryGroup] {
        let calendar = Calendar.current
        let visibleTrips = detailedMode || !tripSearchText.isEmpty
            ? savedTrips
            : Array(savedTrips.prefix(5))
        return Dictionary(grouping: visibleTrips) {
            calendar.startOfDay(for: $0.startDate)
        }
        .map { TripHistoryGroup(day: $0.key, trips: $0.value) }
        .sorted { lhs, rhs in
            tripSort == .oldest ? lhs.day < rhs.day : lhs.day > rhs.day
        }
    }

    private var comparisonTrips: [EVTripDetail] {
        comparedTripIDs.compactMap { id in
            savedTrips.first { $0.id == id }
        }
    }

    private var tripExportDocument: TripCSVDocument {
        let metadata = Dictionary(
            uniqueKeysWithValues: archivedTripRecords
                .filter { $0.vin == bbVehicle?.vin }
                .map {
                    (
                        $0.tripKey,
                        TripCSVMetadata(
                            record: $0,
                            redactLocations: appSettings.redactPrivateLocations
                        )
                    )
                }
        )
        let csv = TripCSVExporter.csv(
            trips: savedTrips,
            vin: bbVehicle?.vin ?? "",
            metadata: metadata,
            ratePerKWh: bbVehicle?.electricityRatePerKWh
        )
        return TripCSVDocument(csv: csv)
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
        .ghostBlueColorScheme()
        .rivianStatusBarBackdrop()
        .tint(appSettings.accentColor)
        .sensoryFeedback(.selection, trigger: comparedTripIDs.count)
        .sheet(isPresented: $showingCostSettings) {
            if let bbVehicle {
                NavigationStack {
                    ChargingCostSettingsView(vehicle: bbVehicle)
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Done") { showingCostSettings = false }
                            }
                        }
                }
            }
        }
        .sheet(isPresented: $showingTripComparison) {
            if comparisonTrips.count == 2 {
                TripComparisonView(
                    first: comparisonTrips[0],
                    second: comparisonTrips[1],
                    firstRecord: archivedRecord(for: comparisonTrips[0]),
                    secondRecord: archivedRecord(for: comparisonTrips[1]),
                    distanceUnit: appSettings.preferredDistanceUnit,
                    electricityRatePerKWh: bbVehicle?.electricityRatePerKWh
                )
            }
        }
        .fileExporter(
            isPresented: $exportingTrips,
            document: tripExportDocument,
            contentType: .commaSeparatedText,
            defaultFilename: "GhostBlue-\(bbVehicle?.displayName ?? "Trips")"
        ) { result in
            if case let .failure(error) = result {
                exportErrorMessage = error.localizedDescription
            }
        }
        .alert(
            "Rename Trip",
            isPresented: Binding(
                get: { recordToRename != nil },
                set: { if !$0 { recordToRename = nil } }
            )
        ) {
            TextField("Trip name", text: $renameText)
            Button("Cancel", role: .cancel) { recordToRename = nil }
            Button("Save") { saveTripName() }
        } message: {
            Text("Use a memorable name such as Commute or Airport run.")
        }
        .alert(
            "Export Failed",
            isPresented: Binding(
                get: { exportErrorMessage != nil },
                set: { if !$0 { exportErrorMessage = nil } }
            )
        ) {
            Button("OK") { exportErrorMessage = nil }
        } message: {
            Text(exportErrorMessage ?? "The trips file could not be exported.")
        }
        .overlay(alignment: .bottom) {
            if pendingDeletedRecord != nil {
                HStack {
                    Label("Trip deleted", systemImage: "trash")
                    Spacer()
                    Button("Undo") { undoTripDeletion() }
                        .fontWeight(.bold)
                }
                .padding(.horizontal, 16)
                .frame(height: 52)
                .background(.regularMaterial, in: Capsule())
                .padding(.horizontal, 20)
                .padding(.bottom, 82)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityElement(children: .combine)
            }
        }
    }

    // MARK: - Content

    @ViewBuilder
    private func content(for bbVehicle: BBVehicle) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12, pinnedViews: [.sectionHeaders]) {
                header(for: bbVehicle)
                    .id("trips-top")
                    .cardEntry(visible: contentVisible, delay: 0)

                if !supportsTrips {
                    unsupportedCard(for: bbVehicle)
                        .cardEntry(visible: contentVisible, delay: 0.05)
                } else if isLoading, trips.isEmpty {
                    loadingCard
                        .cardEntry(visible: contentVisible, delay: 0.05)
                } else if let loadError {
                    errorCard(loadError)
                        .cardEntry(visible: contentVisible, delay: 0.05)
                } else if trips.isEmpty {
                    emptyCard
                        .cardEntry(visible: contentVisible, delay: 0.05)
                } else {
                    if let cachedDataNotice {
                        cachedTripsNotice(cachedDataNotice)
                            .cardEntry(visible: contentVisible, delay: 0.04)
                    }

                    if comparisonMode {
                        tripComparisonToolbar
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }

                    Section {
                        if filteredTrips.isEmpty {
                            filteredEmptyCard
                                .cardEntry(visible: contentVisible, delay: 0.06)
                        } else {
                            overviewCard(for: bbVehicle)
                                .cardEntry(visible: contentVisible, delay: 0.06)

                            collapsibleTripsSection(
                                title: "Efficiency trend",
                                systemImage: "chart.xyaxis.line",
                                isExpanded: $showEfficiencyTrend
                            ) {
                                trendCard
                            }
                            .cardEntry(visible: contentVisible, delay: 0.09)

                            collapsibleTripsSection(
                                title: "Energy usage",
                                systemImage: "chart.bar.fill",
                                isExpanded: $showEnergyUsage
                            ) {
                                chartCard
                            }
                            .cardEntry(visible: contentVisible, delay: 0.11)

                            GBSectionHeader(title: "Saved trips")
                                .cardEntry(visible: contentVisible, delay: 0.14)
                            tripHistoryControls

                            if savedTripGroups.isEmpty {
                                noMatchingTripsCard
                            } else {
                                ForEach(savedTripGroups) { group in
                                    tripGroupHeader(group.day)

                                    ForEach(Array(group.trips.enumerated()), id: \.element.id) { index, trip in
                                        let record = archivedRecord(for: trip)
                                        TripCard(
                                            trip: trip,
                                            record: record,
                                            distanceUnit: appSettings.preferredDistanceUnit,
                                            isExpanded: expandedTripId == trip.id,
                                            comparisonMode: comparisonMode,
                                            isComparisonSelected: comparedTripIDs.contains(trip.id),
                                            hideLocation: appSettings.hideVehicleLocation,
                                            onTap: {
                                                if comparisonMode {
                                                    toggleTripComparisonSelection(trip)
                                                } else {
                                                    withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
                                                        expandedTripId = expandedTripId == trip.id ? nil : trip.id
                                                    }
                                                }
                                            },
                                            onRename: { beginRenaming(record) },
                                            onFavorite: { toggleFavorite(record) },
                                            onDelete: { deleteTrip(record) }
                                        )
                                        .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                            Button {
                                                toggleFavorite(record)
                                            } label: {
                                                Label(
                                                    record?.isFavorite == true ? "Unfavorite" : "Favorite",
                                                    systemImage: "star.fill"
                                                )
                                            }
                                            .tint(.yellow)
                                        }
                                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                            Button { beginRenaming(record) } label: {
                                                Label("Rename", systemImage: "pencil")
                                            }
                                            .tint(.blue)

                                            Button(role: .destructive) {
                                                deleteTrip(record)
                                            } label: {
                                                Label("Delete", systemImage: "trash")
                                            }
                                        }
                                        .cardEntry(
                                            visible: contentVisible,
                                            delay: 0.15 + min(Double(index) * 0.02, 0.12)
                                        )
                                    }
                                }
                            }

                            if !detailedMode,
                               tripSearchText.isEmpty,
                               savedTrips.count > 5 {
                                Button {
                                    withAnimation {
                                        detailedMode = true
                                        showEfficiencyTrend = true
                                        showEnergyUsage = true
                                    }
                                } label: {
                                    Label(
                                        "Show all \(savedTrips.count) trips",
                                        systemImage: "chevron.down.circle"
                                    )
                                    .font(.subheadline.bold())
                                    .frame(maxWidth: .infinity)
                                    .padding(14)
                                }
                                .buttonStyle(.plain)
                                .gbGlassCard(radius: GBDesign.tileRadius, interactive: true)
                            }
                        }
                    } header: {
                        tripFilterBar
                            .padding(.vertical, 8)
                            .padding(.horizontal, 4)
                            .background(.ultraThinMaterial, in: Capsule())
                    }
                }
                }
                .padding(.horizontal, GBDesign.screenPadding)
                .padding(.bottom, 24)
            }
            .contentMargins(.bottom, 88, for: .scrollContent)
            .scrollIndicators(.hidden)
            .scrollEdgeEffectStyle(.hard, for: [.top, .bottom])
            .refreshable {
                await loadTrips(for: bbVehicle)
            }
            .task(id: bbVehicle.vin) {
                contentVisible = false
                rebuildTripIndex()
                trips = archivedTrips(for: bbVehicle)
                expandedTripId = nil
                comparisonMode = false
                comparedTripIDs = []
                await loadTrips(for: bbVehicle)
                withAnimation(.spring(response: 0.55, dampingFraction: 0.86)) {
                    contentVisible = true
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .scrollTripsToTop)) { _ in
                withAnimation(.easeOut(duration: 0.35)) {
                    proxy.scrollTo("trips-top", anchor: .top)
                }
            }
            .onChange(of: detailedMode) { _, enabled in
                withAnimation {
                    showEfficiencyTrend = enabled
                    showEnergyUsage = enabled
                }
            }
            .onChange(of: archivedTripRecords.map(\.persistentModelID)) {
                rebuildTripIndex()
                trips = archivedTrips(for: bbVehicle).filter {
                    TripRecord.key(vin: bbVehicle.vin, trip: $0) != pendingDeletedRecord?.tripKey
                }
            }
            .onChange(of: selectedFilter) { _, _ in
                removeUnavailableComparisonTrips()
            }
            .onChange(of: tripSearchText) { _, _ in
                removeUnavailableComparisonTrips()
            }
        }
    }

    // MARK: - Header

    @ViewBuilder
    private func header(for bbVehicle: BBVehicle) -> some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Trips")
                    .font(.gbDisplay)

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
                        HStack(spacing: 4) {
                            Text(bbVehicle.displayName)
                            Image(systemName: "chevron.down")
                                .font(.caption2)
                        }
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                } else {
                    Text(bbVehicle.displayName)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                }

                if !trips.isEmpty {
                    Label(
                        "Saved locally",
                        systemImage: "checkmark.icloud.fill"
                    )
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                }
            }

            Spacer()

            if savedTrips.count >= 2 {
                Button {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.86)) {
                        comparisonMode.toggle()
                        comparedTripIDs = []
                        expandedTripId = nil
                    }
                } label: {
                    Image(systemName: comparisonMode ? "xmark" : "arrow.left.arrow.right")
                        .foregroundStyle(comparisonMode ? .orange : .primary)
                        .frame(width: 42, height: 42)
                        .gbGlassCircle()
                }
                .buttonStyle(.plain)
                .accessibilityLabel(comparisonMode ? "Cancel trip comparison" : "Compare trips")
            }

            Menu {
                Button {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.86)) {
                        detailedMode.toggle()
                    }
                } label: {
                    Label(
                        detailedMode ? "Use Compact View" : "Use Detailed View",
                        systemImage: detailedMode
                            ? "rectangle.compress.vertical"
                            : "rectangle.expand.vertical"
                    )
                }

                Button {
                    exportingTrips = true
                } label: {
                    Label(
                        "Export \(savedTrips.count) Filtered Trips",
                        systemImage: "square.and.arrow.up"
                    )
                }
                .disabled(savedTrips.isEmpty)
            } label: {
                Image(systemName: "ellipsis")
                    .fontWeight(.semibold)
                    .frame(width: 42, height: 42)
                    .gbGlassCircle()
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Trip display and export options")

            if isLoading, !trips.isEmpty {
                BrandProgressView()
            }
        }
        .padding(.top, 8)
    }

    // MARK: - Filters

    private var tripFilterBar: some View {
        HStack(spacing: 8) {
            ForEach(TripDateFilter.allCases) { filter in
                Button {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                        selectedFilter = filter
                        expandedTripId = nil
                    }
                } label: {
                    Text(filter.title)
                        .font(.caption.weight(.bold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .background(
                            selectedFilter == filter
                                ? Color.accentColor.opacity(0.28)
                                : Color.primary.opacity(0.05),
                            in: Capsule()
                        )
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selectedFilter == filter ? .isSelected : [])
            }
        }
        .padding(8)
        .gbGlassCard(radius: GBDesign.tileRadius)
    }

    private var tripHistoryControls: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(.secondary)
                    TextField("Search saved trips", text: $tripSearchText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if !tripSearchText.isEmpty {
                        Button {
                            tripSearchText = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 12)
                .frame(height: 42)
                .background(Color.primary.opacity(0.07), in: Capsule())

                Menu {
                    Picker("Sort trips", selection: $tripSort) {
                        ForEach(TripSortOption.allCases) { option in
                            Label(option.title, systemImage: option.systemImage)
                                .tag(option)
                        }
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                        .frame(width: 42, height: 42)
                        .gbGlassCircle()
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Sort saved trips")
            }

            Toggle(isOn: $appSettings.showShortTrips) {
                Label(
                    "Show zero-distance and very short trips",
                    systemImage: "point.3.filled.connected.trianglepath.dotted"
                )
                .font(.caption)
            }
            .tint(appSettings.accentColor)
        }
        .padding(12)
        .gbGlassCard(radius: GBDesign.tileRadius)
    }

    private var noMatchingTripsCard: some View {
        ContentUnavailableView(
            "No Matching Trips",
            systemImage: "magnifyingglass",
            description: Text("Try another search or enable short trips.")
        )
        .frame(maxWidth: .infinity, minHeight: 150)
        .gbGlassCard()
    }

    private func cachedTripsNotice(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "icloud.slash.fill")
                .foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 2) {
                Text("Showing saved trips")
                    .font(.caption.bold())
                Text(message)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(12)
        .gbGlassCard(radius: GBDesign.tileRadius)
        .accessibilityElement(children: .combine)
    }

    private var tripComparisonToolbar: some View {
        HStack(spacing: 10) {
            Image(systemName: "checklist")
                .foregroundStyle(.cyan)

            VStack(alignment: .leading, spacing: 2) {
                Text("Select two trips")
                    .font(.subheadline.bold())
                Text("\(comparedTripIDs.count) of 2 selected")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button("Cancel") {
                withAnimation {
                    comparisonMode = false
                    comparedTripIDs = []
                }
            }
            .font(.caption.bold())

            Button("Compare") {
                showingTripComparison = true
            }
            .font(.caption.bold())
            .buttonStyle(.borderedProminent)
            .disabled(comparedTripIDs.count != 2)
        }
        .padding(12)
        .gbGlassCard(radius: GBDesign.tileRadius)
        .accessibilityElement(children: .contain)
    }

    private func collapsibleTripsSection<Content: View>(
        title: String,
        systemImage: String,
        isExpanded: Binding<Bool>,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(spacing: 10) {
            Button {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.88)) {
                    isExpanded.wrappedValue.toggle()
                }
            } label: {
                HStack {
                    Label(title, systemImage: systemImage)
                        .font(.subheadline.bold())
                    Spacer()
                    Text(isExpanded.wrappedValue ? "Hide" : "Show")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Image(systemName: "chevron.down")
                        .font(.caption.bold())
                        .rotationEffect(.degrees(isExpanded.wrappedValue ? 180 : 0))
                }
                .padding(14)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .gbGlassCard(radius: GBDesign.tileRadius, interactive: true)
            .accessibilityValue(isExpanded.wrappedValue ? "Expanded" : "Collapsed")

            if isExpanded.wrappedValue {
                content()
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private func tripGroupHeader(_ day: Date) -> some View {
        Text(tripGroupTitle(day))
            .font(.caption.weight(.heavy))
            .textCase(.uppercase)
            .tracking(1.4)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 6)
    }

    private func tripGroupTitle(_ day: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(day) { return "Today" }
        if calendar.isDateInYesterday(day) { return "Yesterday" }
        return day.formatted(.dateTime.weekday(.wide).month(.wide).day())
    }

    // MARK: - Summary

    private func overviewCard(for bbVehicle: BBVehicle) -> some View {
        let extremes = TripAnalytics.extremes(in: filteredTrips)
        let estimatedCost = TripAnalytics.electricityCost(
            for: filteredTrips,
            ratePerKWh: bbVehicle.electricityRatePerKWh
        )

        return VStack(spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Average efficiency")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(averageEfficiency.map { String(format: "%.1f", $0) } ?? "--")
                            .font(.system(size: 38, weight: .heavy, design: .rounded))
                        Text(efficiencyUnitLabel)
                            .font(.subheadline.bold())
                            .foregroundStyle(.secondary)
                    }
                    Text("Distance traveled for each kilowatt-hour used.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 4) {
                    Text("\(filteredTrips.count) trips")
                    Text(Distance.Units.miles.format(totalDistanceMiles, to: appSettings.preferredDistanceUnit))
                    Text(String(format: "%.1f kWh", totalEnergyKWh))
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            }

            if let extremes {
                HStack(spacing: 10) {
                    efficiencyInsight(
                        title: "Best trip",
                        trip: extremes.best,
                        systemImage: "arrow.up.right",
                        tint: .green
                    )
                    efficiencyInsight(
                        title: "Worst trip",
                        trip: extremes.worst,
                        systemImage: "arrow.down.right",
                        tint: .orange
                    )
                }

                Label(
                    "Best and worst compare efficiency within the selected date range.",
                    systemImage: "info.circle"
                )
                .font(.caption2)
                .foregroundStyle(.secondary)
            }

            Divider()

            Button {
                showingCostSettings = true
            } label: {
                HStack {
                    Label("Estimated cost", systemImage: "dollarsign.circle.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    Spacer()
                    Text(
                        estimatedCost.map {
                            $0.formatted(.currency(code: Locale.current.currency?.identifier ?? "USD"))
                        } ?? "Set rate"
                    )
                    .font(.subheadline.bold())
                    .foregroundStyle(estimatedCost == nil ? appSettings.accentColor : .green)
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(16)
        .gbGlassCard(radius: GBDesign.heroRadius)
    }

    private var totalDistanceMiles: Double {
        filteredTrips.reduce(0) { $0 + $1.distance }
    }
    private var totalEnergyKWh: Double {
        filteredTrips.reduce(0) { $0 + TripChartData.totalEnergyKWh(for: $1) }
    }
    private var totalRegenKWh: Double {
        Double(filteredTrips.reduce(0) { $0 + max(0, $1.regenEnergy) }) / 1000.0
    }
    private var totalDurationSeconds: Int {
        filteredTrips.reduce(0) { $0 + $1.durationSeconds }
    }

    /// Average efficiency in the user's preferred distance unit per kWh
    private var averageEfficiency: Double? {
        guard totalEnergyKWh > 0 else { return nil }
        let distance = appSettings.preferredDistanceUnit == .kilometers
            ? totalDistanceMiles * 1.60934
            : totalDistanceMiles
        return distance / totalEnergyKWh
    }

    private var efficiencyUnitLabel: String {
        appSettings.preferredDistanceUnit == .kilometers ? "km/kWh" : "mi/kWh"
    }

    private var summaryHero: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Efficiency", systemImage: "leaf.fill")
                .font(.system(size: 12, weight: .heavy))
                .textCase(.uppercase)
                .foregroundStyle(.secondary)

            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(averageEfficiency.map { String(format: "%.1f", $0) } ?? "--")
                    .font(.gbHero)
                    .foregroundStyle(.green)
                Text(efficiencyUnitLabel)
                    .font(.gbHeroUnit)
                    .foregroundStyle(.secondary)

                Spacer()
            }

            Text(summaryScopeText)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .gbGlassCard(radius: GBDesign.heroRadius)
    }

    private var statTiles: some View {
        let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

        return LazyVGrid(columns: columns, spacing: 10) {
            GBStatTile(
                systemImage: "point.bottomleft.forward.to.point.topright.scurvepath",
                label: "Distance",
                value: Distance.Units.miles.format(totalDistanceMiles, to: appSettings.preferredDistanceUnit)
            )
            GBStatTile(
                systemImage: "clock.fill",
                label: "Drive time",
                value: Duration.seconds(totalDurationSeconds)
                    .formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
            )
            GBStatTile(
                systemImage: "bolt.fill",
                label: "Energy used",
                value: String(format: "%.1f kWh", totalEnergyKWh),
                tint: .orange
            )
            GBStatTile(
                systemImage: "arrow.3.trianglepath",
                label: "Regen recovered",
                value: String(format: "%.1f kWh", totalRegenKWh),
                tint: .green
            )
        }
    }

    private var summaryScopeText: String {
        if selectedFilter == .all {
            return "Average across all \(filteredTrips.count) saved trips"
        }
        return "Average across \(filteredTrips.count) trips from the last \(selectedFilter.title)"
    }

    // MARK: - Insights

    private func insightsCard(for bbVehicle: BBVehicle) -> some View {
        let extremes = TripAnalytics.extremes(in: filteredTrips)
        let estimatedCost = TripAnalytics.electricityCost(
            for: filteredTrips,
            ratePerKWh: bbVehicle.electricityRatePerKWh
        )

        return VStack(spacing: 14) {
            if let extremes {
                HStack(spacing: 10) {
                    efficiencyInsight(
                        title: "Best trip",
                        trip: extremes.best,
                        systemImage: "arrow.up.right",
                        tint: .green
                    )
                    efficiencyInsight(
                        title: "Worst trip",
                        trip: extremes.worst,
                        systemImage: "arrow.down.right",
                        tint: .orange
                    )
                }
            }

            Divider()

            Button {
                showingCostSettings = true
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "dollarsign.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.green)

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Estimated electricity cost")
                            .font(.subheadline.weight(.semibold))
                        Text(
                            bbVehicle.electricityRatePerKWh == nil
                                ? "Add your electricity price to calculate trip costs"
                                : String(
                                    format: "%.1f kWh at %.3f/kWh",
                                    totalEnergyKWh,
                                    bbVehicle.electricityRatePerKWh ?? 0
                                )
                        )
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }

                    Spacer()

                    if let estimatedCost {
                        Text(
                            estimatedCost,
                            format: .currency(
                                code: Locale.current.currency?.identifier ?? "USD"
                            )
                        )
                        .font(.title3.bold())
                        .foregroundStyle(.green)
                    } else {
                        Text("Set rate")
                            .font(.caption.bold())
                            .foregroundStyle(.blue)
                    }

                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(16)
        .gbGlassCard()
    }

    private func efficiencyInsight(
        title: String,
        trip: EVTripDetail,
        systemImage: String,
        tint: Color
    ) -> some View {
        let milesEfficiency = TripAnalytics.efficiencyMilesPerKWh(for: trip) ?? 0
        let efficiency = appSettings.preferredDistanceUnit == .kilometers
            ? milesEfficiency * 1.60934
            : milesEfficiency

        return VStack(alignment: .leading, spacing: 7) {
            Label(title, systemImage: systemImage)
                .font(.caption.bold())
                .foregroundStyle(tint)

            Text(
                "\(efficiency.formatted(.number.precision(.fractionLength(1)))) \(efficiencyUnitLabel)"
            )
            .font(.headline.bold())

            Text(
                trip.startDate,
                format: .dateTime.month(.abbreviated).day()
            )
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.primary.opacity(0.055), in: RoundedRectangle(cornerRadius: 15))
    }

    // MARK: - Trends

    private var trendPoints: [TripEfficiencyTrendPoint] {
        TripAnalytics.trendPoints(from: filteredTrips, period: trendPeriod)
    }

    private func displayedEfficiency(_ point: TripEfficiencyTrendPoint) -> Double {
        appSettings.preferredDistanceUnit == .kilometers
            ? point.efficiencyMilesPerKWh * 1.60934
            : point.efficiencyMilesPerKWh
    }

    private var trendCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Picker("Trend period", selection: $trendPeriod) {
                    ForEach(TripTrendPeriod.allCases) { period in
                        Text(period.title).tag(period)
                    }
                }
                .pickerStyle(.segmented)

                if let change = TripAnalytics.percentageChange(in: trendPoints) {
                    Label(
                        "\(abs(change).formatted(.number.precision(.fractionLength(0))))%",
                        systemImage: change >= 0 ? "arrow.up.right" : "arrow.down.right"
                    )
                    .font(.caption.bold())
                    .foregroundStyle(change >= 0 ? .green : .orange)
                }
            }

            if trendPoints.isEmpty {
                ContentUnavailableView(
                    "Not Enough Data",
                    systemImage: "chart.xyaxis.line",
                    description: Text("Drive history will build this trend over time.")
                )
                .frame(maxWidth: .infinity, minHeight: 180)
            } else if let point = trendPoints.first, trendPoints.count == 1 {
                singlePeriodTrend(point)
            } else {
                Chart(trendPoints) { point in
                    AreaMark(
                        x: .value("Period", point.periodStart),
                        y: .value("Efficiency", displayedEfficiency(point))
                    )
                    .foregroundStyle(
                        LinearGradient(
                            colors: [.green.opacity(0.32), .green.opacity(0.02)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )

                    LineMark(
                        x: .value("Period", point.periodStart),
                        y: .value("Efficiency", displayedEfficiency(point))
                    )
                    .foregroundStyle(.green)
                    .lineStyle(StrokeStyle(lineWidth: 3, lineCap: .round))

                    PointMark(
                        x: .value("Period", point.periodStart),
                        y: .value("Efficiency", displayedEfficiency(point))
                    )
                    .foregroundStyle(.green)
                    .symbolSize(42)
                    .accessibilityLabel(
                        point.periodStart.formatted(
                            trendPeriod == .monthly
                                ? .dateTime.month(.wide).year()
                                : .dateTime.month(.wide).day().year()
                        )
                    )
                    .accessibilityValue(
                        "\(displayedEfficiency(point).formatted(.number.precision(.fractionLength(1)))) \(efficiencyUnitLabel)"
                    )
                }
                .chartYAxis {
                    AxisMarks(position: .leading) { value in
                        AxisGridLine()
                        AxisValueLabel {
                            if let efficiency = value.as(Double.self) {
                                Text(efficiency.formatted(.number.precision(.fractionLength(1))))
                            }
                        }
                    }
                }
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: 4)) { value in
                        AxisGridLine()
                        AxisValueLabel(format: trendPeriod == .monthly
                            ? .dateTime.month(.abbreviated)
                            : .dateTime.month(.abbreviated).day())
                    }
                }
                .chartYAxisLabel(efficiencyUnitLabel, position: .leading)
                .chartXSelection(value: $selectedTrendDate)
                .frame(height: 190)
                .accessibilityLabel("Efficiency trend")
                .accessibilityHint("Shows average efficiency by \(trendPeriod.title.lowercased()) period.")

                if let selected = selectedTrendPoint {
                    HStack {
                        Text(selected.periodStart, format: .dateTime.month(.abbreviated).day())
                        Spacer()
                        Text(
                            "\(displayedEfficiency(selected).formatted(.number.precision(.fractionLength(1)))) \(efficiencyUnitLabel)"
                        )
                        .foregroundStyle(.green)
                    }
                    .font(.caption.weight(.semibold))
                }
            }
        }
        .padding(16)
        .gbGlassCard()
    }

    private func singlePeriodTrend(_ point: TripEfficiencyTrendPoint) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "chart.line.uptrend.xyaxis")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(.green)

            Text(singlePeriodTitle)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            Text(
                "\(displayedEfficiency(point).formatted(.number.precision(.fractionLength(1)))) \(efficiencyUnitLabel)"
            )
            .font(.title2.bold())

            Text(
                "A trend will appear after trips are available in at least two \(trendPeriod.pluralTitle.lowercased())."
            )
            .font(.caption)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, minHeight: 160)
        .accessibilityElement(children: .combine)
    }

    private var singlePeriodTitle: String {
        switch trendPeriod {
        case .daily: "Current day"
        case .weekly: "Current week"
        case .monthly: "Current month"
        }
    }

    private var selectedTrendPoint: TripEfficiencyTrendPoint? {
        guard let selectedTrendDate else { return nil }
        return trendPoints.min {
            abs($0.periodStart.timeIntervalSince(selectedTrendDate))
                < abs($1.periodStart.timeIntervalSince(selectedTrendDate))
        }
    }

    // MARK: - Chart

    /// Keep the graph readable as the local archive grows. The archive is
    /// newest-first, while charts read naturally oldest-to-newest.
    private var chartTrips: [(index: Int, trip: EVTripDetail)] {
        TripChartData.indexedTrips(from: filteredTrips)
    }

    private var totalChartData: [TripTotalEnergyPoint] {
        TripChartData.totalPoints(from: filteredTrips)
    }

    private var hasChartEnergy: Bool {
        totalChartData.contains { $0.energyKWh > 0 }
    }

    private func axisLabel(forTripAt index: Int) -> String {
        guard index >= 0, index < chartTrips.count else { return "" }
        return chartTrips[index].trip.startDate
            .formatted(.dateTime.month(.defaultDigits).day())
    }

    private var chartCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(showEnergyBreakdown ? "By system" : "Total per trip")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Toggle("Breakdown", isOn: $showEnergyBreakdown.animation(.easeInOut(duration: 0.3)))
                    .toggleStyle(.button)
                    .buttonStyle(.bordered)
                    .font(.caption)
                    .disabled(!hasChartEnergy)
            }

            if hasChartEnergy {
                Group {
                    if showEnergyBreakdown {
                        breakdownChart
                            .transition(.opacity.combined(with: .scale(scale: 0.97)))
                    } else {
                        totalChart
                            .transition(.opacity.combined(with: .scale(scale: 0.97)))
                    }
                }
                .frame(height: 190)
                .animation(.spring(response: 0.45, dampingFraction: 0.86), value: showEnergyBreakdown)

                if let selected = selectedEnergyPoint {
                    HStack {
                        Text(selected.startDate, format: .dateTime.month(.abbreviated).day().hour().minute())
                        Spacer()
                        Text(String(format: "%.2f kWh", selected.energyKWh))
                            .foregroundStyle(.orange)
                    }
                    .font(.caption.weight(.semibold))
                    .transition(.opacity)
                }
            } else {
                ContentUnavailableView(
                    "No Energy Data",
                    systemImage: "chart.bar.xaxis",
                    description: Text("These trips do not include usable energy readings yet.")
                )
                .frame(maxWidth: .infinity, minHeight: 190)
            }

            if filteredTrips.count > TripChartData.maximumTripCount {
                Text("Showing the latest \(TripChartData.maximumTripCount) trips")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(16)
        .gbGlassCard()
    }

    private var totalChart: some View {
        Chart(totalChartData) { item in
            BarMark(
                // A String creates a categorical x-axis. Using the numeric
                // index with a ratio width creates a continuous scale whose
                // inferred bar width can collapse to zero.
                x: .value("Trip", item.slot),
                y: .value("Energy", item.energyKWh),
                width: .ratio(0.6)
            )
            .foregroundStyle(
                LinearGradient(colors: [.orange, .orange.opacity(0.5)], startPoint: .top, endPoint: .bottom)
            )
            .opacity(selectedEnergySlot == nil || selectedEnergySlot == item.slot ? 1 : 0.38)
            .cornerRadius(5)
            .accessibilityLabel(item.startDate.formatted(date: .abbreviated, time: .shortened))
            .accessibilityValue("\(item.energyKWh.formatted(.number.precision(.fractionLength(1)))) kilowatt-hours")
        }
        .chartXScale(domain: chartTrips.map { String($0.index) })
        .chartYScale(domain: 0...TripChartData.yAxisMaximum(for: totalChartData.map(\.energyKWh)))
        .chartYAxis {
            AxisMarks(position: .leading) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let energy = value.as(Double.self) {
                        Text(String(format: "%.1f", energy))
                    }
                }
            }
        }
        .chartXAxis {
            AxisMarks(values: TripChartData.axisSlots(for: chartTrips.count)) { value in
                AxisValueLabel {
                    if let slot = value.as(String.self), let index = Int(slot) {
                        Text(axisLabel(forTripAt: index))
                            .font(.caption2)
                    }
                }
            }
        }
        .chartYAxisLabel("kWh", position: .leading)
        .chartXSelection(value: $selectedEnergySlot)
    }

    private var breakdownChart: some View {
        Chart(breakdownData) { dataPoint in
            BarMark(
                x: .value("Trip", dataPoint.slot),
                y: .value("Energy", dataPoint.energy),
                width: .ratio(0.6)
            )
            .foregroundStyle(by: .value("Category", dataPoint.category))
            .opacity(selectedEnergySlot == nil || selectedEnergySlot == dataPoint.slot ? 1 : 0.38)
            .cornerRadius(5)
            .accessibilityLabel("\(axisLabel(forTripAt: dataPoint.tripIndex)), \(dataPoint.category)")
            .accessibilityValue("\(dataPoint.energy.formatted(.number.precision(.fractionLength(1)))) kilowatt-hours")
        }
        .chartForegroundStyleScale([
            "Drivetrain": Color.orange,
            "Climate": Color.blue,
            "Accessories": Color.purple,
            "Battery Care": Color.cyan
        ])
        .chartXScale(domain: chartTrips.map { String($0.index) })
        .chartYScale(domain: 0...TripChartData.breakdownYAxisMaximum(for: breakdownData))
        .chartYAxis {
            AxisMarks(position: .leading) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let energy = value.as(Double.self) {
                        Text(String(format: "%.1f", energy))
                    }
                }
            }
        }
        .chartXAxis {
            AxisMarks(values: TripChartData.axisSlots(for: chartTrips.count)) { value in
                AxisValueLabel {
                    if let slot = value.as(String.self), let index = Int(slot) {
                        Text(axisLabel(forTripAt: index))
                            .font(.caption2)
                    }
                }
            }
        }
        .chartYAxisLabel("kWh", position: .leading)
        .chartLegend(position: .bottom, spacing: 8)
        .chartXSelection(value: $selectedEnergySlot)
    }

    private var breakdownData: [TripEnergyPoint] {
        TripChartData.breakdownPoints(from: filteredTrips)
    }

    private var selectedEnergyPoint: TripTotalEnergyPoint? {
        guard let selectedEnergySlot else { return nil }
        return totalChartData.first { $0.slot == selectedEnergySlot }
    }

    // MARK: - States

    private func unsupportedCard(for bbVehicle: BBVehicle) -> some View {
        stateCard(
            systemImage: "road.lanes",
            title: "Trips not available",
            message: bbVehicle.fuelType.hasElectricCapability
                ? "\(bbVehicle.account?.brandEnum.displayName ?? "This brand") doesn't provide trip history in your region yet."
                : "Trip history is currently available for electric vehicles only."
        )
    }

    private var loadingCard: some View {
        VStack(spacing: 12) {
            HStack {
                BrandProgressView()
                Text("Loading saved trips…")
                    .font(.subheadline.weight(.semibold))
                Spacer()
            }
            .padding(.horizontal, 4)

            ForEach(0..<3, id: \.self) { _ in
                GBSkeletonCard()
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Loading saved trips")
    }

    private var emptyCard: some View {
        stateCard(
            systemImage: "car.fill",
            title: "No recent trips",
            message: "Drive your vehicle and your trips will show up here."
        )
    }

    private var filteredEmptyCard: some View {
        VStack(spacing: 12) {
            Image(systemName: "line.3.horizontal.decrease.circle")
                .font(.system(size: 38, weight: .semibold))
                .foregroundStyle(.secondary)
            Text("No trips in this range")
                .font(.headline.bold())
            Text("Choose a longer time range to see older saved trips.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("Show all trips") {
                withAnimation {
                    selectedFilter = .all
                }
            }
            .buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 44)
        .padding(.horizontal, 20)
        .gbGlassCard()
    }

    private func errorCard(_ error: ActionError) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.largeTitle)
                .foregroundStyle(.orange)

            ErrorDetailsView(error: error)

            Button("Try Again") {
                guard let bbVehicle else { return }
                Task { await loadTrips(for: bbVehicle) }
            }
            .buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .gbGlassCard()
    }

    private func stateCard(systemImage: String, title: String, message: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 40, weight: .semibold))
                .foregroundStyle(.secondary)
                .padding(.bottom, 4)

            Text(title)
                .font(.headline.weight(.bold))

            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
        .padding(.horizontal, 20)
        .gbGlassCard()
    }

    // MARK: - Loading

    private func archivedRecord(for trip: EVTripDetail) -> TripRecord? {
        guard let bbVehicle else { return nil }
        let key = TripRecord.key(vin: bbVehicle.vin, trip: trip)
        return recordsByKey[key]
    }

    private func rebuildTripIndex() {
        recordsByKey = Dictionary(archivedTripRecords.map { ($0.tripKey, $0) },
                                  uniquingKeysWith: { first, _ in first })
    }

    private func beginRenaming(_ record: TripRecord?) {
        guard let record else { return }
        renameText = record.customName ?? ""
        recordToRename = record
    }

    private func saveTripName() {
        guard let record = recordToRename else { return }
        let trimmed = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
        record.customName = trimmed.isEmpty ? nil : trimmed
        try? modelContext.save()
        recordToRename = nil
    }

    private func toggleFavorite(_ record: TripRecord?) {
        guard let record else { return }
        record.isFavorite.toggle()
        try? modelContext.save()
    }

    private func toggleTripComparisonSelection(_ trip: EVTripDetail) {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.86)) {
            if let index = comparedTripIDs.firstIndex(of: trip.id) {
                comparedTripIDs.remove(at: index)
            } else {
                if comparedTripIDs.count == 2 {
                    comparedTripIDs.removeFirst()
                }
                comparedTripIDs.append(trip.id)
            }
        }
    }

    private func removeUnavailableComparisonTrips() {
        let availableIDs = Set(savedTrips.map(\.id))
        comparedTripIDs.removeAll { !availableIDs.contains($0) }
    }

    private func deleteTrip(_ record: TripRecord?) {
        guard let record, let bbVehicle else { return }
        commitPendingDeletion()

        withAnimation {
            pendingDeletedRecord = record
            if let trip = trips.first(where: {
                TripRecord.key(vin: bbVehicle.vin, trip: $0) == record.tripKey
            }) {
                comparedTripIDs.removeAll { $0 == trip.id }
            }
            trips.removeAll {
                TripRecord.key(vin: bbVehicle.vin, trip: $0) == record.tripKey
            }
        }

        deletionTask = Task {
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                commitPendingDeletion()
            }
        }
    }

    private func undoTripDeletion() {
        deletionTask?.cancel()
        deletionTask = nil
        pendingDeletedRecord = nil
        if let bbVehicle {
            withAnimation {
                trips = archivedTrips(for: bbVehicle)
            }
        }
    }

    private func commitPendingDeletion() {
        deletionTask?.cancel()
        deletionTask = nil
        guard let record = pendingDeletedRecord else { return }
        modelContext.delete(record)
        try? modelContext.save()
        pendingDeletedRecord = nil
    }

    @MainActor
    private func loadTrips(for bbVehicle: BBVehicle) async {
        guard bbVehicle.account?.supportsEVTripDetails == true else { return }

        isLoading = true
        loadError = nil
        cachedDataNotice = nil

        guard let account = bbVehicle.account else {
            loadError = ActionError(
                action: "Load trip history",
                error: APIError(message: "Vehicle account not found")
            )
            isLoading = false
            return
        }

        do {
            if try await account.fetchEVTripDetails(for: bbVehicle, modelContext: modelContext) != nil {
                rebuildTripIndex()
                withAnimation(.spring(response: 0.42, dampingFraction: 0.88)) {
                    trips = archivedTrips(for: bbVehicle)
                }
            } else {
                // Keep cached history visible when the upstream endpoint has
                // no new window to return.
                trips = archivedTrips(for: bbVehicle)
            }
        } catch {
            BBLogger.error(.api, "TripsView: Failed to fetch trip details: \(error)")
            trips = archivedTrips(for: bbVehicle)
            if trips.isEmpty {
                loadError = ActionError(
                    action: "Load trip history",
                    error: error,
                    accountId: account.id
                )
            } else {
                let age = bbVehicle.lastTripArchiveAttemptDate?
                    .formatted(.relative(presentation: .named)) ?? "an earlier refresh"
                cachedDataNotice = "The vehicle service is unavailable. This history was saved \(age)."
            }
        }

        isLoading = false
    }
}

// MARK: - Trip Presentation Models

private enum TripSortOption: String, CaseIterable, Identifiable {
    case newest
    case oldest
    case distance
    case efficiency
    case energy

    var id: String { rawValue }

    var title: String {
        switch self {
        case .newest: "Newest first"
        case .oldest: "Oldest first"
        case .distance: "Longest distance"
        case .efficiency: "Best efficiency"
        case .energy: "Most energy"
        }
    }

    var systemImage: String {
        switch self {
        case .newest: "calendar.badge.clock"
        case .oldest: "calendar"
        case .distance: "road.lanes"
        case .efficiency: "leaf.fill"
        case .energy: "bolt.fill"
        }
    }
}

private struct TripHistoryGroup: Identifiable {
    let day: Date
    let trips: [EVTripDetail]
    var id: Date { day }
}

// MARK: - Trip Energy Point

/// One stacked-bar segment for the breakdown chart, keyed by trip index so
/// trips with identical timestamps never merge into a single bar.
struct TripEnergyPoint: Identifiable, Equatable {
    let id: String
    let tripIndex: Int
    let category: String
    let energy: Double

    var slot: String { String(tripIndex) }
}

struct TripTotalEnergyPoint: Identifiable, Equatable {
    let id: String
    let index: Int
    let startDate: Date
    let energyKWh: Double

    var slot: String { String(index) }
}

/// Converts imperfect API readings into stable, visible chart values. Some
/// regions omit `totalused` while still returning the component readings;
/// those trips now use the component sum instead of rendering an empty bar.
enum TripChartData {
    static let maximumTripCount = 12

    static func indexedTrips(
        from trips: [EVTripDetail],
        limit: Int = maximumTripCount
    ) -> [(index: Int, trip: EVTripDetail)] {
        trips
            .prefix(max(0, limit))
            .reversed()
            .enumerated()
            .map { (index: $0.offset, trip: $0.element) }
    }

    static func totalEnergyKWh(for trip: EVTripDetail) -> Double {
        TripAnalytics.energyKWh(for: trip)
    }

    static func totalPoints(from trips: [EVTripDetail]) -> [TripTotalEnergyPoint] {
        indexedTrips(from: trips).map { item in
            TripTotalEnergyPoint(
                id: item.trip.id,
                index: item.index,
                startDate: item.trip.startDate,
                energyKWh: totalEnergyKWh(for: item.trip)
            )
        }
    }

    static func breakdownPoints(from trips: [EVTripDetail]) -> [TripEnergyPoint] {
        indexedTrips(from: trips).flatMap { item -> [TripEnergyPoint] in
            let values: [(String, Int)] = [
                ("Drivetrain", item.trip.drivetrainEnergy),
                ("Climate", item.trip.climateEnergy),
                ("Accessories", item.trip.accessoriesEnergy),
                ("Battery Care", item.trip.batteryCareEnergy)
            ]

            return values.compactMap { category, energy in
                let energyKWh = positiveKWh(energy)
                guard energyKWh > 0 else { return nil }
                return TripEnergyPoint(
                    id: "\(item.trip.id)-\(category)",
                    tripIndex: item.index,
                    category: category,
                    energy: energyKWh
                )
            }
        }
    }

    static func axisIndices(for count: Int, maximumLabelCount: Int = 6) -> [Int] {
        guard count > 0 else { return [] }
        guard count > maximumLabelCount else { return Array(0..<count) }

        let stride = max(1, Int(ceil(Double(count - 1) / Double(maximumLabelCount - 1))))
        var indices = Array(Swift.stride(from: 0, to: count, by: stride))
        if indices.last != count - 1 {
            indices.append(count - 1)
        }
        return indices
    }

    static func axisSlots(for count: Int, maximumLabelCount: Int = 6) -> [String] {
        axisIndices(for: count, maximumLabelCount: maximumLabelCount).map(String.init)
    }

    static func yAxisMaximum(for values: [Double]) -> Double {
        max(1, (values.max() ?? 0) * 1.15)
    }

    static func breakdownYAxisMaximum(for points: [TripEnergyPoint]) -> Double {
        let totals = Dictionary(grouping: points, by: \.tripIndex)
            .values
            .map { $0.reduce(0) { $0 + $1.energy } }
        return yAxisMaximum(for: totals)
    }

    private static func positiveKWh(_ wattHours: Int) -> Double {
        Double(max(0, wattHours)) / 1000.0
    }
}

// MARK: - Trip Card

/// One trip as an expandable Liquid Glass card: date, distance, and key
/// numbers up front; energy breakdown and speeds on tap.
struct TripCard: View {
    let trip: EVTripDetail
    let record: TripRecord?
    let distanceUnit: Distance.Units
    let isExpanded: Bool
    let comparisonMode: Bool
    let isComparisonSelected: Bool
    let hideLocation: Bool
    let onTap: () -> Void
    let onRename: () -> Void
    let onFavorite: () -> Void
    let onDelete: () -> Void

    private var formattedDistance: String {
        Distance.Units.miles.format(trip.distance, to: distanceUnit)
    }

    private var formattedEfficiency: String {
        let value = distanceUnit == .kilometers ? trip.efficiency * 1.60934 : trip.efficiency
        let unit = distanceUnit == .kilometers ? "km/kWh" : "mi/kWh"
        return String(format: "%.1f %@", value, unit)
    }

    private var formattedEnergy: String {
        let energyKWh = TripChartData.totalEnergyKWh(for: trip)
        if energyKWh >= 1 {
            return String(format: "%.1f kWh", energyKWh)
        } else {
            return "\(Int((energyKWh * 1000).rounded())) Wh"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    if comparisonMode {
                        Image(
                            systemName: isComparisonSelected
                                ? "checkmark.circle.fill"
                                : "circle"
                        )
                        .font(.title3)
                        .foregroundStyle(isComparisonSelected ? .cyan : .secondary)
                        .accessibilityHidden(true)
                    }

                    VStack(alignment: .leading, spacing: 2) {
                        if let name = record?.customName {
                            Text(name)
                                .font(.subheadline.weight(.bold))
                        }
                        Text(
                            trip.startDate,
                            format: .dateTime.weekday(.abbreviated).month(.abbreviated).day().hour().minute()
                        )
                        .font(record?.customName == nil ? .subheadline.weight(.semibold) : .caption)
                        .foregroundStyle(record?.customName == nil ? .primary : .secondary)
                    }

                    Spacer()

                    if record?.isFavorite == true {
                        Image(systemName: "star.fill")
                            .font(.caption)
                            .foregroundStyle(.yellow)
                    }

                    Text(formattedDistance)
                        .font(.title3.weight(.bold))

                    if !comparisonMode {
                        Menu {
                            Button(action: onFavorite) {
                                Label(
                                    record?.isFavorite == true ? "Remove Favorite" : "Favorite",
                                    systemImage: record?.isFavorite == true ? "star.slash" : "star"
                                )
                            }
                            Button(action: onRename) {
                                Label("Rename", systemImage: "pencil")
                            }
                            Button(role: .destructive, action: onDelete) {
                                Label("Delete", systemImage: "trash")
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                                .font(.body)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                HStack(spacing: 6) {
                    Label(trip.formattedDuration, systemImage: "clock")
                        .foregroundStyle(.secondary)

                    Text("·")
                        .foregroundStyle(.secondary)
                    Text(formattedEnergy)
                        .foregroundStyle(.orange)

                    Text("·")
                        .foregroundStyle(.secondary)
                    Text(formattedEfficiency)
                        .foregroundStyle(.green)

                    Spacer()

                    Image(systemName: "chevron.down")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .rotationEffect(.degrees(isExpanded ? 180 : 0))
                }
                .font(.caption.weight(.medium))

                if isExpanded {
                    VStack(alignment: .leading, spacing: 12) {
                        Divider()

                        if !hideLocation,
                           record?.startLocationName != nil || record?.endLocationName != nil {
                            HStack(spacing: 8) {
                                Image(systemName: "mappin.and.ellipse")
                                    .foregroundStyle(.blue)
                                Text(
                                    [record?.startLocationName, record?.endLocationName]
                                        .compactMap { $0 }
                                        .joined(separator: " → ")
                                )
                                .font(.caption)
                                .lineLimit(2)
                            }
                        }

                        if !hideLocation, let record, record.hasRouteCoordinates {
                            TripRouteThumbnail(record: record)
                        } else if hideLocation,
                                  record?.hasRouteCoordinates == true
                                    || record?.startLocationName != nil
                                    || record?.endLocationName != nil {
                            Label("Trip locations hidden", systemImage: "location.slash.fill")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        HStack(spacing: 8) {
                            EnergyBreakdownPill(label: "Drivetrain", value: trip.drivetrainEnergy, color: .orange)
                            EnergyBreakdownPill(label: "Regen", value: trip.regenEnergy, color: .green)
                            EnergyBreakdownPill(label: "Climate", value: trip.climateEnergy, color: .blue)
                        }

                        HStack(spacing: 8) {
                            EnergyBreakdownPill(label: "Accessories", value: trip.accessoriesEnergy, color: .purple)
                            if trip.batteryCareEnergy > 0 {
                                EnergyBreakdownPill(label: "Batt Care", value: trip.batteryCareEnergy, color: .cyan)
                            }
                            EnergyBreakdownSpeedPill(avgSpeed: trip.avgSpeed, maxSpeed: trip.maxSpeed, distanceUnit: distanceUnit)
                        }
                    }
                }
            }
            .padding(16)
            .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .gbGlassCard(radius: GBDesign.tileRadius, interactive: true)
        .overlay {
            RoundedRectangle(cornerRadius: GBDesign.tileRadius, style: .continuous)
                .stroke(isComparisonSelected ? Color.cyan : .clear, lineWidth: 2)
        }
        .accessibilityAddTraits(comparisonMode ? .isButton : [])
        .accessibilityAddTraits(isComparisonSelected ? .isSelected : [])
        .accessibilityHint(
            comparisonMode
                ? "Double tap to add or remove this trip from comparison."
                : "Double tap to show trip details."
        )
        // Avoid the zero-distance DragGesture used by bouncePress(). A trip
        // card fills almost the entire screen width, so that gesture could
        // win over the parent ScrollView and make Saved Trips feel stuck.
        .sensoryFeedback(.selection, trigger: isExpanded)
        .contextMenu {
            Button(action: onFavorite) {
                Label(
                    record?.isFavorite == true ? "Remove Favorite" : "Favorite",
                    systemImage: record?.isFavorite == true ? "star.slash" : "star"
                )
            }
            Button(action: onRename) {
                Label("Rename", systemImage: "pencil")
            }
            Button(role: .destructive, action: onDelete) {
                Label("Delete", systemImage: "trash")
            }
        }
    }
}

private extension TripRecord {
    var hasRouteCoordinates: Bool {
        startLatitude != nil
            && startLongitude != nil
            && endLatitude != nil
            && endLongitude != nil
    }
}

private struct TripRouteThumbnail: View {
    let record: TripRecord
    @State private var estimatedRoadRoute: MKRoute?

    private var start: CLLocationCoordinate2D? {
        guard let latitude = record.startLatitude,
              let longitude = record.startLongitude
        else { return nil }
        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    private var end: CLLocationCoordinate2D? {
        guard let latitude = record.endLatitude,
              let longitude = record.endLongitude
        else { return nil }
        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    private var region: MKCoordinateRegion {
        guard let start, let end else {
            return MKCoordinateRegion(
                center: CLLocationCoordinate2D(latitude: 0, longitude: 0),
                span: MKCoordinateSpan(latitudeDelta: 1, longitudeDelta: 1)
            )
        }
        return MKCoordinateRegion(
            center: CLLocationCoordinate2D(
                latitude: (start.latitude + end.latitude) / 2,
                longitude: (start.longitude + end.longitude) / 2
            ),
            span: MKCoordinateSpan(
                latitudeDelta: max(abs(start.latitude - end.latitude) * 1.55, 0.012),
                longitudeDelta: max(abs(start.longitude - end.longitude) * 1.55, 0.012)
            )
        )
    }

    var body: some View {
        if let start, let end {
            ZStack(alignment: .topLeading) {
                Map(initialPosition: .region(region), interactionModes: []) {
                    if let estimatedRoadRoute {
                        MapPolyline(estimatedRoadRoute.polyline)
                            .stroke(.blue, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    } else {
                        MapPolyline(coordinates: [start, end])
                            .stroke(
                                .blue,
                                style: StrokeStyle(lineWidth: 4, lineCap: .round, dash: [7, 5])
                            )
                    }

                    Annotation("Start", coordinate: start) {
                        Image(systemName: "circle.fill")
                            .font(.caption)
                            .foregroundStyle(.white, .green)
                            .padding(4)
                            .background(.green, in: Circle())
                    }

                    Annotation("Destination", coordinate: end) {
                        Image(systemName: "flag.checkered")
                            .font(.caption.bold())
                            .foregroundStyle(.white)
                            .padding(6)
                            .background(.blue, in: Circle())
                    }
                }
                .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))

                Text(estimatedRoadRoute == nil ? "Finding road route…" : "Estimated road route")
                    .font(.caption2.bold())
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(.ultraThinMaterial, in: Capsule())
                    .padding(8)
            }
            .frame(height: 118)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(.white.opacity(0.12))
            }
            .allowsHitTesting(false)
            .task(id: "\(start.latitude),\(start.longitude),\(end.latitude),\(end.longitude)") {
                let request = MKDirections.Request()
                request.source = MKMapItem(
                    location: CLLocation(
                        latitude: start.latitude,
                        longitude: start.longitude
                    ),
                    address: nil
                )
                request.destination = MKMapItem(
                    location: CLLocation(
                        latitude: end.latitude,
                        longitude: end.longitude
                    ),
                    address: nil
                )
                request.transportType = .automobile
                estimatedRoadRoute = try? await MKDirections(request: request)
                    .calculate()
                    .routes
                    .first
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(
                "Estimated road route from \(record.startLocationName ?? "start") to \(record.endLocationName ?? "destination")"
            )
        }
    }
}

struct TripCSVMetadata: Equatable {
    let customName: String?
    let isFavorite: Bool
    let startLocationName: String?
    let endLocationName: String?

    init(record: TripRecord, redactLocations: Bool = false) {
        customName = record.customName
        isFavorite = record.isFavorite
        startLocationName = redactLocations && record.startLocationName != nil
            ? "Private location"
            : record.startLocationName
        endLocationName = redactLocations && record.endLocationName != nil
            ? "Private location"
            : record.endLocationName
    }

    init(
        customName: String? = nil,
        isFavorite: Bool = false,
        startLocationName: String? = nil,
        endLocationName: String? = nil
    ) {
        self.customName = customName
        self.isFavorite = isFavorite
        self.startLocationName = startLocationName
        self.endLocationName = endLocationName
    }
}

enum TripCSVExporter {
    static func csv(
        trips: [EVTripDetail],
        vin: String,
        metadata: [String: TripCSVMetadata],
        ratePerKWh: Double?
    ) -> String {
        let header = [
            "Date",
            "Name",
            "Favorite",
            "Distance (mi)",
            "Distance (km)",
            "Energy (kWh)",
            "Efficiency (mi/kWh)",
            "Efficiency (km/kWh)",
            "Duration (min)",
            "Average speed (mph)",
            "Maximum speed (mph)",
            "Start",
            "Destination",
            "Estimated cost"
        ]

        let rows = trips.map { trip in
            let key = TripRecord.key(vin: vin, trip: trip)
            let tripMetadata = metadata[key]
            let energy = TripAnalytics.energyKWh(for: trip)
            let efficiency = TripAnalytics.efficiencyMilesPerKWh(for: trip) ?? 0
            let cost = ratePerKWh.map { energy * $0 }
            return [
                ISO8601DateFormatter().string(from: trip.startDate),
                tripMetadata?.customName ?? "",
                tripMetadata?.isFavorite == true ? "Yes" : "No",
                decimal(trip.distance, digits: 2),
                decimal(trip.distance * 1.60934, digits: 2),
                decimal(energy, digits: 3),
                decimal(efficiency, digits: 2),
                decimal(efficiency * 1.60934, digits: 2),
                decimal(Double(trip.durationSeconds) / 60, digits: 1),
                decimal(trip.avgSpeed, digits: 1),
                decimal(trip.maxSpeed, digits: 1),
                tripMetadata?.startLocationName ?? "",
                tripMetadata?.endLocationName ?? "",
                cost.map { decimal($0, digits: 2) } ?? ""
            ]
        }

        return ([header] + rows)
            .map { $0.map(escape).joined(separator: ",") }
            .joined(separator: "\n") + "\n"
    }

    private static func decimal(_ value: Double, digits: Int) -> String {
        String(
            format: "%.\(digits)f",
            locale: Locale(identifier: "en_US_POSIX"),
            value
        )
    }

    private static func escape(_ value: String) -> String {
        guard value.contains(",") || value.contains("\"") || value.contains("\n") else {
            return value
        }
        return "\"\(value.replacingOccurrences(of: "\"", with: "\"\""))\""
    }
}

struct TripCSVDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText] }
    let data: Data

    init(csv: String) {
        data = Data(csv.utf8)
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

private struct TripComparisonView: View {
    let first: EVTripDetail
    let second: EVTripDetail
    let firstRecord: TripRecord?
    let secondRecord: TripRecord?
    let distanceUnit: Distance.Units
    let electricityRatePerKWh: Double?
    @Environment(\.dismiss) private var dismiss

    private var efficiencyUnit: String {
        distanceUnit == .kilometers ? "km/kWh" : "mi/kWh"
    }

    private func efficiency(_ trip: EVTripDetail) -> Double {
        let miles = TripAnalytics.efficiencyMilesPerKWh(for: trip) ?? 0
        return distanceUnit == .kilometers ? miles * 1.60934 : miles
    }

    private func speed(_ mph: Double) -> String {
        let value = distanceUnit == .kilometers ? mph * 1.60934 : mph
        return "\(value.formatted(.number.precision(.fractionLength(0)))) \(distanceUnit == .kilometers ? "km/h" : "mph")"
    }

    private func name(for trip: EVTripDetail, record: TripRecord?) -> String {
        record?.customName
            ?? trip.startDate.formatted(.dateTime.month(.abbreviated).day().hour().minute())
    }

    var body: some View {
        NavigationStack {
            ZStack {
                GBBackground(colors: [Color(red: 0.05, green: 0.08, blue: 0.18), .black])

                ScrollView {
                    VStack(spacing: 12) {
                        comparisonHeader

                        comparisonMetric(
                            title: "Distance",
                            firstValue: Distance.Units.miles.format(first.distance, to: distanceUnit),
                            secondValue: Distance.Units.miles.format(second.distance, to: distanceUnit)
                        )
                        comparisonMetric(
                            title: "Efficiency",
                            firstValue: "\(efficiency(first).formatted(.number.precision(.fractionLength(1)))) \(efficiencyUnit)",
                            secondValue: "\(efficiency(second).formatted(.number.precision(.fractionLength(1)))) \(efficiencyUnit)",
                            firstWins: efficiency(first) >= efficiency(second)
                        )
                        comparisonMetric(
                            title: "Energy used",
                            firstValue: "\(TripAnalytics.energyKWh(for: first).formatted(.number.precision(.fractionLength(1)))) kWh",
                            secondValue: "\(TripAnalytics.energyKWh(for: second).formatted(.number.precision(.fractionLength(1)))) kWh",
                            firstWins: TripAnalytics.energyKWh(for: first) <= TripAnalytics.energyKWh(for: second)
                        )
                        comparisonMetric(
                            title: "Duration",
                            firstValue: first.formattedDuration,
                            secondValue: second.formattedDuration
                        )
                        comparisonMetric(
                            title: "Average speed",
                            firstValue: speed(first.avgSpeed),
                            secondValue: speed(second.avgSpeed)
                        )

                        if let electricityRatePerKWh {
                            comparisonMetric(
                                title: "Estimated cost",
                                firstValue: (TripAnalytics.energyKWh(for: first) * electricityRatePerKWh)
                                    .formatted(.currency(code: Locale.current.currency?.identifier ?? "USD")),
                                secondValue: (TripAnalytics.energyKWh(for: second) * electricityRatePerKWh)
                                    .formatted(.currency(code: Locale.current.currency?.identifier ?? "USD")),
                                firstWins: TripAnalytics.energyKWh(for: first) <= TripAnalytics.energyKWh(for: second)
                            )
                        }
                    }
                    .padding(GBDesign.screenPadding)
                }
                .scrollIndicators(.hidden)
            }
            .ghostBlueColorScheme()
            .navigationTitle("Compare Trips")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private var comparisonHeader: some View {
        let firstEfficiency = efficiency(first)
        let secondEfficiency = efficiency(second)
        let difference = abs(firstEfficiency - secondEfficiency)

        return VStack(spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                tripHeading("A", trip: first, record: firstRecord)
                Image(systemName: "arrow.left.arrow.right")
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
                tripHeading("B", trip: second, record: secondRecord)
            }

            Label(
                difference < 0.05
                    ? "These trips have nearly identical efficiency"
                    : "Trip \(firstEfficiency > secondEfficiency ? "A" : "B") was \(difference.formatted(.number.precision(.fractionLength(1)))) \(efficiencyUnit) more efficient",
                systemImage: difference < 0.05 ? "equal.circle.fill" : "leaf.fill"
            )
            .font(.subheadline.bold())
            .foregroundStyle(.green)
            .multilineTextAlignment(.center)
        }
        .padding(16)
        .gbGlassCard()
    }

    private func tripHeading(
        _ label: String,
        trip: EVTripDetail,
        record: TripRecord?
    ) -> some View {
        VStack(spacing: 5) {
            Text(label)
                .font(.caption2.bold())
                .foregroundStyle(.secondary)
            Text(name(for: trip, record: record))
                .font(.subheadline.bold())
                .multilineTextAlignment(.center)
                .lineLimit(2)
            Text(trip.startDate, format: .dateTime.month(.abbreviated).day())
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private func comparisonMetric(
        title: String,
        firstValue: String,
        secondValue: String,
        firstWins: Bool? = nil
    ) -> some View {
        HStack(spacing: 8) {
            comparisonValue(firstValue, isWinner: firstWins)

            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(width: 82)
                .multilineTextAlignment(.center)

            comparisonValue(secondValue, isWinner: firstWins.map { !$0 })
        }
        .padding(14)
        .gbGlassCard(radius: GBDesign.tileRadius)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title). Trip A \(firstValue). Trip B \(secondValue).")
    }

    private func comparisonValue(_ value: String, isWinner: Bool?) -> some View {
        HStack(spacing: 4) {
            if isWinner == true {
                Image(systemName: "checkmark.circle.fill")
                    .font(.caption2)
            }
            Text(value)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
        }
        .font(.caption.bold())
        .foregroundStyle(isWinner == true ? .green : .primary)
        .frame(maxWidth: .infinity)
        .multilineTextAlignment(.center)
    }
}

/// Compact avg/max speed readout matching the breakdown pill layout.
struct EnergyBreakdownSpeedPill: View {
    let avgSpeed: Double
    let maxSpeed: Double
    let distanceUnit: Distance.Units

    private var unit: String { distanceUnit == .kilometers ? "km/h" : "mph" }

    private func converted(_ mph: Double) -> Int {
        Int(distanceUnit == .kilometers ? mph * 1.60934 : mph)
    }

    var body: some View {
        VStack(spacing: 2) {
            Text("Speed")
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text("\(converted(avgSpeed)) avg · \(converted(maxSpeed)) \(unit)")
                .font(.caption)
                .fontWeight(.medium)
        }
        .frame(maxWidth: .infinity)
    }
}
