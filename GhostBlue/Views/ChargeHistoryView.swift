//
//  ChargeHistoryView.swift
//  GhostBlue
//
//  Charging history: lifetime stats and per-session cards, fed by the
//  auto-detected ChargeSession log (see Vehicle.swift). Shown as a sheet
//  from the Home tab; the session card is also reused on Home itself.
//

import BetterBlueKit
import Charts
import SwiftData
import SwiftUI

private enum ChargeHistoryRange: Int, CaseIterable, Identifiable {
    case week = 7
    case month = 30
    case quarter = 90

    var id: Int { rawValue }
    var dayCount: Int { rawValue }

    var title: String {
        switch self {
        case .week: "7 days"
        case .month: "30 days"
        case .quarter: "90 days"
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
    let session: ChargeSession
    var batteryCapacityKWh: Double?
    var electricityRatePerKWh: Double?

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

    private var estimatedEnergyKWh: Double {
        guard let capacity = batteryCapacityKWh, capacity > 0, session.percentAdded > 0 else {
            return session.energyAddedKWh
        }
        return capacity * session.percentAdded / 100
    }

    private var estimatedCost: Double? {
        guard let rate = electricityRatePerKWh, rate > 0, estimatedEnergyKWh > 0 else { return nil }
        return estimatedEnergyKWh * rate
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
                                .background(Capsule().fill(Color.cyan.opacity(0.25)))
                                .foregroundStyle(.cyan)
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
                    }
                }
            }

            Divider()

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

// MARK: - History Sheet

struct ChargeHistoryView: View {
    let bbVehicle: BBVehicle
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \ChargeSession.startDate, order: .reverse)
    private var allSessions: [ChargeSession]
    @State private var selectedRange: ChargeHistoryRange = .month
    @State private var selectedChargeDay: Date?

    private var sessions: [ChargeSession] {
        allSessions.filter { $0.vin == bbVehicle.vin && $0.isMeaningful }
    }

    private var displayedSessions: [ChargeSession] {
        let start = Calendar.current.date(
            byAdding: .day,
            value: -selectedRange.dayCount,
            to: Date()
        ) ?? .distantPast
        return sessions.filter { $0.startDate >= start }
    }

    private var previousSessions: [ChargeSession] {
        let calendar = Calendar.current
        let end = calendar.date(
            byAdding: .day,
            value: -selectedRange.dayCount,
            to: Date()
        ) ?? .distantPast
        let start = calendar.date(
            byAdding: .day,
            value: -(selectedRange.dayCount * 2),
            to: Date()
        ) ?? .distantPast
        return sessions.filter { $0.startDate >= start && $0.startDate < end }
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
        guard let rate = bbVehicle.electricityRatePerKWh, rate > 0 else { return nil }
        return totalEnergyKWh * rate
    }

    private func energy(for session: ChargeSession) -> Double {
        guard let capacity = bbVehicle.batteryCapacityKWh, capacity > 0, session.percentAdded > 0 else {
            return session.energyAddedKWh
        }
        return capacity * session.percentAdded / 100
    }

    private var dailyEnergyPoints: [ChargeEnergyDayPoint] {
        let calendar = Calendar.current
        return Dictionary(grouping: displayedSessions) {
            calendar.startOfDay(for: $0.startDate)
        }
        .map { day, sessions in
            ChargeEnergyDayPoint(
                day: day,
                energyKWh: sessions.reduce(0) { $0 + energy(for: $1) },
                sessionCount: sessions.count
            )
        }
        .sorted { $0.day < $1.day }
    }

    private var energyChange: Double? {
        let previousEnergy = previousSessions.reduce(0) { $0 + energy(for: $1) }
        guard previousEnergy > 0 else { return nil }
        return (totalEnergyKWh - previousEnergy) / previousEnergy * 100
    }

    var body: some View {
        ZStack {
            GBBackground(colors: bbVehicle.backgroundGradient)

            ScrollView {
                VStack(spacing: 12) {
                    header

                    if sessions.isEmpty {
                        emptyCard
                    } else {
                        rangePicker

                        if displayedSessions.isEmpty {
                            noSessionsInRangeCard
                        } else {
                            statsGrid
                            energyTrendCard
                            chargingInsightsCard

                            GBSectionHeader(title: "Sessions")

                            ForEach(displayedSessions) { session in
                                ChargeSessionCard(
                                    session: session,
                                    batteryCapacityKWh: bbVehicle.batteryCapacityKWh,
                                    electricityRatePerKWh: bbVehicle.electricityRatePerKWh
                                )
                            }
                        }
                    }
                }
                .padding(.horizontal, GBDesign.screenPadding)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
        }
        .environment(\.colorScheme, bbVehicle.backgroundColorScheme)
    }

    private var rangePicker: some View {
        Picker("Charging history range", selection: $selectedRange) {
            ForEach(ChargeHistoryRange.allCases) { range in
                Text(range.title).tag(range)
            }
        }
        .pickerStyle(.segmented)
        .padding(8)
        .gbGlassCard(radius: GBDesign.tileRadius)
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Charging")
                    .font(.gbDisplay)
                Text(bbVehicle.displayName)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .fontWeight(.semibold)
                    .frame(width: 40, height: 40)
                    .gbGlassCircle()
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 20)
    }

    private var statsGrid: some View {
        let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

        return LazyVGrid(columns: columns, spacing: 10) {
            GBStatTile(
                systemImage: "bolt.fill",
                label: "Energy added",
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
                tint: .cyan
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

            Chart(dailyEnergyPoints) { point in
                BarMark(
                    x: .value("Day", point.day),
                    y: .value("Energy", point.energyKWh)
                )
                .foregroundStyle(
                    LinearGradient(
                        colors: [.cyan, .blue],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .cornerRadius(4)
                .accessibilityLabel(point.day.formatted(date: .complete, time: .omitted))
                .accessibilityValue(
                    "\(point.energyKWh.formatted(.number.precision(.fractionLength(1)))) kilowatt-hours in \(point.sessionCount) sessions"
                )
            }
            .chartYAxis {
                AxisMarks(position: .leading) {
                    AxisGridLine()
                    AxisValueLabel()
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 5)) {
                    AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                }
            }
            .chartYAxisLabel("kWh", position: .leading)
            .chartXSelection(value: $selectedChargeDay)
            .frame(height: 180)
            .accessibilityLabel("Charging energy by day")

            if let selectedPoint {
                HStack {
                    Text(selectedPoint.day, format: .dateTime.weekday(.abbreviated).month(.abbreviated).day())
                    Spacer()
                    Text(
                        "\(selectedPoint.energyKWh.formatted(.number.precision(.fractionLength(1)))) kWh · \(selectedPoint.sessionCount) session\(selectedPoint.sessionCount == 1 ? "" : "s")"
                    )
                        .foregroundStyle(.cyan)
                }
                .font(.caption.bold())
            }
        }
        .padding(16)
        .gbGlassCard()
    }

    private var selectedPoint: ChargeEnergyDayPoint? {
        guard let selectedChargeDay else { return nil }
        return dailyEnergyPoints.min {
            abs($0.day.timeIntervalSince(selectedChargeDay))
                < abs($1.day.timeIntervalSince(selectedChargeDay))
        }
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
                .foregroundStyle(.cyan)
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

            Text("Plug in your vehicle and sessions will be tracked here automatically.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
        .padding(.horizontal, 20)
        .gbGlassCard()
    }
}
