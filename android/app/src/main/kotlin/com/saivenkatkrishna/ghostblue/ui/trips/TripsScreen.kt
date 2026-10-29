package com.saivenkatkrishna.ghostblue.ui.trips

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.TripRecord
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.ui.components.cardToggleColors
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.components.BarChart
import com.saivenkatkrishna.ghostblue.ui.components.CardHeader
import com.saivenkatkrishna.ghostblue.ui.components.ChartBar
import com.saivenkatkrishna.ghostblue.ui.components.EmptyState
import com.saivenkatkrishna.ghostblue.ui.components.ErrorCard
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.components.LineChart
import com.saivenkatkrishna.ghostblue.ui.components.SectionCard
import com.saivenkatkrishna.ghostblue.ui.components.StatTile
import com.saivenkatkrishna.ghostblue.ui.components.VehicleSwitcher
import com.saivenkatkrishna.ghostblue.ui.components.WideWidth
import com.saivenkatkrishna.ghostblue.ui.components.labelIndices
import com.saivenkatkrishna.ghostblue.ui.energy.CostSettingsSheet
import com.saivenkatkrishna.ghostblue.ui.settings.shareFile
import com.saivenkatkrishna.ghostblue.ui.theme.LocalGhostBlueColors
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Trips: the saved trip archive, as iOS's `TripsView`: filters, the
 * efficiency trend and energy use, then every saved trip with rename,
 * favourite, delete, comparison and CSV export.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripsScreen(reselection: Int) {
    val graph = LocalAppGraph.current
    val garage = graph.garage
    val settings = graph.settings
    val vehicles by garage.vehicles.collectAsStateWithLifecycle()
    val selected by garage.selectedVehicle.collectAsStateWithLifecycle()
    val vehicle = selected ?: return
    val records by remember(vehicle.vin) { garage.trips(vehicle.vin) }.collectAsStateWithLifecycle(emptyList())
    val pendingDeletes by garage.pendingTripDeletions.collectAsStateWithLifecycle()
    val loads by garage.tripLoads.collectAsStateWithLifecycle()
    val load = loads[vehicle.vin]
    val distanceUnit by settings.distanceUnit.collectAsStateWithLifecycle()
    val showShortTrips by settings.showShortTrips.collectAsStateWithLifecycle()
    val detailedMode by settings.tripsDetailedMode.collectAsStateWithLifecycle()
    val hideLocation by settings.hideVehicleLocation.collectAsStateWithLifecycle()
    val supported by produceState<Boolean?>(null, vehicle.vin) { value = garage.supportsTrips(vehicle.vin) }

    var filter by rememberSaveable { mutableStateOf(TripDateFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(TripSortOption.NEWEST) }
    var search by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var comparing by remember { mutableStateOf(false) }
    val comparison = remember { mutableStateListOf<String>() }
    var showComparison by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TripRecord?>(null) }
    var showCosts by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(vehicle.vin) {
        expanded = null
        comparing = false
        comparison.clear()
        garage.loadTrips(vehicle.vin)
    }
    LaunchedEffect(reselection) { if (reselection > 0) list.animateScrollToItem(0) }
    LaunchedEffect(filter) { expanded = null }
    BackHandler(enabled = comparing) {
        comparing = false
        comparison.clear()
    }

    val visibleRecords = records.filter { it.id !in pendingDeletes }
    val filtered = TripAnalytics.filtered(visibleRecords.map { it.tripDetail }, filter)
        .filter { showShortTrips || it.distance >= 0.1 }
    val byKey = visibleRecords.associateBy { it.tripKey }
    fun record(trip: com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail) = byKey[TripRecord.key(vehicle.vin, trip)]
    val searched = filtered.filter { trip ->
        if (search.isBlank()) return@filter true
        val r = record(trip)
        val haystack = listOfNotNull(r?.customName, r?.startLocationName, r?.endLocationName, Format.dateTime(trip.startDate))
        haystack.any { it.contains(search.trim(), ignoreCase = true) }
    }
    val sorted = searched.sortedWith(
        compareByDescending<com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail> { record(it)?.isFavorite == true }.then(
            when (sort) {
                TripSortOption.NEWEST -> compareByDescending { it.startDate }
                TripSortOption.OLDEST -> compareBy { it.startDate }
                TripSortOption.LONGEST -> compareByDescending { it.distance }
                TripSortOption.MOST_EFFICIENT -> compareByDescending { TripAnalytics.efficiencyMilesPerKWh(it) ?: 0.0 }
                TripSortOption.MOST_ENERGY -> compareByDescending { TripAnalytics.energyKWh(it) }
            },
        ),
    )
    // Picks that leave the list with a filter or search are dropped.
    LaunchedEffect(sorted.map { it.id }) { comparison.retainAll(sorted.map { it.id }.toSet()) }
    val shown = if (detailedMode || search.isNotBlank()) sorted else sorted.take(5)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Trips") },
                subtitle = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VehicleSwitcher(vehicles.orEmpty(), vehicle, onSelect = { garage.selectVehicle(it.vin) }, style = MaterialTheme.typography.titleSmall)
                        if (records.isNotEmpty()) {
                            Text(" · Saved locally", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (load?.loading == true && records.isNotEmpty()) LoadingIndicator(Modifier.size(28.dp))
                    if (sorted.size >= 2) {
                        IconButton(
                            onClick = {
                                comparing = !comparing
                                comparison.clear()
                                expanded = null
                            },
                            modifier = Modifier.testTag("compareButton"),
                        ) { Icon(Icons.Rounded.CompareArrows, contentDescription = if (comparing) "Cancel comparison" else "Compare trips") }
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Trip options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (detailedMode) "Use compact view" else "Use detailed view") },
                                onClick = { menu = false; settings.setTripsDetailedMode(!detailedMode) },
                            )
                            DropdownMenuItem(
                                text = { Text("Export ${sorted.size} filtered trips") },
                                enabled = sorted.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    runCatching {
                                        val csv = TripCSVExporter.csv(
                                            sorted,
                                            vehicle.vin,
                                            byKey,
                                            vehicle.electricityRatePerKWh,
                                            settings.redactPrivateLocations.value,
                                        )
                                        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
                                        val file = File(directory, TripCSVExporter.filename(vehicle.displayName).replace('/', '-'))
                                        file.writeText(csv)
                                        shareFile(context, file, "text/csv", "Export trips")
                                    }.onFailure { garage.post("Export Failed: The trips file could not be exported.") }
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            AnimatedVisibility(comparing) {
                Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (comparison.isEmpty()) "Select two trips" else "${comparison.size} of 2 selected",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { comparing = false; comparison.clear() }) { Text("Cancel") }
                        Button(
                            onClick = { showComparison = true },
                            enabled = comparison.size == 2,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.testTag("showComparison"),
                        ) { Text("Compare") }
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = load?.loading == true && records.isNotEmpty(),
            onRefresh = { garage.loadTrips(vehicle.vin) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val isWide = maxWidth >= WideWidth
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().testTag("tripsList"),
                ) {
                    when {
                        supported == false && records.isEmpty() -> item {
                            EmptyState(
                                Icons.Rounded.Route,
                                "Trips not available",
                                if (vehicle.fuelType.hasElectricCapability) "${brandName(vehicle)} doesn't provide trip history in your region yet."
                                else "Trip history is currently available for electric vehicles only.",
                            )
                        }
                        load?.loading == true && records.isEmpty() -> item {
                            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                LoadingIndicator(Modifier.size(64.dp))
                                Text("Loading saved trips…", modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                        load?.error != null && records.isEmpty() -> item {
                            ErrorCard(load.error, onRetry = { garage.loadTrips(vehicle.vin) })
                        }
                        records.isEmpty() -> item {
                            EmptyState(Icons.Rounded.Route, "No recent trips", "Drive your vehicle and your trips will show up here.")
                        }
                        else -> {
                            if (load?.error != null) {
                                item {
                                    SectionCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                        Text("Showing saved trips", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "The vehicle service is unavailable. This history was saved " +
                                                (vehicle.lastTripArchiveAttemptDate?.let { Format.relative(it) } ?: "an earlier refresh") + ".",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                            item(key = "filters") { FilterBar(filter) { filter = it } }
                            if (filtered.isEmpty()) {
                                item {
                                    EmptyState(
                                        Icons.Rounded.Route,
                                        "No trips in this range",
                                        "Choose a longer time range to see older saved trips.",
                                        action = { FilledTonalButton(onClick = { filter = TripDateFilter.ALL }) { Text("Show all trips") } },
                                    )
                                }
                            } else {
                                item(key = "overview") {
                                    if (isWide) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                                            Box(Modifier.weight(1f)) { OverviewCard(filtered, vehicle, distanceUnit, onSetRate = { showCosts = true }) }
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                TrendCard(filtered, distanceUnit, startExpanded = true)
                                                EnergyUsageCard(filtered, startExpanded = true)
                                            }
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OverviewCard(filtered, vehicle, distanceUnit, onSetRate = { showCosts = true })
                                            TrendCard(filtered, distanceUnit, startExpanded = detailedMode)
                                            EnergyUsageCard(filtered, startExpanded = detailedMode)
                                        }
                                    }
                                }
                                item(key = "savedHeader") {
                                    SavedTripsControls(
                                        search = search,
                                        onSearch = { search = it },
                                        sort = sort,
                                        onSort = { sort = it },
                                        showShortTrips = showShortTrips,
                                        onShowShortTrips = settings::setShowShortTrips,
                                    )
                                }
                                if (sorted.isEmpty()) {
                                    item { EmptyState(Icons.Rounded.Search, "No Matching Trips", "Try another search or enable short trips.") }
                                }
                                val zone = ZoneId.systemDefault()
                                val groups = shown.groupBy { it.startDate.atZone(zone).toLocalDate() }.toList()
                                    .let { if (sort == TripSortOption.OLDEST) it.sortedBy { g -> g.first } else it.sortedByDescending { g -> g.first } }
                                groups.forEach { (day, trips) ->
                                    item(key = "day-$day") {
                                        Text(
                                            dayTitle(day).uppercase(),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 8.dp),
                                        )
                                    }
                                    val rows = if (isWide) trips.chunked(2) else trips.map { listOf(it) }
                                    items(rows, key = { row -> "trip-" + row.first().id }) { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                                            row.forEach { trip ->
                                                val tripRecord = record(trip)
                                                TripCard(
                                                    trip = trip,
                                                    record = tripRecord,
                                                    distanceUnit = distanceUnit,
                                                    isExpanded = expanded == trip.id,
                                                    comparing = comparing,
                                                    isSelected = trip.id in comparison,
                                                    hideLocation = hideLocation,
                                                    onTap = {
                                                        if (comparing) {
                                                            if (trip.id in comparison) comparison.remove(trip.id)
                                                            else {
                                                                if (comparison.size == 2) comparison.removeAt(0)
                                                                comparison += trip.id
                                                            }
                                                        } else {
                                                            expanded = if (expanded == trip.id) null else trip.id
                                                        }
                                                    },
                                                    onRename = { renaming = tripRecord },
                                                    onFavorite = { tripRecord?.let(garage::toggleFavorite) },
                                                    onDelete = {
                                                        tripRecord?.let {
                                                            comparison.remove(trip.id)
                                                            garage.deleteTrip(it)
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                            if (row.size == 1 && isWide) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                                if (!detailedMode && search.isBlank() && sorted.size > 5) {
                                    item {
                                        FilledTonalButton(
                                            onClick = { settings.setTripsDetailedMode(true) },
                                            shapes = ButtonDefaults.shapes(),
                                            modifier = Modifier.fillMaxWidth().testTag("showAllTrips"),
                                        ) { Text("Show all ${sorted.size} trips") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { trip ->
        var name by remember(trip.id) { mutableStateOf(trip.customName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename Trip") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Use a memorable name such as Commute or Airport run.")
                    OutlinedTextField(name, { name = it }, label = { Text("Trip name") }, singleLine = true, modifier = Modifier.testTag("tripNameField"))
                }
            },
            confirmButton = {
                TextButton(onClick = { garage.renameTrip(trip, name); renaming = null }, modifier = Modifier.testTag("saveTripName")) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    if (showComparison) {
        val first = sorted.firstOrNull { it.id == comparison.getOrNull(0) }
        val second = sorted.firstOrNull { it.id == comparison.getOrNull(1) }
        if (first != null && second != null) {
            TripComparisonSheet(first, second, record(first), record(second), distanceUnit, vehicle.electricityRatePerKWh, onDismiss = { showComparison = false })
        } else {
            showComparison = false
        }
    }
    if (showCosts) CostSettingsSheet(vehicle, onDismiss = { showCosts = false })
}

@Composable
private fun brandName(vehicle: BBVehicle): String {
    val accounts by LocalAppGraph.current.garage.accounts.collectAsStateWithLifecycle()
    return accounts?.firstOrNull { it.id == vehicle.ownerAccountId }?.brandEnum?.displayName ?: "Your brand"
}

private fun dayTitle(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> Format.pattern(day, "EEEE, MMMM d")
    }
}

fun efficiencyUnit(unit: Distance.Units) = if (unit == Distance.Units.KILOMETERS) "km/kWh" else "mi/kWh"

fun efficiencyIn(unit: Distance.Units, milesPerKWh: Double) = if (unit == Distance.Units.KILOMETERS) milesPerKWh * KM_PER_MILE else milesPerKWh

@Composable
private fun FilterBar(filter: TripDateFilter, onChange: (TripDateFilter) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        TripDateFilter.entries.forEachIndexed { index, option ->
            ToggleButton(
                colors = cardToggleColors(),
                checked = filter == option,
                onCheckedChange = { onChange(option) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    TripDateFilter.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).testTag("filter_${option.name}"),
            ) { Text(option.title, maxLines = 1) }
        }
    }
}

@Composable
private fun OverviewCard(
    trips: List<com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail>,
    vehicle: BBVehicle,
    unit: Distance.Units,
    onSetRate: () -> Unit,
) {
    val colors = LocalGhostBlueColors.current
    val distance = trips.sumOf { it.distance }
    val energy = trips.sumOf(TripAnalytics::energyKWh)
    val average = if (energy > 0) efficiencyIn(unit, distance / energy) else null
    val extremes = TripAnalytics.extremes(trips)
    val cost = TripAnalytics.electricityCost(trips, vehicle.electricityRatePerKWh)
    SectionCard(Modifier.testTag("tripsOverview")) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Average efficiency", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(average?.let(Format::oneDecimal) ?: "--", style = MaterialTheme.typography.displayMediumEmphasized, color = colors.energy)
                    Text(" ${efficiencyUnit(unit)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text("Distance traveled for each kilowatt-hour used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${trips.size} trips", style = MaterialTheme.typography.titleSmall)
                Text(Format.miles(distance, unit), style = MaterialTheme.typography.bodyMedium)
                Text(Format.kWh(energy), style = MaterialTheme.typography.bodyMedium)
            }
        }
        extremes?.let { (best, worst) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    "Best trip",
                    "${Format.oneDecimal(efficiencyIn(unit, TripAnalytics.efficiencyMilesPerKWh(best) ?: 0.0))} ${efficiencyUnit(unit)}",
                    Modifier.weight(1f),
                    caption = Format.pattern(best.startDate, "MMM d"),
                    accent = colors.energy,
                )
                StatTile(
                    "Worst trip",
                    "${Format.oneDecimal(efficiencyIn(unit, TripAnalytics.efficiencyMilesPerKWh(worst) ?: 0.0))} ${efficiencyUnit(unit)}",
                    Modifier.weight(1f),
                    caption = Format.pattern(worst.startDate, "MMM d"),
                    accent = colors.service,
                )
            }
            Text(
                "Best and worst compare efficiency within the selected date range.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Estimated cost", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSetRate) { Text(cost?.let { Format.currency(it) } ?: "Set rate") }
        }
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    startExpanded: Boolean,
    tag: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(startExpanded) { mutableStateOf(startExpanded) }
    SectionCard(Modifier.testTag(tag)) {
        CardHeader(title, subtitle = subtitle) {
            trailing()
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
        }
        if (expanded) content()
    }
}

@Composable
private fun TrendCard(trips: List<com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail>, unit: Distance.Units, startExpanded: Boolean) {
    val colors = LocalGhostBlueColors.current
    var period by rememberSaveable { mutableStateOf(TripTrendPeriod.WEEKLY) }
    val points = TripAnalytics.trendPoints(trips, period)
    val change = TripAnalytics.percentageChange(points)
    var selected by remember(period, trips) { mutableStateOf<Int?>(null) }
    CollapsibleCard(
        "Efficiency trend",
        startExpanded,
        "efficiencyTrend",
        trailing = {
            change?.let {
                val up = it >= 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (up) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                        contentDescription = null,
                        tint = if (up) colors.energy else colors.service,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("${Format.decimals(abs(it), 0)}%", color = if (up) colors.energy else colors.service, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            TripTrendPeriod.entries.forEachIndexed { index, option ->
                ToggleButton(
                    colors = cardToggleColors(),
                    checked = period == option,
                    onCheckedChange = { period = option },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        TripTrendPeriod.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(option.title) }
            }
        }
        when (points.size) {
            0 -> EmptyState(Icons.Rounded.Route, "Not Enough Data", "Drive history will build this trend over time.")
            1 -> Column {
                Text("Current ${period.title.lowercase()}", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${Format.oneDecimal(efficiencyIn(unit, points[0].efficiencyMilesPerKWh))} ${efficiencyUnit(unit)}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.energy,
                )
                Text(
                    "A trend will appear after trips are available in at least two ${period.pluralTitle.lowercase()}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                val shownLabels = labelIndices(points.size, 4)
                LineChart(
                    values = points.map { efficiencyIn(unit, it.efficiencyMilesPerKWh) },
                    labels = points.mapIndexed { index, point ->
                        if (index in shownLabels) Format.pattern(point.periodStart, if (period == TripTrendPeriod.MONTHLY) "MMM" else "MMM d") else null
                    },
                    color = colors.energy,
                    selectedIndex = selected,
                    onSelect = { selected = it },
                    unit = efficiencyUnit(unit),
                    description = "Efficiency trend",
                )
                selected?.let { points.getOrNull(it) }?.let { point ->
                    Row {
                        Text(Format.pattern(point.periodStart, "MMM d"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text(
                            "${Format.oneDecimal(efficiencyIn(unit, point.efficiencyMilesPerKWh))} ${efficiencyUnit(unit)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.energy,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EnergyUsageCard(trips: List<com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail>, startExpanded: Boolean) {
    val colors = LocalGhostBlueColors.current
    var breakdown by rememberSaveable { mutableStateOf(false) }
    var selected by remember(trips) { mutableStateOf<Int?>(null) }
    val totals = TripChartData.totalPoints(trips)
    val parts = TripChartData.breakdownPoints(trips)
    val hasEnergy = totals.any { it.energyKWh > 0 }
    val categoryColors = mapOf(
        TripEnergyCategory.DRIVETRAIN to colors.service,
        TripEnergyCategory.CLIMATE to colors.climate,
        TripEnergyCategory.ACCESSORIES to colors.location,
        TripEnergyCategory.BATTERY_CARE to MaterialTheme.colorScheme.tertiary,
    )
    CollapsibleCard(
        "Energy usage",
        startExpanded,
        "energyUsage",
        subtitle = if (breakdown) "By system" else "Total per trip",
        trailing = {
            Text("Breakdown", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            Switch(checked = breakdown, onCheckedChange = { breakdown = it }, enabled = hasEnergy)
        },
    ) {
        if (!hasEnergy) {
            EmptyState(Icons.Rounded.Route, "No Energy Data", "These trips do not include usable energy readings yet.")
        } else {
            val axis = TripChartData.axisIndices(totals.size).toSet()
            val bars = totals.map { point ->
                val label = if (point.index in axis) Format.pattern(point.trip.startDate, "M/d") else null
                if (breakdown) {
                    ChartBar(parts.filter { it.tripIndex == point.index }.map { categoryColors.getValue(it.category) to it.energyKWh }, label)
                } else {
                    ChartBar(listOf(colors.service to point.energyKWh), label)
                }
            }
            BarChart(
                bars = bars,
                selectedIndex = selected,
                onSelect = { selected = it },
                yMaximum = if (breakdown) TripChartData.breakdownYAxisMaximum(parts) else TripChartData.yAxisMaximum(totals.map { it.energyKWh }),
                unit = "kWh",
                description = "Energy per trip",
            )
            if (breakdown) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TripEnergyCategory.entries.forEach { category ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).padding(0.dp)) {
                                androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(categoryColors.getValue(category)) }
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(category.title, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            selected?.let { totals.getOrNull(it) }?.let { point ->
                Row {
                    Text(Format.pattern(point.trip.startDate, "MMM d, h:mm a"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(Format.decimals(point.energyKWh, 2) + " kWh", style = MaterialTheme.typography.labelLarge, color = colors.service)
                }
            }
            if (trips.size > TripChartData.MAXIMUM_TRIP_COUNT) {
                Text(
                    "Showing the latest ${TripChartData.MAXIMUM_TRIP_COUNT} trips",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SavedTripsControls(
    search: String,
    onSearch: (String) -> Unit,
    sort: TripSortOption,
    onSort: (TripSortOption) -> Unit,
    showShortTrips: Boolean,
    onShowShortTrips: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text("Saved trips", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = search,
                onValueChange = onSearch,
                placeholder = { Text("Search saved trips") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.weight(1f).testTag("tripSearch"),
            )
            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { expanded = true }) { Icon(Icons.Rounded.Sort, contentDescription = "Sort trips") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    TripSortOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.title) },
                            trailingIcon = { if (option == sort) Icon(Icons.Rounded.CheckCircle, null) },
                            onClick = { onSort(option); expanded = false },
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show zero-distance and very short trips", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = showShortTrips, onCheckedChange = onShowShortTrips)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripCard(
    trip: com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail,
    record: TripRecord?,
    distanceUnit: Distance.Units,
    isExpanded: Boolean,
    comparing: Boolean,
    isSelected: Boolean,
    hideLocation: Boolean,
    onTap: () -> Unit,
    onRename: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGhostBlueColors.current
    val efficiency = efficiencyIn(distanceUnit, trip.efficiency)
    Card(
        onClick = onTap,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier.fillMaxWidth().testTag("tripCard"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (comparing) {
                    Icon(
                        if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.CompareArrows,
                        contentDescription = if (isSelected) "Selected for comparison" else null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    record?.customName?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(Format.pattern(trip.startDate, "EEE, MMM d, h:mm a"), style = MaterialTheme.typography.bodyMedium)
                }
                if (record?.isFavorite == true) Icon(Icons.Rounded.Star, contentDescription = "Favorite", tint = colors.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(Format.miles(trip.distance, distanceUnit), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("tripMenu")) { Icon(Icons.Rounded.MoreVert, contentDescription = "Trip actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (record?.isFavorite == true) "Remove Favorite" else "Favorite") },
                            leadingIcon = { Icon(if (record?.isFavorite == true) Icons.Rounded.StarBorder else Icons.Rounded.Star, null) },
                            onClick = { menu = false; onFavorite() },
                        )
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            Text(
                "${trip.formattedDuration} · ${Format.tripEnergy(TripAnalytics.energyKWh(trip))} · ${Format.oneDecimal(efficiency)} ${efficiencyUnit(distanceUnit)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isExpanded) {
                val start = record?.startLocationName
                val end = record?.endLocationName
                if (!hideLocation && (start != null || end != null)) {
                    Text("${start ?: "Start"} → ${end ?: "End"}", style = MaterialTheme.typography.bodyMedium)
                } else if (hideLocation && record?.startLatitude != null) {
                    Text("Trip locations hidden", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Drivetrain", Format.wattHours(trip.drivetrainEnergy))
                    Pill("Regen", Format.wattHours(trip.regenEnergy))
                    Pill("Climate", Format.wattHours(trip.climateEnergy))
                    Pill("Accessories", Format.wattHours(trip.accessoriesEnergy))
                    if (trip.batteryCareEnergy > 0) Pill("Batt Care", Format.wattHours(trip.batteryCareEnergy))
                    val speedUnit = if (distanceUnit == Distance.Units.KILOMETERS) "km/h" else "mph"
                    val factor = if (distanceUnit == Distance.Units.KILOMETERS) KM_PER_MILE else 1.0
                    Pill("Speed", "${(trip.avgSpeed * factor).toInt()} avg · ${(trip.maxSpeed * factor).toInt()} $speedUnit")
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, value: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
    }
}
