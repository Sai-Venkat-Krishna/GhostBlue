//
//  SettingsView.swift
//  GhostBlue
//
//  Created by Mark Schmidt on 7/14/25.
//

import BetterBlueKit
import SwiftData
import SwiftUI
import UIKit
import WidgetKit

extension Bundle {
    var releaseVersionNumber: String? {
        return infoDictionary?["CFBundleShortVersionString"] as? String
    }

    var buildVersionNumber: String? {
        return infoDictionary?["CFBundleVersion"] as? String
    }
}

struct SettingsView: View {
    @Environment(\.modelContext) private var modelContext
    @Query private var accounts: [BBAccount]
    @Query(
        filter: #Predicate<BBVehicle> { $0.isHidden == false },
        sort: \BBVehicle.sortOrder,
    ) private var displayedVehicles: [BBVehicle]
    @Environment(\.dismiss) private var dismiss
    @State private var appSettings = AppSettings.shared

    // Debug functionality - only in debug builds
    @State private var showingClearDataAlert = false
    @State private var clearDataResult: String?
    @State private var showingLiveActivitiesInfo = false
    @State private var showingExportSheet = false

    var body: some View {
        NavigationView {
            List {
                Section {
                    ForEach(accounts) { account in
                        NavigationLink(destination: AccountInfoView(
                            account: account,
                        ).rivianListBackground()) {
                            RivianRowIcon(systemImage: "person.crop.circle.fill", tint: RV.vehicleCenter) {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(account.username)
                                            .font(.headline)
                                        Text(account.brandEnum.displayName)
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }

                                    Spacer()
                                }
                            }
                        }
                    }
                    .onDelete(perform: deleteAccounts)
                } header: {
                    HStack {
                        Text("Accounts")
                        Spacer()
                        NavigationLink("Add Account") {
                            AddAccountView()
                        }
                        .font(.caption)
                        .foregroundColor(appSettings.appTheme == .rivian ? RV.accent : .blue)
                    }
                }

                Section {
                    Picker(selection: $appSettings.appRefreshInterval) {
                        ForEach(AppRefreshInterval.allCases, id: \.rawValue) { interval in
                            Text(interval.displayName).tag(interval)
                        }
                    } label: {
                        SettingsRowLabel(title: "Background Refresh", systemImage: "arrow.clockwise", tint: RV.climate)
                    }
                } header: {
                    Text("App Settings")
                } footer: {
                    Text("How often the app polls vehicle status in the background. More frequent polling increases BlueLink API usage.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Section {
                    Picker(selection: $appSettings.appTheme) {
                        ForEach(AppTheme.allCases) { theme in
                            Text(theme.title).tag(theme)
                        }
                    } label: {
                        SettingsRowLabel(title: "Theme", systemImage: "paintpalette.fill", tint: RV.accent)
                    }

                    if appSettings.appTheme == .rivian {
                        HStack(spacing: 8) {
                            SettingsIconChip(systemImage: "drop.fill", tint: RV.accent)
                                .padding(.trailing, 4)
                            Text("Accent")
                            Spacer()
                            Image(systemName: "circle.fill")
                                .foregroundStyle(AppTheme.rivianGold)
                            Text("Rivian Gold")
                                .foregroundStyle(.secondary)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityHint("Set by the Rivian theme")
                    } else {
                        Picker("Accent", selection: $appSettings.appAccent) {
                            ForEach(AppAccent.allCases) { accent in
                                Label(accent.title, systemImage: "circle.fill")
                                    .foregroundStyle(accent.color)
                                    .tag(accent)
                            }
                        }
                    }

                    Picker(
                        selection: Binding(
                            get: { appSettings.homeDashboardPreset },
                            set: { appSettings.applyDashboardPreset($0) }
                        )
                    ) {
                        ForEach(HomeDashboardPreset.allCases) { preset in
                            Text(preset.title).tag(preset)
                        }
                    } label: {
                        SettingsRowLabel(title: "Dashboard Preset", systemImage: "rectangle.3.group.fill", tint: RV.location)
                    }

                    Picker(selection: $appSettings.vehicleHeroSize) {
                        ForEach(VehicleHeroSize.allCases) { size in
                            Text(size.title).tag(size)
                        }
                    } label: {
                        SettingsRowLabel(title: "Vehicle Size", systemImage: "arrow.up.left.and.arrow.down.right", tint: RV.location)
                    }

                    Picker(selection: $appSettings.vehicleHeroAlignment) {
                        ForEach(VehicleHeroAlignment.allCases) { alignment in
                            Text(alignment.title).tag(alignment)
                        }
                    } label: {
                        SettingsRowLabel(title: "Vehicle Position", systemImage: "align.horizontal.center.fill", tint: RV.location)
                    }

                    NavigationLink {
                        HomeLayoutEditorView(order: $appSettings.homeSectionOrder)
                            .rivianListBackground()
                    } label: {
                        SettingsRowLabel(title: "Home Layout", systemImage: "square.grid.2x2", tint: RV.location, classicShowsIcon: true)
                    }
                } header: {
                    Text("Appearance")
                } footer: {
                    Text("Themes, dashboard layouts, and vehicle presentation are shared with supported companion targets.")
                }

                ABRPSettingsSection()

                Section {
                    Picker(selection: $appSettings.widgetRefreshInterval) {
                        ForEach(WidgetRefreshInterval.allCases, id: \.rawValue) { interval in
                            Text(interval.displayName).tag(interval)
                        }
                    } label: {
                        SettingsRowLabel(title: "Widget Refresh Interval", systemImage: "timer", tint: RV.climate)
                    }
                    Toggle(isOn: $appSettings.notificationsEnabled) {
                        SettingsRowLabel(title: "Widget Notifications", systemImage: "bell.badge.fill", tint: RV.danger)
                    }
                    Toggle(isOn: $appSettings.liveActivitiesEnabled) {
                        RivianRowIcon(systemImage: "dot.radiowaves.left.and.right", tint: RV.energy) {
                        HStack(spacing: 6) {
                            Text("Live Activities")
                            Button {
                                showingLiveActivitiesInfo = true
                            } label: {
                                Text("Beta")
                                    .font(.caption2)
                                    .fontWeight(.bold)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(appSettings.appTheme == .rivian ? RV.accent : Color.blue)
                                    .foregroundColor(.white)
                                    .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                        }
                    }
                } header: {
                    Text("Widget Settings")
                }

                Section {
                    Picker(selection: $appSettings.preferredDistanceUnit) {
                        ForEach(Distance.Units.allCases) { unit in
                            Text(unit.displayName).tag(unit)
                        }
                    } label: {
                        SettingsRowLabel(title: "Distance Unit", systemImage: "ruler.fill", tint: RV.service)
                    }

                    Picker(selection: $appSettings.preferredTemperatureUnit) {
                        ForEach(Temperature.Units.allCases) { unit in
                            Text(unit.displayName).tag(unit)
                        }
                    } label: {
                        SettingsRowLabel(title: "Temperature Unit", systemImage: "thermometer.medium", tint: RV.service)
                    }
                } header: {
                    Text("Units")
                }

                Section {
                    Toggle(isOn: $appSettings.debugModeEnabled) {
                        SettingsRowLabel(title: "Debug Mode", systemImage: "ladybug.fill", tint: RV.vehicleCenter)
                    }

                    if appSettings.debugModeEnabled {
                        #if DEBUG
                            NavigationLink {
                                MapCenteringDebugView()
                                    .rivianListBackground()
                            } label: {
                                SettingsRowLabel(title: "Map Centering Debug", systemImage: "map.fill", tint: RV.vehicleCenter)
                            }
                        #endif

                        NavigationLink {
                            HTTPLogView()
                                .rivianListBackground()
                        } label: {
                            SettingsRowLabel(title: "HTTP Logs", systemImage: "list.bullet.rectangle.fill", tint: RV.vehicleCenter)
                        }

                        NavigationLink {
                            DiagnosticInfoView()
                                .rivianListBackground()
                        } label: {
                            SettingsRowLabel(title: "Sync Diagnostics", systemImage: "arrow.triangle.2.circlepath", tint: RV.vehicleCenter)
                        }
                    }

                    Button {
                        showingClearDataAlert = true
                    } label: {
                        SettingsRowLabel(title: "Clear All Data", systemImage: "trash.fill", tint: RV.danger)
                    }
                    .foregroundColor(.red)

                    if let result = clearDataResult {
                        Text(result)
                            .font(.caption)
                            .foregroundColor(result.contains("Error") ? .red : .green)
                    }

                    Button {
                        showingExportSheet = true
                    } label: {
                        SettingsRowLabel(title: "Export Debug Data", systemImage: "square.and.arrow.up.fill", tint: RV.vehicleCenter)
                    }
                } header: {
                    Text("Debug Settings")
                }

                Section {
                    NavigationLink {
                        PrivacySecurityView()
                            .rivianListBackground()
                    } label: {
                        SettingsRowLabel(title: "Privacy & Security", systemImage: "lock.shield.fill", tint: RV.climate, classicShowsIcon: true)
                    }

                    NavigationLink {
                        OwnershipSetupView(vehicles: displayedVehicles)
                            .rivianListBackground()
                    } label: {
                        SettingsRowLabel(title: "Setup Guide", systemImage: "checklist.checked", tint: RV.energy, classicShowsIcon: true)
                    }

                    NavigationLink {
                        BackupRestoreView()
                            .rivianListBackground()
                    } label: {
                        SettingsRowLabel(title: "Backup & Restore", systemImage: "externaldrive.badge.timemachine", tint: RV.energy, classicShowsIcon: true)
                    }

                } header: {
                    Text("Privacy & Setup")
                } footer: {
                    Text("Face ID, location privacy, data export and deletion, and the remaining setup steps for notifications, widgets, and Watch.")
                }

                Section {
                    NavigationLink(destination: TroubleshootingView().rivianListBackground()) {
                        SettingsRowLabel(title: "Troubleshooting", systemImage: "questionmark.circle", tint: RV.climate, classicShowsIcon: true)
                    }
                } header: {
                    Text("Help")
                }

                // About section with version and GitHub links
                Section {
                    if let version = Bundle.main.releaseVersionNumber, let build = Bundle.main.buildVersionNumber {
                        HStack {
                            SettingsRowLabel(title: "Version Number", systemImage: "calendar", tint: RV.vehicleCenter, classicShowsIcon: true)
                            Spacer()
                            Text(version)
                        }
                        HStack {
                            SettingsRowLabel(title: "Build Number", systemImage: "swift", tint: RV.service, classicShowsIcon: true)
                            Spacer()
                            Text(build)
                        }
                    }

                    Link(destination: URL(string: "https://github.com/schmidtwmark/GhostBlue")!) {
                        SettingsRowLabel(title: "App Source Code", systemImage: "location.app", tint: RV.vehicleCenter, classicShowsIcon: true)
                    }

                    Link(destination: URL(string: "https://github.com/schmidtwmark/BetterBlueKit")!) {
                        SettingsRowLabel(title: "Client Source Code", systemImage: "apple.terminal", tint: RV.vehicleCenter, classicShowsIcon: true)
                    }
                } header: {
                    Text("About")
                }

                Section {
                    Link(destination: URL(string: "https://apps.apple.com/qa/developer/mark-schmidt/id1502505700")!) {
                        SettingsRowLabel(title: "My Other Apps", systemImage: "storefront", tint: RV.climate, classicShowsIcon: true)
                    }
                } header: {
                    Text("Shameless Self Promotion")
                } footer: {
                    let link = "[Mark Schmidt](https://markschmidt.io)"
                    if let mailLink = try? AttributedString(markdown: "Created by \(link)") {
                        Text(mailLink)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("Settings")
            .scrollContentBackground(appSettings.appTheme == .rivian ? .hidden : .automatic)
            .background {
                if appSettings.appTheme == .rivian { RVBackground() }
            }
            .tint(appSettings.accentColor)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
        .rivianScreen()
        .alert("Clear All Data", isPresented: $showingClearDataAlert) {
            Button("Cancel", role: .cancel) {}
            Button("Clear All", role: .destructive) {
                clearAllData()
            }
        } message: {
            Text(
                "This will permanently delete all accounts, vehicles, debug " +
                    "configurations, and other app data. This action cannot be undone.",
            )
        }
        .sheet(isPresented: $showingLiveActivitiesInfo) {
            LiveActivitiesInfoSheet()
        }
        .sheet(isPresented: $showingExportSheet) {
            DebugExportSheet(accounts: accounts, appSettings: appSettings, modelContext: modelContext)
        }
    }

    private func deleteAccounts(offsets: IndexSet) {
        for index in offsets {
            BBAccount.removeAccount(accounts[index], modelContext: modelContext)
        }
    }

    private func clearAllData() {
        do {
            // Delete all BBAccounts (which should cascade delete their vehicles due to .cascade relationship)
            try modelContext.delete(model: BBAccount.self)

            // Delete any orphaned BBVehicles that might still exist
            try modelContext.delete(model: BBVehicle.self)

            // Delete any orphaned climate presets
            try modelContext.delete(model: ClimatePreset.self)

                    // Delete any HTTP logs
                    try modelContext.delete(model: BBHTTPLog.self)

                    try modelContext.delete(model: ChargeSession.self)
                    try modelContext.delete(model: TripRecord.self)
                    try modelContext.delete(model: RemoteCommandActivity.self)
                    try modelContext.delete(model: MaintenanceReminder.self)
                    try modelContext.delete(model: VehicleAutomation.self)

            try modelContext.save()
            // Deleted rules must not leave their daily reminders behind.
            Task { await AutomationReminderScheduler.sync(in: modelContext) }

            clearDataResult = "✅ All data cleared successfully"
            BBLogger.info(.app, "SettingsView: Successfully cleared all SwiftData storage")

            // Clear the result message after 3 seconds
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                clearDataResult = nil
            }
        } catch {
            clearDataResult = "❌ Error: \(error.localizedDescription)"
            BBLogger.error(.app, "SettingsView: Failed to clear data: \(error)")

            // Clear the error message after 5 seconds
            DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) {
                clearDataResult = nil
            }
        }
    }

}

// `DebugExportData` and its supporting Codable types live in
// `GhostBlue/Utility/DebugExport.swift` so the error-detail share
// button produces the same payload as Settings → Export Debug Data.

// MARK: - Debug Export Sheet

private enum ExportMode: String, CaseIterable {
    case redacted = "Redacted"
    case raw = "Unredacted"
}

private struct DebugExportSheet: View {
    let accounts: [BBAccount]
    let appSettings: AppSettings
    let modelContext: ModelContext

    @Environment(\.dismiss) private var dismiss
    @State private var exportData: DebugExportData?
    @State private var showingShareSheet = false
    @State private var exportMode: ExportMode = .redacted

    private var displayedContent: String {
        guard let data = exportData else { return "" }
        return exportMode == .redacted ? data.redacted : data.raw
    }

    var body: some View {
        NavigationView {
            Group {
                if exportData == nil {
                    VStack(spacing: 16) {
                        BrandProgressView()
                            .scaleEffect(1.5)
                        Text("Generating export...")
                            .foregroundColor(.secondary)
                    }
                } else {
                    ScrollView {
                        Text(displayedContent)
                            .font(.system(.caption, design: .monospaced))
                            .padding()
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                }
            }
            .navigationTitle("Debug Export")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Done") {
                        dismiss()
                    }
                }
                if exportData != nil {
                    ToolbarItem(placement: .principal) {
                        Picker("Export Mode", selection: $exportMode) {
                            ForEach(ExportMode.allCases, id: \.self) { mode in
                                Text(mode.rawValue).tag(mode)
                            }
                        }
                        .pickerStyle(.segmented)
                        .fixedSize()
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button {
                            showingShareSheet = true
                        } label: {
                            Image(systemName: "square.and.arrow.up")
                        }
                    }
                }
            }
            .sheet(isPresented: $showingShareSheet) {
                ShareSheet(activityItems: [displayedContent])
            }
            .task {
                exportData = await DebugExportData.generate(
                    accounts: accounts,
                    appSettings: appSettings,
                    modelContext: modelContext
                )
            }
        }
    }
}

// MARK: - Live Activities Info Sheet

private struct LiveActivitiesInfoSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // Header
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Live Activities", systemImage: "bolt.fill")
                            .font(.title2)
                            .fontWeight(.bold)
                        Text("Beta Feature")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }

