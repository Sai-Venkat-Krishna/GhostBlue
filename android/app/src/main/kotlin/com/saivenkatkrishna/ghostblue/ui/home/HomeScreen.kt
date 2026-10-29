package com.saivenkatkrishna.ghostblue.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.ui.Destination
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.components.EmptyState
import com.saivenkatkrishna.ghostblue.ui.components.ErrorCard
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.components.VehicleSwitcher
import com.saivenkatkrishna.ghostblue.ui.components.WidthAware
import com.saivenkatkrishna.ghostblue.ui.theme.LocalGhostBlueColors
import kotlinx.coroutines.delay

/** Which Home sheet is open. */
enum class HomeSheet { CLIMATE, CHARGE_LIMITS, VEHICLE }

/**
 * Home: the car, its remote controls and what it reports, as iOS's
 * `HomeView` (and `RivianDashboard` under the Rivian theme). Two columns
 * from 600 dp, as the iOS dashboard does on wide displays.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(reselection: Int, onNavigate: (Destination) -> Unit) {
    val graph = LocalAppGraph.current
    val garage = graph.garage
    val vehicles by garage.vehicles.collectAsStateWithLifecycle()
    val selected by garage.selectedVehicle.collectAsStateWithLifecycle()
    val loading by garage.garageLoading.collectAsStateWithLifecycle()
    val lastError by garage.lastError.collectAsStateWithLifecycle()
    val refreshing by garage.refreshing.collectAsStateWithLifecycle()

    val vehicle = selected
    if (vehicle == null) {
        EmptyGarage(loading = loading || vehicles == null, error = lastError, onRetry = { garage.start(); garage.dismissError() })
        return
    }

    LaunchedEffect(vehicle.vin) { garage.refreshIfStale(vehicle.vin) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scroll = rememberScrollState()
    LaunchedEffect(reselection) { if (reselection > 0) scroll.animateScrollTo(0) }
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    val isRefreshing = vehicle.vin in refreshing

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    VehicleSwitcher(
                        vehicles = vehicles.orEmpty(),
                        selected = vehicle,
                        onSelect = { garage.selectVehicle(it.vin) },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                subtitle = {
                    Text(vehicle.lastUpdated?.let { "Updated ${Format.relative(it)}" } ?: "Not updated yet")
                },
                actions = {
                    RefreshButton(isRefreshing) { garage.refresh(vehicle.vin, forced = true) }
                    HomeMenu(vehicle, onVehicleSettings = { sheet = HomeSheet.VEHICLE }, onSettings = { onNavigate(Destination.SETTINGS) })
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { garage.refresh(vehicle.vin, forced = true) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            WidthAware(Modifier.fillMaxSize()) { isWide ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 24.dp)
                        .testTag("homeContent"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    lastError?.let { error ->
                        ErrorCard(error, onRetry = { garage.dismissError(); garage.refresh(vehicle.vin, forced = true) })
                    }
                    if (LocalGhostBlueColors.current.isRivian) {
                        RivianDashboard(vehicle, isWide, onOpenSheet = { sheet = it }, onNavigate = onNavigate)
                    } else {
                        MaterialDashboard(vehicle, isWide, onOpenSheet = { sheet = it }, onNavigate = onNavigate)
                    }
                }
            }
        }
    }

    when (sheet) {
        HomeSheet.CLIMATE -> ClimateSettingsSheet(vehicle, onDismiss = { sheet = null })
        HomeSheet.CHARGE_LIMITS -> ChargeLimitSheet(vehicle, onDismiss = { sheet = null })
        HomeSheet.VEHICLE -> VehicleSettingsSheet(vehicle, onDismiss = { sheet = null })
        null -> {}
    }
}

/** Refreshes from the car; at most once a minute, as on iOS. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RefreshButton(isRefreshing: Boolean, onRefresh: () -> Unit) {
    var lastRefresh by remember { mutableLongStateOf(0L) }
    var cooldownHint by remember { mutableStateOf(false) }
    var succeeded by remember { mutableStateOf(false) }
    var wasRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) {
        if (wasRefreshing && !isRefreshing) {
            succeeded = true
            delay(2_000)
            succeeded = false
        }
        wasRefreshing = isRefreshing
    }
    LaunchedEffect(cooldownHint) {
        if (cooldownHint) {
            delay(2_000)
            cooldownHint = false
        }
    }
    IconButton(
        onClick = {
            val now = System.currentTimeMillis()
            if (now - lastRefresh < 60_000) {
                cooldownHint = true
            } else {
                lastRefresh = now
                onRefresh()
            }
        },
        modifier = Modifier.testTag("refreshButton"),
    ) {
        when {
            isRefreshing -> LoadingIndicator(Modifier.size(24.dp))
            cooldownHint -> Icon(Icons.Rounded.Schedule, contentDescription = "Refreshed less than a minute ago", tint = LocalGhostBlueColors.current.danger)
            succeeded -> Icon(Icons.Rounded.CheckCircle, contentDescription = "Refreshed", tint = LocalGhostBlueColors.current.energy)
            else -> Icon(Icons.Rounded.Refresh, contentDescription = "Refresh from vehicle")
        }
    }
}

@Composable
private fun HomeMenu(vehicle: BBVehicle, onVehicleSettings: () -> Unit, onSettings: () -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val hideLocation by graph.settings.hideVehicleLocation.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("homeMenu")) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Vehicle actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val location = vehicle.location
            if (location != null && !hideLocation) {
                DropdownMenuItem(
                    text = { Text("Navigate to vehicle") },
                    onClick = {
                        expanded = false
                        val uri = Uri.parse("google.navigation:q=${location.latitude},${location.longitude}")
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        runCatching { context.startActivity(intent) }.onFailure {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}")),
                            )
                        }
                    },
                )
            }
            DropdownMenuItem(text = { Text("Vehicle settings") }, onClick = { expanded = false; onVehicleSettings() })
            DropdownMenuItem(text = { Text("Settings") }, onClick = { expanded = false; onSettings() })
        }
    }
}

/** No cars yet: loading the garage, a failure to retry, or an empty account. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyGarage(loading: Boolean, error: com.saivenkatkrishna.ghostblue.ui.UserError?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                loading -> {
                    LoadingIndicator(Modifier.size(72.dp))
                    Text("Loading your garage…", style = MaterialTheme.typography.titleMedium)
                }
                error != null -> {
                    Text("Couldn't refresh", style = MaterialTheme.typography.titleLarge)
                    ErrorCard(error, onRetry = onRetry)
                }
                else -> EmptyState(
                    Icons.Rounded.DirectionsCar,
                    "No vehicles found",
                    "This account has no vehicles yet, or all of them are hidden in Settings.",
                    action = { FilledTonalButton(onClick = onRetry) { Text("Try Again") } },
                )
            }
        }
    }
}
