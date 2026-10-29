package com.saivenkatkrishna.ghostblue

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogLevel
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogSink
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.ui.TestCarScreen
import com.saivenkatkrishna.ghostblue.ui.theme.GhostBlueTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BBLogger.sink = LogcatSink
        enableEdgeToEdge()
        setContent {
            GhostBlueTheme {
                TestCarScreen()
            }
        }
    }
}

/** Sends the vehicle API's log lines to Logcat under "GhostBlue/<category>". */
private object LogcatSink : BBLogSink {
    override fun log(level: BBLogLevel, category: BBLogCategory, message: String) {
        val tag = "GhostBlue/${category.rawValue}"
        when (level) {
            BBLogLevel.DEBUG -> Log.d(tag, message)
            BBLogLevel.INFO -> Log.i(tag, message)
            BBLogLevel.WARNING -> Log.w(tag, message)
            BBLogLevel.ERROR -> Log.e(tag, message)
        }
    }
}
