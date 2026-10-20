//
//  WatchVehicleView.swift
//  GhostBlueWatch Watch App
//
//  Created by Mark Schmidt on 8/29/25.
//

import BetterBlueKit
import SwiftData
import SwiftUI

struct WatchVehicleView: View {
    let vehicle: BBVehicle
    @State private var appSettings = AppSettings.shared
    @State private var isRefreshing = false
    @State private var lastRefreshDate: Date?
    @State private var showingSettings = false
    @State private var companionSyncRevision = 0
    @Query private var allVehicles: [BBVehicle]
    @Environment(\.modelContext) private var modelContext

    // Get the latest background info for this vehicle from the query
    private var currentVehicle: BBVehicle {
        allVehicles.first(where: { $0.vin == vehicle.vin }) ?? vehicle
    }

    private var syncedAppearance: VehicleAppearanceHint? {
        _ = companionSyncRevision
        return VehicleAppearanceStore.hint(for: currentVehicle.vin)
    }

    private var batteryPercentage: Int? {
        guard currentVehicle.fuelType.hasElectricCapability, let evStatus = currentVehicle.evStatus else { return nil }
        return Int(evStatus.evRange.percentage)
    }

    private var fuelPercentage: Int? {
        guard !currentVehicle.fuelType.hasElectricCapability, let gasRange = currentVehicle.gasRange else { return nil }
        return Int(gasRange.percentage)
    }

    private var rangeText: String {
        if currentVehicle.fuelType.hasElectricCapability, let evStatus = currentVehicle.evStatus {
            let range = evStatus.evRange.range.length > 0 ?
                evStatus.evRange.range.units.format(evStatus.evRange.range.length, to: appSettings.preferredDistanceUnit) :
                "--"
            return "\(Int(evStatus.evRange.percentage))% • \(range)"
        } else if let gasRange = currentVehicle.gasRange {
            let range = gasRange.range.length > 0 ?
                gasRange.range.units.format(gasRange.range.length, to: appSettings.preferredDistanceUnit) :
                "--"
            return "\(Int(gasRange.percentage))% • \(range)"
        }
        return "No data"
    }

    private var isLocked: Bool {
        currentVehicle.lockStatus == .locked
    }

    private var isClimateRunning: Bool {
        currentVehicle.climateStatus?.airControlOn ?? false
    }

    private var isPluggedIn: Bool {
        guard currentVehicle.fuelType.hasElectricCapability, let evStatus = currentVehicle.evStatus else { return false }
        return evStatus.pluggedIn
    }

    private var isCharging: Bool {
        guard currentVehicle.fuelType.hasElectricCapability, let evStatus = currentVehicle.evStatus else { return false }
        return evStatus.charging
    }

    // VehicleAction instances
    private var lockAction: MainVehicleAction {
        MainVehicleAction(
            action: { statusUpdater in
                try await performLockAction(shouldLock: true, statusUpdater: statusUpdater)
            },
            icon: Image(systemName: "lock.open.fill"),
            label: "Lock",
            inProgressLabel: "Locking",
            completedText: "Locked",
            color: .red,
            stateLabel: "Unlocked"
        )
    }

    private var unlockAction: MainVehicleAction {
        MainVehicleAction(
            action: { statusUpdater in
                try await performLockAction(shouldLock: false, statusUpdater: statusUpdater)
            },
            icon: Image(systemName: "lock.fill"),
            label: "Unlock",
            inProgressLabel: "Unlocking",
            completedText: "Unlocked",
            color: .green,
            stateLabel: "Locked"
        )
    }

    private var startClimateAction: MainVehicleAction {
        MainVehicleAction(
            action: { statusUpdater in
                try await performClimateAction(shouldStart: true, statusUpdater: statusUpdater)
            },
            icon: Image(systemName: "fan.slash"),
            label: "Start Climate",
            inProgressLabel: "Starting",
            completedText: "Started",
            color: .secondary,
            stateLabel: "Climate Off"
        )
    }

    private var stopClimateAction: MainVehicleAction {
        MainVehicleAction(
            action: { statusUpdater in
                try await performClimateAction(shouldStart: false, statusUpdater: statusUpdater)
            },
            icon: Image(systemName: "fan"),
            label: "Stop Climate",
            inProgressLabel: "Stopping",
            completedText: "Stopped",
            color: .blue,
            stateLabel: "Climate Running",
            shouldRotate: true
        )
    }

