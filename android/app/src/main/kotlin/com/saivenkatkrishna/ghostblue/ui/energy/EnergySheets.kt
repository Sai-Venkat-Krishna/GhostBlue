package com.saivenkatkrishna.ghostblue.ui.energy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.data.charging.BatteryCapacityCatalog
import com.saivenkatkrishna.ghostblue.data.charging.SDGESmartChargingSchedule
import com.saivenkatkrishna.ghostblue.data.charging.SmartChargingOverride
import com.saivenkatkrishna.ghostblue.data.charging.activeChargingOverride
import com.saivenkatkrishna.ghostblue.data.charging.chargingDisplayState
import com.saivenkatkrishna.ghostblue.data.charging.smartChargingWarning
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ChargingLocationType
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.components.LabeledRow
import com.saivenkatkrishna.ghostblue.ui.home.SwitchRow
import com.saivenkatkrishna.ghostblue.ui.home.rememberNow
import com.saivenkatkrishna.ghostblue.ui.theme.LocalGhostBlueColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/** Adding or correcting a charging session, as iOS's session editor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionEditorSheet(vehicle: BBVehicle, session: ChargeSession?, onDismiss: () -> Unit) {
    val garage = LocalAppGraph.current.garage
    val scope = rememberCoroutineScope()
    var draft by remember(session?.id) {
        mutableStateOf(session?.let { SessionDraft.forSession(it, vehicle) } ?: SessionDraft.forNewSession(vehicle))
    }
    var saveError by remember { mutableStateOf<String?>(null) }
    val validation = draft.validationMessage
    val colors = LocalGhostBlueColors.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (session == null) "Add Charging Session" else "Edit Charging Session",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val target = session ?: draft.newSession(vehicle)
                                draft.applyTo(target, vehicle)
                                garage.saveSession(target, isNew = session == null)
                                onDismiss()
                            } catch (_: Exception) {
                                saveError = "Could not save this charging session. Please try again."
                            }
                        }
                    },
                    enabled = validation == null,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.testTag("saveSession"),
                ) { Text("Save") }
            }

            SectionTitle("Date and time")
            DateTimeField("Started", draft.start) { draft = draft.copy(start = it).withDefaultRate(vehicle) }
            if (session != null && draft.wasActive) {
                SwitchRow("Mark session complete", draft.completed) { draft = draft.copy(completed = it) }
            }
            if (draft.completed) {
                DateTimeField("Ended", draft.end) { draft = draft.copy(end = it) }
            } else {
                Text("Automatic tracking will continue this session.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionTitle("Charge")
            PercentStepper("Started at", draft.startPercentage) { draft = draft.copy(startPercentage = it) }
            PercentStepper("Ended at", draft.endPercentage) { draft = draft.copy(endPercentage = it) }
            DecimalField("Measured energy", draft.energyText, "kWh", "Optional", Modifier.testTag("energyField")) { draft = draft.copy(energyText = it) }
            DecimalField("Peak speed", draft.peakText, "kW", "Optional") { draft = draft.copy(peakText = it) }
            SwitchRow("DC fast charging", draft.isDCFast) { draft = draft.copy(isDCFast = it).withDefaultRate(vehicle) }
            Text(
                "Enter the charger’s kWh reading to override estimates. Leave it blank to estimate from the battery or recorded samples.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Location")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChargingLocationType.entries.forEach { type ->
                    FilterChip(
                        selected = draft.locationType == type,
                        onClick = { draft = draft.copy(locationType = type).withDefaultRate(vehicle) },
                        label = { Text(type.title) },
                    )
                }
            }

            SectionTitle("Cost")
            val usesTariff = draft.tariff(vehicle) != null
            if (draft.offersTariff(vehicle)) {
                SwitchRow("Split cost by time-of-use periods", draft.useTariff, enabled = draft.tariffApplies(vehicle)) {
                    draft = draft.copy(useTariff = it)
                }
                if (usesTariff) {
                    Text(
                        "Uses saved tariff rates across the session. Energy is allocated by elapsed time, so the bill remains an estimate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LabeledRow("Energy delivered", Format.kWh(draft.energyKWh(vehicle, session)))
            DecimalField("Rate per kWh", draft.rateText, Format.currencyCode(), "Not set", enabled = !usesTariff) { draft = draft.copy(rateText = it) }
            DecimalField("Actual total paid", draft.actualCostText, Format.currencyCode(), "Optional") { draft = draft.copy(actualCostText = it) }
            LabeledRow(
                if (draft.actualCostText.isNotBlank()) "Recorded cost" else "Estimated cost",
                draft.cost(vehicle, session)?.let { Format.currency(it) } ?: "--",
                valueColor = colors.energy,
            )
            OutlinedButton(onClick = { draft = draft.copy(actualCostText = "").withDefaultRate(vehicle) }, shapes = ButtonDefaults.shapes()) {
                Text("Restore default estimate")
            }
            Text(
                "Use the receipt total for DC chargers with session fees, idle fees, or station-specific pricing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            (validation ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun PercentStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label $value%", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            FilledTonalIconButton(onClick = { onChange((value - 1).coerceAtLeast(0)) }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Remove, contentDescription = "Decrease $label")
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = { onChange((value + 1).coerceAtMost(100)) }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Add, contentDescription = "Increase $label")
            }
        }
        Slider(value = value.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = 0f..100f)
    }
}

@Composable
fun DecimalField(
    label: String,
    value: String,
    suffix: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        suffix = { Text(suffix) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A date and a time, each picked with Material's pickers. */
