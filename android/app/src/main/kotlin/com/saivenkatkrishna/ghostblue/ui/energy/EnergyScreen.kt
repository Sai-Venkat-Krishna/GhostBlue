package com.saivenkatkrishna.ghostblue.ui.energy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.data.charging.ChargingCSVExporter
import com.saivenkatkrishna.ghostblue.data.charging.ChargingDisplayState
import com.saivenkatkrishna.ghostblue.data.charging.chargingDisplayState
import com.saivenkatkrishna.ghostblue.data.charging.vehicleSampleDate
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.vehicle.effectiveBatteryCapacityKWh
import com.saivenkatkrishna.ghostblue.data.vehicle.energySource
import com.saivenkatkrishna.ghostblue.data.vehicle.isUsingModelDefaultBatteryCapacity
import com.saivenkatkrishna.ghostblue.ui.components.cardToggleColors
import com.saivenkatkrishna.ghostblue.ui.CommandKind
import com.saivenkatkrishna.ghostblue.ui.CommandState
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.components.BarChart
import com.saivenkatkrishna.ghostblue.ui.components.CardHeader
import com.saivenkatkrishna.ghostblue.ui.components.ChartBar
import com.saivenkatkrishna.ghostblue.ui.components.EmptyState
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.components.LabeledRow
import com.saivenkatkrishna.ghostblue.ui.components.SectionCard
import com.saivenkatkrishna.ghostblue.ui.components.StatGrid
import com.saivenkatkrishna.ghostblue.ui.components.StatTile
import com.saivenkatkrishna.ghostblue.ui.components.VehicleSwitcher
import com.saivenkatkrishna.ghostblue.ui.components.WideWidth
import com.saivenkatkrishna.ghostblue.ui.home.CommandLine
import com.saivenkatkrishna.ghostblue.ui.home.rememberNow
import com.saivenkatkrishna.ghostblue.ui.settings.shareFile
import com.saivenkatkrishna.ghostblue.ui.theme.LocalGhostBlueColors
import java.io.File
import kotlin.math.abs

private enum class EnergySheet { COSTS, SCHEDULE }

/**
 * Energy: the charging log, as iOS's `ChargeHistoryView`: status, the
 * period's energy and cost, the trend, insights and every session, with
 * editing and manual entry.
 */
