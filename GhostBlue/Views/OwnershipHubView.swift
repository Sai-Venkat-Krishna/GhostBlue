//
//  OwnershipHubView.swift
//  GhostBlue
//
//  A single home for alerts, remote-action history, charging costs,
//  maintenance, automations, privacy, and setup health.
//

import AppIntents
import BetterBlueKit
import SwiftData
import SwiftUI
import UserNotifications

struct OwnershipHubView: View {
    let vehicles: [BBVehicle]
    @Environment(\.modelContext) private var modelContext
    @State private var selectedVIN: String
    @State private var appSettings = AppSettings.shared

    init(vehicles: [BBVehicle], initialVehicle: BBVehicle? = nil) {
        self.vehicles = vehicles
        _selectedVIN = State(initialValue: initialVehicle?.vin ?? vehicles.first?.vin ?? "")
    }

    private var vehicle: BBVehicle? {
        vehicles.first(where: { $0.vin == selectedVIN }) ?? vehicles.first
    }

    var body: some View {
        List {
            Section {
                MultiVehicleOverview(vehicles: vehicles, selectedVIN: $selectedVIN)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }

            if let vehicle {
                Section("Vehicle") {
                    NavigationLink {
                        VehicleInfoView(bbVehicle: vehicle)
                    } label: {
                        OwnershipDestinationLabel(
                            icon: "car.fill",
                            color: .blue,
                            title: "Overview",
                            detail: "Name, appearance, powertrain, widgets, and VIN"
                        )
                    }

                    NavigationLink {
                        VehicleClimateSettingsView(bbVehicle: vehicle)
                    } label: {
                        OwnershipDestinationLabel(
                            icon: "fan.fill",
                            color: .teal,
                            title: "Climate",
                            detail: "Seat heat availability and preset duration"
                        )
                    }

                    if vehicle.fuelType.hasElectricCapability {
                        NavigationLink {
                            ChargingIntelligenceView(vehicle: vehicle)
                        } label: {
                            OwnershipDestinationLabel(
                                icon: "bolt.fill",
                                color: .green,
                                title: "Charging",
                                detail: "Limits, costs, smart schedule, and budget"
                            )
                        }
                    }
                }

                Section("Intelligence") {
                    NavigationLink {
                        SmartAlertRulesView(vehicle: vehicle)
                    } label: {
                        OwnershipDestinationLabel(
                            icon: "bell.badge.fill",
                            color: .orange,
                            title: "Smart alerts",
                            detail: "Charging, security, battery, and tire rules"
                        )
                    }

                    NavigationLink {
                        RemoteCommandActivityView(selectedVIN: vehicle.vin)
                    } label: {
                        OwnershipDestinationLabel(
                            icon: "bolt.horizontal.circle.fill",
                            color: .cyan,
                            title: "Remote activity",
                            detail: "Commands from iPhone, widgets, shortcuts, and Watch"
                        )
                    }

                    NavigationLink {
                        MaintenanceCenterView(vehicle: vehicle)
                    } label: {
                        OwnershipDestinationLabel(
                            icon: "wrench.adjustable.fill",
                            color: .purple,
                            title: "Maintenance",
                            detail: "Service schedule and ownership reminders"
                        )
                    }

                    NavigationLink {
                        VehicleAutomationsView(vehicle: vehicle)
                    } label: {
                        OwnershipDestinationLabel(
                            icon: "wand.and.stars.inverse",
                            color: .indigo,
                            title: "Personal automations",
                            detail: "Battery, unplugged, and precondition reminders"
                        )
                    }
                }
            }

            if !vehicles.isEmpty {
                Section {
                    ForEach(vehicles, id: \.id) { entry in
                        Button {
                            selectedVIN = entry.vin
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(entry.displayName)
                                        .font(.headline)
                                    Text("VIN: \(entry.vin)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if entry.vin == selectedVIN {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(appSettings.accentColor)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    .onMove(perform: moveVehicles)
                    .onDelete(perform: hideVehicles)
                } header: {
                    Text("All vehicles")
                } footer: {
                    Text("Drag to reorder. Swipe to hide a vehicle from Home, widgets, and Watch.")
                }
            }

        }
        .navigationTitle("Vehicle Center")
    }

    private func moveVehicles(from source: IndexSet, to destination: Int) {
        var reordered = Array(vehicles)
        reordered.move(fromOffsets: source, toOffset: destination)

        for (index, vehicle) in reordered.enumerated() {
            vehicle.sortOrder = index
        }

        do {
            try modelContext.save()
        } catch {
            BBLogger.error(.app, "OwnershipHubView: Failed to update vehicle order: \(error)")
        }
    }

    private func hideVehicles(offsets: IndexSet) {
        for index in offsets {
            vehicles[index].isHidden = true
            do {
                try modelContext.save()
            } catch {
                BBLogger.error(.app, "OwnershipHubView: Failed to hide vehicle: \(error)")
            }
        }
    }
}

private struct OwnershipDestinationLabel: View {
    let icon: String
    let color: Color
    let title: String
    let detail: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.headline)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: icon)
                .foregroundStyle(color)
                .frame(width: 30)
        }
        .padding(.vertical, 4)
    }
}

