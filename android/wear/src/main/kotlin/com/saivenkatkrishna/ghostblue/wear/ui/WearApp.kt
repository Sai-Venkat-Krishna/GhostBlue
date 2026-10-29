package com.saivenkatkrishna.ghostblue.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import coil3.compose.AsyncImage
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.VehicleImageURLs
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.wear.WatchCommand
import com.saivenkatkrishna.ghostblue.wear.WatchControl
import com.saivenkatkrishna.ghostblue.wear.WearGraph
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The Rivian theme on the watch: ink and paper surfaces, amber accent, as iOS's watch's Rivian look. */
private val RivianWatchColors = ColorScheme(
    primary = Color(0xFFF2F2F0),
    onPrimary = Color(0xFF111113),
    primaryContainer = Color(0xFF2A2A2D),
    onPrimaryContainer = Color(0xFFF2F2F0),
    secondary = Color(0xFFFFAC00),
    onSecondary = Color(0xFF231600),
    secondaryContainer = Color(0xFF3A2A08),
    onSecondaryContainer = Color(0xFFFFC747),
    tertiary = Color(0xFF34C468),
    surfaceContainer = Color(0xFF18181A),
    surfaceContainerHigh = Color(0xFF232326),
    onSurface = Color(0xFFF2F2F0),
    onSurfaceVariant = Color(0xFF9C9CA3),
    background = Color(0xFF0B0B0C),
    onBackground = Color(0xFFF2F2F0),
)

@Composable
private fun WearTheme(rivian: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (rivian) RivianWatchColors else (if (LocalInspectionMode.current) null else dynamicColorScheme(context)) ?: ColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

private object Route {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SIGN_IN = "signin"
    const val HELP = "help"
}

/**
 * The watch app, as iOS's: a page per car to swipe between, each with its
 * charge, range and controls; settings, sign-in and help behind them.
 * [remoteImages] is off in tests.
 */
@Composable
fun WearApp(graph: WearGraph, remoteImages: Boolean = true) {
    val rivian by graph.settings.rivian.collectAsStateWithLifecycle()
    val navController = rememberSwipeDismissableNavController()
    WearTheme(rivian) {
        AppScaffold(modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            SwipeDismissableNavHost(navController = navController, startDestination = Route.HOME) {
                composable(Route.HOME) {
                    Home(
                        graph,
                        remoteImages,
                        onSettings = { navController.navigate(Route.SETTINGS) },
                        onSignIn = { navController.navigate(Route.SIGN_IN) },
                        onHelp = { navController.navigate(Route.HELP) },
                    )
                }
                composable(Route.SETTINGS) {
                    SettingsScreen(graph, onSignIn = { navController.navigate(Route.SIGN_IN) }, onHelp = { navController.navigate(Route.HELP) })
                }
                composable(Route.SIGN_IN) { SignInScreen(graph, onDone = { navController.popBackStack(Route.HOME, inclusive = false) }) }
                composable(Route.HELP) { HelpScreen() }
            }
        }
    }
}

@Composable
private fun Home(graph: WearGraph, remoteImages: Boolean, onSettings: () -> Unit, onSignIn: () -> Unit, onHelp: () -> Unit) {
    val vehicles by graph.model.vehicles.collectAsStateWithLifecycle()
    val list = vehicles
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isEmpty() -> EmptyState(onSignIn, onHelp)
        else -> VehiclePager(graph, list, remoteImages, onSettings)
    }
}

/** iOS's "No Vehicles" state, with ways in that work on Android: the test account, sign-in, or the phone. */
@Composable
private fun EmptyState(onSignIn: () -> Unit, onHelp: () -> Unit) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.testTag("emptyState")) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.DirectionsCar, contentDescription = null, modifier = Modifier.size(32.dp))
                    Text("No Vehicles", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(
                        "Open GhostBlue on your phone, or sign in here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().testTag("signInButton"), label = { Text("Sign in") })
            }
            item {
                OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth(), label = { Text("Help") })
            }
        }
    }
}

@Composable
private fun VehiclePager(graph: WearGraph, vehicles: List<BBVehicle>, remoteImages: Boolean, onSettings: () -> Unit) {
    val selected by graph.settings.selectedVin.collectAsStateWithLifecycle()
    val initial = vehicles.indexOfFirst { it.vin == selected }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initial) { vehicles.size }
    // The page in front is the car the tile and complications show.
    LaunchedEffect(pagerState.currentPage, vehicles.size) {
        vehicles.getOrNull(pagerState.currentPage)?.let { graph.settings.setSelectedVin(it.vin) }
    }
    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(state = pagerState) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                vehicles.getOrNull(page)?.let { VehiclePage(graph, it, remoteImages, onSettings) }
            }
        }
    }
}

