package com.saivenkatkrishna.ghostblue.settings

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app's look. Material 3 Expressive is Android's own; Rivian is the iOS app's alternate theme. */
enum class AppTheme(val rawValue: String, val title: String) {
    MATERIAL("material", "Material You"),
    RIVIAN("rivian", "Rivian");

    companion object {
        fun fromRawValue(raw: String?): AppTheme = entries.firstOrNull { it.rawValue == raw } ?: MATERIAL
    }
}

/** How often the open app refreshes the selected car, as iOS's `AppRefreshInterval` (minutes). */
enum class AppRefreshInterval(val minutes: Int, val displayName: String) {
    ONE_MINUTE(1, "1 minute"),
    TWO_MINUTES(2, "2 minutes"),
    FIVE_MINUTES(5, "5 minutes"),
    TEN_MINUTES(10, "10 minutes");

    val seconds: Double get() = minutes * 60.0

    companion object {
        fun fromMinutes(minutes: Int): AppRefreshInterval = entries.firstOrNull { it.minutes == minutes } ?: TWO_MINUTES
    }
}

/**
 * The owner's preferences: the port of iOS's `AppSettings`, with the same
 * keys and defaults where the setting exists on both. Each one is a
 * [StateFlow] so screens follow changes.
 */
class AppSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

    private val _distanceUnit = MutableStateFlow(
        Distance.Units.entries.firstOrNull { it.rawValue == prefs.getString(DISTANCE_UNIT, null) }
            ?: Distance.Units.MILES,
    )
    val distanceUnit: StateFlow<Distance.Units> = _distanceUnit.asStateFlow()

    private val _temperatureUnit = MutableStateFlow(
        Temperature.Units.entries.firstOrNull { it.rawValue == prefs.getString(TEMPERATURE_UNIT, null) }
            ?: Temperature.Units.FAHRENHEIT,
    )
    val temperatureUnit: StateFlow<Temperature.Units> = _temperatureUnit.asStateFlow()

    private val _theme = MutableStateFlow(AppTheme.fromRawValue(prefs.getString(APP_THEME, null)))
    val theme: StateFlow<AppTheme> = _theme.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(DYNAMIC_COLOR, true))

    /** Wallpaper colours for the Material theme (Android 12+). */
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _refreshInterval = MutableStateFlow(AppRefreshInterval.fromMinutes(prefs.getInt(APP_REFRESH_INTERVAL, 0)))
    val refreshInterval: StateFlow<AppRefreshInterval> = _refreshInterval.asStateFlow()

    // Debug builds log requests by default, as on iOS.
    private val _debugMode = MutableStateFlow(prefs.getBoolean(DEBUG_MODE, com.saivenkatkrishna.ghostblue.BuildConfig.DEBUG))

    /** Stores API requests for Settings › HTTP Logs. */
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    private val _showShortTrips = MutableStateFlow(prefs.getString(SHOW_SHORT_TRIPS, null) == "true")
    val showShortTrips: StateFlow<Boolean> = _showShortTrips.asStateFlow()

    private val _hideVehicleLocation = MutableStateFlow(prefs.getBoolean(HIDE_VEHICLE_LOCATION, false))
    val hideVehicleLocation: StateFlow<Boolean> = _hideVehicleLocation.asStateFlow()

    private val _redactPrivateLocations = MutableStateFlow(prefs.getBoolean(REDACT_PRIVATE_LOCATIONS, true))

    /** Trip exports write "Private location" for named places. */
    val redactPrivateLocations: StateFlow<Boolean> = _redactPrivateLocations.asStateFlow()

    private val _tripsDetailedMode = MutableStateFlow(prefs.getBoolean(TRIPS_DETAILED_MODE, false))
    val tripsDetailedMode: StateFlow<Boolean> = _tripsDetailedMode.asStateFlow()

    private val _lastViewedTripsDate = MutableStateFlow(
        Instant.ofEpochSecond(prefs.getLong(LAST_VIEWED_TRIPS_DATE, 0)),
    )

    /** Trips saved after this get a badge on the Trips tab. */
    val lastViewedTripsDate: StateFlow<Instant> = _lastViewedTripsDate.asStateFlow()

    private val _selectedVin = MutableStateFlow(prefs.getString(SELECTED_VIN, null))

    /** The car Home, Location, Energy and Trips show. */
    val selectedVin: StateFlow<String?> = _selectedVin.asStateFlow()

    fun setDistanceUnit(value: Distance.Units) {
        _distanceUnit.value = value
        prefs.edit().putString(DISTANCE_UNIT, value.rawValue).apply()
    }

    fun setTemperatureUnit(value: Temperature.Units) {
        _temperatureUnit.value = value
        prefs.edit().putString(TEMPERATURE_UNIT, value.rawValue).apply()
    }

    fun setTheme(value: AppTheme) {
        _theme.value = value
        prefs.edit().putString(APP_THEME, value.rawValue).apply()
    }

    fun setDynamicColor(value: Boolean) {
        _dynamicColor.value = value
        prefs.edit().putBoolean(DYNAMIC_COLOR, value).apply()
    }

    fun setRefreshInterval(value: AppRefreshInterval) {
        _refreshInterval.value = value
        prefs.edit().putInt(APP_REFRESH_INTERVAL, value.minutes).apply()
    }

    fun setDebugMode(value: Boolean) {
        _debugMode.value = value
        prefs.edit().putBoolean(DEBUG_MODE, value).apply()
    }

    fun setShowShortTrips(value: Boolean) {
        _showShortTrips.value = value
        prefs.edit().putString(SHOW_SHORT_TRIPS, if (value) "true" else "false").apply()
    }

    fun setHideVehicleLocation(value: Boolean) {
        _hideVehicleLocation.value = value
        prefs.edit().putBoolean(HIDE_VEHICLE_LOCATION, value).apply()
    }

    fun setRedactPrivateLocations(value: Boolean) {
        _redactPrivateLocations.value = value
        prefs.edit().putBoolean(REDACT_PRIVATE_LOCATIONS, value).apply()
    }

    fun setTripsDetailedMode(value: Boolean) {
        _tripsDetailedMode.value = value
        prefs.edit().putBoolean(TRIPS_DETAILED_MODE, value).apply()
    }

    fun setLastViewedTripsDate(value: Instant) {
        _lastViewedTripsDate.value = value
        prefs.edit().putLong(LAST_VIEWED_TRIPS_DATE, value.epochSecond).apply()
    }

    fun setSelectedVin(value: String?) {
        _selectedVin.value = value
        prefs.edit().putString(SELECTED_VIN, value).apply()
    }

    companion object {
        const val FILE_NAME = "ghostblue_settings"

        // iOS's UserDefaults keys.
        const val DISTANCE_UNIT = "DistanceUnit"
        const val TEMPERATURE_UNIT = "TemperatureUnit"
        const val APP_THEME = "AppTheme"
        const val APP_REFRESH_INTERVAL = "AppRefreshInterval"
        const val DEBUG_MODE = "DebugModeEnabled"
        const val SHOW_SHORT_TRIPS = "ShowShortTrips"
        const val HIDE_VEHICLE_LOCATION = "HideVehicleLocation"
        const val REDACT_PRIVATE_LOCATIONS = "RedactPrivateLocations"
        const val TRIPS_DETAILED_MODE = "TripsDetailedMode"
        const val LAST_VIEWED_TRIPS_DATE = "LastViewedTripsDate"

        // Android's own.
        const val DYNAMIC_COLOR = "DynamicColor"
        const val SELECTED_VIN = "SelectedVehicleVIN"
    }
}