private struct MultiVehicleOverview: View {
    let vehicles: [BBVehicle]
    @Binding var selectedVIN: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("GARAGE")
                        .font(.caption.bold())
                        .tracking(2)
                        .foregroundStyle(.secondary)
                    Text("\(vehicles.count) vehicle\(vehicles.count == 1 ? "" : "s") at a glance")
                        .font(.title3.bold())
                }
                Spacer()
                Image(systemName: "car.2.fill")
                    .font(.title2)
                    .foregroundStyle(AppSettings.shared.accentColor)
            }
            .padding(.horizontal)

            ScrollView(.horizontal) {
                HStack(spacing: 12) {
                    ForEach(vehicles) { vehicle in
                        Button {
                            withAnimation(.snappy) {
                                selectedVIN = vehicle.vin
                            }
                        } label: {
                            VehicleOverviewCard(
                                vehicle: vehicle,
                                isSelected: selectedVIN == vehicle.vin
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
                .padding(.bottom, 4)
            }
            .scrollIndicators(.hidden)
        }
        .padding(.vertical, 12)
    }
}

private struct VehicleOverviewCard: View {
    let vehicle: BBVehicle
    let isSelected: Bool

    private var energySummary: String {
        if let ev = vehicle.evStatus {
            return "\(Int(ev.evRange.percentage.rounded()))% battery"
        }
        if let gas = vehicle.gasRange {
            return "\(Int(gas.percentage.rounded()))% fuel"
        }
        return "Status unavailable"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: vehicle.lockStatus == .locked ? "lock.fill" : "lock.open.fill")
                    .foregroundStyle(vehicle.lockStatus == .locked ? .green : .orange)
                Spacer()
                if vehicle.evStatus?.charging == true {
                    Label("Charging", systemImage: "bolt.fill")
                        .font(.caption2.bold())
                        .foregroundStyle(.green)
                }
            }

            HomeVehicleImageView(
                modelName: vehicle.model,
                colorName: vehicle.vehicleImageColor.rawValue
            )
            .frame(height: 52)

            Text(vehicle.displayName)
                .font(.headline)
                .lineLimit(1)
            Text(energySummary)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
        .frame(width: 190, height: 160)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 22))
        .overlay {
            RoundedRectangle(cornerRadius: 22)
                .stroke(isSelected ? AppSettings.shared.accentColor : Color.secondary.opacity(0.2), lineWidth: isSelected ? 2 : 1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

// MARK: - Smart Alerts

private struct SmartAlertRulesView: View {
    let vehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext
    @State private var appSettings = AppSettings.shared

    var body: some View {
        Form {
            Section {
                Toggle("Enable notifications", isOn: $appSettings.notificationsEnabled)
            } footer: {
                Text("Rules are evaluated after every status refresh from the app, widgets, shortcuts, or Apple Watch.")
            }

            Section("Charging") {
                persistedToggle("Charge completed", keyPath: \.notifyChargeComplete)
                persistedToggle("Charging interrupted", keyPath: \.notifyChargeInterrupted)
                persistedToggle("Low EV battery", keyPath: \.notifyLowEVBattery)
                if vehicle.notifyLowEVBattery {
                    Stepper(
                        "Alert at \(vehicle.lowEVBatteryThreshold)%",
                        value: persistedInt(\.lowEVBatteryThreshold),
                        in: 5 ... 50,
                        step: 5
                    )
                }
            }

            Section("Security and health") {
                persistedToggle("Vehicle becomes unlocked", keyPath: \.notifyUnlocked)
                persistedToggle("Door, hood, or trunk opens", keyPath: \.notifyOpenPanels)
                persistedToggle("Low 12V battery", keyPath: \.notifyLow12VBattery)
                if vehicle.notifyLow12VBattery {
                    Stepper(
                        "12V alert at \(vehicle.low12VBatteryThreshold)%",
                        value: persistedInt(\.low12VBatteryThreshold),
                        in: 10 ... 70,
                        step: 5
                    )
                }
                persistedToggle("Tire pressure warning", keyPath: \.notifyTirePressure)
            }

            Section {
                Toggle("Silence smart alerts", isOn: $appSettings.quietHoursEnabled)
                if appSettings.quietHoursEnabled {
                    Stepper(
                        "Start: \(hourLabel(appSettings.quietHoursStart))",
                        value: $appSettings.quietHoursStart,
                        in: 0 ... 23
                    )
                    Stepper(
                        "End: \(hourLabel(appSettings.quietHoursEnd))",
                        value: $appSettings.quietHoursEnd,
                        in: 0 ... 23
                    )
                }
            } header: {
                Text("Quiet hours")
            } footer: {
                Text("Safety alerts remain visible in the vehicle status even while notifications are silenced.")
            }
        }
        .navigationTitle("Smart Alerts")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func persistedToggle(
        _ title: String,
        keyPath: ReferenceWritableKeyPath<BBVehicle, Bool>
    ) -> some View {
        Toggle(
            title,
            isOn: Binding(
                get: { vehicle[keyPath: keyPath] },
                set: {
                    vehicle[keyPath: keyPath] = $0
                    try? modelContext.save()
                }
            )
        )
    }

    private func persistedInt(
        _ keyPath: ReferenceWritableKeyPath<BBVehicle, Int>
    ) -> Binding<Int> {
        Binding(
            get: { vehicle[keyPath: keyPath] },
            set: {
                vehicle[keyPath: keyPath] = $0
                try? modelContext.save()
            }
        )
    }
}

// MARK: - Remote Activity

struct RemoteCommandActivityView: View {
    let selectedVIN: String?
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \RemoteCommandActivity.startedAt, order: .reverse)
    private var allActivities: [RemoteCommandActivity]
    @State private var showAllVehicles = false

    private var activities: [RemoteCommandActivity] {
        guard !showAllVehicles, let selectedVIN else { return allActivities }
        return allActivities.filter { $0.vin == selectedVIN }
    }

    var body: some View {
        List {
            if selectedVIN != nil {
                Picker("Vehicles", selection: $showAllVehicles) {
                    Text("This vehicle").tag(false)
                    Text("All vehicles").tag(true)
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
            }

            if activities.isEmpty {
                ContentUnavailableView(
                    "No Remote Activity",
                    systemImage: "bolt.horizontal.circle",
                    description: Text("Lock, climate, charging, widget, shortcut, and Watch commands will appear here.")
                )
                .listRowBackground(Color.clear)
            } else {
                ForEach(activities) { activity in
                    RemoteActivityRow(activity: activity)
                }
                .onDelete(perform: deleteActivities)
            }
        }
        .navigationTitle("Remote Activity")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !activities.isEmpty {
                Button("Clear", role: .destructive) {
                    for activity in activities {
                        modelContext.delete(activity)
                    }
                    try? modelContext.save()
                }
            }
        }
    }

    private func deleteActivities(at offsets: IndexSet) {
        for index in offsets {
            modelContext.delete(activities[index])
        }
        try? modelContext.save()
    }
}

private struct RemoteActivityRow: View {
    let activity: RemoteCommandActivity

    private var stateColor: Color {
        switch activity.state {
        case .sending: .orange
        case .succeeded: .green
        case .failed: .red
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: activity.state.systemImage)
                .foregroundStyle(stateColor)
                .font(.title3)
                .frame(width: 28)
                .symbolEffect(.rotate, isActive: activity.state == .sending)

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(activity.commandName)
                        .font(.headline)
                    Spacer()
                    Text(activity.state.title)
                        .font(.caption.bold())
                        .foregroundStyle(stateColor)
                }
                Text("\(activity.vehicleName) • \(activity.sourceName)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(activity.startedAt, format: .dateTime.month(.abbreviated).day().hour().minute())
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                if let error = activity.errorMessage {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Charging Intelligence

private struct ChargingIntelligenceView: View {
    let vehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext
    @Environment(\.openURL) private var openURL
    @Query(sort: \ChargeSession.startDate, order: .reverse)
    private var allSessions: [ChargeSession]

    init(vehicle: BBVehicle) {
        self.vehicle = vehicle
        let vin = vehicle.vin
        let monthStart = Calendar.current.dateInterval(of: .month, for: Date())?.start ?? .distantPast
        _allSessions = Query(filter: #Predicate<ChargeSession> { $0.vin == vin && $0.startDate >= monthStart },
                             sort: \ChargeSession.startDate, order: .reverse)
    }
    @State private var isReconcilingSmartCharging = false
    @State private var smartChargingRunMessage: String?

    private var sessions: [ChargeSession] {
        allSessions.filter { $0.vin == vehicle.vin }
    }

    private var monthSessions: [ChargeSession] {
        sessions.filter { Calendar.current.isDate($0.startDate, equalTo: Date(), toGranularity: .month) }
    }

    private var monthCost: Double {
        monthSessions.reduce(0) { result, session in
            result + (vehicle.chargingCost(for: session) ?? 0)
        }
    }

    private var monthEnergy: Double {
        monthSessions.reduce(0) { $0 + vehicle.energyDelivered(for: $1) }
    }

    private var projectedCost: Double {
        let day = max(1, Calendar.current.component(.day, from: Date()))
        let days = Calendar.current.range(of: .day, in: .month, for: Date())?.count ?? 30
        return monthCost / Double(day) * Double(days)
    }

    private var currencyCode: String {
        Locale.current.currency?.identifier ?? "USD"
    }

    var body: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    metric("Month", value: monthCost.formatted(.currency(code: currencyCode)))
                    Divider()
                    metric("Projected", value: projectedCost.formatted(.currency(code: currencyCode)))
                    Divider()
                    metric("Energy", value: "\(monthEnergy.formatted(.number.precision(.fractionLength(1)))) kWh")
                }

                if let budget = vehicle.monthlyChargingBudget, budget > 0 {
                    ProgressView(value: min(monthCost / budget, 1)) {
                        Text("Monthly budget")
                    } currentValueLabel: {
                        Text("\(monthCost.formatted(.currency(code: currencyCode))) of \(budget.formatted(.currency(code: currencyCode)))")
                            .font(.caption)
                    }
                    .tint(monthCost > budget ? .red : .green)
                }
            } header: {
                Text("This month")
            }

            Section {
                NavigationLink(destination: ChargeLimitSettingsContent(vehicle: vehicle)) {
                    HStack {
                        Label("Charge Limits", systemImage: "battery.100.bolt")
                        Spacer()
                        if let evStatus = vehicle.evStatus,
                           let acTarget = evStatus.targetSocAC,
                           let dcTarget = evStatus.targetSocDC {
                            Text("AC: \(Int(acTarget))% / DC: \(Int(dcTarget))%")
                                .foregroundColor(.secondary)
                                .font(.caption)
                        }
                    }
                }

                NavigationLink(destination: ChargingCostSettingsView(vehicle: vehicle)) {
                    HStack {
                        Label("Charging Costs", systemImage: "dollarsign.circle")
                        Spacer()
                        if let homeRate = vehicle.offPeakElectricityRatePerKWh {
                            Text("Home \(homeRate.formatted(.currency(code: currencyCode)))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } header: {
                Text("This vehicle")
            }

            SmartChargingScheduleSection(vehicle: vehicle)

            Section {
                OptionalCurrencyField(
                    title: "Other charging / kWh",
                    value: optionalDouble(\.electricityRatePerKWh)
                )
                OptionalCurrencyField(
                    title: "Home super off-peak / kWh",
                    value: optionalDouble(\.offPeakElectricityRatePerKWh)
                )
                OptionalCurrencyField(
                    title: "Home on-peak / kWh",
                    value: optionalDouble(\.peakElectricityRatePerKWh)
                )
                OptionalCurrencyField(
                    title: "DC fast default / kWh",
                    value: optionalDouble(\.dcFastChargingRatePerKWh)
                )
                Stepper(
                    "Off-peak starts \(hourLabel(vehicle.offPeakStartHour))",
                    value: persistedInt(\.offPeakStartHour),
                    in: 0 ... 23
                )
                Stepper(
                    "Off-peak ends \(hourLabel(vehicle.offPeakEndHour))",
                    value: persistedInt(\.offPeakEndHour),
                    in: 0 ... 23
                )
                OptionalCurrencyField(
                    title: "Monthly budget",
                    value: optionalDouble(\.monthlyChargingBudget)
                )
            } header: {
                Text("Tariff")
            } footer: {
                Text("New home sessions save the tariff and split estimated costs across time periods. SDG&E on-peak is 4–9 PM. If no on-peak rate is set, the other-charging rate is used. DC sessions use the DC default. Receipt totals override estimates.")
            }

            Section {
                if vehicle.homeLatitude != nil {
                    Label("Home geofence saved privately on this device", systemImage: "checkmark.shield.fill")
                        .foregroundStyle(.green)
                    Button("Update from vehicle location") {
                        saveCurrentLocationAsHome()
                    }
                    Button("Remove home geofence", role: .destructive) {
                        vehicle.homeLatitude = nil
                        vehicle.homeLongitude = nil
                        try? modelContext.save()
                    }
                } else {
                    Button("Use current vehicle location as home") {
                        saveCurrentLocationAsHome()
                    }
                    .disabled(vehicle.location == nil)
                }
                Stepper(
                    "Privacy radius: \(Int(vehicle.homePrivacyRadiusMeters)) m",
                    value: persistedDouble(\.homePrivacyRadiusMeters),
                    in: 100 ... 1_500,
                    step: 50
                )
            } header: {
                Text("Home charging")
            } footer: {
                Text("The exact home coordinate stays in the shared on-device store. GhostBlue uses it to classify sessions and redact private exports.")
            }

            Section {
                Button {
                    if let url = URL(string: "https://maps.apple.com/?q=EV%20Charging") {
                        openURL(url)
                    }
                } label: {
                    Label("Find nearby charging stations", systemImage: "map.fill")
                }
            } footer: {
                Text("Station availability and payment require a supported charging-network provider; Apple Maps is used as the privacy-preserving fallback.")
            }

            Section("Recent sessions") {
                if sessions.isEmpty {
                    Text("Charging sessions will be detected automatically from status refreshes.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(sessions.prefix(12)) { session in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(session.startDate, format: .dateTime.month(.abbreviated).day().hour().minute())
                                    .font(.headline)
                                Spacer()
                                Text("\(vehicle.energyDelivered(for: session).formatted(.number.precision(.fractionLength(1)))) kWh")
                                    .foregroundStyle(.secondary)
                            }
                            Picker(
                                "Location type",
                                selection: Binding(
                                    get: { session.locationType },
                                    set: {
                                        session.locationType = $0
                                        try? modelContext.save()
                                    }
                                )
                            ) {
                                ForEach(ChargingLocationType.allCases) { type in
                                    Label(type.title, systemImage: type.systemImage).tag(type)
                                }
                            }
                            .pickerStyle(.menu)
                        }
                        .padding(.vertical, 3)
                    }
                }
            }
        }
        .navigationTitle("Charging")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func metric(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.subheadline.bold())
                .lineLimit(1)
                .minimumScaleFactor(0.65)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func optionalDouble(
        _ keyPath: ReferenceWritableKeyPath<BBVehicle, Double?>
    ) -> Binding<Double?> {
        Binding(
            get: { vehicle[keyPath: keyPath] },
            set: {
                vehicle[keyPath: keyPath] = $0
                try? modelContext.save()
            }
        )
    }

    private func persistedInt(
        _ keyPath: ReferenceWritableKeyPath<BBVehicle, Int>
    ) -> Binding<Int> {
        Binding(
            get: { vehicle[keyPath: keyPath] },
            set: {
                vehicle[keyPath: keyPath] = $0
                try? modelContext.save()
            }
        )
    }

    private func persistedBool(
        _ keyPath: ReferenceWritableKeyPath<BBVehicle, Bool>
    ) -> Binding<Bool> {
        Binding(
            get: { vehicle[keyPath: keyPath] },
            set: {
                vehicle[keyPath: keyPath] = $0
                try? modelContext.save()
            }
        )
    }

    private func persistedDouble(
        _ keyPath: ReferenceWritableKeyPath<BBVehicle, Double>
    ) -> Binding<Double> {
        Binding(
            get: { vehicle[keyPath: keyPath] },
            set: {
                vehicle[keyPath: keyPath] = $0
                try? modelContext.save()
            }
        )
    }

    private func saveCurrentLocationAsHome() {
        vehicle.homeLatitude = vehicle.location?.latitude
        vehicle.homeLongitude = vehicle.location?.longitude
        try? modelContext.save()
    }

    private func reconcileSmartChargingNow() {
        guard !isReconcilingSmartCharging else { return }
        isReconcilingSmartCharging = true
        smartChargingRunMessage = nil

        Task { @MainActor in
            defer { isReconcilingSmartCharging = false }
            guard let account = vehicle.account else {
                smartChargingRunMessage = "Account not found for this vehicle."
                return
            }
            do {
                let result = try await account.reconcileSmartCharging(
                    for: vehicle,
                    modelContext: modelContext
                )
                smartChargingRunMessage = result.message
            } catch {
                smartChargingRunMessage = error.localizedDescription
            }
        }
    }
}

private struct OptionalCurrencyField: View {
    let title: String
    @Binding var value: Double?
    @State private var text = ""

    init(title: String, value: Binding<Double?>) {
        self.title = title
        _value = value
        _text = State(initialValue: value.wrappedValue.map { String(format: "%.3f", $0) } ?? "")
    }

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            TextField("Not set", text: $text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 100)
                .onChange(of: text) { _, newValue in
                    value = Double(newValue)
                }
        }
    }
}

// MARK: - Maintenance

private struct MaintenanceCenterView: View {
    let vehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \MaintenanceReminder.createdAt, order: .reverse)
    private var allReminders: [MaintenanceReminder]
    @State private var showingAdd = false

    private var reminders: [MaintenanceReminder] {
        allReminders.filter { $0.vin == vehicle.vin }
    }

    var body: some View {
        List {
            if !reminders.isEmpty {
                Section {
                    HStack {
                        Label("Recorded service cost", systemImage: "dollarsign.circle.fill")
                        Spacer()
                        Text(
                            reminders.compactMap(\.cost).reduce(0, +),
                            format: .currency(code: Locale.current.currency?.identifier ?? "USD")
                        )
                        .fontWeight(.semibold)
                    }
                }
            }

            if reminders.isEmpty {
                ContentUnavailableView(
                    "No Maintenance Reminders",
                    systemImage: "wrench.adjustable",
                    description: Text("Track service by date or odometer and receive an on-device reminder.")
                )
                .listRowBackground(Color.clear)
            } else {
                ForEach(reminders) { reminder in
                    Button {
                        reminder.completedAt = reminder.isCompleted ? nil : Date()
                        try? modelContext.save()
                    } label: {
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: reminder.isCompleted ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(reminder.isCompleted ? .green : .secondary)
                                .font(.title3)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(reminder.title)
                                    .font(.headline)
                                    .strikethrough(reminder.isCompleted)
                                if let dueDate = reminder.dueDate {
                                    Label(
                                        dueDate.formatted(date: .abbreviated, time: .omitted),
                                        systemImage: "calendar"
                                    )
                                    .font(.caption)
                                    .foregroundStyle(dueDate < Date() && !reminder.isCompleted ? .red : .secondary)
                                }
                                if let mileage = reminder.dueOdometer {
                                    Label(
                                        "\(Int(mileage).formatted()) \(vehicle.odometer.units.rawValue)",
                                        systemImage: "gauge.with.dots.needle.50percent"
                                    )
                                    .font(.caption)
                                    .foregroundStyle(
                                        vehicle.odometer.length >= mileage && !reminder.isCompleted ? .red : .secondary
                                    )
                                }
                                if !reminder.notes.isEmpty {
                                    Text(reminder.notes)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                if let cost = reminder.cost {
                                    Text(
                                        cost,
                                        format: .currency(code: Locale.current.currency?.identifier ?? "USD")
                                    )
                                    .font(.caption.bold())
                                }
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
                .onDelete(perform: delete)
            }

            Section("Quick add") {
                Button("Tire rotation") {
                    addPreset(title: "Tire rotation", mileageDelta: 7_500)
                }
                Button("Cabin air filter") {
                    addPreset(title: "Cabin air filter", mileageDelta: 15_000)
                }
                Button("Annual inspection") {
                    addPreset(title: "Annual inspection", monthDelta: 12)
                }
                Button("Registration renewal") {
                    addPreset(title: "Registration renewal", monthDelta: 12)
                }
                Button("Insurance renewal") {
                    addPreset(title: "Insurance renewal", monthDelta: 6)
                }
            }
        }
        .navigationTitle("Maintenance")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Button("Add", systemImage: "plus") {
                showingAdd = true
            }
        }
        .sheet(isPresented: $showingAdd) {
            AddMaintenanceReminderView(vehicle: vehicle)
        }
    }

    private func addPreset(title: String, mileageDelta: Double? = nil, monthDelta: Int? = nil) {
        let reminder = MaintenanceReminder(
            vin: vehicle.vin,
            title: title,
            dueDate: monthDelta.flatMap { Calendar.current.date(byAdding: .month, value: $0, to: Date()) },
            dueOdometer: mileageDelta.map { vehicle.odometer.length + $0 }
        )
        modelContext.insert(reminder)
        try? modelContext.save()
    }

    private func delete(at offsets: IndexSet) {
        for index in offsets {
            modelContext.delete(reminders[index])
        }
        try? modelContext.save()
    }
}

private struct AddMaintenanceReminderView: View {
    let vehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var notes = ""
    @State private var hasDueDate = true
    @State private var dueDate = Calendar.current.date(byAdding: .month, value: 6, to: Date()) ?? Date()
    @State private var hasMileage = false
    @State private var dueMileage = 0.0
    @State private var costText = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Service or task", text: $title)
                TextField("Notes", text: $notes, axis: .vertical)
                Toggle("Due date", isOn: $hasDueDate)
                if hasDueDate {
                    DatePicker("Date", selection: $dueDate, displayedComponents: .date)
                }
                Toggle("Odometer target", isOn: $hasMileage)
                if hasMileage {
                    TextField("Mileage", value: $dueMileage, format: .number)
                        .keyboardType(.numberPad)
                }
                TextField("Cost (optional)", text: $costText)
                    .keyboardType(.decimalPad)
            }
            .navigationTitle("New Reminder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear {
                dueMileage = vehicle.odometer.length + 7_500
            }
        }
    }

    private func save() {
        let reminder = MaintenanceReminder(
            vin: vehicle.vin,
            title: title,
            notes: notes,
            dueDate: hasDueDate ? dueDate : nil,
            dueOdometer: hasMileage ? dueMileage : nil,
            cost: Double(costText)
        )
        modelContext.insert(reminder)
        try? modelContext.save()

        if hasDueDate {
            let content = UNMutableNotificationContent()
            content.title = "Maintenance due"
            content.body = "\(title) is due for \(vehicle.displayName)."
            content.sound = .default
            content.userInfo = ["vin": vehicle.vin]
            var components = Calendar.current.dateComponents([.year, .month, .day], from: dueDate)
            components.hour = 9
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            let request = UNNotificationRequest(
                identifier: "maintenance-\(reminder.id.uuidString)",
                content: content,
                trigger: trigger
            )
            Task { try? await UNUserNotificationCenter.current().add(request) }
        }
        dismiss()
    }
}

