package com.saivenkatkrishna.ghostblue.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.saivenkatkrishna.ghostblue.ui.energy.EnergyScreen
import com.saivenkatkrishna.ghostblue.ui.home.HomeScreen
import com.saivenkatkrishna.ghostblue.ui.location.LocationScreen
import com.saivenkatkrishna.ghostblue.ui.settings.SettingsTab
import com.saivenkatkrishna.ghostblue.ui.trips.TripsScreen
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.DirectionsCar),
    LOCATION("Location", Icons.Rounded.Map),
    ENERGY("Energy", Icons.Rounded.Bolt),
    TRIPS("Trips", Icons.Rounded.Route),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

/** Per-tab signal that its tab was tapped again: scroll to the top, or recenter the map. */
class Reselections {
    private val counts = Destination.entries.associateWith { mutableIntStateOf(0) }
    fun count(destination: Destination): Int = counts.getValue(destination).intValue
    fun bump(destination: Destination) {
        counts.getValue(destination).intValue++
    }
}

/**
 * The tabs, as iOS's `MainView`: a navigation bar on phones and a rail on
 * foldables and tablets, chosen by `NavigationSuiteScaffold` from the
 * window size. Settings is a tab, as Android apps do, rather than a sheet.
 */
@Composable
fun MainScaffold(incomingBackup: MutableStateFlow<Uri?>) {
    val graph = LocalAppGraph.current
    val garage = graph.garage
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    val reselections = remember { Reselections() }
    val snackbar = remember { SnackbarHostState() }
    val selected by garage.selectedVehicle.collectAsStateWithLifecycle()
    val lastViewedTrips by graph.settings.lastViewedTripsDate.collectAsStateWithLifecycle()
    val trips by remember(selected?.vin) { selected?.vin?.let(garage::trips) ?: emptyFlow() }
        .collectAsStateWithLifecycle(emptyList())
    val hasNewTrips = trips.any { it.savedDate > lastViewedTrips }
    val pendingBackup by incomingBackup.collectAsStateWithLifecycle()

    // The selected car refreshes on the timer while the app is in front.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { garage.runAutoRefresh() }
    }
    LaunchedEffect(Unit) {
        garage.messages.collect { message ->
            if (message == GarageModel.TRIP_DELETED_MESSAGE) {
                val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) garage.undoTripDelete()
            } else {
                snackbar.showSnackbar(message)
            }
        }
    }
    // Trips archived while the tab is open count as seen.
    LaunchedEffect(destination, trips.size) {
        if (destination == Destination.TRIPS) graph.settings.setLastViewedTripsDate(Instant.now())
    }
    // A backup opened from another app goes to Settings, which shows the import sheet.
    LaunchedEffect(pendingBackup) {
        if (pendingBackup != null) destination = Destination.SETTINGS
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Destination.entries.forEach { item ->
                item(
                    selected = destination == item,
                    onClick = {
                        if (destination == item) reselections.bump(item) else destination = item
                    },
                    icon = {
                        if (item == Destination.TRIPS && hasNewTrips && destination != Destination.TRIPS) {
                            BadgedBox(badge = { Badge() }) { Icon(item.icon, contentDescription = null) }
                        } else {
                            Icon(item.icon, contentDescription = null)
                        }
                    },
                    label = { Text(item.label) },
                    modifier = Modifier.testTag("tab_${item.name}"),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when (destination) {
                Destination.HOME -> HomeScreen(
                    reselection = reselections.count(Destination.HOME),
                    onNavigate = { destination = it },
                )
                Destination.LOCATION -> LocationScreen(reselection = reselections.count(Destination.LOCATION))
                Destination.ENERGY -> EnergyScreen(reselection = reselections.count(Destination.ENERGY))
                Destination.TRIPS -> TripsScreen(reselection = reselections.count(Destination.TRIPS))
                Destination.SETTINGS -> SettingsTab(incomingBackup)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
