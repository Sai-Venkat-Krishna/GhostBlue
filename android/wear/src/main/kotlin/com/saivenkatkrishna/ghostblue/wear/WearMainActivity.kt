package com.saivenkatkrishna.ghostblue.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.saivenkatkrishna.ghostblue.wear.ui.WearApp

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A tile or complication tap names the car to show first.
        intent?.getStringExtra(EXTRA_VIN)?.let { wearGraph.settings.setSelectedVin(it) }
        setContent { WearApp(wearGraph) }
    }

    companion object {
        const val EXTRA_VIN = "vin"
    }
}
