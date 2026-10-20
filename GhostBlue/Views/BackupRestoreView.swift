//
//  BackupRestoreView.swift
//  GhostBlue
//
//  Settings → Backup & Restore. Writes everything the app stores to a
//  single GhostBlue backup file, and folds one of those files back in
//  without overwriting anything already here. Moving data from a locally
//  built app to a TestFlight build is an export here and an import there.
//

import BetterBlueKit
import SwiftData
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct BackupRestoreView: View {
    @Environment(\.modelContext) private var modelContext

    @State private var shareItem: ShareableBackup?
    @State private var isImporting = false
    @State private var isWorking = false
    @State private var summary: BackupMergeSummary?
    @State private var errorMessage: String?
    @State private var savedToMacPath: String?

    var body: some View {
        Form {
            Section {
                Button {
                    shareBackup()
                } label: {
                    Label("Export Backup", systemImage: "square.and.arrow.up")
                }
                .disabled(isWorking)

                #if targetEnvironment(simulator)
                Button {
                    saveToMac()
                } label: {
                    Label("Save to Mac Downloads", systemImage: "desktopcomputer.and.arrow.down")
                }
                .disabled(isWorking)
                #endif
            } header: {
                Text("Export")
            } footer: {
                Text("Writes accounts, vehicles, climate presets, charging and trip history, "
                    + "maintenance reminders and automations to a GhostBlue backup file. "
                    + "HTTP logs are left out. AirDrop it or save it to Files, then open it "
                    + "on the other device to import. The file contains your saved "
                    + "credentials, so keep it somewhere private.")
            }

            Section {
                Button {
                    isImporting = true
                } label: {
                    Label("Import and Merge", systemImage: "square.and.arrow.down")
                }
                .disabled(isWorking)
            } header: {
                Text("Import")
            } footer: {
                Text("Adds anything the file has that this device does not. Nothing is "
                    + "deleted and no existing record is overwritten, so importing the "
                    + "same file twice changes nothing. For a vehicle already here, only "
                    + "settings with no value yet are filled in.")
            }

            if isWorking {
                Section {
                    HStack(spacing: 10) {
                        BrandProgressView()
                        Text("Working…")
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if let summary {
                BackupMergeSummarySection(summary: summary, title: "Last Import")
            }
        }
        .navigationTitle("Backup & Restore")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $shareItem) { item in
            ShareSheet(activityItems: [item.url])
                .presentationDetents([.medium, .large])
        }
        .fileImporter(
            isPresented: $isImporting,
            allowedContentTypes: BackupTransfer.importableTypes,
            allowsMultipleSelection: false
        ) { result in
            handleImport(result)
        }
        .alert("Backup Failed", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
        .alert("Saved to Mac", isPresented: Binding(
            get: { savedToMacPath != nil },
            set: { if !$0 { savedToMacPath = nil } }
        )) {
            Button("OK") { savedToMacPath = nil }
        } message: {
            Text("\(savedToMacPath ?? "")\n\nAirDrop it from Finder to your iPhone and choose GhostBlue to import it.")
        }
    }

    private func shareBackup() {
        isWorking = true
        defer { isWorking = false }
        do {
            let url = try BackupTransfer.writeBackup(from: modelContext, deviceName: UIDevice.current.name)
            shareItem = ShareableBackup(url: url)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    #if targetEnvironment(simulator)
    private func saveToMac() {
        guard let downloads = BackupTransfer.hostDownloadsFolder else {
            errorMessage = "Couldn't find the Mac's Downloads folder."
            return
        }
        isWorking = true
        defer { isWorking = false }
        do {
            let url = try BackupTransfer.writeBackup(
                from: modelContext,
                deviceName: "\(UIDevice.current.name) (Simulator)",
                to: downloads
            )
            savedToMacPath = url.path
        } catch {
            errorMessage = error.localizedDescription
        }
    }
    #endif

    private func handleImport(_ result: Result<[URL], Error>) {
        guard case let .success(urls) = result, let url = urls.first else {
            if case let .failure(error) = result {
                errorMessage = error.localizedDescription
            }
            return
        }

        isWorking = true
        defer { isWorking = false }
        do {
            let archive = try BackupTransfer.readArchive(at: url)
            summary = try BackupTransfer.importArchive(archive, into: modelContext)
        } catch {
            errorMessage = error.localizedDescription
            BBLogger.error(.app, "BackupRestoreView: import failed: \(error)")
        }
    }
}

private struct ShareableBackup: Identifiable {
    let id = UUID()
    let url: URL
}

// MARK: - Import from a file opened outside the app

/// Shown when a backup is AirDropped or opened from Files or Mail: what the
/// file holds, then the merge result.
struct BackupImportSheet: View {
    let file: PendingBackupFile

    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @State private var archive: BackupArchive?
    @State private var summary: BackupMergeSummary?
    @State private var errorMessage: String?
    @State private var isImporting = false

    var body: some View {
        NavigationStack {
            Form {
                if let summary {
                    BackupMergeSummarySection(summary: summary, title: "Imported")
                } else if let archive {
                    Section {
                        LabeledContent("From", value: archive.sourceDevice)
                        LabeledContent("Created", value: archive.createdAt.formatted(date: .abbreviated, time: .shortened))
                    } header: {
                        Text(file.url.lastPathComponent)
                    }

                    Section("Contains") {
                        contentRow("Charge sessions", archive.chargeSessions.count)
                        contentRow("Trips", archive.trips.count)
                        contentRow("Vehicles", archive.vehicles.count)
                        contentRow("Accounts", archive.accounts.count)
                        contentRow("Climate presets", archive.climatePresets.count)
                        contentRow("Maintenance and automations",
                                   archive.maintenanceReminders.count + archive.automations.count)
                    }

                    Section {
                        Button {
                            runImport(archive)
                        } label: {
                            HStack {
                                Label("Import and Merge", systemImage: "square.and.arrow.down")
                                if isImporting {
                                    Spacer()
                                    BrandProgressView()
                                }
                            }
                        }
                        .disabled(isImporting)
                    } footer: {
                        Text("Adds only what this device doesn't already have. Nothing here is "
                            + "deleted or overwritten, so importing the same file again is safe.")
                    }
                } else if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                    }
                } else {
                    Section {
                        BrandProgressView()
                    }
                }
            }
            .navigationTitle("Import Backup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: summary == nil ? .cancellationAction : .confirmationAction) {
                    Button(summary == nil ? "Cancel" : "Done") { dismiss() }
                }
            }
        }
        .task {
            do {
                archive = try BackupTransfer.readArchive(at: file.url)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
        .onDisappear {
            BackupTransfer.discardInboxCopy(file.url)
        }
    }

    private func contentRow(_ title: String, _ count: Int) -> some View {
        LabeledContent(title, value: count.formatted())
    }

    private func runImport(_ archive: BackupArchive) {
        isImporting = true
        defer { isImporting = false }
        do {
            summary = try BackupTransfer.importArchive(archive, into: modelContext)
        } catch {
            errorMessage = error.localizedDescription
            self.archive = nil
            BBLogger.error(.app, "BackupImportSheet: import failed: \(error)")
        }
    }
}

/// The per-model added/skipped counts after a merge.
struct BackupMergeSummarySection: View {
    let summary: BackupMergeSummary
    let title: String

    var body: some View {
        Section {
            ForEach(summary.rows, id: \.0) { label, detail in
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(.subheadline.weight(.medium))
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            if !summary.orphanedVehicles.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Skipped — no matching account")
                        .font(.subheadline.weight(.medium))
                    Text(summary.orphanedVehicles.joined(separator: ", "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text(title)
        } footer: {
            Text(summary.totalAdded == 0
                ? "Everything in that file was already on this device."
                : "Added \(summary.totalAdded) record\(summary.totalAdded == 1 ? "" : "s").")
        }
    }
}
