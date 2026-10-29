package com.saivenkatkrishna.ghostblue.widgets

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.saivenkatkrishna.ghostblue.MainActivity
import com.saivenkatkrishna.ghostblue.background.CommandWorker
import com.saivenkatkrishna.ghostblue.background.LiveRefreshWorker
import com.saivenkatkrishna.ghostblue.background.RefreshWorker
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.data.charging.chargingDisplayState
import com.saivenkatkrishna.ghostblue.data.charging.vehicleSampleDate
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.components.VehicleImages
import com.saivenkatkrishna.ghostblue.ui.home.chargeTarget
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which car each widget shows. Like iOS's widget configuration, a widget
 * can pin a car; otherwise it follows the car selected in the app, and
 * then the first one.
 */
object WidgetVehicles {
    private const val PREFS = "widget_vehicles"

    private val changes = MutableStateFlow(0)

    /** Moves when a widget's car or accent changes, so a widget already on screen redraws with it. */
    val configChanges: StateFlow<Int> = changes.asStateFlow()

    fun pin(context: Context, appWidgetId: Int, vin: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { if (vin == null) remove("$appWidgetId") else putString("$appWidgetId", vin) }
        changes.update { it + 1 }
    }

    fun pinned(context: Context, appWidgetId: Int): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("$appWidgetId", null)

    fun resolve(vehicles: List<BBVehicle>, pinned: String?, selected: String?): BBVehicle? {
        val visible = vehicles.filter { !it.isHidden }
        return visible.firstOrNull { it.vin == pinned } ?: visible.firstOrNull { it.vin == selected } ?: visible.firstOrNull()
    }

    fun setAccent(context: Context, appWidgetId: Int, accent: WidgetAccent) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("accent-$appWidgetId", accent.name) }
        changes.update { it + 1 }
    }

    fun accent(context: Context, appWidgetId: Int): WidgetAccent =
        WidgetAccent.fromName(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("accent-$appWidgetId", null))

}

/** The accent a Woven Wordmark widget is drawn in, as iOS's widget configuration offers it. */
enum class WidgetAccent(val title: String, val argb: Int) {
    MINT("Mint", 0xFF34E5C4.toInt()),
    VIOLET("Violet", 0xFF7A7FFF.toInt()),
    AMBER("Amber", 0xFFFFB347.toInt()),
    CORAL("Coral", 0xFFFF6B6B.toInt());

    companion object {
        fun fromName(name: String?): WidgetAccent = entries.firstOrNull { it.name == name } ?: MINT
    }
}

/** What the widgets show for a car: iOS's `VehicleEntity`. */
data class WidgetVehicle(
    val vin: String,
    val name: String,
    val model: String,
    val percent: Int?,
    val rangeText: String?,
    val isElectric: Boolean,
    val isLocked: Boolean?,
    val isClimateOn: Boolean,
    val isCharging: Boolean,
    val isPluggedIn: Boolean,
    val isChargeComplete: Boolean,
    val chargeSpeed: Double,
    val plugTypeLabel: String?,
    val target: Int?,
    val remaining: String?,
    val finish: String?,
    val odometer: String,
    val updated: String?,
    val updatedAt: Instant? = null,
    /** While plugged in and not charging: "Plugged in · not charging", "Scheduled pause"... */
    val chargingTitle: String = "",
    val remainingSeconds: Long = 0,
) {
    companion object {
        fun from(vehicle: BBVehicle, unit: Distance.Units, now: Instant = Instant.now()): WidgetVehicle {
            val ev = vehicle.evStatus
            val range = ev?.evRange ?: vehicle.gasRange
            val target = ev?.let(::chargeTarget)
            val sample = vehicle.vehicleSampleDate
            val finish = ev?.takeIf { it.charging && it.chargeTimeSeconds > 60 && sample != null }
                ?.let { sample!!.plusSeconds(it.chargeTimeSeconds) }
                ?.takeIf { it > now }
            return WidgetVehicle(
                vin = vehicle.vin,
                name = vehicle.displayName,
                model = vehicle.model,
                percent = range?.percentage?.toInt(),
                rangeText = range?.let { Format.distance(it.range, unit) },
                isElectric = ev != null,
                isLocked = when (vehicle.lockStatus) {
                    VehicleStatus.LockStatus.LOCKED -> true
                    VehicleStatus.LockStatus.UNLOCKED -> false
                    else -> null
                },
                isClimateOn = vehicle.climateStatus?.airControlOn == true,
                isCharging = ev?.charging == true,
                isPluggedIn = ev?.pluggedIn == true,
                isChargeComplete = ev != null && ev.pluggedIn && !ev.charging && target != null && ev.evRange.percentage >= target - 1,
                chargeSpeed = ev?.chargeSpeed ?: 0.0,
                plugTypeLabel = when (ev?.plugType) {
                    VehicleStatus.PlugType.AC_CHARGER -> "AC"
                    VehicleStatus.PlugType.DC_CHARGER -> "DC"
                    else -> null
                },
                target = target,
                remaining = ev?.chargeTimeSeconds?.takeIf { it > 0 }?.let { Format.duration(it.toDouble()) },
                finish = finish?.let { Format.finishTime(it, now) },
                odometer = Format.distance(vehicle.odometer, unit),
                updated = vehicle.lastUpdated?.let { Format.time(it) },
                updatedAt = vehicle.lastUpdated,
                chargingTitle = vehicle.chargingDisplayState(now).title,
                remainingSeconds = ev?.chargeTimeSeconds ?: 0,
            )
        }
    }
}

/** iOS's battery colours: blue above 50%, orange above 20%, red below. */
fun batteryColor(percent: Int?): Color = when {
    percent == null -> Color(0xFF8E8E93)
    percent > 50 -> Color(0xFF3D7BFF)
    percent > 20 -> Color(0xFFF08C2B)
    else -> Color(0xFFFF5A5A)
}

fun fixedColor(color: Color): ColorProvider = ColorProvider(color)

/** The car render as a bitmap, from Coil's cache; widgets can't load images themselves. */
suspend fun loadVehicleBitmap(context: Context, vehicle: BBVehicle): Bitmap? {
    val loader = SingletonImageLoader.get(context)
    for (url in listOf(VehicleImages.url(vehicle.model, vehicle.vehicleImageColor), VehicleImages.url(vehicle.model, com.saivenkatkrishna.ghostblue.data.model.VehicleImageColor.WHITE))) {
        val result = runCatching {
            loader.execute(ImageRequest.Builder(context).data(url).allowHardware(false).size(600, 236).build())
        }.getOrNull()
        if (result is SuccessResult) return result.image.toBitmap()
    }
    return null
}

/** Opens the app on the car, as iOS's `ghostblue://vehicle/<VIN>`. */
fun openVehicleIntent(context: Context, vin: String?): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_VEHICLE)
        .putExtra(MainActivity.EXTRA_VIN, vin)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

val KeyVin = ActionParameters.Key<String>("vin")
val KeyAction = ActionParameters.Key<String>("action")

/** A widget button: runs the command as expedited work, with its progress in a notification. */
class CommandCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val vin = parameters[KeyVin] ?: return
        val action = RemoteAction.fromName(parameters[KeyAction]) ?: return
        CommandWorker.enqueue(context, vin, action)
    }
}

/** A widget's refresh button: a read from the car itself, then every widget redraws ([LiveRefreshWorker]). */
class RefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val vin = parameters[KeyVin]
        if (vin == null) RefreshWorker.refreshNow(context) else LiveRefreshWorker.enqueue(context, vin)
    }
}
