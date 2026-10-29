package com.saivenkatkrishna.ghostblue.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.ui.CommandKind
import com.saivenkatkrishna.ghostblue.ui.CommandState
import com.saivenkatkrishna.ghostblue.ui.Destination
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.components.AdaptiveColumns
import com.saivenkatkrishna.ghostblue.ui.components.ShortHeight
import com.saivenkatkrishna.ghostblue.ui.components.VehicleImage
import com.saivenkatkrishna.ghostblue.ui.theme.LocalGhostBlueColors

/**
 * The Rivian theme's Home, after iOS's `RivianDashboard`: the car on a
 * solid surface, round lock and climate commands in solid ink, and a grid
 * of colour-coded tiles that open the rest of the app.
 */
@Composable
fun RivianDashboard(vehicle: BBVehicle, isWide: Boolean, onOpenSheet: (HomeSheet) -> Unit, onNavigate: (Destination) -> Unit) {
    val garage = LocalAppGraph.current.garage
    val commands by garage.commands.collectAsStateWithLifecycle()
    val lock = commands[vehicle.vin to CommandKind.LOCK] ?: CommandState()
    val climate = commands[vehicle.vin to CommandKind.CLIMATE] ?: CommandState()
    val ev = vehicle.evStatus
    val statusLine = when {
        ev?.charging == true -> "Charging · ${ev.evRange.percentage.toInt()}%"
        ev != null -> "${ev.evRange.percentage.toInt()}% battery"
        vehicle.lockStatus == VehicleStatus.LockStatus.LOCKED -> "Locked"
        else -> "Unlocked"
    }
    val colors = LocalGhostBlueColors.current
    AdaptiveColumns(
        isWide = isWide,
        spacing = 16.dp,
        leading = {
            Text(statusLine, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BoxWithConstraints {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    val short = maxHeight < ShortHeight && maxHeight > 0.dp
                    Box(Modifier.padding(24.dp).height(if (isWide) 250.dp else if (short) 170.dp else 220.dp), contentAlignment = Alignment.Center) {
                        VehicleImage(vehicle.model, vehicle.vehicleImageColor, Modifier.fillMaxWidth())
                    }
                }
            }
            val running = lock.running || climate.running
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundCommand(
                    "Lock",
                    Icons.Rounded.Lock,
                    active = vehicle.lockStatus == VehicleStatus.LockStatus.LOCKED,
                    enabled = !running,
                    tag = "rivianLock",
                ) { garage.lock(vehicle.vin) }
                RoundCommand(
                    "Unlock",
                    Icons.Rounded.LockOpen,
                    active = vehicle.lockStatus == VehicleStatus.LockStatus.UNLOCKED,
                    enabled = !running,
                    tag = "rivianUnlock",
                ) { garage.unlock(vehicle.vin) }
                RoundCommand(
                    "Climate",
                    Icons.Rounded.AcUnit,
                    active = vehicle.climateStatus?.airControlOn == true,
                    enabled = !running,
                    tag = "rivianClimate",
                ) { garage.toggleClimate(vehicle) }
            }
            val state = listOf(lock, climate).firstOrNull { it.error != null || it.running }
            if (state != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.running) LoadingIndicator(Modifier.size(24.dp))
                    CommandLine(state, "")
                }
            }
        },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(
                    "Climate",
                    if (vehicle.climateStatus?.airControlOn == true) "On" else "Off",
                    Icons.Rounded.AcUnit,
                    colors.climate,
                    Modifier.weight(1f),
                ) { onOpenSheet(HomeSheet.CLIMATE) }
                Tile(
                    "Energy",
                    (ev?.evRange ?: vehicle.gasRange)?.let { "${it.percentage.toInt()}%" } ?: "--",
                    Icons.Rounded.Bolt,
                    colors.energy,
                    Modifier.weight(1f),
                ) { onNavigate(Destination.ENERGY) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("Vehicle", "Details", Icons.Rounded.DirectionsCar, MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) {
                    onOpenSheet(HomeSheet.VEHICLE)
                }
                Tile("Location", "Map", Icons.Rounded.Map, colors.location, Modifier.weight(1f)) { onNavigate(Destination.LOCATION) }
            }
            if (ev != null) {
                Tile(
                    "Charging",
                    when {
                        ev.charging -> "Stop charging"
                        ev.pluggedIn -> "Start charging"
                        else -> "Unplugged"
                    },
                    Icons.Rounded.Bolt,
                    colors.energy,
                    Modifier.fillMaxWidth(),
                    enabled = ev.pluggedIn || ev.charging,
                ) { garage.toggleCharging(vehicle) }
            }
        },
    )
}

/** A 72 dp round command: solid ink when it matches the car's state. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RoundCommand(label: String, icon: ImageVector, active: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val fill = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(72.dp)
                .background(fill, CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Tile(
    title: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.heightIn(min = 124.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(36.dp).background(tint.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