@Composable
fun EnergyScreen(reselection: Int) {
    val graph = LocalAppGraph.current
    val garage = graph.garage
    val vehicles by garage.vehicles.collectAsStateWithLifecycle()
    val selected by garage.selectedVehicle.collectAsStateWithLifecycle()
    val vehicle = selected ?: return
    val allSessions by remember(vehicle.vin) { garage.sessions(vehicle.vin) }.collectAsStateWithLifecycle(emptyList())
    var range by rememberSaveable { mutableStateOf(ChargeHistoryRange.MONTH) }
    var filter by rememberSaveable { mutableStateOf(ChargePowerFilter.ALL) }
    var sheet by remember { mutableStateOf<EnergySheet?>(null) }
    var editing by remember { mutableStateOf<ChargeSession?>(null) }
    var addingSession by remember { mutableStateOf(false) }
    val now = rememberNow()
    val summary = remember(allSessions, vehicle, range, filter, now) { ChargeHistorySummary(allSessions, vehicle, range, filter, now) }
    val list = rememberLazyListState()
    LaunchedEffect(reselection) { if (reselection > 0) list.animateScrollToItem(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Energy") },
                subtitle = {
                    VehicleSwitcher(vehicles.orEmpty(), vehicle, onSelect = { garage.selectVehicle(it.vin) }, style = MaterialTheme.typography.titleSmall)
                },
                actions = {
                    IconButton(onClick = { addingSession = true }, modifier = Modifier.testTag("addSession")) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add charging session manually")
                    }
                    IconButton(onClick = { sheet = EnergySheet.COSTS }) {
                        Icon(Icons.Rounded.Payments, contentDescription = "Charging price settings")
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Charging schedule and export") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Charging schedule") },
                                leadingIcon = { Icon(Icons.Rounded.Schedule, null) },
                                onClick = { menu = false; sheet = EnergySheet.SCHEDULE },
                            )
                            DropdownMenuItem(
                                text = { Text("Export selected period (CSV)") },
                                enabled = summary.displayed.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
                                    val file = File(directory, "GhostBlue-Charging.csv")
                                    runCatching {
                                        file.writeText(ChargingCSVExporter.csv(summary.displayed, vehicle))
                                        shareFile(context, file, "text/csv", "Export charging history")
                                    }.onFailure { garage.post("Export Failed: The charging file could not be exported.") }
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val isWide = maxWidth >= WideWidth
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().testTag("energyList"),
            ) {
                item(key = "top") {
                    CardPair(
                        isWide,
                        first = { StatusHero(vehicle) },
                        second = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                RangeCard(range, filter, onRange = { range = it }, onFilter = { filter = it })
                                EnergyDeliveredCard(summary, vehicle, onSetCapacity = { sheet = EnergySheet.COSTS })
                            }
                        },
                    )
                }
                if (!summary.hasAnySessions) {
                    item(key = "empty") {
                        SectionCard {
                            EmptyState(
                                Icons.Rounded.EvStation,
                                "No charging sessions yet",
                                "Plug in your vehicle for automatic tracking, or add a completed session manually.",
                                action = {
                                    FilledTonalButton(onClick = { addingSession = true }, shapes = ButtonDefaults.shapes()) { Text("Add session manually") }
                                },
                            )
                        }
                    }
                } else if (summary.displayed.isEmpty()) {
                    item(key = "emptyRange") {
                        SectionCard {
                            EmptyState(
                                Icons.Rounded.BarChart,
                                "No Sessions",
                                "No charging sessions were recorded in the last ${range.dayCount} days.",
                            )
                        }
                    }
                } else {
                    item(key = "stats") { StatsCard(summary, isWide) }
                    item(key = "trend") {
                        CardPair(
                            isWide,
                            first = { TrendCard(summary, vehicle, onSetCapacity = { sheet = EnergySheet.COSTS }) },
                            second = { InsightsCard(summary) },
                        )
                    }
                    item(key = "sessionsHeader") {
                        Text("Sessions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    }
                    val rows = if (isWide) summary.displayed.chunked(2) else summary.displayed.map { listOf(it) }
                    items(rows, key = { row -> row.first().id }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { session ->
                                SessionCard(session, vehicle, summary, onEdit = { editing = session }, modifier = Modifier.weight(1f))
                            }
                            if (row.size == 1 && isWide) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    when (sheet) {
        EnergySheet.COSTS -> CostSettingsSheet(vehicle, onDismiss = { sheet = null })
        EnergySheet.SCHEDULE -> SmartChargingSheet(vehicle, onDismiss = { sheet = null })
        null -> {}
    }
    if (addingSession) SessionEditorSheet(vehicle, session = null, onDismiss = { addingSession = false })
    editing?.let { session -> SessionEditorSheet(vehicle, session = session, onDismiss = { editing = null }) }
}

/** Two cards side by side when wide, stacked otherwise. */
@Composable
private fun CardPair(isWide: Boolean, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    if (isWide) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { first() }
            Box(Modifier.weight(1f)) { second() }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            first()
            second()
        }
    }
}

@Composable
private fun StatusHero(vehicle: BBVehicle) {
    val graph = LocalAppGraph.current
    val garage = graph.garage
    val distanceUnit by graph.settings.distanceUnit.collectAsStateWithLifecycle()
    val commands by garage.commands.collectAsStateWithLifecycle()
    val charge = commands[vehicle.vin to CommandKind.CHARGE] ?: CommandState()
    val colors = LocalGhostBlueColors.current
    val now = rememberNow()
    val ev = vehicle.evStatus
    val state = vehicle.chargingDisplayState(now)
    SectionCard(Modifier.testTag("chargingStatus"), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        if (ev == null) {
            Text("Battery data unavailable", style = MaterialTheme.typography.titleMedium)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${ev.evRange.percentage.toInt()}", style = MaterialTheme.typography.displayLargeEmphasized)
                Text("%", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(Format.distance(ev.evRange.range, distanceUnit), style = MaterialTheme.typography.titleMedium)
                    Text("Estimated range", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val progress = (ev.evRange.percentage / 100).toFloat().coerceIn(0f, 1f)
            if (ev.charging) {
                LinearWavyProgressIndicator(progress = { progress }, color = colors.energy, modifier = Modifier.fillMaxWidth())
                if (ev.chargeTimeSeconds > 0) {
                    Text("${Format.duration(ev.chargeTimeSeconds.toDouble())} remaining", style = MaterialTheme.typography.bodyMedium, color = colors.energy)
                }
            } else {
                LinearProgressIndicator(progress = { progress }, color = if (ev.evRange.percentage <= 20) colors.danger else colors.energy, modifier = Modifier.fillMaxWidth())
            }
        }
        val warn = state == ChargingDisplayState.OUTDATED || state == ChargingDisplayState.CONFIRMING_STOP
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (state == ChargingDisplayState.OUTDATED) Icons.Rounded.Schedule else Icons.Rounded.Bolt,
                contentDescription = null,
                tint = if (warn) colors.service else colors.energy,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(state.title, style = MaterialTheme.typography.titleSmall, color = if (warn) colors.service else MaterialTheme.colorScheme.onSurface)
        }
        if (state == ChargingDisplayState.CONFIRMING_STOP) {
            Text(
                "The last update reported a stop. Waiting for a newer vehicle reading to confirm it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        vehicle.vehicleSampleDate?.let {
            Text("Vehicle reading ${Format.relative(it, now)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ev != null) {
            Button(
                onClick = { garage.toggleCharging(vehicle) },
                enabled = ev.pluggedIn || ev.charging || charge.running,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().testTag("energyChargeButton"),
            ) {
                Text(
                    when {
                        charge.running -> "Cancel"
                        ev.charging -> "Stop charging"
                        ev.pluggedIn -> "Start charging"
                        else -> "Unplugged"
                    },
                )
            }
            CommandLine(
                charge,
                when {
                    ev.charging && ev.chargeSpeed > 0 -> "${Format.oneDecimal(ev.chargeSpeed)} kW"
                    ev.charging -> "Tap to stop"
                    ev.pluggedIn -> "Ready to charge"
                    else -> "Not plugged in"
                },
            )
        }
    }
}

@Composable
private fun RangeCard(
    range: ChargeHistoryRange,
    filter: ChargePowerFilter,
    onRange: (ChargeHistoryRange) -> Unit,
    onFilter: (ChargePowerFilter) -> Unit,
) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            ChargeHistoryRange.entries.forEachIndexed { index, option ->
                ToggleButton(
                    colors = cardToggleColors(),
                    checked = range == option,
                    onCheckedChange = { onRange(option) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        ChargeHistoryRange.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f).testTag("range_${option.name}"),
                ) { Text(option.title) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Peak speed", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }) { Text(filter.title) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ChargePowerFilter.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.title) }, onClick = { onFilter(option); expanded = false })
                    }
                }
            }
        }
    }
}

