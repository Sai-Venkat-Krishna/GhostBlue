package com.saivenkatkrishna.ghostblue.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset
import com.saivenkatkrishna.ghostblue.data.model.VehicleImageColor
import com.saivenkatkrishna.ghostblue.data.vehicle.effectiveBatteryCapacityKWh
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.ui.components.cardToggleColors
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.components.VehicleImage
import com.saivenkatkrishna.ghostblue.ui.components.VehicleImages
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** [value] in [from] as a temperature in [to]. */
fun convertTemperature(value: Double, from: Temperature.Units, to: Temperature.Units): Double = when {
    from == to -> value
    from == Temperature.Units.CELSIUS -> value * 9 / 5 + 32
    else -> (value - 32) * 5 / 9
}

/**
 * Climate presets for one car, as iOS's climate settings: a tab per
 * preset, each saving as it changes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClimateSettingsSheet(vehicle: BBVehicle, onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current
    val garage = graph.garage
    val presets by remember(vehicle.id) { garage.presets(vehicle.id) }.collectAsStateWithLifecycle(emptyList())
    val accounts by garage.accounts.collectAsStateWithLifecycle()
    val region = accounts?.firstOrNull { it.id == vehicle.ownerAccountId }?.regionEnum
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(presets.size) { if (tab >= presets.size) tab = (presets.size - 1).coerceAtLeast(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Climate settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (presets.isNotEmpty()) {
                PrimaryScrollableTabRow(selectedTabIndex = tab.coerceIn(0, presets.size), edgePadding = 16.dp) {
                    presets.forEachIndexed { index, preset ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(preset.name) },
                            icon = { Icon(presetIcon(preset.iconName), contentDescription = null) },
                        )
                    }
                    Tab(
                        selected = false,
                        onClick = {
                            garage.addPreset(vehicle.id)
                            tab = presets.size
                        },
                        icon = { Icon(Icons.Rounded.Add, contentDescription = "Add preset") },
                    )
                }
                presets.getOrNull(tab)?.let { preset ->
                    PresetEditor(
                        preset = preset,
                        canDelete = presets.size > 1,
                        showsSelect = presets.size > 1,
                        showDuration = vehicle.showClimateDuration(region),
                        showSeats = vehicle.generation >= 3 || vehicle.enableSeatHeatControls,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PresetEditor(preset: ClimatePreset, canDelete: Boolean, showsSelect: Boolean, showDuration: Boolean, showSeats: Boolean) {
    val graph = LocalAppGraph.current
    val garage = graph.garage
    val unit by graph.settings.temperatureUnit.collectAsStateWithLifecycle()
    val options = preset.climateOptions
    fun save(change: (ClimateOptions) -> ClimateOptions) {
        preset.climateOptions = change(preset.climateOptions)
        garage.updatePreset(preset)
    }
    var renaming by remember { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(preset.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, contentDescription = "Rename preset") }
            if (showsSelect) {
                if (preset.isSelected) {
                    Text("Selected", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                } else {
                    FilledTonalButton(onClick = { garage.selectPreset(preset) }, shapes = ButtonDefaults.shapes()) { Text("Use preset") }
                }
            }
        }
        // Icons, as iOS's icon menu.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ClimatePreset.availableIcons.forEach { (name, label) ->
                IconToggleButton(
                    checked = preset.iconName == name,
                    onCheckedChange = {
                        preset.iconName = name
                        garage.updatePreset(preset)
                    },
                ) { Icon(presetIcon(name), contentDescription = label) }
            }
        }

        // Temperature, shown in the owner's unit.
        val range = unit.hvacRange
        val shown = convertTemperature(options.temperature.value, options.temperature.units, unit).coerceIn(range)
        var dragging by remember(preset.id) { mutableFloatStateOf(shown.toFloat()) }
        LaunchedEffect(shown) { dragging = shown.toFloat() }
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Temperature", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "${dragging.roundToInt()}${unit.symbol}",
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    modifier = Modifier.testTag("presetTemperature"),
                )
            }
            Slider(
                value = dragging,
                onValueChange = { dragging = it.roundToInt().toFloat() },
                onValueChangeFinished = { save { it.copy(temperature = Temperature(dragging.toDouble(), unit)) } },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                steps = (range.endInclusive - range.start).toInt() - 1,
            )
        }

        if (showDuration) {
            Text("Duration", style = MaterialTheme.typography.titleSmall)
            val durations = listOf(5, 10, 20)
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                durations.forEachIndexed { index, minutes ->
                    ToggleButton(
                        colors = cardToggleColors(),
                        checked = options.duration == minutes,
                        onCheckedChange = { save { it.copy(duration = minutes) } },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            durations.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("$minutes min") }
                }
            }
        }

        SwitchRow("Front defrost", options.defrost) { value -> save { it.copy(defrost = value) } }
        SwitchRow("Rear defrost", options.rearDefrostEnabled) { value -> save { it.copy(rearDefrost = value) } }
        SwitchRow("Heated steering wheel", options.steeringWheel > 0) { value -> save { it.copy(steeringWheel = if (value) 1 else 0) } }

        if (showSeats) {
            HorizontalDivider()
            Text("Seats", style = MaterialTheme.typography.titleSmall)
            SeatControl("Driver", options.frontLeftSeat, options.frontLeftVentilationEnabled) { level, cool ->
                save { it.copy(frontLeftSeat = level, frontLeftVentilation = cool) }
            }
            SeatControl("Passenger", options.frontRightSeat, options.frontRightVentilationEnabled) { level, cool ->
                save { it.copy(frontRightSeat = level, frontRightVentilation = cool) }
            }
            SeatControl("Rear left", options.rearLeftSeat, options.rearLeftVentilationEnabled) { level, cool ->
                save { it.copy(rearLeftSeat = level, rearLeftVentilation = cool) }
            }
            SeatControl("Rear right", options.rearRightSeat, options.rearRightVentilationEnabled) { level, cool ->
                save { it.copy(rearRightSeat = level, rearRightVentilation = cool) }
            }
        }

        if (canDelete) {
            OutlinedButton(
                onClick = { garage.deletePreset(preset) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete preset")
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(preset.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename preset") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(
                    onClick = {
                        name.trim().takeIf { it.isNotEmpty() }?.let {
                            preset.name = it
                            garage.updatePreset(preset)
                        }
                        renaming = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}

/** Seat heat or ventilation, off and three levels, as iOS's `SeatHeatControl`. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SeatControl(name: String, level: Int, cooling: Boolean, onChange: (Int, Boolean) -> Unit) {
    val levels = listOf("Off", "Low", "Med", "High")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                ToggleButton(
                    colors = cardToggleColors(),
                    checked = !cooling,
                    onCheckedChange = { onChange(level, false) },
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                ) { Text("Heat") }
                ToggleButton(
                    colors = cardToggleColors(),
                    checked = cooling,
                    onCheckedChange = { onChange(level, true) },
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                ) { Text("Cool") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            levels.forEachIndexed { index, label ->
                ToggleButton(
                    colors = cardToggleColors(),
                    checked = level == index,
                    onCheckedChange = { onChange(index, cooling) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        levels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(label) }
            }
        }
    }
}

/** AC and DC charge limits, 50–100% in steps of ten, as iOS's charge limit sheet. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChargeLimitSheet(vehicle: BBVehicle, onDismiss: () -> Unit) {
    val garage = LocalAppGraph.current.garage
    val ev = vehicle.evStatus
    var ac by remember { mutableFloatStateOf((ev?.targetSocAC ?: 80.0).toFloat().coerceIn(50f, 100f)) }
    var dc by remember { mutableFloatStateOf((ev?.targetSocDC ?: 80.0).toFloat().coerceIn(50f, 100f)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Charge limits", style = MaterialTheme.typography.titleLarge)
            Text(
                "The car stops charging at these levels. Home and public AC use the AC limit; fast chargers the DC limit.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LimitSlider("AC charging", ac) { ac = it }
            LimitSlider("DC fast charging", dc) { dc = it }
            Button(
                onClick = {
                    garage.setChargeLimits(vehicle.vin, ac.roundToInt(), dc.roundToInt())
                    onDismiss()
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().testTag("saveChargeLimits"),
            ) { Text("Save charge limits") }
        }
    }
}

@Composable
private fun LimitSlider(title: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${value.roundToInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = { onChange((it / 10).roundToInt() * 10f) }, valueRange = 50f..100f, steps = 4)
    }
}

/** The car's own settings from Home: name, paint colour and battery, as iOS's Vehicle Center. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleSettingsSheet(vehicle: BBVehicle, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            VehicleSettingsContent(vehicle)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun VehicleSettingsContent(vehicle: BBVehicle) {
    val garage = LocalAppGraph.current.garage
    val scope = rememberCoroutineScope()
    var name by remember(vehicle.vin) { mutableStateOf(vehicle.customName.orEmpty()) }
    var capacity by remember(vehicle.vin) { mutableStateOf(vehicle.batteryCapacityKWh?.let(Format::upToOneDecimal).orEmpty()) }
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Vehicle", style = MaterialTheme.typography.titleLarge)
        VehicleImage(vehicle.model, vehicle.vehicleImageColor, Modifier.fillMaxWidth())
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                garage.editVehicle(vehicle.vin) { row -> row.customName = it.trim().ifEmpty { null } }
            },
            label = { Text("Name") },
            placeholder = { Text(vehicle.model) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Paint colour", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VehicleImageColor.availableColors(vehicle.model).forEach { color ->
                val selected = vehicle.vehicleImageColor == color
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(MaterialShapes.Cookie9Sided.toShape())
                            .border(
                                if (selected) 3.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                MaterialShapes.Cookie9Sided.toShape(),
                            )
                            .clickable { garage.editVehicle(vehicle.vin) { it.vehicleImageColor = color } }
                            .then(Modifier.padding(4.dp))
                            .clip(MaterialShapes.Cookie9Sided.toShape())
                            .then(Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.Canvas(Modifier.size(36.dp)) { drawRect(VehicleImages.swatch(color)) }
                        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(color.displayName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        OutlinedTextField(
            value = capacity,
            onValueChange = { text ->
                capacity = text
                val value = Format.parseDecimal(text)
                garage.editVehicle(vehicle.vin) { it.batteryCapacityKWh = value?.takeIf { v -> v > 0 } }
            },
            label = { Text("Usable battery capacity (kWh)") },
            supportingText = {
                val default = vehicle.effectiveBatteryCapacityKWh
                Text(
                    if (vehicle.batteryCapacityKWh == null && default != null) "Using the ${Format.upToOneDecimal(default)} kWh model default."
                    else "Used to turn charge added into energy.",
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(
            "Seat heat controls",
            vehicle.enableSeatHeatControls || vehicle.generation >= 3,
            subtitle = if (vehicle.generation >= 3) "Always on for this car" else "Show seat controls in climate presets",
            enabled = vehicle.generation < 3,
        ) { value -> garage.editVehicle(vehicle.vin) { it.enableSeatHeatControls = value } }
        val location = vehicle.location
        Text("Home location", style = MaterialTheme.typography.titleSmall)
        Text(
            if (vehicle.homeLatitude != null) {
                String.format(java.util.Locale.US, "Saved · %.4f, %.4f · %d m radius", vehicle.homeLatitude, vehicle.homeLongitude, vehicle.homePrivacyRadiusMeters.toInt())
            } else {
                "Not set. Smart charging's home-only mode and home pricing use it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        garage.editVehicle(vehicle.vin) {
                            it.homeLatitude = location?.latitude
                            it.homeLongitude = location?.longitude
                        }
                    }
                },
                enabled = location != null,
                shapes = ButtonDefaults.shapes(),
            ) { Text("Use car's location") }
            if (vehicle.homeLatitude != null) {
                TextButton(onClick = {
                    garage.editVehicle(vehicle.vin) {
                        it.homeLatitude = null
                        it.homeLongitude = null
                    }
                }) { Text("Clear") }
            }
        }
        SwitchRow("Hide from garage", vehicle.isHidden, subtitle = "Show it again from Settings › Accounts") { value ->
            garage.editVehicle(vehicle.vin) { it.isHidden = value }
        }
        Text("VIN ${vehicle.vin}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