// MARK: - Automations

private struct VehicleAutomationsView: View {
    let vehicle: BBVehicle
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \VehicleAutomation.createdAt, order: .reverse)
    private var allRules: [VehicleAutomation]

    private var rules: [VehicleAutomation] {
        allRules.filter { $0.vin == vehicle.vin }
    }

    var body: some View {
        List {
            Section {
                Label(
                    "Automations run as private on-device rules. Status rules are evaluated whenever GhostBlue receives a refresh.",
                    systemImage: "lock.shield"
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            if rules.isEmpty {
                ContentUnavailableView(
                    "No Automations",
                    systemImage: "wand.and.stars",
                    description: Text("Choose a safe starter rule below.")
                )
                .listRowBackground(Color.clear)
            } else {
                Section("Your rules") {
                    ForEach(rules) { rule in
                        HStack {
                            Image(systemName: rule.kind.systemImage)
                                .foregroundStyle(AppSettings.shared.accentColor)
                                .frame(width: 28)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(rule.title)
                                    .font(.headline)
                                Text(ruleDetail(rule))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Toggle(
                                "Enabled",
                                isOn: Binding(
                                    get: { rule.isEnabled },
                                    set: {
                                        rule.isEnabled = $0
                                        try? modelContext.save()
                                        updateScheduledReminder(for: rule)
                                    }
                                )
                            )
                            .labelsHidden()
                        }
                    }
                    .onDelete(perform: delete)
                }
            }

            Section {
                Button {
                    add(kind: .lowBattery, title: "Charge \(vehicle.displayName)", threshold: 20)
                } label: {
                    Label("Notify at 20% battery", systemImage: "battery.25percent")
                }
                Button {
                    add(kind: .unplugged, title: "\(vehicle.displayName) was unplugged")
                } label: {
                    Label("Notify when unplugged", systemImage: "powerplug")
                }
                Button {
                    add(
                        kind: .preconditionReminder,
                        title: "Precondition \(vehicle.displayName)",
                        hour: 7,
                        minute: 30
                    )
                } label: {
                    Label("Daily precondition reminder at 7:30 AM", systemImage: "fan")
                }
            } header: {
                Text("Starter rules")
            } footer: {
                Text("Automatic remote commands are intentionally not sent without confirmation: manufacturer APIs and iOS background execution cannot guarantee a safe, timely action. Reminder notifications deep-link to the vehicle so you can confirm it.")
            }
        }
        .navigationTitle("Automations")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func ruleDetail(_ rule: VehicleAutomation) -> String {
        switch rule.kind {
        case .lowBattery: "When battery crosses \(rule.threshold)%"
        case .unplugged: "When a refresh detects the cable was removed"
        case .preconditionReminder: "Every day at \(hourLabel(rule.hour, minute: rule.minute))"
        }
    }

    private func add(
        kind: VehicleAutomationKind,
        title: String,
        threshold: Int = 20,
        hour: Int = 7,
        minute: Int = 30
    ) {
        guard !rules.contains(where: { $0.kind == kind }) else { return }
        let rule = VehicleAutomation(
            vin: vehicle.vin,
            title: title,
            kind: kind,
            threshold: threshold,
            hour: hour,
            minute: minute
        )
        modelContext.insert(rule)
        try? modelContext.save()
        updateScheduledReminder(for: rule)
    }

    private func delete(at offsets: IndexSet) {
        for index in offsets {
            let rule = rules[index]
            UNUserNotificationCenter.current().removePendingNotificationRequests(
                withIdentifiers: ["automation-\(rule.id.uuidString)"]
            )
            modelContext.delete(rule)
        }
        try? modelContext.save()
    }

    private func updateScheduledReminder(for rule: VehicleAutomation) {
        let identifier = "automation-\(rule.id.uuidString)"
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [identifier])
        guard rule.isEnabled, rule.kind == .preconditionReminder else { return }
        let content = UNMutableNotificationContent()
        content.title = rule.title
        content.body = "Open GhostBlue to review conditions and start climate."
        content.sound = .default
        content.userInfo = ["vin": vehicle.vin]
        let trigger = UNCalendarNotificationTrigger(
            dateMatching: DateComponents(hour: rule.hour, minute: rule.minute),
            repeats: true
        )
        Task {
            try? await UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)
            )
        }
    }
}

