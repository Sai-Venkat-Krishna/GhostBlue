//
//  ChargeHistoryView.swift
//  GhostBlue
//
//  Charging history: energy totals and per-session cards, fed by the
//  auto-detected ChargeSession log (see Vehicle.swift). Used by the
//  Charging tab.
//

import BetterBlueKit
import Charts
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

private enum ChargeHistoryRange: Int, CaseIterable, Identifiable {
    case week = 7
    case month = 30
    case quarter = 90

    var id: Int { rawValue }
    var dayCount: Int { rawValue }

    var title: String {
        switch self {
        case .week: "Week"
        case .month: "Month"
        case .quarter: "3 months"
        }
    }

    /// Three months of daily bars are unreadably thin, so that range is
    /// summed per week.
    var bucket: Calendar.Component { self == .quarter ? .weekOfYear : .day }

    /// Start of the first calendar day in the range, so "Week" is today plus
    /// the six days before it and the chart, totals and list agree.
    func start(endingAt now: Date = Date(), calendar: Calendar = .current) -> Date {
        ChargeHistoryRange.start(days: dayCount, endingAt: now, calendar: calendar)
    }

    static func start(days: Int, endingAt now: Date = Date(), calendar: Calendar = .current) -> Date {
        let today = calendar.startOfDay(for: now)
        return calendar.date(byAdding: .day, value: -(max(1, days) - 1), to: today) ?? today
    }
}

private enum ChargePowerFilter: String, CaseIterable, Identifiable {
    case all
    case underTwoKW
    case twoKWAndAbove

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: "All"
        case .underTwoKW: "< 2 kW"
        case .twoKWAndAbove: "2 kW+"
        }
    }

    func includes(_ session: ChargeSession) -> Bool {
        switch self {
        case .all: true
        case .underTwoKW: session.maxChargeSpeedKW > 0 && session.maxChargeSpeedKW < 2
        case .twoKWAndAbove: session.maxChargeSpeedKW >= 2
        }
    }
}

private struct ChargeEnergyDayPoint: Identifiable {
    let day: Date
    let energyKWh: Double
    let sessionCount: Int
    var id: Date { day }
}

// MARK: - Session Card

/// One charging session as a glass card: charge gained, energy, window.
struct ChargeSessionCard: View {
    @State private var badgeSettings = AppSettings.shared
    private var badgeTint: Color { badgeSettings.appTheme == .rivian ? RV.energy : .cyan }
    let session: ChargeSession
    /// Resolved by the vehicle (manual reading, battery estimate, or bounded
    /// samples) so the card always agrees with the totals and the chart.
    let energyKWh: Double
    let energySource: String
    var cost: Double?

    private var headline: String {
        var parts: [String] = []
        if session.percentAdded >= 1 {
            parts.append("+\(Int(session.percentAdded))%")
        }
        if estimatedEnergyKWh >= 0.05 {
            parts.append(String(format: "%.1f kWh", estimatedEnergyKWh))
        }
        if parts.isEmpty {
            parts.append(session.isActive ? "Starting…" : "No charge added")
        }
        return parts.joined(separator: " · ")
    }

    private var estimatedEnergyKWh: Double { energyKWh }

    private var estimatedCost: Double? {
        cost
    }

    private var durationText: String {
        Duration.seconds(max(60, session.duration))
            .formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
    }

