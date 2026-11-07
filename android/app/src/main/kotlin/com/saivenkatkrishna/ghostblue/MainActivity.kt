package com.saivenkatkrishna.ghostblue

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import com.saivenkatkrishna.ghostblue.ui.GhostBlueApp

/** A `FragmentActivity`, which `BiometricPrompt` needs for the app lock. */
class MainActivity : FragmentActivity() {
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
        // With the app lock on, the recent-apps preview is blank, as iOS blurs the app switcher.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    graph.settings.requireAppLock.collect { setRecentsScreenshotEnabled(!it) }
                }
            }
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
