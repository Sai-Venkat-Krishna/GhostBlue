package com.saivenkatkrishna.ghostblue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TestCarScreen(viewModel: TestCarViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("GhostBlue") },
                subtitle = { Text("Test car · Android preview") },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading && state.vehicles.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.size(64.dp))
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.vehicles, key = { it.vehicle.vin }) { card ->
                    VehicleCard(card, onCommand = { viewModel.send(card.vehicle, it) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VehicleCard(card: VehicleCardState, onCommand: (VehicleCommand) -> Unit) {
    val status = card.status
    val ev = status?.evStatus
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LockBadge(status?.lockStatus)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(card.vehicle.model, style = MaterialTheme.typography.titleLarge)
                    Text(
                        statusLine(status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.busy) LoadingIndicator(Modifier.size(36.dp))
            }

            val charge = ev?.evRange ?: status?.gasRange
            if (charge != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${charge.percentage.toInt()}%",
                        style = MaterialTheme.typography.displayMediumEmphasized,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${charge.range.length.toInt()} ${charge.range.units.abbreviation}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                val progress = (charge.percentage / 100).toFloat().coerceIn(0f, 1f)
                if (ev?.charging == true) {
                    LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }

            LockToggle(status?.lockStatus, enabled = !card.busy, onCommand = onCommand)

            if (ev != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val charging = ev.charging
                    Button(
                        onClick = { onCommand(if (charging) VehicleCommand.StopCharge else VehicleCommand.StartCharge) },
                        enabled = !card.busy && ev.pluggedIn,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(if (charging) "Stop charging" else "Start charging")
                    }
                    FilledTonalButton(
                        onClick = {
                            val climateOn = status.climateStatus.airControlOn
                            onCommand(
                                if (climateOn) VehicleCommand.StopClimate
                                else VehicleCommand.StartClimate(com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions()),
                            )
                        },
                        enabled = !card.busy,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(if (status.climateStatus.airControlOn) "Climate off" else "Climate on")
                    }
                }
            }
        }
    }
}

/** Lock and Unlock as a connected button group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LockToggle(lockStatus: VehicleStatus.LockStatus?, enabled: Boolean, onCommand: (VehicleCommand) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        ToggleButton(
            checked = lockStatus == VehicleStatus.LockStatus.LOCKED,
            onCheckedChange = { onCommand(VehicleCommand.Lock) },
            enabled = enabled,
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            modifier = Modifier.weight(1f),
        ) {
            Text("Lock")
        }
        ToggleButton(
            checked = lockStatus == VehicleStatus.LockStatus.UNLOCKED,
            onCheckedChange = { onCommand(VehicleCommand.Unlock) },
            enabled = enabled,
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            modifier = Modifier.weight(1f),
        ) {
            Text("Unlock")
        }
    }
}

/** A cookie-shaped badge (one of the expressive Material shapes) showing the lock state. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LockBadge(lockStatus: VehicleStatus.LockStatus?) {
    val locked = lockStatus == VehicleStatus.LockStatus.LOCKED
    Box(
        Modifier
            .size(48.dp)
            .clip(MaterialShapes.Cookie9Sided.toShape())
            .background(
                if (locked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
            )
            .semantics { contentDescription = if (locked) "Locked" else "Unlocked" },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (locked) "🔒" else "🔓")
    }
}

private fun statusLine(status: VehicleStatus?): String {
    status ?: return "Loading…"
    val ev = status.evStatus
    return when {
        ev?.charging == true -> "Charging · ${ev.chargeSpeed} kW"
        ev?.pluggedIn == true -> "Plugged in"
        else -> if (status.lockStatus == VehicleStatus.LockStatus.LOCKED) "Locked" else "Unlocked"
    }
}
