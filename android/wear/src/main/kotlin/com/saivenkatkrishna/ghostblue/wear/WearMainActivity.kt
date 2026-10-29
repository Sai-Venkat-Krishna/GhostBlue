package com.saivenkatkrishna.ghostblue.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.saivenkatkrishna.ghostblue.wear.ui.WearApp

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A tile or complication tap names the car to show first; a recreated activity keeps the page the owner chose.
        if (savedInstanceState == null) showVehicleFrom(intent)
        setContent { WearApp(wearGraph) }
    }

    /** A tap while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showVehicleFrom(intent)
    }

    private fun showVehicleFrom(intent: Intent?) {
        intent?.getStringExtra(EXTRA_VIN)?.let { wearGraph.settings.setSelectedVin(it) }
    }

    companion object {
        const val EXTRA_VIN = "vin"
    }
}
