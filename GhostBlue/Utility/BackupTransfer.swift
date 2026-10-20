//
//  BackupTransfer.swift
//  GhostBlue
//
//  Moving a backup between installs: a locally built app (simulator, Mac or
//  Xcode-installed iPhone) and a TestFlight or App Store build of the same
//  code. Backups get their own file type so AirDrop, Files and Mail offer
//  GhostBlue to open them, and an opened file goes straight to an import
//  preview instead of having to be found again inside Backup & Restore.
//

import BetterBlueKit
import Foundation
import Observation
import SwiftData
import UniformTypeIdentifiers

extension UTType {
    /// The JSON written by Backup & Restore, under its own extension.
    /// Declared in Info.plist (`UTExportedTypeDeclarations`), which is what
    /// makes iOS route `.ghostbluebackup` files to GhostBlue.
    static let ghostBlueBackup = UTType(exportedAs: "com.saivenkatkrishna.ghostblue.backup", conformingTo: .json)
}

enum BackupTransfer {
    static let fileExtension = "ghostbluebackup"

    /// Backups older than the dedicated file type were plain `.json`.
    static let importableTypes: [UTType] = [.ghostBlueBackup, .json]

    static func filename(on date: Date = Date()) -> String {
        "GhostBlue-Backup-\(date.formatted(.iso8601.year().month().day())).\(fileExtension)"
    }

    /// Encodes everything the app stores into a backup file at `directory`.
    @MainActor
    static func writeBackup(
        from context: ModelContext,
        deviceName: String,
        to directory: URL = FileManager.default.temporaryDirectory
    ) throws -> URL {
        let data = try BackupArchive.export(from: context, deviceName: deviceName).encoded()
        let url = directory.appendingPathComponent(filename())
        try data.write(to: url, options: .atomic)
        return url
    }

    /// Reads a backup picked in Files, AirDropped, or opened from another app.
    static func readArchive(at url: URL) throws -> BackupArchive {
        // Files picked outside the app's container arrive security-scoped.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let archive = try BackupArchive.decoded(from: Data(contentsOf: url))
        guard archive.version <= BackupArchive.currentVersion else {
            throw BackupTransferError.newerVersion
        }
        return archive
    }

    /// Merges without overwriting; see `BackupArchive.merge`.
    @MainActor
    static func importArchive(_ archive: BackupArchive, into context: ModelContext) throws -> BackupMergeSummary {
        let summary = try BackupArchive.merge(archive, into: context)
        // Restored precondition reminders need their notifications.
        Task { await AutomationReminderScheduler.sync(in: context) }
        return summary
    }

    /// Files opened from other apps are copied into the app's Inbox; drop the
    /// copy once handled so it doesn't accumulate.
    static func discardInboxCopy(_ url: URL) {
        guard url.pathComponents.contains("Inbox") else { return }
        try? FileManager.default.removeItem(at: url)
    }

    static var inboxDirectory: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent("Inbox", isDirectory: true)
    }

    /// Backups contain credentials. Removes Inbox copies left by an import
    /// that never finished (the app was closed with the preview open). Only
    /// copies older than `age` go, so a file being opened right now stays.
    static func purgeStaleInboxCopies(
        in inbox: URL? = inboxDirectory,
        olderThan age: TimeInterval = 3_600,
        now: Date = Date()
    ) {
        guard let inbox,
              let files = try? FileManager.default.contentsOfDirectory(
                  at: inbox, includingPropertiesForKeys: [.contentModificationDateKey]
              )
        else { return }
        for file in files where [fileExtension, "json"].contains(file.pathExtension.lowercased()) {
            let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?
                .contentModificationDate ?? .distantPast
            if now.timeIntervalSince(modified) > age {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }

    #if targetEnvironment(simulator)
    /// The Mac's Downloads folder. A simulator app runs as a Mac process, so a
    /// backup written here can be AirDropped from Finder to an iPhone.
    static var hostDownloadsFolder: URL? {
        guard let home = ProcessInfo.processInfo.environment["SIMULATOR_HOST_HOME"] else { return nil }
        return URL(fileURLWithPath: home, isDirectory: true).appendingPathComponent("Downloads", isDirectory: true)
    }
    #endif
}

enum BackupTransferError: LocalizedError {
    case newerVersion

    var errorDescription: String? {
        "That backup was written by a newer version of GhostBlue. Update this app, then import it again."
    }
}

// MARK: - Opening a backup from outside the app

struct PendingBackupFile: Identifiable {
    let id = UUID()
    let url: URL
}

/// Holds a backup file handed to the app (AirDrop, Files, Mail) until the
/// app is unlocked and can show the import preview.
@MainActor
@Observable
final class BackupImportCoordinator {
    static let shared = BackupImportCoordinator()

    var pendingFile: PendingBackupFile?

    /// Returns true when `url` is a backup file, which it then queues.
    @discardableResult
    func handle(_ url: URL) -> Bool {
        guard url.isFileURL,
              [BackupTransfer.fileExtension, "json"].contains(url.pathExtension.lowercased())
        else { return false }
        pendingFile = PendingBackupFile(url: url)
        return true
    }
}
