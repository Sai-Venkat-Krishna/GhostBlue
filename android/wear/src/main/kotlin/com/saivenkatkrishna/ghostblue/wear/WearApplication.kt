package com.saivenkatkrishna.ghostblue.wear

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.saivenkatkrishna.ghostblue.data.account.AccountControllers
import com.saivenkatkrishna.ghostblue.data.account.HTTPLogSinkManager
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.DeviceType
import com.saivenkatkrishna.ghostblue.data.vehicle.VehicleStatusEvents
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogLevel
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogSink
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.wear.complication.VehicleComplicationService
import com.saivenkatkrishna.ghostblue.wear.handoff.AccountListenerService
import com.saivenkatkrishna.ghostblue.wear.tile.VehicleTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The watch's preferences: the distance unit and look, as iOS's watch settings, and the car shown first. */
class WearSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("ghostblue_wear_settings", Context.MODE_PRIVATE))

    private val _distanceUnit = MutableStateFlow(
        Distance.Units.entries.firstOrNull { it.rawValue == prefs.getString(DISTANCE_UNIT, null) } ?: Distance.Units.MILES,
    )
    val distanceUnit: StateFlow<Distance.Units> = _distanceUnit.asStateFlow()

    private val _rivian = MutableStateFlow(prefs.getString(APP_THEME, null) == "rivian")

    /** The Rivian look instead of the watch's dynamic colours. */
    val rivian: StateFlow<Boolean> = _rivian.asStateFlow()

    private val _selectedVin = MutableStateFlow(prefs.getString(SELECTED_VIN, null))

    /** The car the tile and complications show. */
    val selectedVin: StateFlow<String?> = _selectedVin.asStateFlow()

    fun setDistanceUnit(value: Distance.Units) {
        _distanceUnit.value = value
        prefs.edit { putString(DISTANCE_UNIT, value.rawValue) }
    }

    fun setRivian(value: Boolean) {
        _rivian.value = value
        prefs.edit { putString(APP_THEME, if (value) "rivian" else "material") }
    }

    fun setSelectedVin(value: String?) {
        _selectedVin.value = value
        prefs.edit { putString(SELECTED_VIN, value) }
    }

    companion object {
        // iOS's keys, as the phone app uses.
        const val DISTANCE_UNIT = "DistanceUnit"
        const val APP_THEME = "AppTheme"
        const val SELECTED_VIN = "SelectedVehicleVIN"
    }
}

/**
 * What the watch app, its tile and complications share: its own database
 * (the watch signs in and talks to the vehicle service itself, as iOS's
 * watch app does), the account controllers and the [WatchModel].
 */
class WearGraph(
    private val context: Context,
    val db: GhostBlueDatabase,
    val settings: WearSettings,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Seconds between checks after a command; tests shorten it. */
    val commandWaitSeconds: Int = 10,
) {
    val logSinks = HTTPLogSinkManager(db, scope, DeviceType.WATCH)
    val controllers = AccountControllers(db, scope, logSinks, sourceName = DeviceType.WATCH.displayName)
    val model = WatchModel(db, settings, controllers, scope, commandWaitSeconds)

    /** Redraws the tile and complications from the latest status. */
    fun refreshSurfaces() {
        runCatching { TileService.getUpdater(context).requestUpdate(VehicleTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, VehicleComplicationService::class.java))
                .requestUpdateAll()
        }
    }

    /** The tile and complications follow every status the watch applies, and the tile each command's progress. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch { VehicleStatusEvents.statusUpdated.debounce(500).collect { refreshSurfaces() } }
        scope.launch { model.commands.drop(1).debounce(500).collect { refreshSurfaces() } }
        scope.launch { AccountListenerService.syncFromPhone(context) }
    }

    companion object {
        fun create(context: Context) = WearGraph(context.applicationContext, GhostBlueDatabase.get(context), WearSettings(context))
    }
}

open class WearApplication : Application() {
    lateinit var graph: WearGraph
        private set

    override fun onCreate() {
        super.onCreate()
        BBLogger.sink = LogcatSink
        graph = createGraph()
        startGraph()
    }

    protected open fun createGraph(): WearGraph = WearGraph.create(this)

    protected open fun startGraph() = graph.start()
}

val Context.wearGraph: WearGraph get() = (applicationContext as WearApplication).graph

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