// MARK: - Privacy & Setup

/// App-level, so it lives in Settings rather than Vehicle Center: it must
/// stay reachable before any vehicle has loaded.
struct PrivacySecurityView: View {
    @Environment(\.modelContext) private var modelContext
    @State private var appSettings = AppSettings.shared
    @Query private var trips: [TripRecord]
    @Query private var chargeSessions: [ChargeSession]
    @Query private var activities: [RemoteCommandActivity]
    @Query private var reminders: [MaintenanceReminder]
    @Query private var automations: [VehicleAutomation]
    @State private var showingDelete = false

    private var exportText: String {
        let summary: [String: Any] = [
            "exportedAt": ISO8601DateFormatter().string(from: Date()),
            "privacy": [
                "exactVehicleLocationHidden": appSettings.hideVehicleLocation,
                "privateLocationRedaction": appSettings.redactPrivateLocations
            ],
            "counts": [
                "trips": trips.count,
                "chargingSessions": chargeSessions.count,
                "remoteCommands": activities.count,
                "maintenanceReminders": reminders.count,
                "automations": automations.count
            ],
            "recordedMaintenanceCost": reminders.compactMap(\.cost).reduce(0, +),
            "remoteActivity": activities.map {
                [
                    "vehicle": $0.vehicleName,
                    "command": $0.commandName,
                    "source": $0.sourceName,
                    "state": $0.state.title,
                    "date": ISO8601DateFormatter().string(from: $0.startedAt)
                ]
            }
        ]
        guard let data = try? JSONSerialization.data(
            withJSONObject: summary,
            options: [.prettyPrinted, .sortedKeys]
        ) else { return "{}" }
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    var body: some View {
        Form {
            Section {
                Toggle("Require Face ID or device passcode", isOn: $appSettings.requireBiometricUnlock)
            } header: {
                Text("App protection")
            } footer: {
                Text("GhostBlue locks after leaving the app. Authentication is handled by iOS; biometric data never enters GhostBlue.")
            }

            Section {
                Toggle("Hide exact vehicle location", isOn: $appSettings.hideVehicleLocation)
                Toggle("Redact private locations in exports", isOn: $appSettings.redactPrivateLocations)
            } header: {
                Text("Location privacy")
            } footer: {
                Text("Home coordinates and privacy radii are stored locally. Hiding the location replaces map content with a privacy card.")
            }

            Section {
                ShareLink(
                    item: exportText,
                    preview: SharePreview("GhostBlue ownership export")
                ) {
                    Label("Export ownership summary", systemImage: "square.and.arrow.up")
                }

                Button("Delete history and ownership data", role: .destructive) {
                    showingDelete = true
                }
            } header: {
                Text("Your data")
            } footer: {
                Text("Account credentials and vehicles are not included in this deletion. Use Settings → Clear All Data to remove the entire app database.")
            }
        }
        .navigationTitle("Privacy & Security")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Delete ownership data?", isPresented: $showingDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                try? modelContext.delete(model: TripRecord.self)
                try? modelContext.delete(model: ChargeSession.self)
                try? modelContext.delete(model: RemoteCommandActivity.self)
                try? modelContext.delete(model: MaintenanceReminder.self)
                try? modelContext.delete(model: VehicleAutomation.self)
                try? modelContext.save()
            }
        } message: {
            Text("This permanently removes trips, charging sessions, remote activity, maintenance reminders, and automations.")
        }
    }
}

