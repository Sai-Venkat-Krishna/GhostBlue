package com.saivenkatkrishna.ghostblue.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.data.account.RemoteCommandLog
import com.saivenkatkrishna.ghostblue.data.model.CommandActivityState
import com.saivenkatkrishna.ghostblue.data.model.RemoteCommandActivity
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.components.EmptyState
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.components.cardToggleColors
import com.saivenkatkrishna.ghostblue.ui.theme.LocalGhostBlueColors
import kotlinx.coroutines.launch

/** The rows a filter shows: one car's, or every car's. */
fun remoteActivityRows(all: List<RemoteCommandActivity>, vin: String?, allVehicles: Boolean): List<RemoteCommandActivity> =
    if (allVehicles || vin == null) all else all.filter { it.vin == vin }

/**
 * iOS's `RemoteCommandActivityView`: every lock, climate and charging
 * command, from the app, widgets, tiles, shortcuts or the watch, newest
 * first, with how it ended. Opened for a car it shows that car's, with a
 * switch to all of them. Swipe a row away to delete it; Clear deletes what's
 * shown.
 */
@Composable
fun RemoteActivityScreen(vin: String?, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val db = graph.db
    val scope = graph.scope
    val all by remember { db.remoteCommands().observeAll() }.collectAsStateWithLifecycle(null)
    val vehicles by graph.garage.allVehicles.collectAsStateWithLifecycle()
    var allVehicles by rememberSaveable { mutableStateOf(vin == null) }
    var confirmClear by remember { mutableStateOf(false) }
    val rows = remoteActivityRows(all.orEmpty(), vin, allVehicles)
    val vehicleName = vehicles?.firstOrNull { it.vin == vin }?.displayName

    LaunchedEffect(Unit) { RemoteCommandLog.prune(db) }

    SubScreen(
        "Remote activity",
        onBack,
        actions = {
            if (rows.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.testTag("clearActivity")) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().testTag("activityList"),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (vin != null) {
                item {
                    Row(
                        Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    ) {
                        listOf(false to (vehicleName ?: "This vehicle"), true to "All vehicles").forEachIndexed { index, (value, label) ->
                            ToggleButton(
                                checked = allVehicles == value,
                                onCheckedChange = { allVehicles = value },
                                colors = cardToggleColors(),
                                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                modifier = Modifier.weight(1f).testTag(if (value) "allVehicles" else "thisVehicle"),
                            ) { Text(label, maxLines = 1) }
                        }
                    }
                }
            }
            if (all != null && rows.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.History,
                        "No Remote Activity",
                        "Lock, climate, charging, widget, tile, shortcut and watch commands will appear here.",
                        Modifier.testTag("noActivity"),
                    )
                }
            }
            items(rows, key = { it.id }) { activity ->
                Box(Modifier.widthIn(max = 720.dp).animateItem()) {
                    SwipeToDelete(onDelete = { scope.launch { db.remoteCommands().delete(listOf(activity.id)) } }) {
                        ActivityRow(activity)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear remote activity?") },
            text = { Text(if (allVehicles || vin == null) "Deletes every car's command history." else "Deletes this car's command history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        val ids = rows.map { it.id }
                        scope.launch { db.remoteCommands().delete(ids) }
                    },
                    modifier = Modifier.testTag("confirmClearActivity"),
                ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** A list row that's deleted by swiping it away to the start. */
@Composable
internal fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            val color by animateColorAsState(
                if (state.targetValue == SwipeToDismissBoxValue.EndToStart) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                label = "swipeBackground",
            )
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

@Composable
private fun ActivityRow(activity: RemoteCommandActivity) {
    val colors = LocalGhostBlueColors.current
    val stateColor = when (activity.state) {
        CommandActivityState.SENDING -> colors.service
        CommandActivityState.SUCCEEDED -> colors.energy
        CommandActivityState.FAILED -> MaterialTheme.colorScheme.error
    }
    ListItem(
        leadingContent = {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                when (activity.state) {
                    CommandActivityState.SENDING -> CircularWavyProgressIndicator(Modifier.size(24.dp), color = stateColor)
                    CommandActivityState.SUCCEEDED -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = stateColor)
                    CommandActivityState.FAILED -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = stateColor)
                }
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(activity.commandName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(activity.state.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = stateColor)
            }
        },
        supportingContent = {
            Column {
                Text("${activity.vehicleName} • ${activity.sourceName}")
                Text(
                    Format.pattern(activity.startedAt, "MMM d, h:mm a"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                activity.errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        colors = transparentListColors(),
        modifier = Modifier.testTag("activityRow"),
    )
}
