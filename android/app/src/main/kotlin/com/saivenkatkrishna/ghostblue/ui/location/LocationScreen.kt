package com.saivenkatkrishna.ghostblue.ui.location

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.VehicleMapBox
import com.saivenkatkrishna.ghostblue.ui.components.EmptyState
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.components.VehicleSwitcher
import com.saivenkatkrishna.ghostblue.ui.home.rememberNow

/** Map apps the Navigate menu offers, as iOS's (Google Maps first on Android). */
enum class NavigationApp(val title: String) {
    GOOGLE_MAPS("Google Maps"),
    WAZE("Waze"),
    OTHER("Another app");

    fun uri(location: VehicleStatus.Location, name: String): Uri = when (this) {
        GOOGLE_MAPS -> Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${location.latitude},${location.longitude}&travelmode=driving")
        WAZE -> Uri.parse("https://waze.com/ul?ll=${location.latitude},${location.longitude}&navigate=yes")
        OTHER -> Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}(${Uri.encode(name)})")
    }
}

/**
 * Location: the car on a full-screen map with a card for where it's parked
 * and the way there, as iOS's `MapTabView`. The owner's "hide exact
 * location" setting covers the map.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LocationScreen(reselection: Int) {
    val graph = LocalAppGraph.current
    val garage = graph.garage
    val vehicles by garage.vehicles.collectAsStateWithLifecycle()
    val selected by garage.selectedVehicle.collectAsStateWithLifecycle()
    val hidden by graph.settings.hideVehicleLocation.collectAsStateWithLifecycle()
    val vehicle = selected ?: return
    val location = vehicle.location
    var recenter by remember { mutableIntStateOf(0) }
    val now = rememberNow()

    Box(Modifier.fillMaxSize()) {
        when {
            hidden -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Rounded.LocationOff,
                    "Vehicle Location Hidden",
                    "Exact map content is protected by your privacy setting.",
                    action = {
                        FilledTonalButton(onClick = { graph.settings.setHideVehicleLocation(false) }, shapes = ButtonDefaults.shapes()) {
                            Text("Show location")
                        }
                    },
                )
            }
            location == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Rounded.LocationOff, "No location reported yet", "The car's position appears after its next status update.")
            }
            else -> VehicleMapBox(location, vehicle.displayName, interactive = true, recenter = recenter + reselection)
        }

        if (!hidden && location != null) {
            FilledTonalIconButton(
                onClick = { recenter++ },
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .testTag("recenterButton"),
            ) { Icon(Icons.Rounded.MyLocation, contentDescription = "Center on vehicle") }
        }

        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .testTag("locationCard"),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VehicleSwitcher(vehicles.orEmpty(), vehicle, onSelect = { garage.selectVehicle(it.vin) }, style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (location == null) Icons.Rounded.LocationOff else Icons.Rounded.LocalParking,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            location == null -> "No location reported yet"
                            vehicle.lastUpdated != null -> "Parked · ${Format.relative(vehicle.lastUpdated!!, now)}"
                            else -> "Parked"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (location != null && !hidden) NavigateButton(location, vehicle.displayName)
            }
        }
    }
}

@Composable
private fun NavigateButton(location: VehicleStatus.Location, name: String) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().testTag("navigateButton")) {
            Icon(Icons.Rounded.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Navigate", textAlign = TextAlign.Center)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NavigationApp.entries.forEach { app ->
                DropdownMenuItem(
                    text = { Text(app.title) },
                    onClick = {
                        expanded = false
                        val intent = Intent(Intent.ACTION_VIEW, app.uri(location, name))
                        runCatching { context.startActivity(intent) }.onFailure {
                            // Falls back to whatever handles a map location, as iOS falls back to Apple Maps.
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, NavigationApp.OTHER.uri(location, name))) }
                        }
                    },
                )
            }
        }
    }
}
