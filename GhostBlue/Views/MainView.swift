//
//  MainView.swift
//  GhostBlue
//
//  Root shell for the tabbed redesign: Home (controls + status),
//  Location (map), Charging (energy history), and Trips (drive history)
//  over a Liquid Glass tab
//  bar. Owns account loading, the periodic refresh loop, and deep-link
//  vehicle selection.
//

import BetterBlueKit
import LocalAuthentication
import SwiftData
import SwiftUI
import UIKit
import WidgetKit

enum AppTab: Hashable {
    case home
    case location
    case charging
    case trips

    /// The tab requested by `-startTab` or `-startOnLocationTab`, for UI checks.
    static var launchTab: AppTab {
        switch UITestFlags.startTab {
        case "location": .location
        case "energy", "charging": .charging
        case "trips": .trips
        default: UITestFlags.startOnLocationTab ? .location : .home
        }
    }
}

extension Notification.Name {
    static let scrollHomeToTop = Notification.Name("GhostBlue.ScrollHomeToTop")
    static let scrollChargingToTop = Notification.Name("GhostBlue.ScrollChargingToTop")
    static let scrollTripsToTop = Notification.Name("GhostBlue.ScrollTripsToTop")
    static let recenterLocationTab = Notification.Name("GhostBlue.RecenterLocationTab")
}

