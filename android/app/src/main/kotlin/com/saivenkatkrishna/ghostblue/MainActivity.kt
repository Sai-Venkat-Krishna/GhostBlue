package com.saivenkatkrishna.ghostblue

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
import com.saivenkatkrishna.ghostblue.ui.GhostBlueApp

class MainActivity : ComponentActivity() {
    /** A backup file opened from another app, waiting for the import sheet. */
    private val incomingBackup = MutableStateFlow<Uri?>(null)

    /** A car a widget, tile or notification opened the app on; Home shows it. */
    private val openVehicle = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        val graph = (application as GhostBlueApplication).graph
        setContent {
            GhostBlueApp(graph, incomingBackup, openVehicle = openVehicle)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_VEHICLE) {
            intent.getStringExtra(EXTRA_VIN)?.let { vin ->
                (application as GhostBlueApplication).graph.garage.selectVehicle(vin)
                openVehicle.value = vin
            }
            return
        }
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND) {
            val uri = intent.data ?: @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            if (uri != null) incomingBackup.value = uri
        }
    }

    companion object {
        const val ACTION_OPEN_VEHICLE = "com.saivenkatkrishna.ghostblue.OPEN_VEHICLE"
        const val EXTRA_VIN = "vin"
    }
}
