package com.saivenkatkrishna.ghostblue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.saivenkatkrishna.ghostblue.BuildConfig
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus

/**
 * Draws the map with the car on it. Swappable so tests (and builds without
 * a Google Maps key) draw a placeholder instead of the Maps SDK.
 */
interface MapContent {
    /** [recenter] changes when the map should move back to the car. */
    @Composable
    fun VehicleMap(location: VehicleStatus.Location, title: String, interactive: Boolean, recenter: Int, modifier: Modifier)
}

val LocalMapContent = compositionLocalOf<MapContent> { PlaceholderMapContent }

/** Google Maps, when the build has a key (MAPS_API_KEY in local.properties); otherwise the placeholder. */
object GoogleMapContent : MapContent {
    @Composable
    override fun VehicleMap(location: VehicleStatus.Location, title: String, interactive: Boolean, recenter: Int, modifier: Modifier) {
        if (!BuildConfig.HAS_MAPS_KEY) {
            PlaceholderMapContent.VehicleMap(location, title, interactive, recenter, modifier)
            return
        }
        val position = LatLng(location.latitude, location.longitude)
        val camera: CameraPositionState = rememberCameraPositionState {
            this.position = CameraPosition.fromLatLngZoom(position, if (interactive) 15f else 16f)
        }
        LaunchedEffect(position, recenter) {
            camera.animate(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(position, 15f), 800)
        }
        val markerState = remember(position) { MarkerState(position) }
        GoogleMap(
            modifier = modifier.testTag("vehicleMap"),
            cameraPositionState = camera,
            mapColorScheme = ComposeMapColorScheme.FOLLOW_SYSTEM,
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
                myLocationButtonEnabled = false,
                scrollGesturesEnabled = interactive,
                zoomGesturesEnabled = interactive,
                rotationGesturesEnabled = interactive,
                tiltGesturesEnabled = interactive,
            ),
        ) {
            Marker(state = markerState, title = title)
        }
    }
}

/** A stand-in map: the coordinates on a tinted panel. */
object PlaceholderMapContent : MapContent {
    @Composable
    override fun VehicleMap(location: VehicleStatus.Location, title: String, interactive: Boolean, recenter: Int, modifier: Modifier) {
        val colors = MaterialTheme.colorScheme
        Box(
            modifier
                .testTag("vehicleMap")
                .background(Brush.linearGradient(listOf(colors.secondaryContainer, colors.tertiaryContainer))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Rounded.DirectionsCar, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(40.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSecondaryContainer)
                Text(
                    String.format(java.util.Locale.US, "%.5f, %.5f", location.latitude, location.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                )
                if (interactive && !BuildConfig.HAS_MAPS_KEY) {
                    Text(
                        "Add MAPS_API_KEY to local.properties to show Google Maps.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSecondaryContainer,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun VehicleMapBox(location: VehicleStatus.Location, title: String, interactive: Boolean, recenter: Int = 0, modifier: Modifier = Modifier) {
    LocalMapContent.current.VehicleMap(location, title, interactive, recenter, modifier.then(Modifier.fillMaxSize()))
}
