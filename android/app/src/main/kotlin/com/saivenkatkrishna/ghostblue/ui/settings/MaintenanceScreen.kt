package com.saivenkatkrishna.ghostblue.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.MaintenanceReminder
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.components.EmptyState
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.home.SwitchRow
import com.saivenkatkrishna.ghostblue.ui.theme.LocalGhostBlueColors
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * iOS's `MaintenanceCenterView` for one car: the recorded service cost,
 * reminders newest first (the box marks one done; tapping opens it to
 * edit or delete), iOS's Quick add services, and a new reminder of the
 * owner's own. Dated ones notify at 9 AM on the day and odometer ones when
 * a status reaches the reading, both from `ReminderScheduler`.
 */
@Composable
fun MaintenanceScreen(vin: String, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val db = graph.db
    val scope = graph.scope
    val vehicles by graph.garage.allVehicles.collectAsStateWithLifecycle()
    val vehicle = vehicles?.firstOrNull { it.vin == vin } ?: return
    val reminders by remember(vin) {
        db.maintenanceReminders().observeAll().map { list -> list.filter { it.vin == vin }.sortedByDescending { it.createdAt } }
    }.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<MaintenanceReminder?>(null) }
    var adding by remember { mutableStateOf(false) }
    val now = Instant.now()

    SubScreen(
        "Maintenance",
        onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Reminder") },
                modifier = Modifier.testTag("addReminder"),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().testTag("maintenanceList"),
            contentPadding = PaddingValues(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (reminders.isNotEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text("Recorded service cost") },
                        leadingContent = { Icon(Icons.Rounded.Payments, contentDescription = null) },
                        trailingContent = {
                            Text(
                                Format.currency(reminders.mapNotNull { it.cost }.sum()),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        colors = transparentListColors(),
                        modifier = Modifier.widthIn(max = 720.dp).testTag("serviceCost"),
                    )
                }
            } else {
                item {
                    EmptyState(
                        Icons.Rounded.Build,
                        "No Maintenance Reminders",
                        "Track service by date or odometer and get a reminder on this phone.",
                        Modifier.testTag("noReminders"),
                    )
                }
            }
            items(reminders, key = { it.id }) { reminder ->
                Box(Modifier.widthIn(max = 720.dp).animateItem()) {
                    SwipeToDelete(onDelete = { scope.launch { db.maintenanceReminders().delete(reminder) } }) {
                        ReminderRow(
                            reminder,
                            vehicle,
                            now,
                            onToggle = {
                                scope.launch {
                                    val row = db.maintenanceReminders().byId(reminder.id) ?: return@launch
                                    row.completedAt = if (row.isCompleted) null else Instant.now()
                                    db.maintenanceReminders().update(row)
                                }
                            },
                            onOpen = { editing = reminder },
                        )
                    }
                }
            }
            item {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    Text(
                        "Quick add",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
                    )
                    MaintenancePreset.entries.forEach { preset ->
                        ListItem(
                            headlineContent = { Text(preset.title) },
                            supportingContent = { Text(preset.subtitle(vehicle.odometer.units)) },
                            leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            colors = transparentListColors(),
                            modifier = Modifier.clickable {
                                scope.launch { db.maintenanceReminders().insert(preset.reminder(vehicle)) }
                            }.testTag("preset_${preset.name}"),
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        ReminderEditor(vehicle, null, onDismiss = { adding = false })
    }
    editing?.let { reminder -> ReminderEditor(vehicle, reminder, onDismiss = { editing = null }) }
}

@Composable
private fun ReminderRow(reminder: MaintenanceReminder, vehicle: BBVehicle, now: Instant, onToggle: () -> Unit, onOpen: () -> Unit) {
    val colors = LocalGhostBlueColors.current
    ListItem(
        leadingContent = {
            Checkbox(
                checked = reminder.isCompleted,
                onCheckedChange = { onToggle() },
                modifier = Modifier.testTag("reminderDone"),
            )
        },
        headlineContent = {
            Text(
                reminder.title,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (reminder.isCompleted) TextDecoration.LineThrough else null,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                reminder.dueDate?.let { due ->
                    val overdue = due < now && !reminder.isCompleted
                    Detail(Icons.Rounded.CalendarMonth, Format.pattern(due, "MMM d, yyyy"), if (overdue) MaterialTheme.colorScheme.error else null)
                }
                reminder.dueOdometer?.let { target ->
                    val reached = vehicle.odometer.length >= target && !reminder.isCompleted
                    Detail(
                        Icons.Rounded.Speed,
                        "${NumberFormat.getIntegerInstance().format(target)} ${vehicle.odometer.units.abbreviation}",
                        if (reached) MaterialTheme.colorScheme.error else null,
                    )
                }
                if (reminder.notes.isNotBlank()) Text(reminder.notes, style = MaterialTheme.typography.bodySmall)
                reminder.cost?.let { Text(Format.currency(it), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = colors.energy) }
            }
        },
        colors = transparentListColors(),
        modifier = Modifier.clickable(onClick = onOpen).testTag("reminderRow"),
    )
}

@Composable
private fun Detail(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color?) {
    val tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/** Adding ([reminder] null) or editing a reminder, in a sheet. */
@Composable
private fun ReminderEditor(vehicle: BBVehicle, reminder: MaintenanceReminder?, onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current
    val db = graph.db
    val scope = graph.scope
    var draft by remember(reminder?.id) {
        mutableStateOf(reminder?.let { MaintenanceDraft.forReminder(it, vehicle) } ?: MaintenanceDraft.forNew(vehicle))
    }
    var pickingDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val validation = draft.validationMessage

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp).testTag("reminderEditor"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (reminder == null) "New Reminder" else "Edit Reminder", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = {
                        val saved = draft
                        scope.launch {
                            // The current row, so a reminder posted meanwhile isn't overwritten.
                            val existing = reminder?.let { db.maintenanceReminders().byId(it.id) }
                            if (reminder != null && existing == null) return@launch
                            val target = existing ?: MaintenanceReminder().apply { vin = vehicle.vin }
                            saved.applyTo(target)
                            if (existing == null) db.maintenanceReminders().insert(target) else db.maintenanceReminders().update(target)
                        }
                        onDismiss()
                    },
                    enabled = validation == null,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.testTag("saveReminder"),
                ) { Text("Save") }
            }
            OutlinedTextField(
                draft.title,
                { draft = draft.copy(title = it) },
                label = { Text("Service or task") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("reminderTitle"),
            )
            OutlinedTextField(
                draft.notes,
                { draft = draft.copy(notes = it) },
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Due date", draft.hasDueDate) { draft = draft.copy(hasDueDate = it) }
            if (draft.hasDueDate) {
                OutlinedButton(onClick = { pickingDate = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(Format.pattern(draft.dueDate, "EEEE, MMM d, yyyy"))
                }
            }
            SwitchRow("Odometer target", draft.hasOdometer) { draft = draft.copy(hasOdometer = it) }
            if (draft.hasOdometer) {
                OutlinedTextField(
                    draft.odometerText,
                    { draft = draft.copy(odometerText = it) },
                    label = { Text("Due at") },
                    suffix = { Text(vehicle.odometer.units.abbreviation) },
                    supportingText = { Text("Now ${NumberFormat.getIntegerInstance().format(vehicle.odometer.length)} ${vehicle.odometer.units.abbreviation}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("reminderOdometer"),
                )
            }
            OutlinedTextField(
                draft.costText,
                { draft = draft.copy(costText = it) },
                label = { Text("Cost (optional)") },
                suffix = { Text(Format.currencyCode()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("reminderCost"),
            )
            validation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (reminder != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("deleteReminder")) {
                    Text("Delete reminder", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = draft.dueDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { draft = draft.copy(dueDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (confirmDelete && reminder != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${reminder.title}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { db.maintenanceReminders().delete(reminder) }
                    onDismiss()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