@Composable
private fun EnergyDeliveredCard(summary: ChargeHistorySummary, vehicle: BBVehicle, onSetCapacity: () -> Unit) {
    val colors = LocalGhostBlueColors.current
    SectionCard(Modifier.testTag("energyDelivered")) {
        CardHeader("Energy delivered")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Week", Format.kWh(summary.weekEnergyKWh), Modifier.weight(1f), accent = colors.energy)
            StatTile("Month", Format.kWh(summary.monthEnergyKWh), Modifier.weight(1f), accent = colors.energy)
            StatTile("Total", Format.kWh(summary.lifetimeEnergyKWh), Modifier.weight(1f), accent = colors.energy)
        }
        Text(
            if (summary.powerFilter == ChargePowerFilter.ALL) "Week and month totals use the last 7 and 30 days."
            else "Totals include only sessions with a peak speed of ${summary.powerFilter.title}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val capacity = vehicle.effectiveBatteryCapacityKWh
        when {
            capacity == null -> Text(
                "Set your battery's usable capacity for accurate energy totals.",
                color = colors.service,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable(onClick = onSetCapacity),
            )
            vehicle.isUsingModelDefaultBatteryCapacity -> Text(
                "Using the ${Format.upToOneDecimal(capacity)} kWh ${vehicle.model} default. Tap to set your exact capacity.",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable(onClick = onSetCapacity),
            )
        }
    }
}

@Composable
private fun StatsCard(summary: ChargeHistorySummary, isWide: Boolean) {
    val items = buildList {
        add("Energy delivered" to Format.kWh(summary.totalEnergyKWh))
        add("Charge added" to "+${summary.totalPercentAdded.toInt()}%")
        add("Sessions" to "${summary.displayed.size}")
        add("DC fast" to "${summary.dcSessionCount}")
        summary.totalCost?.let { add("Est. cost" to Format.currency(it)) }
    }
    SectionCard(Modifier.testTag("energyStats")) { StatGrid(items, columns = if (isWide) 4 else 2) }
}