@Composable
private fun VehiclePage(graph: WearGraph, vehicle: BBVehicle, remoteImages: Boolean, onSettings: () -> Unit) {
    val unit by graph.settings.distanceUnit.collectAsStateWithLifecycle()
    val commands by graph.model.commands.collectAsStateWithLifecycle()
    val refreshing by graph.model.refreshing.collectAsStateWithLifecycle()
    val state = rememberTransformingLazyColumnState()
    LaunchedEffect(vehicle.vin) { graph.model.refreshIfStale(vehicle.vin) }
    val ev = vehicle.evStatus
    val locked = vehicle.lockStatus == VehicleStatus.LockStatus.LOCKED
    val climateOn = vehicle.climateStatus?.airControlOn == true

    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.testTag("vehiclePage")) {
            item { Header(vehicle, unit, refreshing = vehicle.vin in refreshing, onRefresh = { graph.model.refresh(vehicle.vin, forced = true) }, onSettings = onSettings) }
            item { CarImage(vehicle, remoteImages) }
            item {
                ControlButton(
                    command = commands[vehicle.vin to WatchControl.LOCK],
                    action = if (locked) RemoteAction.UNLOCK else RemoteAction.LOCK,
                    stateText = if (locked) "Locked" else if (vehicle.lockStatus == VehicleStatus.LockStatus.UNLOCKED) "Unlocked" else "Lock unknown",
                    icon = { Icon(if (locked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, contentDescription = null) },
                    tag = "lockControl",
                ) { graph.model.send(vehicle.vin, WatchControl.LOCK, it) }
            }
            item {
                ControlButton(
                    command = commands[vehicle.vin to WatchControl.CLIMATE],
                    action = if (climateOn) RemoteAction.STOP_CLIMATE else RemoteAction.START_CLIMATE,
                    stateText = if (climateOn) "Climate on" else "Climate off",
                    icon = { Icon(Icons.Rounded.AcUnit, contentDescription = null) },
                    tag = "climateControl",
                ) { graph.model.send(vehicle.vin, WatchControl.CLIMATE, it) }
            }
            // Charging only for a car that's plugged in, as on iOS.
            if (ev != null && (ev.pluggedIn || ev.charging)) {
                item {
                    ControlButton(
                        command = commands[vehicle.vin to WatchControl.CHARGE],
                        action = if (ev.charging) RemoteAction.STOP_CHARGE else RemoteAction.START_CHARGE,
                        stateText = if (ev.charging) "Charging at ${"%.0f".format(ev.chargeSpeed)} kW" else "Plugged in",
                        icon = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
                        tag = "chargeControl",
                    ) { graph.model.send(vehicle.vin, WatchControl.CHARGE, it) }
                }
            }
        }
    }
}

@Composable
private fun Header(vehicle: BBVehicle, unit: Distance.Units, refreshing: Boolean, onRefresh: () -> Unit, onSettings: () -> Unit) {
    val ev = vehicle.evStatus
    val range = ev?.evRange ?: vehicle.gasRange
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            vehicle.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("vehicleName"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                range?.let { "${it.percentage.toInt()}%" } ?: "--",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("watchPercent"),
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ev != null) Icons.Rounded.Bolt else Icons.Rounded.LocalGasStation,
                        contentDescription = null,
                        tint = if (ev != null) Color(0xFF34C468) else Color(0xFFF08C2B),
                        modifier = Modifier.size(14.dp),
                    )
                    Text(range?.let { it.range.units.format(it.range.length, unit) } ?: "--", style = MaterialTheme.typography.bodySmall)
                }
                vehicle.lastUpdated?.let {
                    Text(updatedText(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.size(IconButtonDefaults.SmallButtonSize).testTag("refreshButton")) {
                if (refreshing) CircularProgressIndicator(Modifier.size(18.dp)) else Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(IconButtonDefaults.SmallButtonSize).testTag("settingsButton")) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings")
            }
        }
    }
}

/** "Updated 9:41 AM", as iOS's watch shows the reading's time. */
fun updatedText(date: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    "Updated " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(date.atZone(zone))

@Composable
private fun CarImage(vehicle: BBVehicle, remoteImages: Boolean) {
    Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
        if (remoteImages && !LocalInspectionMode.current) {
            AsyncImage(
                model = VehicleImageURLs.url(vehicle.model, vehicle.vehicleImageColor),
                contentDescription = vehicle.model,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().aspectRatio(VehicleImageURLs.ASPECT_RATIO),
            )
        } else {
            Icon(Icons.Rounded.DirectionsCar, contentDescription = vehicle.model, modifier = Modifier.size(40.dp))
        }
    }
}

/**
 * A control showing what the car reports; tapping sends [action], and
 * while it waits the button shows progress and stops waiting on a tap, as
 * iOS's watch buttons do.
 */
@Composable
private fun ControlButton(
    command: WatchCommand?,
    action: RemoteAction,
    stateText: String,
    icon: @Composable () -> Unit,
    tag: String,
    onSend: (RemoteAction) -> Unit,
) {
    val running = command?.running == true
    val label = if (running) "Stop" else action.title
    FilledTonalButton(
        onClick = { onSend(command?.action ?: action) },
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = "$label, $stateText" },
        icon = { if (running) Icon(Icons.Rounded.Stop, contentDescription = null) else icon() },
        secondaryLabel = { Text(command?.text ?: stateText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        label = { Text(label) },
        colors = if (running) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
    )
}
