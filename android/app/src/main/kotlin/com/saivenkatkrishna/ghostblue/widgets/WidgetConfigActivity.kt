package com.saivenkatkrishna.ghostblue.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.saivenkatkrishna.ghostblue.GhostBlueApplication
import com.saivenkatkrishna.ghostblue.ui.theme.GhostBlueTheme
import kotlinx.coroutines.launch

/**
 * Picks the car a widget shows, as iOS's widget configuration: a car, or
 * "Selected vehicle" to follow the app; for the Woven Wordmark, its accent
 * too. Optional on Android 12+, so adding a widget doesn't stop here unless
 * the owner reconfigures it.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out leaves the widget unconfigured, as Android expects.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val graph = (application as GhostBlueApplication).graph
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className
        val woven = provider == WovenWidgetReceiver::class.java.name
        setContent {
            val theme by graph.settings.theme.collectAsStateWithLifecycle()
            val vehicles by graph.garage.vehicles.collectAsStateWithLifecycle()
            var accent by remember { mutableStateOf(WidgetVehicles.accent(this, appWidgetId)) }
            GhostBlueTheme(theme = theme) {
                Scaffold(topBar = { TopAppBar(title = { Text("Choose a vehicle") }) }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        if (woven) {
                            Text("Accent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                WidgetAccent.entries.forEach { option ->
                                    FilterChip(
                                        selected = accent == option,
                                        onClick = { accent = option },
                                        label = { Text(option.title) },
                                        leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(option.argb))) },
                                        modifier = Modifier.testTag("accent_${option.name}"),
                                    )
                                }
                            }
                        }
                        ListItem(
                            headlineContent = { Text("Selected vehicle") },
                            supportingContent = { Text("Follows the car selected in GhostBlue") },
                            leadingContent = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                            modifier = Modifier.clickable { choose(appWidgetId, null, accent.takeIf { woven }) },
                        )
                        vehicles.orEmpty().forEach { vehicle ->
                            ListItem(
                                headlineContent = { Text(vehicle.displayName) },
                                supportingContent = { Text(vehicle.model, style = MaterialTheme.typography.bodySmall) },
                                leadingContent = { Icon(Icons.Rounded.DirectionsCar, contentDescription = null) },
                                modifier = Modifier.clickable { choose(appWidgetId, vehicle.vin, accent.takeIf { woven }) },
                            )
                        }
                        if (vehicles.isNullOrEmpty()) {
                            Text("Open GhostBlue to add an account.", modifier = Modifier.padding(24.dp))
                        }
                    }
                }
            }
        }
    }

    private fun choose(appWidgetId: Int, vin: String?, accent: WidgetAccent?) {
        WidgetVehicles.pin(this, appWidgetId, vin)
        accent?.let { WidgetVehicles.setAccent(this, appWidgetId, it) }
        lifecycleScope.launch {
            val manager = GlanceAppWidgetManager(this@WidgetConfigActivity)
            runCatching {
                val id = manager.getGlanceIdBy(appWidgetId)
                // Redraw with the widget's own kind: updating it as the other would draw the wrong one.
                val provider = AppWidgetManager.getInstance(this@WidgetConfigActivity).getAppWidgetInfo(appWidgetId)?.provider?.className
                when (provider) {
                    ChargeDialWidgetReceiver::class.java.name -> ChargeDialWidget().update(this@WidgetConfigActivity, id)
                    WovenWidgetReceiver::class.java.name -> WovenWidget().update(this@WidgetConfigActivity, id)
                    else -> VehicleWidget().update(this@WidgetConfigActivity, id)
                }
            }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}