    private func timestampText(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .standard)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .stroke((session.isActive ? Color.green : Color.secondary).opacity(0.35), lineWidth: 2)
                    Image(systemName: session.isActive ? "bolt.fill" : "bolt.badge.checkmark")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(session.isActive ? Color.green : Color.secondary)
                }
                .frame(width: 44, height: 44)

                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(session.isActive ? "Charging" : headline)
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(session.isActive ? Color.green : Color.primary)

                        if session.isDCFast {
                            Text("DC")
                                .font(.system(size: 9, weight: .heavy))
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1.5)
                                .background(Capsule().fill(badgeTint.opacity(0.25)))
                                .foregroundStyle(badgeTint)
                        }
                    }

                    HStack(spacing: 5) {
                        Text("\(Int(session.startPercentage))% → \(Int(session.endPercentage))%")
                        Text("·")
                        Text(durationText)
                        if session.isActive, estimatedEnergyKWh >= 0.05 {
                            Text("·")
                            Text(String(format: "%.1f kWh", estimatedEnergyKWh))
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 3) {
                    if session.maxChargeSpeedKW > 0 {
                        Text(String(format: "%.0f kW peak", session.maxChargeSpeedKW))
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }

                    if let estimatedCost {
                        Text(estimatedCost, format: .currency(code: Locale.current.currency?.identifier ?? "USD"))
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.green)
                        Text(session.actualCost == nil ? "estimated" : "actual")
                            .font(.system(size: 8, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Divider()

            Text(energySource)
                .font(.caption2).foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 7) {
                timestampRow("Started", date: session.startDate, systemImage: "play.circle.fill")
                if let endDate = session.endDate {
                    timestampRow("Ended", date: endDate, systemImage: "stop.circle.fill")
                } else {
                    Label("Charging is still in progress", systemImage: "bolt.fill")
                        .foregroundStyle(.green)
                }
                timestampRow("Updated", date: session.lastSampleDate, systemImage: "arrow.clockwise.circle.fill")
            }
            .font(.caption)
        }
        .padding(14)
        .gbGlassCard(radius: GBDesign.tileRadius)
    }

    private func timestampRow(_ label: String, date: Date, systemImage: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 7) {
            Image(systemName: systemImage)
                .frame(width: 14)
                .foregroundStyle(.secondary)
            Text(label)
                .foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Text(timestampText(date))
                .fontWeight(.medium)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Charging Tab

struct ChargingView: View {
    let displayedVehicles: [BBVehicle]
    @Binding var selectedVehicleIndex: Int

    private var bbVehicle: BBVehicle? {
        guard displayedVehicles.indices.contains(selectedVehicleIndex) else { return nil }
        return displayedVehicles[selectedVehicleIndex]
    }

    var body: some View {
        if let bbVehicle {
            PersistentModelGuard(model: bbVehicle) {
                ChargeHistoryView(
                    bbVehicle: bbVehicle,
                    vehicleOptions: displayedVehicles,
                    onSelectVehicle: { selectedVehicleIndex = $0 }
                )
            }
        }
    }
}

// MARK: - History Dashboard

struct ChargeHistoryView: View {
    let bbVehicle: BBVehicle
    var vehicleOptions: [BBVehicle] = []
    var onSelectVehicle: ((Int) -> Void)?
    @Query(sort: \ChargeSession.startDate, order: .reverse)
    private var allSessions: [ChargeSession]
    @State private var showingSchedule = false
    @State private var exportingHistory = false
    @State private var exportCSV = ""
    @State private var exportError: String?

    init(bbVehicle: BBVehicle, vehicleOptions: [BBVehicle] = [], onSelectVehicle: ((Int) -> Void)? = nil) {
        self.bbVehicle = bbVehicle
        self.vehicleOptions = vehicleOptions
        self.onSelectVehicle = onSelectVehicle
        let vin = bbVehicle.vin
        _allSessions = Query(filter: #Predicate<ChargeSession> { $0.vin == vin },
                             sort: \ChargeSession.startDate, order: .reverse)
    }
    @State private var selectedRange: ChargeHistoryRange = .month
    @State private var selectedPowerFilter: ChargePowerFilter = .all
    @State private var selectedChargeDay: Date?
    @State private var editingSession: ChargeSession?
    @State private var showingManualEntry = false
    @State private var showingCostSettings = false
    @State private var appSettings = AppSettings.shared
    @State private var screenSize: CGSize = .zero

    /// Wide screens (the iPhone Duo's inner display) pair cards side by side
    /// and lay sessions out in two columns.
    private var isWide: Bool { AdaptiveLayout.isWide(screenSize.width) }

    /// Energy accent: Rivian green under the Rivian theme, classic cyan otherwise.
    private var energyTint: Color { appSettings.appTheme == .rivian ? RV.energy : .cyan }
    private var energyGradient: [Color] {
        appSettings.appTheme == .rivian ? [RV.energy, RV.energy.opacity(0.55)] : [.cyan, .blue]
    }

    private var sessions: [ChargeSession] {
        allSessions.filter { $0.vin == bbVehicle.vin && $0.isMeaningful }
    }

    private var powerFilteredSessions: [ChargeSession] {
        sessions.filter(selectedPowerFilter.includes)
    }

    private var displayedSessions: [ChargeSession] {
        let start = selectedRange.start()
        return powerFilteredSessions.filter { $0.startDate >= start }
    }

    private var previousSessions: [ChargeSession] {
        let end = selectedRange.start()
        let start = Calendar.current.date(byAdding: .day, value: -selectedRange.dayCount, to: end)
            ?? .distantPast
        return powerFilteredSessions.filter { $0.startDate >= start && $0.startDate < end }
    }

    private var weekEnergyKWh: Double {
        energyDelivered(inLastDays: 7)
    }

    private var monthEnergyKWh: Double {
        energyDelivered(inLastDays: 30)
    }

    private var lifetimeEnergyKWh: Double {
        powerFilteredSessions.reduce(0) { $0 + energy(for: $1) }
    }

    private var totalEnergyKWh: Double {
        displayedSessions.reduce(0) { $0 + energy(for: $1) }
    }

    private var totalPercentAdded: Double {
        displayedSessions.reduce(0) { $0 + $1.percentAdded }
    }

    private var dcSessionCount: Int {
        displayedSessions.filter(\.isDCFast).count
    }

    private var totalCost: Double? {
        let costs = displayedSessions.compactMap { bbVehicle.chargingCost(for: $0) }
        guard !costs.isEmpty else { return nil }
        return costs.reduce(0, +)
    }

    private func energy(for session: ChargeSession) -> Double {
        bbVehicle.energyDelivered(for: session)
    }

    private func energyDelivered(inLastDays dayCount: Int) -> Double {
        let start = ChargeHistoryRange.start(days: dayCount)
        return powerFilteredSessions
            .filter { $0.startDate >= start }
            .reduce(0) { $0 + energy(for: $1) }
    }

    private func bucketStart(for date: Date) -> Date {
        let calendar = Calendar.current
        return calendar.dateInterval(of: selectedRange.bucket, for: date)?.start
            ?? calendar.startOfDay(for: date)
    }

    private var dailyEnergyPoints: [ChargeEnergyDayPoint] {
        Dictionary(grouping: displayedSessions) { bucketStart(for: $0.startDate) }
            .map { day, sessions in
                ChargeEnergyDayPoint(
                    day: day,
                    energyKWh: sessions.reduce(0) { $0 + energy(for: $1) },
                    sessionCount: sessions.count
                )
            }
            .sorted { $0.day < $1.day }
    }

    /// A fixed domain keeps the selected period on screen even when only one
    /// day has a session; otherwise the axis collapses onto that day.
    private var trendDomain: ClosedRange<Date> {
        let calendar = Calendar.current
        let start = bucketStart(for: selectedRange.start())
        let endOfToday = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: Date())) ?? Date()
        let end = calendar.dateInterval(of: selectedRange.bucket, for: endOfToday.addingTimeInterval(-1))?.end
            ?? endOfToday
        return start ... max(end, start.addingTimeInterval(86_400))
    }

    /// Daily ticks for a week (labelled by weekday, centred on the bar),
    /// weekly for a month and three-weekly for three months. Ticks close to
    /// the trailing edge are dropped: their labels would be clipped to "…".
    private var trendAxisDates: [Date] {
        let calendar = Calendar.current
        let domain = trendDomain
        let stepDays = switch selectedRange {
        case .week: 1
        case .month: 7
        case .quarter: 21
        }
        let margin: TimeInterval = selectedRange == .week ? 0 : Double(stepDays) * 86_400 * 0.4
        var dates: [Date] = []
        var cursor = domain.lowerBound
        while cursor < domain.upperBound.addingTimeInterval(-margin) {
            dates.append(cursor)
            guard let next = calendar.date(byAdding: .day, value: stepDays, to: cursor) else { break }
            cursor = next
        }
        // A centred label spans to the next tick, so today's bar needs a
        // closing tick at the end of the domain to get its weekday.
        if selectedRange == .week {
            dates.append(domain.upperBound)
        }
        return dates
    }

    private var hasTrendEnergy: Bool {
        dailyEnergyPoints.contains { $0.energyKWh >= 0.05 }
    }

    private var energyChange: Double? {
        let previousEnergy = previousSessions.reduce(0) { $0 + energy(for: $1) }
        guard previousEnergy > 0 else { return nil }
        return (totalEnergyKWh - previousEnergy) / previousEnergy * 100
    }

    var body: some View {
        ZStack {
            GBBackground(colors: bbVehicle.backgroundGradient)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        header
                            .id("charging-top")

                        if sessions.isEmpty {
                            statusHero
                            emptyCard
                        } else {
                            AdaptiveColumns(isWide: isWide) {
                                statusHero
                            } trailing: {
                                rangePicker
                                energyDeliveredCard
                            }

                            if displayedSessions.isEmpty {
                                noSessionsInRangeCard
                            } else {
                                statsGrid
                                AdaptiveColumns(isWide: isWide) {
                                    energyTrendCard
                                } trailing: {
                                    chargingInsightsCard
                                }

                                GBSectionHeader(title: "Sessions")

                                LazyVGrid(
                                    columns: Array(
                                        repeating: GridItem(.flexible(), spacing: 12, alignment: .top),
                                        count: isWide ? 2 : 1
                                    ),
                                    spacing: 12
                                ) {
                                    ForEach(displayedSessions) { session in
                                        sessionRow(session)
                                    }
                                }
                            }
                        }
                    }
                    .padding(.horizontal, GBDesign.screenPadding)
                    .padding(.bottom, 24)
                }
                .scrollIndicators(.hidden)
                .readSize(into: $screenSize)
                .onReceive(NotificationCenter.default.publisher(for: .scrollChargingToTop)) { _ in
                    withAnimation(.easeOut(duration: 0.35)) {
                        proxy.scrollTo("charging-top", anchor: .top)
                    }
                }
            }
        }
        .ghostBlueColorScheme(classic: bbVehicle.backgroundColorScheme)
        .rivianStatusBarBackdrop()
        .onChange(of: selectedRange) { _, _ in selectedChargeDay = nil }
        .onChange(of: selectedPowerFilter) { _, _ in selectedChargeDay = nil }
        .sheet(isPresented: $showingSchedule) {
            NavigationStack {
                Form { SmartChargingScheduleSection(vehicle: bbVehicle) }
                    .navigationTitle("Smart Charging")
                    .toolbar { ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { showingSchedule = false }
                    } }
            }
        }
        .fileExporter(isPresented: $exportingHistory, document: TripCSVDocument(csv: exportCSV),
                      contentType: .commaSeparatedText, defaultFilename: "GhostBlue-Charging") { result in
            if case let .failure(error) = result { exportError = error.localizedDescription }
        }
        .alert("Export Failed", isPresented: Binding(get: { exportError != nil }, set: { if !$0 { exportError = nil } })) {
            Button("OK") { exportError = nil }
        } message: { Text(exportError ?? "") }
        .sheet(item: $editingSession) { session in
            NavigationStack {
                ChargeSessionEditor(session: session, vehicle: bbVehicle)
            }
            .presentationDetents([.large])
        }
        .sheet(isPresented: $showingManualEntry) {
            NavigationStack {
                ChargeSessionEditor(session: nil, vehicle: bbVehicle)
            }
            .presentationDetents([.large])
        }
        .sheet(isPresented: $showingCostSettings) {
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

    private var rangePicker: some View {
        VStack(alignment: .leading, spacing: 10) {
            Picker("Charging history range", selection: $selectedRange) {
                ForEach(ChargeHistoryRange.allCases) { range in
                    Text(range.title).tag(range)
                }
            }
            .pickerStyle(.segmented)

            HStack {
                Label("Peak speed", systemImage: "gauge.with.needle")
                    .font(.subheadline.weight(.semibold))

                Spacer()

                Picker("Peak charging speed", selection: $selectedPowerFilter) {
                    ForEach(ChargePowerFilter.allCases) { filter in
                        Text(filter.title).tag(filter)
                    }
                }
                .pickerStyle(.menu)
            }
        }
        .padding(8)
        .gbGlassCard(radius: GBDesign.tileRadius)
    }

    private var energyDeliveredCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Energy delivered", systemImage: "bolt.circle.fill")
                .font(.subheadline.bold())

            HStack(spacing: 10) {
                energyDeliveredMetric(title: "Week", energyKWh: weekEnergyKWh)
                energyDeliveredMetric(title: "Month", energyKWh: monthEnergyKWh)
                energyDeliveredMetric(title: "Total", energyKWh: lifetimeEnergyKWh)
            }

            Text(
                selectedPowerFilter == .all
                    ? "Week and month totals use the last 7 and 30 days."
                    : "Totals include only sessions with a peak speed of \(selectedPowerFilter.title)."
            )
            .font(.caption2)
            .foregroundStyle(.secondary)

            capacityNote
        }
        .padding(16)
        .gbGlassCard()
    }

    /// Energy is charge added × usable capacity, so say which capacity is in
    /// use and offer to correct a model default.
    @ViewBuilder
    private var capacityNote: some View {
        if bbVehicle.isUsingModelDefaultBatteryCapacity, let capacity = bbVehicle.effectiveBatteryCapacityKWh {
            Button {
                showingCostSettings = true
            } label: {
                Label(
                    "Using the \(capacity.formatted(.number.precision(.fractionLength(0 ... 1)))) kWh \(bbVehicle.model) default. Tap to set your exact capacity.",
                    systemImage: "info.circle"
                )
                .font(.caption2)
                .multilineTextAlignment(.leading)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
        } else if bbVehicle.effectiveBatteryCapacityKWh == nil {
            Button {
                showingCostSettings = true
            } label: {
                Label(
                    "Set your battery's usable capacity for accurate energy totals.",
                    systemImage: "exclamationmark.circle"
                )
                .font(.caption2.weight(.semibold))
            }
            .buttonStyle(.plain)
            .foregroundStyle(.orange)
        }
    }

    private func energyDeliveredMetric(title: String, energyKWh: Double) -> some View {
        VStack(spacing: 4) {
            Text(energyKWh.formatted(.number.precision(.fractionLength(1))))
                .font(.headline.bold())
                .foregroundStyle(energyTint)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text("kWh")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.caption.weight(.semibold))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .background(Color.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title) energy delivered")
        .accessibilityValue("\(energyKWh.formatted(.number.precision(.fractionLength(1)))) kilowatt-hours")
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(appSettings.appTheme == .rivian ? "Energy" : "Charging")
                    .font(.gbDisplay)
                vehicleSelector
            }

            Spacer()

            Menu {
                Button("Charging schedule", systemImage: "calendar.badge.clock") { showingSchedule = true }
                Button("Export selected period (CSV)", systemImage: "square.and.arrow.up") {
                    exportCSV = ChargingCSVExporter.csv(sessions: displayedSessions, vehicle: bbVehicle)
                    exportingHistory = true
                }
                .disabled(displayedSessions.isEmpty)
            } label: {
                Image(systemName: "ellipsis.circle").frame(width: 40, height: 40).gbGlassCircle()
            }
            .accessibilityLabel("Charging schedule and export")

            Button {
                showingManualEntry = true
            } label: {
                Image(systemName: "plus")
                    .fontWeight(.semibold)
                    .frame(width: 40, height: 40)
                    .gbGlassCircle()
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Add charging session manually")

            Button {
                showingCostSettings = true
            } label: {
                Image(systemName: "dollarsign.circle.fill")
                    .fontWeight(.semibold)
                    .frame(width: 40, height: 40)
                    .gbGlassCircle()
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Charging price settings")
        }
        .padding(.top, 20)
    }

    @ViewBuilder
    private var vehicleSelector: some View {
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
    }

    @ViewBuilder
    private var statusHero: some View {
        if appSettings.appTheme == .rivian {
            RivianEnergyHero(bbVehicle: bbVehicle)
        } else {
            ChargingStatusSummary(vehicle: bbVehicle)
        }
    }

    private func sessionRow(_ session: ChargeSession) -> some View {
        VStack(spacing: 6) {
            ChargeSessionCard(
                session: session,
                energyKWh: energy(for: session),
                energySource: bbVehicle.energySource(for: session),
                cost: bbVehicle.chargingCost(for: session)
            )

            Button {
                editingSession = session
            } label: {
                Label("Edit session", systemImage: "pencil")
                    .font(.caption.bold())
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
        }
    }

    private var statsGrid: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: isWide ? 4 : 2)

        return LazyVGrid(columns: columns, spacing: 10) {
            GBStatTile(
                systemImage: "bolt.fill",
                label: "Energy delivered",
                value: String(format: "%.1f kWh", totalEnergyKWh),
                tint: .green
            )
            GBStatTile(
                systemImage: "battery.100percent.bolt",
                label: "Charge added",
                value: "+\(Int(totalPercentAdded))%"
            )
            GBStatTile(
                systemImage: "number",
                label: "Sessions",
                value: "\(displayedSessions.count)"
            )
            GBStatTile(
                systemImage: "ev.charger.fill",
                label: "DC fast",
                value: "\(dcSessionCount)",
                tint: energyTint
            )
            if let totalCost {
                GBStatTile(
                    systemImage: "dollarsign.circle.fill",
                    label: "Est. cost",
                    value: totalCost.formatted(.currency(code: Locale.current.currency?.identifier ?? "USD")),
                    tint: .green
                )
            }
        }
    }

    private var energyTrendCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("Energy trend", systemImage: "chart.bar.fill")
                    .font(.subheadline.bold())
                Spacer()
                if let energyChange {
                    Label(
                        "\(abs(energyChange).formatted(.number.precision(.fractionLength(0))))%",
                        systemImage: energyChange >= 0 ? "arrow.up.right" : "arrow.down.right"
                    )
                    .font(.caption.bold())
                    .foregroundStyle(energyChange >= 0 ? .orange : .green)
                    .accessibilityLabel(
                        "\(abs(energyChange).formatted(.number.precision(.fractionLength(0)))) percent \(energyChange >= 0 ? "more" : "less") energy than the previous period"
                    )
                }
            }

            if hasTrendEnergy {
                Chart(dailyEnergyPoints) { point in
                    BarMark(
                        x: .value("Day", point.day, unit: selectedRange.bucket),
                        y: .value("Energy", point.energyKWh)
                    )
                    .foregroundStyle(
                        LinearGradient(
                            colors: energyGradient,
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .cornerRadius(4)
                    .opacity(selectedPoint == nil || selectedPoint?.day == point.day ? 1 : 0.4)
                    .accessibilityLabel(point.day.formatted(date: .complete, time: .omitted))
                    .accessibilityValue(
                        "\(point.energyKWh.formatted(.number.precision(.fractionLength(1)))) kilowatt-hours in \(point.sessionCount) sessions"
                    )
                }
                .chartXScale(domain: trendDomain)
                .chartYScale(domain: .automatic(includesZero: true))
                .chartYAxis {
                    AxisMarks(position: .leading) {
                        AxisGridLine()
                        AxisValueLabel()
                    }
                }
                .chartXAxis {
                    AxisMarks(values: trendAxisDates) {
                        AxisGridLine()
                        if selectedRange == .week {
                            AxisValueLabel(format: .dateTime.weekday(.abbreviated), centered: true)
                        } else {
                            AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                        }
                    }
                }
                .chartYAxisLabel("kWh", position: .leading)
                .chartXSelection(value: $selectedChargeDay)
                .frame(height: 180)
                .accessibilityLabel(selectedRange == .quarter ? "Charging energy by week" : "Charging energy by day")
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "chart.bar.xaxis")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                    Text("No energy measured for these sessions yet")
                        .font(.subheadline.weight(.semibold))
                    Text(
                        bbVehicle.effectiveBatteryCapacityKWh == nil
                            ? "Add your battery's usable capacity so energy can be estimated from the charge added."
                            : "Energy appears once a session adds charge. Sessions that add no charge are counted as 0 kWh."
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    if bbVehicle.effectiveBatteryCapacityKWh == nil {
                        Button("Set battery capacity") { showingCostSettings = true }
                            .font(.caption.bold())
                            .buttonStyle(.bordered)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 180)
            }

            if let selectedPoint {
                HStack {
                    Text(
                        selectedRange.bucket == .day
                            ? selectedPoint.day.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
                            : "Week of \(selectedPoint.day.formatted(.dateTime.month(.abbreviated).day()))"
                    )
                    Spacer()
                    Text(
                        "\(selectedPoint.energyKWh.formatted(.number.precision(.fractionLength(1)))) kWh · \(selectedPoint.sessionCount) session\(selectedPoint.sessionCount == 1 ? "" : "s")"
                    )
                        .foregroundStyle(energyTint)
                }
                .font(.caption.bold())
            }
        }
        .padding(16)
        .gbGlassCard()
    }

    /// Only the bar under the finger: snapping to the nearest bar would
    /// report another day's energy for a day without sessions.
    private var selectedPoint: ChargeEnergyDayPoint? {
        guard let selectedChargeDay else { return nil }
        let bucket = bucketStart(for: selectedChargeDay)
        return dailyEnergyPoints.first { $0.day == bucket }
    }

    private var chargingInsightsCard: some View {
        let averageEnergy = totalEnergyKWh / Double(max(1, displayedSessions.count))
        let peakSpeeds = displayedSessions.map(\.maxChargeSpeedKW).filter { $0 > 0 }
        let averagePeak = peakSpeeds.isEmpty
            ? nil
            : peakSpeeds.reduce(0, +) / Double(peakSpeeds.count)

        return VStack(alignment: .leading, spacing: 12) {
            Label("Insights", systemImage: "lightbulb.fill")
                .font(.subheadline.bold())

            HStack(spacing: 10) {
                chargingInsightMetric(
                    title: "Avg. session",
                    value: "\(averageEnergy.formatted(.number.precision(.fractionLength(1)))) kWh",
                    systemImage: "bolt.circle.fill"
                )
                chargingInsightMetric(
                    title: "Avg. peak",
                    value: averagePeak.map {
                        "\($0.formatted(.number.precision(.fractionLength(0)))) kW"
                    } ?? "--",
                    systemImage: "gauge.with.needle"
                )
                chargingInsightMetric(
                    title: "Avg. cost",
                    value: totalCost.map {
                        ($0 / Double(max(1, displayedSessions.count))).formatted(
                            .currency(code: Locale.current.currency?.identifier ?? "USD")
                        )
                    } ?? "--",
                    systemImage: "dollarsign.circle.fill"
                )
            }

            Text(
                totalCost == nil
                    ? "Add an electricity rate to include cost comparisons."
                    : "Costs use your saved electricity rate and estimated energy added."
            )
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
        .padding(16)
        .gbGlassCard()
    }

    private func chargingInsightMetric(
        title: String,
        value: String,
        systemImage: String
    ) -> some View {
        VStack(spacing: 5) {
            Image(systemName: systemImage)
                .foregroundStyle(energyTint)
            Text(value)
                .font(.caption.bold())
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(title)
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    private var noSessionsInRangeCard: some View {
        ContentUnavailableView(
            "No Sessions",
            systemImage: "calendar.badge.exclamationmark",
            description: Text("No charging sessions were recorded in the last \(selectedRange.dayCount) days.")
        )
        .frame(maxWidth: .infinity, minHeight: 190)
        .gbGlassCard()
    }

    private var emptyCard: some View {
        VStack(spacing: 10) {
            Image(systemName: "powerplug")
                .font(.system(size: 40, weight: .semibold))
                .foregroundStyle(.secondary)
                .padding(.bottom, 4)

            Text("No charging sessions yet")
                .font(.headline.weight(.bold))

            Text("Plug in your vehicle for automatic tracking, or add a completed session manually.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button {
                showingManualEntry = true
            } label: {
                Label("Add session manually", systemImage: "plus.circle.fill")
            }
            .buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
        .padding(.horizontal, 20)
        .gbGlassCard()
    }
}

private struct ChargeSessionEditor: View {
    let session: ChargeSession?
    let vehicle: BBVehicle

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext

    @State private var startDate: Date
    @State private var endDate: Date
    @State private var isCompleted: Bool
    @State private var startPercentage: Double
    @State private var endPercentage: Double
    @State private var energyText: String
    @State private var peakSpeedText: String
    @State private var isDCFast: Bool
    @State private var locationType: ChargingLocationType
    @State private var rateText = ""
    @State private var actualCostText = ""
    @State private var saveError: String?
    @State private var useTimeOfUseRates: Bool
    private let savedTariff: ChargingTariff?

    init(session: ChargeSession?, vehicle: BBVehicle) {
        self.session = session
        self.vehicle = vehicle
        savedTariff = session?.tariff

        let now = Date()
        let initialStart = session?.startDate
            ?? Calendar.current.date(byAdding: .hour, value: -1, to: now)
            ?? now.addingTimeInterval(-3_600)
        let initialEnd = session?.endDate
            ?? max(session?.lastSampleDate ?? now, initialStart.addingTimeInterval(60))
        let initialLocation = session?.locationType ?? (vehicle.smartChargingEnabled ? .home : .automatic)
        let initialIsDCFast = session?.isDCFast ?? false
        let initialRate = session.flatMap { vehicle.chargingRate(for: $0) }
            ?? vehicle.chargingRate(
                at: initialStart,
                locationType: initialLocation,
                isDCFast: initialIsDCFast
            )

        _startDate = State(initialValue: initialStart)
        _endDate = State(initialValue: initialEnd)
        // New manual sessions are always completed so they cannot interfere
        // with the automatic tracker looking for the current open session.
        _isCompleted = State(initialValue: session?.endDate != nil || session == nil)
        _startPercentage = State(initialValue: session?.startPercentage ?? 20)
        _endPercentage = State(initialValue: session?.endPercentage ?? 30)
        _energyText = State(initialValue: Self.numberText(session?.manualEnergyKWh, includeZero: true))
        _useTimeOfUseRates = State(initialValue: session?.tariff != nil || (session == nil &&
            vehicle.chargingTariff(locationType: initialLocation, isDCFast: initialIsDCFast) != nil))
        _peakSpeedText = State(initialValue: Self.numberText(session?.maxChargeSpeedKW))
        _isDCFast = State(initialValue: initialIsDCFast)
        _locationType = State(initialValue: initialLocation)
        _rateText = State(initialValue: Self.numberText(initialRate, fractionDigits: 3))
        _actualCostText = State(
            initialValue: Self.numberText(
                session?.actualCost,
                fractionDigits: 2,
                includeZero: true
            )
        )
    }

    private var currencyCode: String {
        Locale.current.currency?.identifier ?? "USD"
    }

    private var isCreating: Bool { session == nil }

    private var dateValidationMessage: String? {
        guard isCompleted, endDate <= startDate else { return nil }
        return "End time must be later than start time."
    }

    private var numericValidationMessage: String? {
        if !energyText.isEmpty && nonnegativeDouble(energyText) == nil {
            return "Energy must be zero or greater."
        }
        if !peakSpeedText.isEmpty && nonnegativeDouble(peakSpeedText) == nil {
            return "Peak speed must be zero or greater."
        }
        if !rateText.isEmpty && positiveDouble(rateText) == nil {
            return "Rate must be greater than zero."
        }
        if !actualCostText.isEmpty && nonnegativeDouble(actualCostText) == nil {
            return "Actual cost must be zero or greater."
        }
        return nil
    }

    private var validationMessage: String? {
        dateValidationMessage ?? numericValidationMessage
    }

    private var draftEnergyKWh: Double {
        if let manual = nonnegativeDouble(energyText) { return manual }
        if let capacity = vehicle.effectiveBatteryCapacityKWh,
           capacity > 0,
           endPercentage > startPercentage {
            return capacity * (endPercentage - startPercentage) / 100
        }
        guard let session else { return 0 }
        // Bound samples by the draft percentages, not the saved ones.
        let addedPercent = max(0, endPercentage - startPercentage)
        let ceiling = (vehicle.effectiveBatteryCapacityKWh ?? ChargeSession.maxPlausibleCapacityKWh)
            * (addedPercent + 1) / 100
        return min(max(0, session.energyAddedKWh), ceiling)
    }

    private var draftTariff: ChargingTariff? {
        guard useTimeOfUseRates else { return nil }
        guard !isDCFast, locationType == .home || (locationType == .automatic && vehicle.smartChargingEnabled) else { return nil }
        return savedTariff ?? vehicle.chargingTariff(locationType: locationType, isDCFast: isDCFast)
    }

    private var draftCost: Double? {
        if let actualCost = nonnegativeDouble(actualCostText) {
            return actualCost
        }
        if let tariff = draftTariff {
            return tariff.cost(energyKWh: draftEnergyKWh, from: startDate, to: isCompleted ? endDate : (session?.lastSampleDate ?? .now))
        }
        guard let rate = positiveDouble(rateText), draftEnergyKWh > 0 else { return nil }
        return rate * draftEnergyKWh
    }

    var body: some View {
        Form {
            Section("Date and time") {
                DatePicker(
                    "Started",
                    selection: $startDate,
                    displayedComponents: [.date, .hourAndMinute]
                )

                if session?.isActive == true {
                    Toggle("Mark session complete", isOn: $isCompleted)
                }

                if isCompleted {
                    DatePicker(
                        "Ended",
                        selection: $endDate,
                        displayedComponents: [.date, .hourAndMinute]
                    )
                } else {
                    Label("Automatic tracking will continue this session.", systemImage: "bolt.fill")
                        .font(.caption)
                        .foregroundStyle(.green)
                }

                if let dateValidationMessage {
                    Label(dateValidationMessage, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }

            Section("Charge") {
                Stepper(
                    "Started at \(Int(startPercentage))%",
                    value: $startPercentage,
                    in: 0 ... 100,
                    step: 1
                )
                Stepper(
                    "Ended at \(Int(endPercentage))%",
                    value: $endPercentage,
                    in: 0 ... 100,
                    step: 1
                )

                HStack {
                    Text("Measured energy")
                    Spacer()
                    TextField("Optional", text: $energyText)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 100)
                    Text("kWh")
                        .foregroundStyle(.secondary)
                }

                HStack {
                    Text("Peak speed")
                    Spacer()
                    TextField("Optional", text: $peakSpeedText)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 100)
                    Text("kW")
                        .foregroundStyle(.secondary)
                }

                Toggle("DC fast charging", isOn: $isDCFast)
                Text("Enter the charger’s kWh reading to override estimates. Leave it blank to estimate from the battery or recorded samples.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section("Location") {
                Picker("Charging location", selection: $locationType) {
                    ForEach(ChargingLocationType.allCases) { type in
                        Label(type.title, systemImage: type.systemImage)
                            .tag(type)
                    }
                }
            }

            Section {
                if vehicle.chargingTariff(locationType: locationType, isDCFast: isDCFast) != nil || savedTariff != nil {
                    Toggle("Split cost by time-of-use periods", isOn: $useTimeOfUseRates)
                    if draftTariff != nil {
                        Text("Uses saved tariff rates across the session. Energy is allocated by elapsed time, so the bill remains an estimate.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                LabeledContent(
                    "Energy delivered",
                    value: "\(draftEnergyKWh.formatted(.number.precision(.fractionLength(1)))) kWh"
                )
                HStack {
                    Text("Rate per kWh")
                    Spacer()
                    TextField("Not set", text: $rateText)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 120)
                        .disabled(draftTariff != nil)
                }

                HStack {
                    Text("Actual total paid")
                    Spacer()
                    TextField("Optional", text: $actualCostText)
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 120)
                }

                if let cost = draftCost {
                    LabeledContent(
                        actualCostText.isEmpty ? "Estimated cost" : "Recorded cost",
                        value: cost.formatted(.currency(code: currencyCode))
                    )
                }

                Button("Restore default estimate", systemImage: "arrow.counterclockwise") {
                    actualCostText = ""
                    applyDefaultRateToDraft()
                }
            } header: {
                Text("Cost")
            } footer: {
                Text("Use the receipt total for DC chargers with session fees, idle fees, or station-specific pricing.")
            }

            if let numericValidationMessage {
                Section {
                    Label(numericValidationMessage, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }

            if let saveError {
                Section {
                    Label(saveError, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle(isCreating ? "Add Charging Session" : "Edit Charging Session")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") { save() }
                    .disabled(validationMessage != nil)
            }
        }
        .onChange(of: startDate) { _, _ in
            applyDefaultRateToDraft()
        }
        .onChange(of: locationType) { _, _ in
            applyDefaultRateToDraft()
        }
        .onChange(of: isDCFast) { _, _ in
            applyDefaultRateToDraft()
        }
    }

    private func applyDefaultRateToDraft() {
        let rate = vehicle.chargingRate(
            at: startDate,
            locationType: locationType,
            isDCFast: isDCFast
        )
        rateText = Self.numberText(rate, fractionDigits: 3)
    }

    private func positiveDouble(_ text: String) -> Double? {
        guard let value = Double(text), value > 0 else { return nil }
        return value
    }

    private func nonnegativeDouble(_ text: String) -> Double? {
        guard !text.isEmpty, let value = Double(text), value >= 0 else { return nil }
        return value
    }

    private func save() {
        guard validationMessage == nil else { return }

        let target = session ?? ChargeSession(
            vin: vehicle.vin,
            startDate: startDate,
            startPercentage: startPercentage,
            isDCFast: isDCFast
        )
        let isNewSession = session == nil
        if isNewSession {
            modelContext.insert(target)
        }

        target.applyUserEdits(
            startDate: startDate,
            endDate: isCompleted ? endDate : nil,
            startPercentage: startPercentage,
            endPercentage: endPercentage,
            energyAddedKWh: session?.energyAddedKWh ?? 0,
            maxChargeSpeedKW: nonnegativeDouble(peakSpeedText) ?? 0,
            isDCFast: isDCFast,
            locationType: locationType,
            electricityRatePerKWh: positiveDouble(rateText),
            actualCost: nonnegativeDouble(actualCostText),
            manualEnergyKWh: nonnegativeDouble(energyText),
            tariff: draftTariff
        )

        do {
            try modelContext.save()
            dismiss()
        } catch {
            if isNewSession {
                modelContext.delete(target)
            }
            saveError = "Could not save this charging session. Please try again."
            BBLogger.error(.app, "Failed to save charging session: \(error)")
        }
    }

    private static func numberText(
        _ value: Double?,
        fractionDigits: Int = 1,
        includeZero: Bool = false
    ) -> String {
        guard let value, value > 0 || (includeZero && value == 0) else { return "" }
        return String(format: "%.*f", fractionDigits, value)
    }
}