/// App-level, so it lives in Settings rather than Vehicle Center: a new
/// user needs the checklist precisely when no vehicle exists yet.
struct OwnershipSetupView: View {
    let vehicles: [BBVehicle]
    @State private var appSettings = AppSettings.shared

    private var completedCount: Int {
        [
            !vehicles.isEmpty,
            appSettings.notificationsEnabled,
            appSettings.hasCompletedOwnershipOnboarding,
            vehicles.contains { $0.customName != nil },
            vehicles.contains {
                $0.electricityRatePerKWh != nil
                    || $0.offPeakElectricityRatePerKWh != nil
                    || $0.dcFastChargingRatePerKWh != nil
            }
        ].filter { $0 }.count
    }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Text("\(completedCount) of 5 complete")
                        .font(.title2.bold())
                    ProgressView(value: Double(completedCount), total: 5)
                        .tint(AppSettings.shared.accentColor)
                    Text("GhostBlue remains fully usable while you finish setup.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 6)
            }

            Section("Checklist") {
                setupRow(
                    done: !vehicles.isEmpty,
                    title: "Connect a vehicle",
                    detail: "Add your Hyundai, Kia, or a local Fake demo account."
                )
                setupRow(
                    done: appSettings.notificationsEnabled,
                    title: "Enable notifications",
                    detail: "Required for alerts, maintenance, and automation reminders."
                )
                setupRow(
                    done: vehicles.contains { $0.customName != nil },
                    title: "Personalize your vehicle",
                    detail: "Choose a name and the shared vehicle image color."
                )
                setupRow(
                    done: vehicles.contains {
                        $0.electricityRatePerKWh != nil
                            || $0.offPeakElectricityRatePerKWh != nil
                            || $0.dcFastChargingRatePerKWh != nil
                    },
                    title: "Add your electricity tariff",
                    detail: "Unlock accurate charging costs and monthly budget tracking."
                )
                setupRow(
                    done: appSettings.hasCompletedOwnershipOnboarding,
                    title: "Add widgets and Apple Watch",
                    detail: "Long-press the Home Screen to add a widget; install the Watch app from the Watch app."
                )
            }

            Section {
                NavigationLink {
                    AddAccountView()
                } label: {
                    Label("Add account or explore Fake demo", systemImage: "person.badge.plus")
                }

                Button(appSettings.hasCompletedOwnershipOnboarding ? "Setup marked complete" : "Mark companion setup complete") {
                    appSettings.hasCompletedOwnershipOnboarding.toggle()
                }
            } header: {
                Text("Demo and companion setup")
            } footer: {
                Text("Fake mode keeps all data on-device and lets you safely try controls, failures, charging, trips, widgets, and Live Activities without a real car.")
            }
        }
        .navigationTitle("Setup Guide")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func setupRow(done: Bool, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: done ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(done ? .green : .secondary)
                .font(.title3)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.headline)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 3)
    }
}

private func hourLabel(_ hour: Int, minute: Int = 0) -> String {
    var components = DateComponents()
    components.hour = hour
    components.minute = minute
    let date = Calendar.current.date(from: components) ?? Date()
    return date.formatted(date: .omitted, time: .shortened)
}
