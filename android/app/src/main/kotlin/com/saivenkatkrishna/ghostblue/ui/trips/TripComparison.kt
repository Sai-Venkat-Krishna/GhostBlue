package com.saivenkatkrishna.ghostblue.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.saivenkatkrishna.ghostblue.data.model.TripRecord
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.theme.LocalGhostBlueColors
import kotlin.math.abs

/** Which side a comparison row favours. */
enum class Winner { A, B, NONE }

data class ComparisonRow(val label: String, val a: String, val b: String, val winner: Winner)

/** iOS's `TripComparisonView` metrics: distance, efficiency, energy, duration, speed and cost. */
fun comparisonRows(a: EVTripDetail, b: EVTripDetail, unit: Distance.Units, ratePerKWh: Double?): List<ComparisonRow> {
    val effA = efficiencyIn(unit, TripAnalytics.efficiencyMilesPerKWh(a) ?: 0.0)
    val effB = efficiencyIn(unit, TripAnalytics.efficiencyMilesPerKWh(b) ?: 0.0)
    val energyA = TripAnalytics.energyKWh(a)
    val energyB = TripAnalytics.energyKWh(b)
    val factor = if (unit == Distance.Units.KILOMETERS) KM_PER_MILE else 1.0
    val speedUnit = if (unit == Distance.Units.KILOMETERS) "km/h" else "mph"
    val lowerEnergy = if (energyA <= energyB) Winner.A else Winner.B
    return buildList {
        add(ComparisonRow("Distance", Format.miles(a.distance, unit), Format.miles(b.distance, unit), Winner.NONE))
        add(
            ComparisonRow(
                "Efficiency",
                "${Format.oneDecimal(effA)} ${efficiencyUnit(unit)}",
                "${Format.oneDecimal(effB)} ${efficiencyUnit(unit)}",
                if (effA >= effB) Winner.A else Winner.B,
            ),
        )
        add(ComparisonRow("Energy used", Format.kWh(energyA), Format.kWh(energyB), lowerEnergy))
        add(ComparisonRow("Duration", a.formattedDuration, b.formattedDuration, Winner.NONE))
        add(
            ComparisonRow(
                "Average speed",
                "${Format.fraction(a.avgSpeed * factor, 0)} $speedUnit",
                "${Format.fraction(b.avgSpeed * factor, 0)} $speedUnit",
                Winner.NONE,
            ),
        )
        if (ratePerKWh != null && ratePerKWh > 0) {
            add(ComparisonRow("Estimated cost", Format.currency(energyA * ratePerKWh), Format.currency(energyB * ratePerKWh), lowerEnergy))
        }
    }
}

fun comparisonSummary(a: EVTripDetail, b: EVTripDetail, unit: Distance.Units): String {
    val effA = efficiencyIn(unit, TripAnalytics.efficiencyMilesPerKWh(a) ?: 0.0)
    val effB = efficiencyIn(unit, TripAnalytics.efficiencyMilesPerKWh(b) ?: 0.0)
    val delta = effA - effB
    if (abs(delta) < 0.05) return "These trips have nearly identical efficiency"
    return "Trip ${if (delta > 0) "A" else "B"} was ${Format.oneDecimal(abs(delta))} ${efficiencyUnit(unit)} more efficient"
}

@Composable
fun TripComparisonSheet(
    a: EVTripDetail,
    b: EVTripDetail,
    recordA: TripRecord?,
    recordB: TripRecord?,
    unit: Distance.Units,
    ratePerKWh: Double?,
    onDismiss: () -> Unit,
) {
    val colors = LocalGhostBlueColors.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).testTag("comparisonSheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Compare Trips", style = MaterialTheme.typography.titleLarge)
            Row {
                Heading("A", recordA?.customName ?: Format.pattern(a.startDate, "MMM d, h:mm a"), Format.pattern(a.startDate, "MMM d"), Modifier.weight(1f))
                Heading("B", recordB?.customName ?: Format.pattern(b.startDate, "MMM d, h:mm a"), Format.pattern(b.startDate, "MMM d"), Modifier.weight(1f))
            }
            Text(comparisonSummary(a, b, unit), style = MaterialTheme.typography.titleMedium, color = colors.energy)
            HorizontalDivider()
            comparisonRows(a, b, unit, ratePerKWh).forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Value(row.a, row.winner == Winner.A, Modifier.weight(1f), TextAlign.Start)
                    Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Value(row.b, row.winner == Winner.B, Modifier.weight(1f), TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun Heading(letter: String, name: String, date: String, modifier: Modifier) {
    Column(modifier) {
        Text(letter, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Value(text: String, wins: Boolean, modifier: Modifier, align: TextAlign) {
    val colors = LocalGhostBlueColors.current
    Row(modifier, horizontalArrangement = if (align == TextAlign.End) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        if (wins && align == TextAlign.End) Icon(Icons.Rounded.Check, null, tint = colors.energy, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = if (wins) FontWeight.Bold else FontWeight.Normal, color = if (wins) colors.energy else MaterialTheme.colorScheme.onSurface)
        if (wins && align == TextAlign.Start) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Check, null, tint = colors.energy, modifier = Modifier.size(16.dp))
        }
    }
}