    private var startChargeAction: MainVehicleAction {
        MainVehicleAction(
            action: { statusUpdater in
                try await performChargeAction(shouldStart: true, statusUpdater: statusUpdater)
            },
            icon: Image(systemName: "bolt.slash"),
            label: "Start Charge",
            inProgressLabel: "Starting",
            completedText: "Charging",
            color: .secondary,
            stateLabel: "Not Charging"
        )
    }

    private var stopChargeAction: MainVehicleAction {
        MainVehicleAction(
            action: { statusUpdater in
                try await performChargeAction(shouldStart: false, statusUpdater: statusUpdater)
            },
            icon: Image(systemName: "bolt.fill"),
            label: "Stop Charge",
            inProgressLabel: "Stopping",
            completedText: "Stopped",
            color: .green,
            stateLabel: "Charging",
            shouldPulse: true
        )
    }

    private var classicScroll: some View {
        ScrollView {
            VStack(spacing: 8) {
                //                // Vehicle name at the top
                VStack {
                    // Status info with inline refresh button
                    HStack(spacing: 4) {
                        Button {
                            showingSettings = true
                        } label: {
                            VStack(spacing: 4) {
                                HStack {
                                    Text(currentVehicle.displayName)
                                        .font(.headline)
                                        .fontWeight(.bold)
                                        .multilineTextAlignment(.center)
                                        .foregroundColor(.primary)
                                    Spacer()
                                }
                                HStack {
                                    Image(systemName: currentVehicle.fuelType.hasElectricCapability ? "bolt.fill" : "fuelpump.fill")
                                        .foregroundColor(currentVehicle.fuelType.hasElectricCapability ? .green : .orange)
                                        .font(.caption)

                                    Text(rangeText)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                    Spacer()
                                }
                                if let lastUpdated = currentVehicle.lastUpdated {
                                    HStack {
                                        Text(formatUpdateTime(lastUpdated))
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                        Spacer()
                                    }
                                }
                            }
                        }
                        .buttonStyle(.plain)

                        Spacer()

                        // Inline refresh button
                        Button {
                            Task {
                                await refreshStatus()
                            }
                        } label: {
                            if isRefreshing {
                                ProgressView()
                                    .scaleEffect(0.7)
                                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            } else {
                                Image(systemName: "arrow.clockwise")
                                    .font(.system(size: 12, weight: .medium))
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .frame(width: 30, height: 30)
                        .disabled(isRefreshing)
                    }
                }

                WatchVehicleImageView(
                    modelName: syncedAppearance?.model ?? currentVehicle.model,
                    colorName: syncedAppearance?.color ?? currentVehicle.vehicleImageColor.rawValue
                )
                .frame(height: 54)
                .frame(maxWidth: .infinity)

                // Context-sensitive action buttons
                VStack(spacing: 8) {
                    // Lock/Unlock button (context-sensitive)
                    WatchVehicleButton(
                        currentAction: isLocked ? unlockAction : lockAction,
                        allActions: [lockAction, unlockAction],
                        menuLabel: "Door Actions",
                        vehicle: vehicle,
                    )

                    // Climate button (context-sensitive)
                    WatchVehicleButton(
                        currentAction: isClimateRunning ? stopClimateAction : startClimateAction,
                        allActions: [startClimateAction, stopClimateAction],
                        menuLabel: "Climate Actions",
                        vehicle: vehicle,
                    )

                    // Charge button (only for plugged-in electric vehicles)
                    if isPluggedIn {
                        WatchVehicleButton(
                            currentAction: isCharging ? stopChargeAction : startChargeAction,
                            allActions: [startChargeAction, stopChargeAction],
                            menuLabel: "Charge Actions",
                            vehicle: vehicle,
                        )
                    }
                }
            }
            .padding()
        }
    }

    var body: some View {
        Group {
            if appSettings.appTheme == .rivian {
                rivianScroll
            } else {
                classicScroll
            }
        }
        .onAppear {
            // Auto-refresh if data is missing or older than 5 minutes, from
            // the server's snapshot: opening the app must not wake the car.
            if currentVehicle.lastUpdated.map({ $0 < Date().addingTimeInterval(-300) }) ?? true {
                Task {
                    await refreshStatus(forced: false)
                }
            }
        }
        .sheet(isPresented: $showingSettings) {
            WatchVehicleSettingsView(vehicle: currentVehicle)
        }
        .onReceive(NotificationCenter.default.publisher(for: .companionPreferencesDidChange)) { _ in
            companionSyncRevision += 1
        }
        //        .navigationTitle(vehicle.displayName)
    }

    // MARK: Rivian theme

    private var levelPercentage: Int? { batteryPercentage ?? fuelPercentage }

    private var rangeOnly: String {
        let unit = appSettings.preferredDistanceUnit
        if currentVehicle.fuelType.hasElectricCapability, let evStatus = currentVehicle.evStatus,
           evStatus.evRange.range.length > 0 {
            return evStatus.evRange.range.units.format(evStatus.evRange.range.length, to: unit)
        }
        if let gasRange = currentVehicle.gasRange, gasRange.range.length > 0 {
            return gasRange.range.units.format(gasRange.range.length, to: unit)
        }
        return "--"
    }

    private var rivianStatus: (text: String, tint: Color) {
        if isCharging { return ("Charging", RVTokens.energy) }
        if isPluggedIn { return ("Plugged in", RVTokens.energy) }
        if isClimateRunning { return ("Climate on", RVTokens.climate) }
        switch currentVehicle.lockStatus {
        case .locked: return ("Locked", RVTokens.inkSecondary)
        case .unlocked: return ("Unlocked", RVTokens.service)
        default: return ("Parked", RVTokens.inkSecondary)
        }
    }

    /// Rivian layout: charge figure first, slim battery bar, the car, then
    /// the same context-sensitive commands as the classic layout.
    private var rivianScroll: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top, spacing: 6) {
                    Button {
                        showingSettings = true
                    } label: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(currentVehicle.displayName)
                                .font(.headline)
                                .fontWeight(.bold)
                                .foregroundStyle(RVTokens.ink)
                                .lineLimit(1)
                            Text(rivianStatus.text)
                                .font(.caption2)
                                .foregroundStyle(rivianStatus.tint)
                                .lineLimit(1)
                        }
                    }
                    .buttonStyle(.plain)

