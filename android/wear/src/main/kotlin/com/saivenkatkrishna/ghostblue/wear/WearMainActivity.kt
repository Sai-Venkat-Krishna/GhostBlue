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
        val vin = intent?.getStringExtra(EXTRA_VIN) ?: return
        wearGraph.settings.setSelectedVin(vin)
        // The tile's Unlock on a watch with no screen lock: ask here first.
        if (intent.getBooleanExtra(EXTRA_CONFIRM_UNLOCK, false)) wearGraph.unlockToConfirm.value = vin
    }

    companion object {
        const val EXTRA_VIN = "vin"
        const val EXTRA_CONFIRM_UNLOCK = "confirmUnlock"
    }
}
