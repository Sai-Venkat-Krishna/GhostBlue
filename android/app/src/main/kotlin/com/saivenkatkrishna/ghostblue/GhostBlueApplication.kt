package com.saivenkatkrishna.ghostblue

import android.app.Application
import android.content.Context
import android.util.Log
import com.saivenkatkrishna.ghostblue.data.account.AccountAlerts
import com.saivenkatkrishna.ghostblue.data.account.AccountControllers
import com.saivenkatkrishna.ghostblue.data.account.HTTPLogSinkManager
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.DeviceType
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogLevel
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogSink
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.settings.AppSettings
import androidx.glance.appwidget.updateAll
import com.saivenkatkrishna.ghostblue.background.RefreshWorker
import com.saivenkatkrishna.ghostblue.background.ReminderScheduler
import com.saivenkatkrishna.ghostblue.background.SmartChargingScheduler
import com.saivenkatkrishna.ghostblue.data.vehicle.VehicleAlerts
import com.saivenkatkrishna.ghostblue.data.vehicle.VehicleStatusEvents
import com.saivenkatkrishna.ghostblue.notifications.ChargingProgress
import com.saivenkatkrishna.ghostblue.notifications.Notifier
import com.saivenkatkrishna.ghostblue.ui.GarageModel
import com.saivenkatkrishna.ghostblue.watch.WatchHandoff
import com.saivenkatkrishna.ghostblue.widgets.ChargeDialWidget
import com.saivenkatkrishna.ghostblue.widgets.VehicleTiles
import com.saivenkatkrishna.ghostblue.widgets.VehicleWidget
import com.saivenkatkrishna.ghostblue.widgets.WovenWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What every screen, widget, tile and worker shares for the life of the
 * process: the database, the owner's settings, one [AccountControllers] (so
 * a client and its token are shared), the [GarageModel] the screens act
 * through and the [Notifier].
 */
class AppGraph(
    private val context: Context,
    val db: GhostBlueDatabase,
    val settings: AppSettings,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    deviceType: DeviceType = DeviceType.PHONE,
    /** Seconds the command checks wait between reads; tests shorten it. */
    val commandWaitSeconds: Int = 10,
) {
    val logSinks = HTTPLogSinkManager(db, scope, deviceType, isEnabled = { settings.debugMode.value })
    val controllers = AccountControllers(db, scope, logSinks, sourceName = deviceType.displayName)
    val garage = GarageModel(db, settings, controllers, scope, commandWaitSeconds)
    val notifier = Notifier(context, settings)
    val chargingProgress = ChargingProgress(notifier, settings, db)

    /** Redraws the widgets and asks the tiles to re-read the car. */
    suspend fun refreshWidgets() {
        runCatching {
            VehicleWidget().updateAll(context)
            ChargeDialWidget().updateAll(context)
            WovenWidget().updateAll(context)
        }.onFailure { BBLogger.warning(BBLogCategory.APP, "Widget update failed: $it") }
        VehicleTiles.requestUpdate(context)
    }

    /** Can anything outside the app show this car? A change reschedules what depends on it. */
    suspend fun rescheduleBackgroundWork() {
        RefreshWorker.schedule(context)
        SmartChargingScheduler.schedule(context)
        ReminderScheduler.schedule(context)
    }

    /**
     * The process-wide work the app runs (not tests): alerts as notifications,
     * widgets and tiles following every status, and the scheduled refreshes,
     * smart-charging runs and reminders.
     */
    @OptIn(FlowPreview::class)
    fun startBackgroundWork() {
        notifier.createChannels()
        VehicleAlerts.sink = notifier
        AccountAlerts.sink = notifier
        WatchHandoff.start(context, db, scope)
        scope.launch {
            VehicleStatusEvents.statusUpdated.collect { vin -> chargingProgress.update(vin) }
        }
        scope.launch {
            VehicleStatusEvents.statusUpdated.debounce(500).collect { refreshWidgets() }
        }
        scope.launch {
            // What widgets and tiles show besides a status: which car, units, theme, the cars and their names and colours.
            combine(
                settings.selectedVin,
                settings.distanceUnit,
                settings.theme,
                db.vehicles().observeAll().map { list -> list.map { listOf(it.vin, it.isHidden, it.displayName, it.vehicleImageColor) } },
            ) { selected, unit, theme, cars -> listOf(selected, unit, theme, cars) }
                .distinctUntilChanged().drop(1).debounce(500).collect { refreshWidgets() }
        }
        scope.launch {
            // Settings and car changes that move a schedule: the owner's interval, smart charging, reminders.
            combine(
                combine(settings.widgetRefreshInterval, settings.liveProgress, settings.notificationsEnabled) { a, b, c -> Triple(a, b, c) },
                // Every status write re-emits the cars; only these fields move a schedule.
                db.vehicles().observeAll().map { list -> list.map { Triple(it.vin, it.smartChargingEnabled, it.evStatus?.pluggedIn) } }
                    .distinctUntilChanged(),
                db.automations().observeAll(),
                db.maintenanceReminders().observeAll(),
            ) { _, _, _, _ -> }.debounce(1_000).collect { rescheduleBackgroundWork() }
        }
    }

    companion object {
        fun create(context: Context): AppGraph {
            val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
            return AppGraph(
                context = context.applicationContext,
                db = GhostBlueDatabase.get(context),
                settings = AppSettings(context),
                deviceType = if (isTablet) DeviceType.TABLET else DeviceType.PHONE,
            )
        }
    }
}

open class GhostBlueApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        BBLogger.sink = LogcatSink
        graph = createGraph()
        startBackgroundWork()
    }

    /** Tests swap in an in-memory graph. */
    protected open fun createGraph(): AppGraph = AppGraph.create(this)

    protected open fun startBackgroundWork() = graph.startBackgroundWork()
}

/**
 * Sends the vehicle API's log lines to Logcat under "GhostBlue/<category>".
 * Release builds keep only warnings and errors: the rest names the account,
 * its cars and every URL requested, and ends up in bug reports.
 */
private object LogcatSink : BBLogSink {
    override fun log(level: BBLogLevel, category: BBLogCategory, message: String) {
        if (!BuildConfig.DEBUG && (level == BBLogLevel.DEBUG || level == BBLogLevel.INFO)) return
        val tag = "GhostBlue/${category.rawValue}"
        when (level) {
            BBLogLevel.DEBUG -> Log.d(tag, message)
            BBLogLevel.INFO -> Log.i(tag, message)
            BBLogLevel.WARNING -> Log.w(tag, message)
            BBLogLevel.ERROR -> Log.e(tag, message)
        }
    }
}