                    Divider()

                    // What it does
                    VStack(alignment: .leading, spacing: 8) {
                        Text("What it does")
                            .font(.headline)
                        Text(
                            "When enabled, GhostBlue will display a Live Activity on your " +
                            "Lock Screen and Dynamic Island while your vehicle is charging. " +
                            "The Live Activity shows real-time charging progress without " +
                            "needing to open the app."
                        )
                        .foregroundColor(.secondary)
                    }

                    // How it works
                    VStack(alignment: .leading, spacing: 8) {
                        Text("How it works")
                            .font(.headline)
                        Text(
                            "To keep the Live Activity updated, GhostBlue registers your " +
                            "device with a lightweight backend service that periodically " +
                            "sends silent push notifications to wake the app and refresh " +
                            "the charging status."
                        )
                        .foregroundColor(.secondary)
                    }

                    VStack(alignment: .leading, spacing: 12) {
                        Text("Why Beta?").font(.headline)
                        Text(
                            "Live Activities are considered a Beta feature for now. " +
                            "Supporting push notifications in this way requires paying for " +
                            "cloud server time. If this feature ends up being too expensive " +
                            "to maintain, I will likely disable it."
                        )
                        .foregroundColor(.secondary)
                    }

                    // Privacy section
                    VStack(alignment: .leading, spacing: 12) {
                        Label("Your Privacy", systemImage: "lock.shield.fill")
                            .font(.headline)
                            .foregroundColor(.green)

                        VStack(alignment: .leading, spacing: 8) {
                            PrivacyBullet(
                                icon: "checkmark.circle.fill",
                                text: "No vehicle information is sent to the backend"
                            )
                            PrivacyBullet(
                                icon: "checkmark.circle.fill",
                                text: "No account credentials leave your device"
                            )
                            PrivacyBullet(
                                icon: "checkmark.circle.fill",
                                text: "Only your device's push token is stored"
                            )
                            PrivacyBullet(
                                icon: "checkmark.circle.fill",
                                text: "Tokens are automatically deleted after 8 hours"
                            )
                        }
                    }
                    .padding()
                    .background(Color.green.opacity(0.1))
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                    // Open source
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Open Source")
                            .font(.headline)
                        Text(
                            "The backend service is fully open source. You can review " +
                            "exactly what data is collected and how it's used."
                        )
                        .foregroundColor(.secondary)

                        Button {
                            if let url = URL(string: "https://github.com/schmidtwmark/GhostBlue/tree/main/LiveActivityBackend") {
                                UIApplication.shared.open(url)
                            }
                        } label: {
                            Text("View Backend Source Code")
                                .font(.subheadline)
                                .fontWeight(.medium)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                                .background(Color.blue)
                                .foregroundColor(.white)
                                .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                    }

                    Spacer(minLength: 20)
                }
                .padding()
            }
            .navigationTitle("About Live Activities")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
}

private struct PrivacyBullet: View {
    let icon: String
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: icon)
                .foregroundColor(.green)
                .font(.subheadline)
            Text(text)
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
    }
}

#Preview {
    struct PreviewWrapper: View {
        var body: some View {
            SettingsView()
                .modelContainer(for: [BBAccount.self, BBVehicle.self, ClimatePreset.self])
        }
    }
    return PreviewWrapper()
}