@Composable
private fun TrendCard(summary: ChargeHistorySummary, vehicle: BBVehicle, onSetCapacity: () -> Unit) {
    val colors = LocalGhostBlueColors.current
    var selected by remember(summary.range, summary.powerFilter) { mutableStateOf<Int?>(null) }
    SectionCard(Modifier.testTag("energyTrend")) {
        CardHeader("Energy trend") {
            summary.energyChange?.let { change ->
                val up = change >= 0
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = (if (up) colors.service else colors.energy).copy(alpha = 0.15f),
                    contentColor = if (up) colors.service else colors.energy,
                    modifier = Modifier.semantics {
                        contentDescription = "${Format.fraction(abs(change), 0)} percent ${if (up) "more" else "less"} energy than the previous period"
                    },
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (up) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${Format.fraction(abs(change), 0)}%", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (summary.hasTrendEnergy) {
            val buckets = summary.buckets
            val step = when (summary.range) {
                ChargeHistoryRange.WEEK -> 1
                ChargeHistoryRange.MONTH -> 7
                ChargeHistoryRange.QUARTER -> 3
            }
            BarChart(
                bars = buckets.mapIndexed { index, bucket ->
                    val label = when {
                        summary.range == ChargeHistoryRange.WEEK -> Format.pattern(bucket.start, "EEE")
                        index % step == 0 -> Format.pattern(bucket.start, "MMM d")
                        else -> null
                    }
                    ChartBar(listOf(colors.energy to bucket.energyKWh), label)
                },
                selectedIndex = selected,
                onSelect = { index -> selected = index?.takeIf { buckets[it].sessionCount > 0 } },
                unit = "kWh",
                description = if (summary.range.bucketsByWeek) "Charging energy by week" else "Charging energy by day",
            )
            selected?.let { buckets.getOrNull(it) }?.let { bucket ->
                Row {
                    Text(
                        if (summary.range.bucketsByWeek) "Week of ${Format.pattern(bucket.start, "MMM d")}" else Format.pattern(bucket.start, "EEE, MMM d"),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${Format.fraction(bucket.energyKWh, 1)} kWh · ${bucket.sessionCount} session${if (bucket.sessionCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.energy,
                    )
                }
            }
        } else {
            val capacity = vehicle.effectiveBatteryCapacityKWh
            EmptyState(
                Icons.Rounded.BarChart,
                "No energy measured for these sessions yet",
                if (capacity == null) "Add your battery's usable capacity so energy can be estimated from the charge added."
                else "Energy appears once a session adds charge. Sessions that add no charge are counted as 0 kWh.",
                action = if (capacity == null) ({ TextButton(onClick = onSetCapacity) { Text("Set battery capacity") } }) else null,
            )
        }
    }
}

@Composable
private fun InsightsCard(summary: ChargeHistorySummary) {
    SectionCard(Modifier.testTag("energyInsights")) {
        CardHeader("Insights")
        LabeledRow("Avg. session", Format.kWh(summary.averageSessionEnergyKWh))
        LabeledRow("Avg. peak", summary.averagePeakKW?.let { "${Format.fraction(it, 0)} kW" } ?: "--")
        LabeledRow("Avg. cost", summary.averageCost?.let { Format.currency(it) } ?: "--")
        Text(
            if (summary.totalCost == null) "Add an electricity rate to include cost comparisons."
            else "Costs use your saved electricity rate and estimated energy added.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionCard(session: ChargeSession, vehicle: BBVehicle, summary: ChargeHistorySummary, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalGhostBlueColors.current
    val energy = summary.energy(session)
    val cost = summary.cost(session)
    val title = when {
        session.isActive -> "Charging"
        else -> listOfNotNull(
            session.percentAdded.takeIf { it >= 1 }?.let { "+${it.toInt()}%" },
            energy.takeIf { it >= 0.05 }?.let(Format::kWh),
        ).joinToString(" · ").ifEmpty { "No charge added" }
    }
    SectionCard(modifier.testTag("sessionCard")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (session.isActive) colors.energy else MaterialTheme.colorScheme.onSurface,
                    )
                    if (session.isDCFast) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = MaterialTheme.shapes.small, color = colors.service.copy(alpha = 0.15f), contentColor = colors.service) {
                            Text("DC", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                Text(
                    buildString {
                        append("${session.startPercentage.toInt()}% → ${session.endPercentage.toInt()}%")
                        append(" · ${Format.duration(maxOf(60.0, session.duration))}")
                        if (session.isActive && energy >= 0.05) append(" · ${Format.kWh(energy)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (session.maxChargeSpeedKW > 0) {
                    Text("${Format.decimals(session.maxChargeSpeedKW, 0)} kW peak", style = MaterialTheme.typography.labelMedium)
                }
                cost?.let {
                    Text(Format.currency(it), style = MaterialTheme.typography.titleSmall, color = colors.energy)
                    Text(if (session.actualCost != null) "actual" else "estimated", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(vehicle.energySource(session), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LabeledRow("Started", Format.dateTime(session.startDate))
        val end = session.endDate
        if (end != null) LabeledRow("Ended", Format.dateTime(end)) else Text("Charging is still in progress", style = MaterialTheme.typography.bodySmall, color = colors.energy)
        LabeledRow("Updated", Format.dateTimeWithSeconds(session.lastSampleDate))
        TextButton(onClick = onEdit, modifier = Modifier.testTag("editSession")) { Text("Edit session") }
    }
}
