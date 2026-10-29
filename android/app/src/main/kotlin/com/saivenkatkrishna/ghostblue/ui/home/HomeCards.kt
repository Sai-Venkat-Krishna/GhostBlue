package com.saivenkatkrishna.ghostblue.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Toys
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.data.charging.chargingDisplayState
import com.saivenkatkrishna.ghostblue.data.charging.vehicleSampleDate
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset
import com.saivenkatkrishna.ghostblue.kit.model.FuelType
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.ui.components.cardToggleColors
import com.saivenkatkrishna.ghostblue.ui.CommandKind
import com.saivenkatkrishna.ghostblue.ui.CommandState
import com.saivenkatkrishna.ghostblue.ui.Destination
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.VehicleMapBox
import com.saivenkatkrishna.ghostblue.ui.components.AdaptiveColumns
import com.saivenkatkrishna.ghostblue.ui.components.CardHeader
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.components.LabeledRow
import com.saivenkatkrishna.ghostblue.ui.components.LineChart
import com.saivenkatkrishna.ghostblue.ui.components.SectionCard
import com.saivenkatkrishna.ghostblue.ui.components.ShapedIcon
import com.saivenkatkrishna.ghostblue.ui.components.StatGrid
import com.saivenkatkrishna.ghostblue.ui.components.VehicleImage
import com.saivenkatkrishna.ghostblue.ui.theme.LocalGhostBlueColors
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow

/** The Material 3 Expressive Home: hero and controls, then what the car reports. */
@Composable
fun MaterialDashboard(vehicle: BBVehicle, isWide: Boolean, onOpenSheet: (HomeSheet) -> Unit, onNavigate: (Destination) -> Unit) {
    AdaptiveColumns(
        isWide = isWide,
        leading = {
            HeroCard(vehicle)
            ControlsCard(vehicle, onOpenSheet)
        },
        trailing = {
            BriefingCard(vehicle)
            if (vehicle.evStatus != null) ChargingCard(vehicle, onNavigate)
            StatusCard(vehicle)
            if (vehicle.location != null) LocationCard(vehicle, onNavigate)
        },
    )
}

/** Re-reads time-based text every minute ("5 min. ago", outdated states). */
@Composable
fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Instant.now()
        }
    }
    return now
}