                    Spacer(minLength: 4)

                    Button {
                        Task {
                            await refreshStatus()
                        }
                    } label: {
                        Group {
                            if isRefreshing {
                                ProgressView()
                                    .scaleEffect(0.6)
                            } else {
                                Image(systemName: "arrow.clockwise")
                                    .font(.system(size: 12, weight: .semibold))
                            }
                        }
                        .foregroundStyle(RVTokens.commandInactiveIcon)
                        .frame(width: 30, height: 30)
                        .background(Circle().fill(RVTokens.commandInactiveFill))
                    }
                    .buttonStyle(.plain)
                    .disabled(isRefreshing)
                    .accessibilityLabel("Refresh")
                }

                HStack(alignment: .firstTextBaseline, spacing: 5) {
                    HStack(alignment: .firstTextBaseline, spacing: 1) {
                        Text(levelPercentage.map(String.init) ?? "--")
                            .font(.system(size: 34, weight: .bold, design: .rounded))
                        Text("%")
                            .font(.system(size: 15, weight: .semibold, design: .rounded))
                            .foregroundStyle(RVTokens.inkSecondary)
                    }
                    .foregroundStyle(RVTokens.ink)
                    Text(rangeOnly)
                        .font(.system(size: 13, weight: .semibold, design: .rounded))
                        .foregroundStyle(RVTokens.inkSecondary)
                }
                .lineLimit(1)
                .minimumScaleFactor(0.7)

                GeometryReader { geo in
                    let level = levelPercentage.map(Double.init)
                    ZStack(alignment: .leading) {
                        Capsule().fill(RVTokens.surfaceMuted)
                        Capsule()
                            .fill(RVTokens.battery(level))
                            .frame(width: max(5, geo.size.width * min(max((level ?? 0) / 100, 0), 1)))
                    }
                }
                .frame(height: 5)

                WatchVehicleImageView(
                    modelName: syncedAppearance?.model ?? currentVehicle.model,
                    colorName: syncedAppearance?.color ?? currentVehicle.vehicleImageColor.rawValue
                )
                .frame(height: 54)
                .frame(maxWidth: .infinity)

                VStack(spacing: 8) {
                    WatchVehicleButton(
                        currentAction: isLocked ? unlockAction : lockAction,
                        allActions: [lockAction, unlockAction],
                        menuLabel: "Door Actions",
                        vehicle: vehicle,
                        rivianStyle: true
                    )

                    WatchVehicleButton(
                        currentAction: isClimateRunning ? stopClimateAction : startClimateAction,
                        allActions: [startClimateAction, stopClimateAction],
                        menuLabel: "Climate Actions",
                        vehicle: vehicle,
                        rivianStyle: true
                    )

                    if isPluggedIn {
                        WatchVehicleButton(
                            currentAction: isCharging ? stopChargeAction : startChargeAction,
                            allActions: [startChargeAction, stopChargeAction],
                            menuLabel: "Charge Actions",
                            vehicle: vehicle,
                            rivianStyle: true
                        )
                    }
                }

                if let lastUpdated = currentVehicle.lastUpdated {
                    Text(formatUpdateTime(lastUpdated))
                        .font(.caption2)
                        .foregroundStyle(RVTokens.inkSecondary)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 6)
        }
    }

    private func formatUpdateTime(_ date: Date) -> String {
        let calendar = Calendar.current
        let timeFormatter = DateFormatter()
        timeFormatter.timeStyle = .short

        if calendar.isDateInToday(date) {
            return "Today at \(timeFormatter.string(from: date))"
        } else if calendar.isDateInYesterday(date) {
            return "Yesterday at \(timeFormatter.string(from: date))"
        } else {
            let dayFormatter = DateFormatter()
            dayFormatter.dateStyle = .medium
            dayFormatter.timeStyle = .short
            return dayFormatter.string(from: date)
        }
    }

    @MainActor
    /// - Parameter forced: a real-time read that wakes the car's modem.
    ///   Only the refresh button forces one; opening the app reads the
    ///   server's latest snapshot so glancing at the watch never wakes the car.
    private func refreshStatus(forced: Bool = true) async {
        guard !isRefreshing else { return }

        isRefreshing = true
        defer { isRefreshing = false }

        // First, force SwiftData to refetch data by accessing model properties
        // This ensures we get the latest data including background colors from CloudKit sync
        print("🔄 [WatchVehicle] Force refreshing SwiftData for all vehicles")
        for vehicle in allVehicles {
            _ = vehicle.watchBackgroundColorName
            _ = vehicle.watchBackgroundGradient
        }

        do {
            guard let account = currentVehicle.account else {
                throw APIError(message: "Account not found for vehicle")
            }

            // fetchVehicleStatus already applies the status to the vehicle.
            // Applying its returned (debounced) status a second time would
            // read the "confirming stop" placeholder as a resumed charge.
            _ = try await account.fetchVehicleStatus(
                for: currentVehicle,
                modelContext: modelContext,
                cached: !forced
            )
            lastRefreshDate = Date()

        } catch {
            print("❌ [WatchVehicle] Failed to refresh status: \(error)")
        }
    }

    private func performLockAction(shouldLock: Bool, statusUpdater: @escaping @Sendable (String) -> Void) async throws {
        guard let account = currentVehicle.account else {
            throw APIError(message: "Account not found for vehicle")
        }

        if shouldLock {
            try await account.lockVehicle(currentVehicle, modelContext: modelContext)
        } else {
            try await account.unlockVehicle(currentVehicle, modelContext: modelContext)
        }

        let targetLockStatus: VehicleStatus.LockStatus = shouldLock ? .locked : .unlocked
        try await currentVehicle.waitForStatusChange(
            modelContext: modelContext,
            condition: { status in
                status.lockStatus == targetLockStatus
            },
            statusMessageUpdater: statusUpdater,
        )
    }

    private func performClimateAction(shouldStart: Bool, statusUpdater: @escaping @Sendable (String) -> Void) async throws {
        guard let account = currentVehicle.account else {
            throw APIError(message: "Account not found for vehicle")
        }

        if shouldStart {
            try await account.startClimate(currentVehicle, modelContext: modelContext)
        } else {
            try await account.stopClimate(currentVehicle, modelContext: modelContext)
        }

        try await currentVehicle.waitForStatusChange(
            modelContext: modelContext,
            condition: { status in
                status.climateStatus.airControlOn == shouldStart
            },
            statusMessageUpdater: statusUpdater,
        )
    }

    private func performChargeAction(shouldStart: Bool, statusUpdater: @escaping @Sendable (String) -> Void) async throws {
        guard let account = currentVehicle.account else {
            throw APIError(message: "Account not found for vehicle")
        }

        if shouldStart {
            try await account.startCharge(currentVehicle, modelContext: modelContext)
        } else {
            try await account.stopCharge(currentVehicle, modelContext: modelContext)
        }

        try await currentVehicle.waitForStatusChange(
            modelContext: modelContext,
            condition: { status in
                status.evStatus?.charging == shouldStart
            },
            statusMessageUpdater: statusUpdater,
        )
    }
}

