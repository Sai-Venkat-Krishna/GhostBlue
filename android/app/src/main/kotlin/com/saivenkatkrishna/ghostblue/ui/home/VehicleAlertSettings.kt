package com.saivenkatkrishna.ghostblue.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomationKind
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The car's alert rules (iOS's Smart Alerts) and automations: which
 * changes notify, their thresholds, and the low-battery, unplugged and
 * daily precondition reminders.
 */
@Composable
fun VehicleAlertSettings(vehicle: BBVehicle) {
    val garage = LocalAppGraph.current.garage
    val settings = LocalAppGraph.current.settings
    val notificationsOn by settings.notificationsEnabled.collectAsStateWithLifecycle()
    val automations by androidx.compose.runtime.remember(vehicle.vin) { garage.automations(vehicle.vin) }.collectAsStateWithLifecycle(emptyList())
    fun edit(change: (BBVehicle) -> Unit) = garage.editVehicle(vehicle.vin, change)

    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Alerts", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
        if (!notificationsOn) {
            Text(
                "Turn on notifications in Settings to receive these.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SwitchRow("Charging complete", vehicle.notifyChargeComplete) { value -> edit { it.notifyChargeComplete = value } }
        SwitchRow("Charging interrupted", vehicle.notifyChargeInterrupted) { value -> edit { it.notifyChargeInterrupted = value } }
        SwitchRow("Unlocked", vehicle.notifyUnlocked) { value -> edit { it.notifyUnlocked = value } }
        SwitchRow("Door, hood or trunk open", vehicle.notifyOpenPanels) { value -> edit { it.notifyOpenPanels = value } }
        SwitchRow("Low EV battery", vehicle.notifyLowEVBattery) { value -> edit { it.notifyLowEVBattery = value } }
        if (vehicle.notifyLowEVBattery) {
            Stepper("Below ${vehicle.lowEVBatteryThreshold}%", vehicle.lowEVBatteryThreshold, 5..50, 5) { value -> edit { it.lowEVBatteryThreshold = value } }
        }
        SwitchRow("Low 12V battery", vehicle.notifyLow12VBattery) { value -> edit { it.notifyLow12VBattery = value } }
        if (vehicle.notifyLow12VBattery) {
            Stepper("Below ${vehicle.low12VBatteryThreshold}%", vehicle.low12VBatteryThreshold, 10..70, 5) { value -> edit { it.low12VBatteryThreshold = value } }
        }
        SwitchRow("Tire pressure warning", vehicle.notifyTirePressure) { value -> edit { it.notifyTirePressure = value } }

        Text("Automations", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
        val lowBattery = automations.firstOrNull { it.kind == VehicleAutomationKind.LOW_BATTERY }
        SwitchRow(
            "Low battery reminder",
            lowBattery?.isEnabled == true,
            subtitle = "Notify at or below ${lowBattery?.threshold ?: 20}%",
        ) { value -> garage.setAutomation(vehicle.vin, VehicleAutomationKind.LOW_BATTERY, value) }
        if (lowBattery?.isEnabled == true) {
            Stepper("At ${lowBattery.threshold}%", lowBattery.threshold, 5..80, 5) { value ->
                garage.setAutomation(vehicle.vin, VehicleAutomationKind.LOW_BATTERY, true, threshold = value)
            }
        }
        val unplugged = automations.firstOrNull { it.kind == VehicleAutomationKind.UNPLUGGED }
        SwitchRow("Unplugged reminder", unplugged?.isEnabled == true, subtitle = "Notify when the charger is disconnected") { value ->
            garage.setAutomation(vehicle.vin, VehicleAutomationKind.UNPLUGGED, value)
        }
        val precondition = automations.firstOrNull { it.kind == VehicleAutomationKind.PRECONDITION_REMINDER }
        val time = LocalTime.of((precondition?.hour ?: 7).coerceIn(0, 23), (precondition?.minute ?: 30).coerceIn(0, 59))
        var pickingTime by remember { mutableStateOf(false) }
        SwitchRow(
            "Precondition reminder",
            precondition?.isEnabled == true,
            subtitle = "Every day at ${time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}",
        ) { value -> garage.setAutomation(vehicle.vin, VehicleAutomationKind.PRECONDITION_REMINDER, value) }
        if (precondition?.isEnabled == true) {
            TextButton(onClick = { pickingTime = true }) { Text("Change time") }
        }
        if (pickingTime) {
            val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute)
            AlertDialog(
                onDismissRequest = { pickingTime = false },
                confirmButton = {
                    TextButton(onClick = {
                        garage.setAutomation(vehicle.vin, VehicleAutomationKind.PRECONDITION_REMINDER, true, hour = state.hour, minute = state.minute)
                        pickingTime = false
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
                text = { TimePicker(state) },
            )
        }
    }
}

/** A value with minus and plus buttons, in [step]s across [range]. */
@Composable
fun Stepper(label: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        FilledTonalIconButton(
            onClick = { onChange((value - step).coerceIn(range)) },
            enabled = value > range.first,
            shapes = IconButtonDefaults.shapes(),
        ) { Icon(Icons.Rounded.Remove, contentDescription = "Lower") }
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = { onChange((value + step).coerceIn(range)) },
            enabled = value < range.last,
            shapes = IconButtonDefaults.shapes(),
        ) { Icon(Icons.Rounded.Add, contentDescription = "Raise") }
    }
}