struct MainView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.scenePhase) private var scenePhase
    @Query private var accounts: [BBAccount]
    @Query(
        filter: #Predicate<BBVehicle> { vehicle in !vehicle.isHidden },
        sort: \BBVehicle.sortOrder,
    ) private var displayedVehicles: [BBVehicle]
    @Query(sort: \TripRecord.savedDate, order: .reverse)
    private var archivedTrips: [TripRecord]

    @State private var selectedTab: AppTab = .launchTab
    @State private var appSettings = AppSettings.shared
    @State private var selectedVehicleIndex = 0
    @State private var isLoading = false
    @State var lastError: APIError?
    @State private var showingSettings = false
    @State private var isAppLocked = false
    @State private var backupImports = BackupImportCoordinator.shared

    /// A backup opened from outside the app, held back while Face ID lock is up.
    private var pendingBackupImport: Binding<PendingBackupFile?> {
        Binding(
            get: {
                appSettings.requireBiometricUnlock && isAppLocked ? nil : backupImports.pendingFile
            },
            set: { backupImports.pendingFile = $0 }
        )
    }
    @State private var isAuthenticating = false
    @State private var authenticationMessage: String?

    @Namespace private var transition

    var currentVehicle: BBVehicle? {
        guard selectedVehicleIndex < displayedVehicles.count else {
            return nil
        }
        return displayedVehicles[selectedVehicleIndex]
    }

    private var newTripCount: Int {
        guard let currentVehicle else { return 0 }
        return archivedTrips.filter {
            $0.vin == currentVehicle.vin && $0.savedDate > appSettings.lastViewedTripsDate
        }.count
    }

    var body: some View {
        Group {
            if accounts.isEmpty {
                emptyShell {
                    EmptyAccountsView(transition: transition)
                }
            } else if displayedVehicles.isEmpty || lastError != nil {
                emptyShell {
                    EmptyVehiclesView(
                        isLoading: $isLoading,
                        lastError: $lastError,
                        onRetry: {
                            await loadVehiclesForAllAccounts()
                        },
                    )
                }
            } else {
                tabShell
            }
        }
        .onAppear {
            if appSettings.requireBiometricUnlock {
                isAppLocked = true
                authenticateApp()
            }
            Task {
                await loadVehiclesForAllAccounts()
            }
        }
        .onChange(of: displayedVehicles.count) { _, _ in
            // Keep the selection valid when vehicles are removed/hidden
            if selectedVehicleIndex >= displayedVehicles.count,
               !displayedVehicles.isEmpty {
                selectedVehicleIndex = displayedVehicles.count - 1
            }
        }
        .onChange(of: selectedVehicleIndex) { _, _ in
            Task {
                await refreshCurrentVehicleIfNeeded(modelContext: modelContext)
            }
        }
        .onChange(of: selectedTab) { _, newTab in
            if newTab == .trips {
                appSettings.lastViewedTripsDate = Date()
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            guard appSettings.requireBiometricUnlock else { return }
            if newPhase == .active {
                authenticateApp()
            } else {
                isAppLocked = true
            }
        }
        .onChange(of: appSettings.requireBiometricUnlock) { _, isEnabled in
            isAppLocked = isEnabled
            if isEnabled {
                authenticateApp()
            }
        }
        .onReceive(
            NotificationCenter.default.publisher(for: .selectVehicle),
        ) { notification in
            guard let vin = notification.object as? String else { return }
            if let index = displayedVehicles.firstIndex(where: {
                $0.vin == vin
            }) {
                selectedVehicleIndex = index
                selectedTab = .home
                Task {
                    await refreshCurrentVehicleIfNeeded(modelContext: modelContext)
                }
            }
        }
        .task {
            await VehicleRefreshPolicy.run(interval: {
                AppSettings.shared.appRefreshInterval.timeInterval
            }) {
                guard scenePhase == .active else { return }
                await refreshCurrentVehicleIfNeeded(modelContext: modelContext)
            }
        }
        .overlay {
            if appSettings.requireBiometricUnlock && isAppLocked {
                AppLockView(
                    isAuthenticating: isAuthenticating,
                    message: authenticationMessage,
                    unlock: authenticateApp
                )
                .transition(.opacity)
                .zIndex(100)
            }
        }
        .sheet(item: pendingBackupImport) { file in
            BackupImportSheet(file: file)
                .tint(appSettings.accentColor)
        }
    }

    private func authenticateApp() {
        guard appSettings.requireBiometricUnlock, !isAuthenticating else { return }
        isAuthenticating = true
        authenticationMessage = nil

        Task {
            let context = LAContext()
            context.localizedCancelTitle = "Keep Locked"
            var error: NSError?
            guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
                await MainActor.run {
                    authenticationMessage = error?.localizedDescription
                        ?? "Device authentication is not available."
                    isAuthenticating = false
                }
                return
            }
            do {
                let authenticated = try await context.evaluatePolicy(
                    .deviceOwnerAuthentication,
                    localizedReason: "Unlock your vehicles and private location data."
                )
                await MainActor.run {
                    isAppLocked = !authenticated
                    isAuthenticating = false
                }
            } catch {
                await MainActor.run {
                    authenticationMessage = error.localizedDescription
                    isAuthenticating = false
                }
            }
        }
    }

    // MARK: - Tabs

    private var tabShell: some View {
        TabView(selection: $selectedTab) {
            Tab("Home", systemImage: "car.fill", value: AppTab.home) {
                HomeView(
                    displayedVehicles: displayedVehicles,
                    selectedVehicleIndex: $selectedVehicleIndex,
                    onOpenLocation: { selectedTab = .location },
                    onOpenCharging: { selectedTab = .charging },
                    onSuccessfulRefresh: { lastError = nil },
                )
            }

            Tab("Location", systemImage: "map.fill", value: AppTab.location) {
                MapTabView(
                    displayedVehicles: displayedVehicles,
                    selectedVehicleIndex: $selectedVehicleIndex,
                )
            }

            Tab(appSettings.appTheme == .rivian ? "Energy" : "Charging", systemImage: "bolt.fill", value: AppTab.charging) {
                ChargingView(
                    displayedVehicles: displayedVehicles,
                    selectedVehicleIndex: $selectedVehicleIndex,
                )
            }

            Tab("Trips", systemImage: "road.lanes", value: AppTab.trips) {
                TripsView(
                    displayedVehicles: displayedVehicles,
                    selectedVehicleIndex: $selectedVehicleIndex,
                )
            }
            .badge(newTripCount > 0 ? Text("•") : nil)
        }
        .tint(appSettings.accentColor)
        .environment(\.themeMotion, appSettings.appTheme == .rivian ? .rivian : .standard)
        .background(TabBarReselectObserver())
        .animation(appSettings.appTheme == .rivian ? ThemeMotion.rivian.emphasis : .easeInOut(duration: 0.24), value: selectedTab)
    }

    /// Wraps the empty/error states in a NavigationStack so Settings stays
    /// reachable before any account or vehicle exists.
    private func emptyShell(@ViewBuilder content: () -> some View) -> some View {
        NavigationStack {
            content()
                .sheet(isPresented: $showingSettings) {
                    SettingsView()
                        .navigationTransition(
                            .zoom(sourceID: "settings", in: transition),
                        )
                }
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Settings", systemImage: "gearshape.fill") {
                            showingSettings = true
                        }.labelStyle(.iconOnly)
                    }
                    .matchedTransitionSource(id: "settings", in: transition)
                }
        }
    }
}

private struct AppLockView: View {
    let isAuthenticating: Bool
    let message: String?
    let unlock: () -> Void

    var body: some View {
        ZStack {
            GBBackground(colors: AppSettings.shared.themeColors)
                .ignoresSafeArea()

            VStack(spacing: 18) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 52))
                    .foregroundStyle(AppSettings.shared.accentColor)
                    .symbolEffect(.pulse, isActive: isAuthenticating)
                Text("GhostBlue Locked")
                    .font(.title.bold())
                Text("Authenticate to access controls, trips, and vehicle location.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 300)

                if let message {
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 300)
                }

