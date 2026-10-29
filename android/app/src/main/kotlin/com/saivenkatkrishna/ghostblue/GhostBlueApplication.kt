package com.saivenkatkrishna.ghostblue

import android.app.Application
import android.content.Context
import android.util.Log
import com.saivenkatkrishna.ghostblue.data.account.AccountControllers
import com.saivenkatkrishna.ghostblue.data.account.HTTPLogSinkManager
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.DeviceType
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogLevel
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogSink
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.settings.AppSettings
import com.saivenkatkrishna.ghostblue.ui.GarageModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * What every screen shares for the life of the process: the database, the
 * owner's settings, one [AccountControllers] (so a client and its token are
 * shared) and the [GarageModel] the screens observe and act through.
 */
class AppGraph(
    val db: GhostBlueDatabase,
    val settings: AppSettings,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    deviceType: DeviceType = DeviceType.PHONE,
    /** Seconds the command checks wait between reads; tests shorten it. */
    commandWaitSeconds: Int = 10,
) {
    val logSinks = HTTPLogSinkManager(db, scope, deviceType, isEnabled = { settings.debugMode.value })
    val controllers = AccountControllers(db, scope, logSinks, sourceName = deviceType.displayName)
    val garage = GarageModel(db, settings, controllers, scope, commandWaitSeconds)

    companion object {
        fun create(context: Context): AppGraph {
            val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
            return AppGraph(
                db = GhostBlueDatabase.get(context),
                settings = AppSettings(context),
                deviceType = if (isTablet) DeviceType.TABLET else DeviceType.PHONE,
            )
        }
    }
}

class GhostBlueApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        BBLogger.sink = LogcatSink
        graph = AppGraph.create(this)
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
