package com.saivenkatkrishna.ghostblue.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.saivenkatkrishna.ghostblue.GhostBlueApplication
import com.saivenkatkrishna.ghostblue.ui.theme.GhostBlueTheme
import kotlinx.coroutines.launch

/**
 * Picks the car a widget shows, as iOS's widget configuration: a car, or
 * "Selected vehicle" to follow the app. Optional on Android 12+, so adding
 * a widget doesn't stop here unless the owner reconfigures it.
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
        setContent {
            val theme by graph.settings.theme.collectAsStateWithLifecycle()
            val vehicles by graph.garage.vehicles.collectAsStateWithLifecycle()
            GhostBlueTheme(theme = theme) {
                Scaffold(topBar = { TopAppBar(title = { Text("Choose a vehicle") }) }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        ListItem(
                            headlineContent = { Text("Selected vehicle") },
                            supportingContent = { Text("Follows the car selected in GhostBlue") },
                            leadingContent = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                            modifier = Modifier.clickable { choose(appWidgetId, null) },
                        )
                        vehicles.orEmpty().forEach { vehicle ->
                            ListItem(
                                headlineContent = { Text(vehicle.displayName) },
                                supportingContent = { Text(vehicle.model, style = MaterialTheme.typography.bodySmall) },
                                leadingContent = { Icon(Icons.Rounded.DirectionsCar, contentDescription = null) },
                                modifier = Modifier.clickable { choose(appWidgetId, vehicle.vin) },
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

    private fun choose(appWidgetId: Int, vin: String?) {
        WidgetVehicles.pin(this, appWidgetId, vin)
        lifecycleScope.launch {
            val manager = GlanceAppWidgetManager(this@WidgetConfigActivity)
            runCatching {
                val id = manager.getGlanceIdBy(appWidgetId)
                // Redraw with the widget's own kind: updating it as the other would draw the wrong one.
                val provider = AppWidgetManager.getInstance(this@WidgetConfigActivity).getAppWidgetInfo(appWidgetId)?.provider?.className
                if (provider == ChargeDialWidgetReceiver::class.java.name) {
                    ChargeDialWidget().update(this@WidgetConfigActivity, id)
                } else {
                    VehicleWidget().update(this@WidgetConfigActivity, id)
                }
            }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}