@Composable
private fun DateTimeField(label: String, value: Instant, onChange: (Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val local = value.atZone(zone)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { pickingDate = true }) { Text(Format.pattern(value, "MMM d, yyyy")) }
        TextButton(onClick = { pickingTime = true }) { Text(Format.time(value)) }
    }
    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(date.atTime(local.toLocalTime()).atZone(zone).toInstant())
                    }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (pickingTime) {
        val state = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val date: LocalDate = local.toLocalDate()
                    onChange(date.atTime(LocalTime.of(state.hour, state.minute)).atZone(zone).toInstant())
                    pickingTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

/** Battery capacity and prices, as iOS's Charging Costs. Each edit saves at once; zero or less clears. */
@Composable
fun CostSettingsSheet(vehicle: BBVehicle, onDismiss: () -> Unit) {
    val garage = LocalAppGraph.current.garage
    fun text(value: Double?, digits: Int) = value?.let { Format.decimals(it, digits).replace(',', '.') } ?: ""
    var capacity by remember(vehicle.vin) { mutableStateOf(text(vehicle.batteryCapacityKWh, 1)) }
    var offPeak by remember(vehicle.vin) { mutableStateOf(text(vehicle.offPeakElectricityRatePerKWh, 3)) }
    var dcFast by remember(vehicle.vin) { mutableStateOf(text(vehicle.dcFastChargingRatePerKWh, 3)) }
    var other by remember(vehicle.vin) { mutableStateOf(text(vehicle.electricityRatePerKWh, 3)) }
    fun positive(text: String) = Format.parseDecimal(text)?.takeIf { it > 0 }
    val default = BatteryCapacityCatalog.defaultCapacityKWh(vehicle.model, vehicle.fuelType)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Charging Costs", style = MaterialTheme.typography.titleLarge)
            SectionTitle("Battery")
            DecimalField("Usable capacity", capacity, "kWh", default?.let { Format.upToOneDecimal(it) } ?: "Not set", Modifier.testTag("capacityField")) {
                capacity = it
                garage.editVehicle(vehicle.vin) { row -> row.batteryCapacityKWh = positive(it) }
            }
            if (positive(capacity) == null && default != null) {
                FilledTonalButton(
                    onClick = {
                        capacity = Format.upToOneDecimal(default)
                        garage.editVehicle(vehicle.vin) { it.batteryCapacityKWh = default }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Use ${Format.upToOneDecimal(default)} kWh (${vehicle.model} default)") }
            }
            Text(
                if (default != null) "Energy for each session is the charge added times this capacity. Leave it empty to use the model default."
                else "Energy for each session is the charge added times this capacity. Without it, energy comes from charger samples.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionTitle("Charging prices")
            DecimalField("Home super off-peak / kWh", offPeak, Format.currencyCode(), "Not set") {
                offPeak = it
                garage.editVehicle(vehicle.vin) { row -> row.offPeakElectricityRatePerKWh = positive(it) }
            }
            DecimalField("DC fast default / kWh", dcFast, Format.currencyCode(), "Not set") {
                dcFast = it
                garage.editVehicle(vehicle.vin) { row -> row.dcFastChargingRatePerKWh = positive(it) }
            }
            DecimalField("Other charging / kWh", other, Format.currencyCode(), "Not set", Modifier.testTag("otherRateField")) {
                other = it
                garage.editVehicle(vehicle.vin) { row -> row.electricityRatePerKWh = positive(it) }
            }
            Text(
                "New sessions copy the rate for where they happen; editing a price later doesn't change past sessions. " +
                    "Home charging during the super off-peak window uses that rate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** SDG&E smart charging, as iOS's schedule sheet: the schedule, overrides and a check now. */
@Composable
fun SmartChargingSheet(vehicle: BBVehicle, onDismiss: () -> Unit) {
    val garage = LocalAppGraph.current.garage
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val colors = LocalGhostBlueColors.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Smart Charging", style = MaterialTheme.typography.titleLarge)
            SwitchRow("SDG&E smart charging", vehicle.smartChargingEnabled) { enabled ->
                garage.editVehicle(vehicle.vin) {
                    it.smartChargingEnabled = enabled
                    if (!enabled) {
                        it.smartChargingOverrideRaw = null
                        it.smartChargingOverrideExpiresAt = null
                    }
                }
            }
            if (vehicle.smartChargingEnabled) {
                Text(vehicle.chargingDisplayState(now).title, style = MaterialTheme.typography.titleSmall)
                vehicle.smartChargingWarning(now)?.let { Text(it, color = colors.service, style = MaterialTheme.typography.bodySmall) }
                LabeledRow("Weekdays", "12–6 AM, 10 AM–2 PM")
                LabeledRow("Weekends & holidays", "12 AM–2 PM")
                Text("All schedule times use San Diego local time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Only control charging at home", vehicle.smartChargingHomeOnly) { value ->
                    garage.editVehicle(vehicle.vin) { it.smartChargingHomeOnly = value }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Charge target: ${vehicle.smartChargingTargetSOC}%", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    FilledTonalIconButton(
                        onClick = { garage.editVehicle(vehicle.vin) { it.smartChargingTargetSOC = (it.smartChargingTargetSOC - 10).coerceAtLeast(50) } },
                        shapes = IconButtonDefaults.shapes(),
                    ) { Icon(Icons.Rounded.Remove, contentDescription = "Lower target") }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = { garage.editVehicle(vehicle.vin) { it.smartChargingTargetSOC = (it.smartChargingTargetSOC + 10).coerceAtMost(100) } },
                        shapes = IconButtonDefaults.shapes(),
                    ) { Icon(Icons.Rounded.Add, contentDescription = "Raise target") }
                }
                if (vehicle.smartChargingHomeOnly && vehicle.homeLatitude == null) {
                    Text(
                        "Save a home location in Vehicle settings before running home-only charging.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.service,
                    )
                }
                val override = vehicle.activeChargingOverride(now)
                if (override != null) {
                    Text(
                        "${override.title} until ${vehicle.smartChargingOverrideExpiresAt?.let(SDGESmartChargingSchedule::formattedTime) ?: ""}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider()
                SDGESmartChargingSchedule.transitions(now, count = 3).forEach { transition ->
                    LabeledRow(transition.title, SDGESmartChargingSchedule.formattedTime(transition.date))
                }
                HorizontalDivider()
                FilledTonalButton(onClick = { garage.setSmartChargingOverride(vehicle.vin, SmartChargingOverride.CHARGE_NOW) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text("Charge now until next boundary")
                }
                FilledTonalButton(onClick = { garage.setSmartChargingOverride(vehicle.vin, SmartChargingOverride.SKIP_WINDOW) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text("Skip this window")
                }
                if (override != null) {
                    OutlinedButton(onClick = { garage.setSmartChargingOverride(vehicle.vin, null) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Text("Resume normal schedule")
                    }
                }
                Button(
                    onClick = {
                        checking = true
                        scope.launch {
                            result = garage.checkSmartCharging(vehicle.vin)
                            checking = false
                        }
                    },
                    enabled = !checking,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (checking) {
                        LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Checking vehicle…")
                    } else {
                        Text("Check charging now")
                    }
                }
                (result ?: vehicle.smartChargingLastResult)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                vehicle.smartChargingLastRunAt?.let {
                    Text("Last attempt ${Format.relative(it, now)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "GhostBlue pauses charging outside SDG&E's super off-peak hours and resumes it inside them, on top of the car's own " +
                    "12 AM–2 PM schedule. Background checks arrive in a later update; until then use Check charging now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
