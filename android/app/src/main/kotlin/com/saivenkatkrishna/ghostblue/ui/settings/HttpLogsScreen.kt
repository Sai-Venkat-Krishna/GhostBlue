package com.saivenkatkrishna.ghostblue.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Http
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.data.model.BBHTTPLog
import com.saivenkatkrishna.ghostblue.data.model.DeviceType
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLog
import com.saivenkatkrishna.ghostblue.kit.model.HTTPRequestType
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.components.EmptyState
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.theme.LocalGhostBlueColors
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val prettyJson = Json { prettyPrint = true }

/** Pretty-printed JSON when [body] is JSON, else the text as it came. */
fun prettyBody(body: String?): String {
    if (body.isNullOrBlank()) return "(empty)"
    return runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(body)) }.getOrDefault(body)
}

/** Settings › HTTP Logs, newest first, as iOS's: filter by request and device type, clear all. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HttpLogsScreen(onBack: () -> Unit, onOpen: (UUID) -> Unit) {
    val garage = LocalAppGraph.current.garage
    val logs by remember { garage.httpLogs() }.collectAsStateWithLifecycle(emptyList())
    var showFilters by remember { mutableStateOf(false) }
    var requestTypes by remember { mutableStateOf(HTTPRequestType.entries.toSet()) }
    var deviceTypes by remember { mutableStateOf(DeviceType.entries.toSet()) }
    val shown = logs.filter { it.log.requestType in requestTypes && it.deviceType in deviceTypes }

    SubScreen(
        "HTTP Logs",
        onBack,
        actions = {
            IconButton(onClick = { showFilters = !showFilters }) { Icon(Icons.Rounded.FilterList, contentDescription = "Filter") }
            IconButton(onClick = { garage.clearHTTPLogs() }, modifier = Modifier.testTag("clearLogs")) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear All")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("httpLogList")) {
            if (showFilters) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Request types", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HTTPRequestType.entries.forEach { type ->
                                FilterChip(
                                    selected = type in requestTypes,
                                    onClick = { requestTypes = if (type in requestTypes) requestTypes - type else requestTypes + type },
                                    label = { Text(type.displayName) },
                                )
                            }
                        }
                        Text("Device types", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DeviceType.entries.forEach { type ->
                                FilterChip(
                                    selected = type in deviceTypes,
                                    onClick = { deviceTypes = if (type in deviceTypes) deviceTypes - type else deviceTypes + type },
                                    label = { Text(type.displayName) },
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (shown.isEmpty()) {
                item { EmptyState(Icons.Rounded.Http, "No HTTP Logs", "HTTP requests will appear here when made by any account.") }
            }
            items(shown, key = { it.id }) { entry -> LogRow(entry, onClick = { onOpen(entry.id) }) }
        }
    }
}

@Composable
private fun StatusBadge(log: HTTPLog) {
    val colors = LocalGhostBlueColors.current
    val status = log.responseStatus
    val tint = when {
        status == null -> MaterialTheme.colorScheme.onSurfaceVariant
        status in 200..299 -> colors.energy
        status in 300..399 -> colors.service
        else -> colors.danger
    }
    Badge(status?.toString() ?: "Unknown", tint)
}

@Composable
private fun Badge(text: String, tint: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = tint.copy(alpha = 0.15f), contentColor = tint) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun LogRow(entry: BBHTTPLog, onClick: () -> Unit) {
    val log = entry.log
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(log.requestType.displayName, MaterialTheme.colorScheme.primary)
                Badge(log.method, MaterialTheme.colorScheme.secondary)
                StatusBadge(log)
                Spacer(Modifier.weight(1f))
                Text(Format.pattern(log.timestamp, "h:mm:ss a"), style = MaterialTheme.typography.labelSmall)
            }
        },
        supportingContent = {
            Column {
                Text(log.url, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(log.formattedDuration, style = MaterialTheme.typography.labelSmall)
                    Badge(entry.deviceType.displayName, LocalGhostBlueColors.current.climate)
                    if (log.error != null) Icon(Icons.Rounded.Warning, contentDescription = "Error", tint = LocalGhostBlueColors.current.danger, modifier = Modifier.size(14.dp))
                }
            }
        },
        colors = transparentListColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** One request: request, response and stack trace tabs; tapping a value copies it. */
@Composable
fun HttpLogDetailScreen(id: UUID, onBack: () -> Unit) {
    val garage = LocalAppGraph.current.garage
    val context = LocalContext.current
    val logs by remember { garage.httpLogs() }.collectAsStateWithLifecycle(emptyList())
    val entry = logs.firstOrNull { it.id == id } ?: return
    val log = entry.log
    val tabs = listOfNotNull("Request", "Response", "Stack Trace".takeIf { log.stackTrace != null })
    var tab by remember { mutableIntStateOf(0) }
    fun copy(label: String, value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        garage.post("$label copied to clipboard")
    }
    SubScreen(
        "HTTP Log Details",
        onBack,
        actions = {
            IconButton(onClick = {
                val directory = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(directory, "GhostBlue-HTTPLog.json")
                file.writeText(prettyJson.encodeToString(HTTPLog.serializer(), log))
                shareFile(context, file, "application/json", "Share log")
            }) { Icon(Icons.Rounded.Share, contentDescription = "Share log") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }) }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tabs[tab]) {
                    "Request" -> {
                        DetailGroup("Request Details", listOf("Method" to log.method, "URL" to log.url, "Timestamp" to Format.dateTimeWithSeconds(log.timestamp), "Duration" to Format.decimals(log.duration, 2) + " seconds"), ::copy)
                        DetailGroup("Request Headers", log.requestHeaders.toSortedMap().toList(), ::copy)
                        Body("Request Body", log.requestBody, ::copy)
                    }
                    "Response" -> {
                        DetailGroup("Response Details", listOfNotNull("Status Code" to log.statusText, log.error?.let { "Error" to it }), ::copy)
                        DetailGroup("Response Headers", log.responseHeaders.toSortedMap().toList(), ::copy)
                        Body("Response Body", log.responseBody, ::copy)
                    }
                    else -> Body("Stack Trace", log.stackTrace, ::copy)
                }
            }
        }
    }
}

@Composable
private fun DetailGroup(title: String, rows: List<Pair<String, String>>, copy: (String, String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    if (rows.isEmpty()) Text("None", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    rows.forEach { (label, value) ->
        Column(Modifier.fillMaxWidth().clickable { copy(label, value) }.padding(vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Body(title: String, body: String?, copy: (String, String) -> Unit) {
    val text = prettyBody(body)
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable { copy(title, text) },
    ) {
        Box(Modifier.padding(12.dp)) {
            Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal)
        }
    }
    Spacer(Modifier.width(1.dp))
}
