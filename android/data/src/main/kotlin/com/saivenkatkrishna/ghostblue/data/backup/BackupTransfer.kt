package com.saivenkatkrishna.ghostblue.data.backup

import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId

/** A backup written by a newer GhostBlue than this one. */
class BackupNewerVersionException :
    IOException("That backup was written by a newer version of GhostBlue. Update this app, then import it again.")

/**
 * Moving a backup between installs, including between the iPhone and
 * Android apps. Backups are `.ghostbluebackup` files; older ones were `.json`.
 */
object BackupTransfer {
    const val FILE_EXTENSION = "ghostbluebackup"

    /** The MIME type the app registers for backup files. */
    const val MIME_TYPE = "application/vnd.saivenkatkrishna.ghostblue.backup+json"

    /** Extensions an import accepts. */
    val importableExtensions = listOf(FILE_EXTENSION, "json")

    /** "GhostBlue-Backup-2026-10-28.ghostbluebackup". */
    fun filename(on: LocalDate = LocalDate.now(ZoneId.systemDefault())): String = "GhostBlue-Backup-$on.$FILE_EXTENSION"

    /** Writes everything in [db] to a backup file in [directory]. */
    suspend fun writeBackup(db: GhostBlueDatabase, deviceName: String, directory: File): File {
        val data = BackupArchive.export(db, deviceName).encoded()
        val file = File(directory, filename())
        val partial = File(directory, "${file.name}.partial")
        partial.writeBytes(data)
        if (!partial.renameTo(file)) {
            file.delete()
            if (!partial.renameTo(file)) throw IOException("Could not write ${file.name}")
        }
        return file
    }

    /** Reads a backup, refusing one from a newer app version. */
    fun readArchive(input: InputStream): BackupArchive {
        val archive = BackupArchive.decoded(input.readBytes())
        if (archive.version > BackupArchive.CURRENT_VERSION) throw BackupNewerVersionException()
        return archive
    }

    fun readArchive(file: File): BackupArchive = file.inputStream().use(::readArchive)

    /** Merges without overwriting; see [BackupArchive.merge]. */
    suspend fun importArchive(archive: BackupArchive, db: GhostBlueDatabase): BackupMergeSummary =
        BackupArchive.merge(archive, db)

    /** True for a file name an import accepts. */
    fun isBackupFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in importableExtensions
}
