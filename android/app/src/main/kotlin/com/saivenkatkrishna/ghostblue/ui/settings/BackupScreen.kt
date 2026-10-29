package com.saivenkatkrishna.ghostblue.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.data.backup.BackupArchive
import com.saivenkatkrishna.ghostblue.data.backup.BackupMergeSummary
import com.saivenkatkrishna.ghostblue.data.backup.BackupTransfer
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.components.CardHeader
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.components.LabeledRow
import com.saivenkatkrishna.ghostblue.ui.components.SectionCard
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The name other devices see a backup came from, as iOS uses the device name. */
fun deviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

/** Hands a file in the cache to the share sheet. */
fun shareFile(context: Context, file: File, mimeType: String, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, title))
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

/** A backup read and waiting for the owner to confirm the merge. */
private data class PendingImport(val name: String, val archive: BackupArchive)

/**
 * Backup & Restore, as iOS's: export through Android's share sheet or to a
 * file, import from the file picker (or a backup opened from another app)
 * after a preview. Imports only add; nothing is overwritten.
 */
@Composable
fun BackupScreen(incomingBackup: MutableStateFlow<Uri?>, onBack: () -> Unit) {
    val context = LocalContext.current
    val garage = LocalAppGraph.current.garage
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var summary by remember { mutableStateOf<BackupMergeSummary?>(null) }
    val incoming by incomingBackup.collectAsStateWithLifecycle()

    fun read(uri: Uri) {
        scope.launch {
            try {
                val name = displayName(context, uri) ?: uri.lastPathSegment ?: "Backup"
                val archive = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(garage::readBackup)
                } ?: throw IllegalStateException("The file could not be opened.")
                pending = PendingImport(name, archive)
            } catch (error: Exception) {
                failure = error.message ?: "That file isn't a GhostBlue backup."
            }
        }
    }

    LaunchedEffect(incoming) {
        incoming?.let {
            incomingBackup.value = null
            read(it)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::read) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupTransfer.MIME_TYPE)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            try {
                withContext(Dispatchers.IO) {
                    val file = garage.writeBackup(deviceName(context), exportDirectory(context))
                    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                garage.post("Backup saved")
            } catch (error: Exception) {
                failure = error.message
            } finally {
                working = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard {
                    CardHeader("Export")
                    Text(
                        "Writes accounts, vehicles, climate presets, charging and trip history, maintenance reminders and automations " +
                            "to a GhostBlue backup file. HTTP logs are left out. The file opens in GhostBlue on an iPhone or another " +
                            "Android phone. It contains your saved credentials, so keep it somewhere private.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                working = true
                                try {
                                    val file = withContext(Dispatchers.IO) { garage.writeBackup(deviceName(context), exportDirectory(context)) }
                                    shareFile(context, file, BackupTransfer.MIME_TYPE, "Share backup")
                                } catch (error: Exception) {
                                    failure = error.message
                                } finally {
                                    working = false
                                }
                            }
                        },
                        enabled = !working,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().testTag("exportBackup"),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Export backup")
                    }
                    OutlinedButton(
                        onClick = { saver.launch(BackupTransfer.filename()) },
                        enabled = !working,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save to Files")
                    }
                    if (working) LoadingIndicator(Modifier.size(40.dp).align(Alignment.CenterHorizontally))
                }

                SectionCard {
                    CardHeader("Import")
                    Text(
                        "Adds anything the file has that this device does not. Nothing is deleted and no existing record is " +
                            "overwritten, so importing the same file twice changes nothing. For a vehicle already here, only settings " +
                            "with no value yet are filled in.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().testTag("importBackup"),
                    ) {
                        Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import and merge")
                    }
                }

                summary?.let { ImportSummary(it) }
            }
        }
    }

    pending?.let { import ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Import backup") },
            text = { BackupPreview(import) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        scope.launch {
                            working = true
                            try {
                                summary = garage.importBackup(import.archive)
                            } catch (error: Exception) {
                                failure = error.message
                            } finally {
                                working = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirmImport"),
                ) { Text("Import and merge") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }

    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text("Backup Failed") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
        )
    }
}

/** Backups are written to the cache, which the file provider shares. */
private fun exportDirectory(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

@Composable
private fun BackupPreview(import: PendingImport) {
    val archive = import.archive
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(import.name, style = MaterialTheme.typography.titleSmall)
        LabeledRow("From", archive.sourceDevice)
        LabeledRow("Created", Format.dateTime(archive.createdAt))
        Text("Contains", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        LabeledRow("Charge sessions", "${archive.chargeSessions.size}")
        LabeledRow("Trips", "${archive.trips.size}")
        LabeledRow("Vehicles", "${archive.vehicles.size}")
        LabeledRow("Accounts", "${archive.accounts.size}")
        LabeledRow("Climate presets", "${archive.climatePresets.size}")
        LabeledRow("Maintenance and automations", "${archive.maintenanceReminders.size + archive.automations.size}")
        Text(
            "Adds only what this device doesn't already have.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportSummary(summary: BackupMergeSummary) {
    SectionCard(Modifier.testTag("importSummary")) {
        CardHeader("Imported")
        summary.rows.forEach { (label, value) -> LabeledRow(label, value) }
        if (summary.orphanedVehicles.isNotEmpty()) {
            LabeledRow("Skipped — no matching account", summary.orphanedVehicles.joinToString(", "))
        }
        Text(
            if (summary.totalAdded == 0) "Everything in that file was already on this device."
            else "Added ${summary.totalAdded} record${if (summary.totalAdded == 1) "" else "s"}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