@Composable
fun brandLine(vehicle: BBVehicle): String {
    val garage = LocalAppGraph.current.garage
    val accounts by garage.accounts.collectAsStateWithLifecycle()
    val brand = accounts?.firstOrNull { it.id == vehicle.ownerAccountId }?.brandEnum?.displayName
    return listOfNotNull(brand, vehicle.fuelType.title).joinToString(" · ")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroCard(vehicle: BBVehicle) {
    val graph = LocalAppGraph.current
    val distanceUnit by graph.settings.distanceUnit.collectAsStateWithLifecycle()
    val colors = LocalGhostBlueColors.current
    val ev = vehicle.evStatus
    SectionCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(vehicle.model, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(brandLine(vehicle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatePill(vehicle.state)
        }
        VehicleImage(vehicle.model, vehicle.vehicleImageColor, Modifier.fillMaxWidth().padding(vertical = 4.dp))
        val range = ev?.evRange ?: vehicle.gasRange
        if (range != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${range.percentage.toInt()}%",
                    style = MaterialTheme.typography.displayMediumEmphasized,
                    modifier = Modifier.testTag("batteryPercent"),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(bottom = 6.dp)) {
                    Text(Format.distance(range.range, distanceUnit), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (ev != null) "Estimated range" else "Fuel range",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (ev == null) Icon(Icons.Rounded.LocalGasStation, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val progress = (range.percentage / 100).toFloat().coerceIn(0f, 1f)
            val tint = if (range.percentage <= 20) colors.danger else colors.energy
            if (ev?.charging == true) {
                LinearWavyProgressIndicator(progress = { progress }, color = tint, modifier = Modifier.fillMaxWidth().testTag("chargingProgress"))
                val finish = chargeFinishDate(vehicle)
                Text(
                    buildString {
                        append("Charging at ${Format.oneDecimal(ev.chargeSpeed)} kW")
                        if (ev.chargeTimeSeconds > 0) append(" · ${Format.duration(ev.chargeTimeSeconds.toDouble())} left")
                        finish?.let { append(" · done ${Format.finishTime(it)}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.energy,
                )
            } else {
                LinearProgressIndicator(progress = { progress }, color = tint, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun StatePill(state: VehicleState) {
    val colors = LocalGhostBlueColors.current
    val (tint, icon) = when (state) {
        VehicleState.WARNING -> colors.danger to Icons.Rounded.Warning
        VehicleState.CHARGING -> colors.energy to Icons.Rounded.Bolt
        VehicleState.CLIMATE -> colors.climate to Icons.Rounded.AcUnit
        VehicleState.LOCKED -> colors.energy to Icons.Rounded.Lock
        VehicleState.IDLE -> colors.accent to Icons.Rounded.CheckCircle
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = tint.copy(alpha = 0.16f), contentColor = tint) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(state.title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The line under a control: the command's progress, its error, or [idle]. */
@Composable
fun CommandLine(state: CommandState, idle: String) {
    val colors = LocalGhostBlueColors.current
    Text(
        state.error ?: state.message ?: idle,
        style = MaterialTheme.typography.bodySmall,
        color = when {
            state.error != null -> colors.danger
            state.running -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ControlsCard(vehicle: BBVehicle, onOpenSheet: (HomeSheet) -> Unit) {
    val garage = LocalAppGraph.current.garage
    val commands by garage.commands.collectAsStateWithLifecycle()
    val lock = commands[vehicle.vin to CommandKind.LOCK] ?: CommandState()
    val climate = commands[vehicle.vin to CommandKind.CLIMATE] ?: CommandState()
    val charge = commands[vehicle.vin to CommandKind.CHARGE] ?: CommandState()
    val limits = commands[vehicle.vin to CommandKind.CHARGE_LIMITS] ?: CommandState()
    val presets by remember(vehicle.id) { garage.presets(vehicle.id) }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(vehicle.id) { garage.ensureDefaultPreset(vehicle.id) }

    SectionCard {
        CardHeader("Controls")
        // Lock and unlock: a connected button group; the checked one is what the car reports.
        ControlRow(
            icon = if (vehicle.lockStatus == VehicleStatus.LockStatus.UNLOCKED) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
            title = when (vehicle.lockStatus) {
                VehicleStatus.LockStatus.LOCKED -> "Locked"
                VehicleStatus.LockStatus.UNLOCKED -> "Unlocked"
                else -> "Lock status unknown"
            },
            running = lock.running,
        ) { CommandLine(lock, if (vehicle.lockStatus == VehicleStatus.LockStatus.LOCKED) "Tap Unlock to open" else "Tap Lock to secure") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            ToggleButton(
                colors = cardToggleColors(),
                checked = vehicle.lockStatus == VehicleStatus.LockStatus.LOCKED,
                onCheckedChange = { garage.lock(vehicle.vin) },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                modifier = Modifier.weight(1f).testTag("lockButton"),
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Lock")
            }
            ToggleButton(
                colors = cardToggleColors(),
                checked = vehicle.lockStatus == VehicleStatus.LockStatus.UNLOCKED,
                onCheckedChange = { garage.unlock(vehicle.vin) },
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.weight(1f).testTag("unlockButton"),
            ) {
                Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Unlock")
            }
        }

        // Climate: a split button, the menu starting any preset.
        val climateOn = vehicle.climateStatus?.airControlOn == true
        val graph = LocalAppGraph.current
        val temperatureUnit by graph.settings.temperatureUnit.collectAsStateWithLifecycle()
        val selectedPreset = presets.firstOrNull { it.isSelected } ?: presets.firstOrNull()
        ControlRow(
            icon = Icons.Rounded.AcUnit,
            title = if (climateOn) "Climate on" else "Climate off",
            running = climate.running,
            tint = LocalGhostBlueColors.current.climate,
        ) {
            val temperature = vehicle.climateStatus?.temperature?.takeIf(Format::isPlausible)
            CommandLine(
                climate,
                when {
                    climateOn && temperature != null -> "Running at ${Format.temperature(temperature, temperatureUnit)}"
                    climateOn -> "Running"
                    selectedPreset != null -> "Starts with ${selectedPreset.name}"
                    else -> "Tap to start"
                },
            )
        }
        var presetMenu by remember { mutableStateOf(false) }
        Box {
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.LeadingButton(
                        onClick = { garage.toggleClimate(vehicle) },
                        modifier = Modifier.testTag("climateButton"),
                    ) {
                        Icon(if (climateOn) Icons.Rounded.Air else Icons.Rounded.AcUnit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (climate.running) "Cancel" else if (climateOn) "Stop climate" else "Start climate")
                    }
                },
                trailingButton = {
                    SplitButtonDefaults.TrailingButton(
                        checked = presetMenu,
                        onCheckedChange = { presetMenu = it },
                        modifier = Modifier.semantics { contentDescription = "Climate presets" },
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                    }
                },
            )
            DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text("Start ${preset.name}") },
                        leadingIcon = { Icon(presetIcon(preset.iconName), contentDescription = null) },
                        onClick = {
                            presetMenu = false
                            garage.startClimate(vehicle.vin, preset)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Climate settings") },
                    leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                    onClick = {
                        presetMenu = false
                        onOpenSheet(HomeSheet.CLIMATE)
                    },
                )
            }
        }
        if (presets.size > 1) PresetChips(presets, onSelect = garage::selectPreset)

        // Charging.
        val ev = vehicle.evStatus
        if (ev != null) {
            ControlRow(
                icon = Icons.Rounded.EvStation,
                title = when {
                    ev.charging -> "Charging"
                    ev.pluggedIn -> "Ready to charge"
                    else -> "Unplugged"
                },
                running = charge.running || limits.running,
                tint = LocalGhostBlueColors.current.energy,
            ) {
                val state = if (limits.running || limits.error != null) limits else charge
                CommandLine(
                    state,
                    when {
                        ev.charging && ev.chargeSpeed > 0 -> "${Format.oneDecimal(ev.chargeSpeed)} kW"
                        ev.charging -> "Tap to stop"
                        ev.pluggedIn -> "Tap to start"
                        else -> "Not plugged in"
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { garage.toggleCharging(vehicle) },
                    enabled = ev.pluggedIn || ev.charging || charge.running,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).testTag("chargeButton"),
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (charge.running) "Cancel" else if (ev.charging) "Stop charging" else "Start charging")
                }
                FilledTonalIconButton(
                    onClick = { onOpenSheet(HomeSheet.CHARGE_LIMITS) },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.semantics { contentDescription = "Charge limits" },
                ) {
                    Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null)
                }
            }
        }
    }
}

/** The presets as connected toggles: tapping one makes it the one climate starts with. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PresetChips(presets: List<ClimatePreset>, onSelect: (ClimatePreset) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        presets.forEachIndexed { index, preset ->
            ToggleButton(
                colors = cardToggleColors(),
                checked = preset.isSelected,
                onCheckedChange = { onSelect(preset) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    presets.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Icon(presetIcon(preset.iconName), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(preset.name)
            }
        }
    }
}

/** iOS stores SF Symbol names, so presets move between the apps; these are their Material equivalents. */
fun presetIcon(name: String): ImageVector = when (name) {
    "thermometer" -> Icons.Rounded.Thermostat
    "snowflake" -> Icons.Rounded.AcUnit
    "sun.max" -> Icons.Rounded.WbSunny
    "wind" -> Icons.Rounded.Air
    "cloud.snow" -> Icons.Rounded.Cloud
    else -> Icons.Rounded.Toys
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ControlRow(
    icon: ImageVector,
    title: String,
    running: Boolean,
    tint: Color = MaterialTheme.colorScheme.primary,
    subtitle: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShapedIcon(icon, size = 40.dp, shape = MaterialShapes.Cookie6Sided.toShape(), container = tint.copy(alpha = 0.16f), content = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            subtitle()
        }
        if (running) LoadingIndicator(Modifier.size(32.dp))
    }
}

@Composable
private fun BriefingCard(vehicle: BBVehicle) {
    val graph = LocalAppGraph.current
    val distanceUnit by graph.settings.distanceUnit.collectAsStateWithLifecycle()
    val now = rememberNow()
    val items = briefingItems(vehicle, distanceUnit, now)
    val colors = LocalGhostBlueColors.current
    SectionCard(Modifier.testTag("briefingCard")) {
        CardHeader("Smart briefing")
        items.take(3).forEach { item ->
            val tint = when (item.severity) {
                Severity.GOOD -> colors.energy
                Severity.INFO -> MaterialTheme.colorScheme.primary
                Severity.WARNING -> colors.service
                Severity.CRITICAL -> colors.danger
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(MaterialShapes.Circle.toShape()).background(tint))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    if (item.detail.isNotEmpty()) {
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (items.size > 3) {
            Text("+${items.size - 3} more", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChargingCard(vehicle: BBVehicle, onNavigate: (Destination) -> Unit) {
    val garage = LocalAppGraph.current.garage
    val now = rememberNow()
    val details = chargingDetails(vehicle, now) ?: return
    val ev = vehicle.evStatus ?: return
    val sessions by remember(vehicle.vin) { garage.sessions(vehicle.vin) }.collectAsStateWithLifecycle(emptyList())
    val openSession = sessions.firstOrNull { it.isActive }
    val colors = LocalGhostBlueColors.current
    SectionCard(Modifier.clickable { onNavigate(Destination.ENERGY) }) {
        CardHeader(
            "Charging",
            subtitle = vehicle.chargingDisplayState(now).title +
                (vehicle.vehicleSampleDate?.let { " · reading ${Format.relative(it, now)}" } ?: ""),
        )
        if (ev.charging) {
            Text(
                "${ev.evRange.percentage.toInt()}% of ${details.target}% target",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.energy,
            )
            StatGrid(
                listOf(
                    "Remaining" to details.remaining,
                    "Speed" to details.speed,
                    "Energy left" to (details.energyLeftKWh?.let(Format::kWh) ?: "--"),
                    "Est. cost" to (details.estimatedCost?.let { Format.currency(it) } ?: "--"),
                ),
                columns = 2,
            )
            val samples = openSession?.speedSamples?.takeLast(24).orEmpty()
            if (samples.size >= 2) {
                LineChart(
                    values = samples.map { it.speedKW },
                    labels = samples.mapIndexed { index, sample -> if (index == 0 || index == samples.lastIndex) Format.time(sample.date) else null },
                    color = colors.energy,
                    unit = "kW",
                    height = 120.dp,
                    description = "Charging speed",
                )
            }
        } else {
            LabeledRow("Charge limit", ev.currentTargetSOC?.let { "${it.toInt()}%" } ?: "AC ${ev.targetSocAC?.toInt() ?: "--"}% · DC ${ev.targetSocDC?.toInt() ?: "--"}%")
        }
        details.warnings.forEach { warning ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = colors.service, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(warning, style = MaterialTheme.typography.bodySmall, color = colors.service)
            }
        }
    }
}

@Composable
private fun StatusCard(vehicle: BBVehicle) {
    val graph = LocalAppGraph.current
    val distanceUnit by graph.settings.distanceUnit.collectAsStateWithLifecycle()
    val temperatureUnit by graph.settings.temperatureUnit.collectAsStateWithLifecycle()
    val healthTitle = when {
        vehicle.hasOpenPanel || vehicle.tirePressureWarning?.hasWarning == true -> "Check vehicle"
        vehicle.evStatus?.charging == true -> "Charging"
        vehicle.lockStatus == VehicleStatus.LockStatus.LOCKED && vehicle.doorOpen?.anyOpen != true -> "All secure"
        else -> "Status ready"
    }
    val climate = vehicle.climateStatus
    val items = buildList {
        add("Odometer" to Format.distance(vehicle.odometer, distanceUnit))
        add("12V battery" to (vehicle.battery12V?.let { "$it%" } ?: "--"))
        add("Doors" to (vehicle.doorOpen?.let { if (it.anyOpen) it.openDoorsDescription else "Closed" } ?: "Unknown"))
        add("Hood & trunk" to hoodTrunkText(vehicle))
        add("Tires" to (vehicle.tirePressureWarning?.let { if (it.hasWarning) it.warningDescription else "OK" } ?: "OK"))
        add(
            "Climate" to when {
                climate == null || !climate.airControlOn -> "Off"
                Format.isPlausible(climate.temperature) -> Format.temperature(climate.temperature, temperatureUnit)
                else -> "On"
            },
        )
        if (vehicle.fuelType == FuelType.PHEV) add("Fuel" to (vehicle.gasRange?.let { "${it.percentage.toInt()}%" } ?: "--"))
    }
    SectionCard(Modifier.testTag("statusCard")) {
        CardHeader("Vehicle status", subtitle = healthTitle + (vehicle.lastUpdated?.let { " · ${Format.relative(it)}" } ?: " · Not updated"))
        StatGrid(items, columns = 2)
    }
}

@Composable
private fun LocationCard(vehicle: BBVehicle, onNavigate: (Destination) -> Unit) {
    val graph = LocalAppGraph.current
    val hidden by graph.settings.hideVehicleLocation.collectAsStateWithLifecycle()
    val location = vehicle.location ?: return
    SectionCard {
        CardHeader("Location", subtitle = vehicle.lastUpdated?.let { "Parked · ${Format.relative(it)}" } ?: "Parked") {
            FilledTonalButton(onClick = { onNavigate(Destination.LOCATION) }, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("View map")
            }
        }
        if (hidden) {
            Text("Exact location hidden", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Box(Modifier.fillMaxWidth().height(150.dp).clip(MaterialTheme.shapes.large)) {
                VehicleMapBox(location, vehicle.displayName, interactive = false)
            }
        }
    }
}
