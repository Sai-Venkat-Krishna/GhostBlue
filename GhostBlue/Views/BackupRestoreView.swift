//
//  BackupRestoreView.swift
//  GhostBlue
//
//  Settings → Backup & Restore. Writes everything the app stores to a
//  single JSON file, and folds one of those files back in without
//  overwriting anything already here.
//

import BetterBlueKit
import SwiftData
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct BackupRestoreView: View {
    @Environment(\.modelContext) private var modelContext

    @State private var exportDocument: BackupDocument?
    @State private var isExporting = false
    @State private var isImporting = false
    @State private var isWorking = false
    @State private var summary: BackupMergeSummary?
    @State private var errorMessage: String?

    private var defaultFilename: String {
        let stamp = Date().formatted(.iso8601.year().month().day())
        return "GhostBlue-Backup-\(stamp)"
    }

    var body: some View {
        Form {
            Section {
                Button {
                    prepareExport()
                } label: {
                    Label("Export Backup", systemImage: "square.and.arrow.up")
                }
                .disabled(isWorking)
            } header: {
                Text("Export")
            } footer: {
                Text("Writes accounts, vehicles, climate presets, charging and trip history, "
                    + "maintenance reminders and automations to a single JSON file. "
                    + "HTTP logs are left out. The file contains your saved credentials, "
                    + "so keep it somewhere private.")
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
                        ProgressView()
                        Text("Working…")
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if let summary {
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
                    Text("Last Import")
                } footer: {
                    Text(summary.totalAdded == 0
                        ? "Everything in that file was already on this device."
                        : "Added \(summary.totalAdded) record\(summary.totalAdded == 1 ? "" : "s").")
                }
            }
        }
        .navigationTitle("Backup & Restore")
        .navigationBarTitleDisplayMode(.inline)
        .fileExporter(
            isPresented: $isExporting,
            document: exportDocument,
            contentType: .json,
            defaultFilename: defaultFilename
        ) { result in
            if case let .failure(error) = result {
                errorMessage = error.localizedDescription
            }
        }
        .fileImporter(
            isPresented: $isImporting,
            allowedContentTypes: [.json],
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
    }

    private func prepareExport() {
        isWorking = true
        let archive = BackupArchive.export(from: modelContext, deviceName: UIDevice.current.name)
        do {
            exportDocument = BackupDocument(data: try archive.encoded())
            isExporting = true
        } catch {
            errorMessage = error.localizedDescription
        }
        isWorking = false
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        guard case let .success(urls) = result, let url = urls.first else {
            if case let .failure(error) = result {
                errorMessage = error.localizedDescription
            }
            return
        }

        isWorking = true
        defer { isWorking = false }

        // A file picked outside the app's own container arrives
        // security-scoped; reading it without this fails with a bare
        // permission error.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        do {
            let data = try Data(contentsOf: url)
            let archive = try BackupArchive.decoded(from: data)
            guard archive.version <= BackupArchive.currentVersion else {
                errorMessage = "That backup was written by a newer version of GhostBlue."
                return
            }
            summary = try BackupArchive.merge(archive, into: modelContext)
        } catch {
            errorMessage = error.localizedDescription
            BBLogger.error(.app, "BackupRestoreView: import failed: \(error)")
        }
    }
}

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