private struct WatchVehicleImageView: View {
    let modelName: String
    let colorName: String

    private var imageURL: URL? {
        let normalized = modelName
            .replacingOccurrences(
                of: #"^\d{4}\s+"#,
                with: "",
                options: .regularExpression
            )
            .lowercased()
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")
        let keys = [
            "ioniq5n", "ioniq5", "ioniq6", "ioniq9", "ioniq",
            "ev6", "ev9", "kona", "niro"
        ]
        guard let key = keys.first(where: { normalized.contains($0) }) else {
            return nil
        }
        return URL(
            string: "https://bluelink.andyfase.com/app-assets/car-images//\(key)/\(colorName).png"
        )
    }

    var body: some View {
        AsyncImage(url: imageURL) { phase in
            switch phase {
            case .success(let image):
                image
                    .resizable()
                    .aspectRatio(contentMode: .fit)
            case .empty:
                ProgressView()
                    .controlSize(.mini)
            case .failure:
                Image(systemName: "car.side.fill")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .foregroundStyle(.cyan)
                    .padding(.horizontal, 28)
            @unknown default:
                EmptyView()
            }
        }
        .accessibilityLabel("\(modelName), \(colorName) vehicle")
    }
}

// Watch-specific vehicle button component using VehicleAction architecture
struct WatchVehicleButton: View {
    let currentAction: MainVehicleAction
    let allActions: [MainVehicleAction]
    let menuLabel: String
    let vehicle: BBVehicle
    /// Rivian theme: solid surface and a coloured circular icon chip.
    var rivianStyle = false