                Button(action: unlock) {
                    if isAuthenticating {
                        BrandProgressView()
                            .frame(maxWidth: .infinity)
                    } else {
                        Label("Unlock", systemImage: "faceid")
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: 280)
                .disabled(isAuthenticating)
            }
            .padding(30)
        }
        .ghostBlueColorScheme()
        .accessibilityAddTraits(.isModal)
    }
}

/// SwiftUI's `TabView` does not expose selected-tab re-taps. This observer
/// adds a non-cancelling gesture to the system tab bar and converts a second
/// tap into the familiar "return to top" behavior.
private struct TabBarReselectObserver: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> ObserverViewController {
        ObserverViewController()
    }

    func updateUIViewController(_ uiViewController: ObserverViewController, context: Context) {}

    final class ObserverViewController: UIViewController {
        private weak var observedTabBar: UITabBar?

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            installIfNeeded()
        }

        private func installIfNeeded() {
            guard let tabController = tabBarController,
                  observedTabBar !== tabController.tabBar
            else { return }

            let recognizer = UITapGestureRecognizer(target: self, action: #selector(tabBarTapped(_:)))
            recognizer.cancelsTouchesInView = false
            tabController.tabBar.addGestureRecognizer(recognizer)

            let appearance = UITabBarAppearance()
            if UIAccessibility.isReduceTransparencyEnabled {
                appearance.configureWithOpaqueBackground()
                appearance.backgroundColor = UIColor.black.withAlphaComponent(0.94)
            } else {
                appearance.configureWithTransparentBackground()
                appearance.backgroundEffect = UIBlurEffect(style: .systemUltraThinMaterialDark)
                appearance.backgroundColor = UIColor.black.withAlphaComponent(0.24)
            }
            tabController.tabBar.standardAppearance = appearance
            tabController.tabBar.scrollEdgeAppearance = appearance
            tabController.tabBar.layer.shadowColor = UIColor.black.cgColor
            tabController.tabBar.layer.shadowOpacity = 0.20
            tabController.tabBar.layer.shadowRadius = 16
            tabController.tabBar.layer.shadowOffset = CGSize(width: 0, height: -5)
            observedTabBar = tabController.tabBar
        }

        @objc private func tabBarTapped(_ recognizer: UITapGestureRecognizer) {
            guard let tabController = tabBarController,
                  let items = tabController.tabBar.items,
                  !items.isEmpty
            else { return }

            let location = recognizer.location(in: tabController.tabBar)
            let width = tabController.tabBar.bounds.width / CGFloat(items.count)
            let tappedIndex = min(items.count - 1, max(0, Int(location.x / max(width, 1))))
            guard tappedIndex == tabController.selectedIndex else { return }

            let notification: Notification.Name
            switch tappedIndex {
            case 0: notification = .scrollHomeToTop
            case 1: notification = .recenterLocationTab
            case 2: notification = .scrollChargingToTop
            default: notification = .scrollTripsToTop
            }
            NotificationCenter.default.post(name: notification, object: nil)
        }
    }
}

// MARK: - Vehicle Loading

extension MainView {
    private func loadVehiclesForAllAccounts() async {
        await MainActor.run {
            isLoading = true
            lastError = nil
        }

        var hasSuccessfulAccount = false
        var latestError: APIError?

        for account in accounts {
            do {
                try await account.initialize(modelContext: modelContext)
                try await account.loadVehicles(modelContext: modelContext)
                hasSuccessfulAccount = true
            } catch {
                let user = account.username
                if let apiError = error as? APIError {
                    BBLogger.warning(.app, "MainView: Failed to load vehicles for '\(user)': \(apiError.message)")
                    latestError = apiError
                } else {
                    BBLogger.error(.app, "MainView: Failed to load vehicles for '\(user)': \(error.localizedDescription)")
                    latestError = APIError(
                        message: error.localizedDescription,
                    )
                }
            }
        }

        await MainActor.run {
            isLoading = false
            if hasSuccessfulAccount || !displayedVehicles.isEmpty {
                lastError = nil
            } else {
                lastError = latestError
            }
        }

        await loadStatusForAllVehicles()
    }

    private func loadStatusForAllVehicles() async {
        for bbVehicle in displayedVehicles {
            if let lastUpdated = bbVehicle.lastUpdated,
               lastUpdated > Date().addingTimeInterval(-300) {
                continue
            }

            do {
                if let account = bbVehicle.account {
                    try await account.fetchAndUpdateVehicleStatus(
                        for: bbVehicle,
                        modelContext: modelContext,
                    )

                    await MainActor.run {
                        WidgetCenter.shared.reloadTimelines(
                            ofKind: "GhostBlueWidget",
                        )
                    }
                }

            } catch {
                BBLogger.warning(.app, "MainView: Failed to load status for vehicle \(bbVehicle.vin): \(error)")
            }
        }
    }
}

#Preview {
    MainView()
}