    @State private var inProgressAction: MainVehicleAction?

    /// Rivian palette for an action's state colour.
    private var rivianChipColor: Color {
        switch currentAction.color {
        case .red: RVTokens.danger
        case .green: RVTokens.energy
        case .blue: RVTokens.climate
        default: RVTokens.vehicleCenter
        }
    }
    @State private var currentTask: Task<Void, Never>?
    @State private var showingMenu = false

    var body: some View {
        HStack(spacing: 8) {
            Group {
                if inProgressAction != nil {
                    ProgressView()
                        .scaleEffect(0.8)
                } else {
                    currentAction.icon
                        .spin(currentAction.shouldRotate)
                        .pulse(currentAction.shouldPulse)
                        .foregroundColor(rivianStyle ? .white : currentAction.color)
                }
            }
            .frame(width: 24, height: 24)
            .background {
                if rivianStyle {
                    Circle()
                        .fill(rivianChipColor)
                        .frame(width: 30, height: 30)
                }
            }

            Text(inProgressAction?.inProgressLabel ?? currentAction.stateLabel)
                .fontWeight(.medium)
                .lineLimit(1)
            Spacer()
            if inProgressAction != nil {
                ZStack {
                    Circle()
                        .fill(Color.red.opacity(0.2))
                        .frame(width: 24, height: 24)

                    Image(systemName: "stop.fill")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.red)
                }
            }
        }
        .padding()
        .background(rivianStyle ? RVTokens.surface : Color.gray.opacity(0.2))
        .cornerRadius(rivianStyle ? 18 : 12)
        .simultaneousGesture(
            TapGesture()
                .onEnded { _ in
                    if inProgressAction == nil {
                        performPrimaryAction()
                    } else {
                        currentTask?.cancel()
                        Task {
                            await vehicle.clearPendingStatusWaiters()
                        }
                        inProgressAction = nil
                    }
                },
        )
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.7)
                .onEnded { _ in
                    showingMenu = true
                },
        )
        .confirmationDialog(menuLabel, isPresented: $showingMenu, titleVisibility: .visible) {
            ForEach(Array(allActions.enumerated()), id: \.offset) { _, action in
                Button(action.label) {
                    performAction(action)
                }
            }
        }
    }

    private func performPrimaryAction() {
        performAction(currentAction)
    }

    private func performAction(_ action: MainVehicleAction) {
        currentTask = Task {
            await MainActor.run {
                inProgressAction = action
            }

            do {
                try await action.action { _ in }
                await MainActor.run {
                    inProgressAction = nil
                }
            } catch {
                print("❌ [WatchVehicleButton] Action failed: \(error)")
                await MainActor.run {
                    inProgressAction = nil
                }
            }
        }
    }
}

#Preview {
    let schema = Schema([
        BBAccount.self,
        BBVehicle.self,
        BBHTTPLog.self,
        ClimatePreset.self
    ])
    let modelConfiguration = ModelConfiguration(isStoredInMemoryOnly: true)
    let container = try! ModelContainer(for: schema, configurations: [modelConfiguration])

    let sampleVehicle = BBVehicle(from: Vehicle(
        vin: "test",
        regId: "test",
        model: "Ioniq 5",
        accountId: UUID(),
        fuelType: .electric,
        generation: 3,
        odometer: Distance(length: 25000, units: .miles),
        vehicleKey: nil,
    ), backgroundColorName: "lightBlue")

    WatchVehicleView(vehicle: sampleVehicle)
        .modelContainer(container)
}
