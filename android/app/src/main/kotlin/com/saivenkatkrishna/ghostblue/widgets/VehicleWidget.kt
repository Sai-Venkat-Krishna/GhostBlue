package com.saivenkatkrishna.ghostblue.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.FilledButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.saivenkatkrishna.ghostblue.GhostBlueApplication
import com.saivenkatkrishna.ghostblue.R
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.settings.AppTheme
import com.saivenkatkrishna.ghostblue.ui.theme.RivianDarkColors
import com.saivenkatkrishna.ghostblue.ui.theme.RivianLightColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Small = DpSize(110.dp, 110.dp)
private val Medium = DpSize(250.dp, 110.dp)
private val Large = DpSize(250.dp, 250.dp)

/** Everything a widget draws: the car, its image, the owner's theme and the widget's accent (the Woven Wordmark's). */
internal data class WidgetState(val vehicle: WidgetVehicle?, val image: Bitmap?, val rivian: Boolean, val accent: WidgetAccent = WidgetAccent.MINT)

/**
 * A widget's state as it changes: each status of the car, which car (pinned,
 * else the selected one), the units, the theme and the widget's own settings. Collected inside the
 * composition, so a widget whose session is still up (Glance keeps one for a
 * while after each update, and drops updates that arrive meanwhile) redraws
 * with every change instead of keeping what it opened with.
 */
internal fun widgetStates(context: Context, id: GlanceId, withImage: Boolean): Flow<WidgetState> {
    val graph = (context.applicationContext as GhostBlueApplication).graph
    return combine(
        graph.db.vehicles().observeAll(),
        graph.settings.selectedVin,
        graph.settings.distanceUnit,
        graph.settings.theme,
        combine(WidgetVehicles.configChanges, CommandFeedback.all) { _, feedback -> feedback },
    ) { vehicles, selected, unit, theme, feedback ->
        val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrNull()
        val vehicle: BBVehicle? = WidgetVehicles.resolve(vehicles, appWidgetId?.let { WidgetVehicles.pinned(context, it) }, selected)
        WidgetState(
            vehicle?.let { WidgetVehicle.from(it, unit).copy(commandText = CommandFeedback.latest(feedback, it.vin)?.text) },
            if (withImage && vehicle != null) loadVehicleBitmap(context, vehicle) else null,
            theme == AppTheme.RIVIAN,
            appWidgetId?.let { WidgetVehicles.accent(context, it) } ?: WidgetAccent.MINT,
        )
    }.distinctUntilChanged()
}

/** Material You colours, or the Rivian palette under the Rivian theme, as iOS's widgets switch layout. */
@Composable
private fun WidgetTheme(rivian: Boolean, content: @Composable () -> Unit) {
    if (rivian) GlanceTheme(colors = ColorProviders(light = RivianLightColors, dark = RivianDarkColors), content = content)
    else GlanceTheme(content = content)
}

/**
 * iOS's Command Center widget: the car, its charge and range, and lock and
 * climate buttons (from the medium size). Each size is laid out for its
 * space; tapping the car opens it in the app.
 */
class VehicleWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(Small, Medium, Large))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = widgetStates(context, id, withImage = true)
        val initial = states.first()
        provideContent {
            val state by states.collectAsState(initial)
            WidgetTheme(state.rivian) { CommandCenter(state) }
        }
    }
}

class VehicleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = VehicleWidget()
}

@Composable
private fun Frame(vin: String?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    Box(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(24.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .clickable(actionStartActivity(openVehicleIntent(context, vin)))
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun NoVehicle() {
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Text("No Vehicle", style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp))
        Text("Open GhostBlue to add an account", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
    }
}

private fun command(vin: String, action: RemoteAction): Action =
    actionRunCallback<CommandCallback>(actionParametersOf(KeyVin to vin, KeyAction to action.name))

private fun refresh(vin: String): Action = actionRunCallback<RefreshCallback>(actionParametersOf(KeyVin to vin))

@Composable
internal fun CommandCenter(state: WidgetState) {
    val vehicle = state.vehicle
    Frame(vehicle?.vin) {
        if (vehicle == null) {
            NoVehicle()
            return@Frame
        }
        val size = LocalSize.current
        when {
            size.height >= Large.height -> LargeLayout(vehicle, state.image)
            size.width >= Medium.width -> MediumLayout(vehicle, state.image)
            else -> SmallLayout(vehicle)
        }
    }
}

@Composable
private fun Title(text: String, size: Int = 14) {
    Text(text, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, fontSize = size.sp))
}

@Composable
private fun Caption(text: String) {
    Text(text, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
}

@Composable
private fun Percent(vehicle: WidgetVehicle, size: Int) {
    Text(vehicle.percent?.let { "$it%" } ?: "--", style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = size.sp))
}

@Composable
private fun BatteryBar(vehicle: WidgetVehicle) {
    LinearProgressIndicator(
        progress = (vehicle.percent ?: 0) / 100f,
        modifier = GlanceModifier.fillMaxWidth().height(6.dp),
        color = fixedColor(if (vehicle.isCharging) Color(0xFF34C468) else batteryColor(vehicle.percent)),
        backgroundColor = GlanceTheme.colors.surfaceVariant,
    )
}

@Composable
private fun RangeLine(vehicle: WidgetVehicle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            ImageProvider(if (vehicle.isElectric) R.drawable.ic_bolt else R.drawable.ic_notification),
            contentDescription = null,
            modifier = GlanceModifier.size(14.dp),
            colorFilter = androidx.glance.ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
        )
        Spacer(GlanceModifier.width(4.dp))
        Caption(vehicle.rangeText ?: "--")
    }
}

@Composable
private fun SmallLayout(vehicle: WidgetVehicle) {
    Column(GlanceModifier.fillMaxSize()) {
        Title(vehicle.name, 13)
        Spacer(GlanceModifier.defaultWeight())
        Percent(vehicle, 34)
        RangeLine(vehicle)
        Spacer(GlanceModifier.height(6.dp))
        BatteryBar(vehicle)
    }
}

/** The charging line: complete, the charging state, or speed, time left and target. */
@Composable
private fun ChargingStrip(vehicle: WidgetVehicle, wordy: Boolean) {
    if (!vehicle.isCharging && !vehicle.isPluggedIn) return
    val text = when {
        vehicle.isChargeComplete -> if (wordy) "Charging Complete · ${vehicle.percent}% / Target ${vehicle.target}%" else "Charged ${vehicle.percent}%"
        !vehicle.isCharging -> "Plugged in · not charging"
        else -> listOfNotNull(
            vehicle.plugTypeLabel,
            vehicle.chargeSpeed.takeIf { it > 0 }?.let { "${com.saivenkatkrishna.ghostblue.ui.components.Format.decimals(it, if (wordy) 1 else 0)} kW" },
            vehicle.remaining?.let { if (wordy) "$it left" else it },
            vehicle.finish?.let { if (wordy) "done at $it" else it },
            vehicle.target?.let { "→ $it%" },
        ).joinToString(" · ")
    }
    Text(text, maxLines = 1, style = TextStyle(color = fixedColor(Color(0xFF34C468)), fontWeight = FontWeight.Medium, fontSize = 12.sp))
}

@Composable
private fun Buttons(vehicle: WidgetVehicle, filled: Boolean) {
    val lockAction = if (vehicle.isLocked == true) RemoteAction.UNLOCK else RemoteAction.LOCK
    val climateAction = if (vehicle.isClimateOn) RemoteAction.STOP_CLIMATE else RemoteAction.START_CLIMATE
    if (filled) {
        Row(GlanceModifier.fillMaxWidth()) {
            FilledButton(
                text = lockAction.title,
                icon = ImageProvider(if (lockAction == RemoteAction.LOCK) R.drawable.ic_lock else R.drawable.ic_lock_open),
                onClick = command(vehicle.vin, lockAction),
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(8.dp))
            FilledButton(
                text = climateAction.title,
                icon = ImageProvider(R.drawable.ic_climate),
                onClick = command(vehicle.vin, climateAction),
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    } else {
        Column {
            CircleIconButton(
                imageProvider = ImageProvider(if (lockAction == RemoteAction.LOCK) R.drawable.ic_lock else R.drawable.ic_lock_open),
                contentDescription = lockAction.title,
                onClick = command(vehicle.vin, lockAction),
            )
            Spacer(GlanceModifier.height(8.dp))
            CircleIconButton(
                imageProvider = ImageProvider(R.drawable.ic_climate),
                contentDescription = climateAction.title,
                onClick = command(vehicle.vin, climateAction),
                backgroundColor = if (vehicle.isClimateOn) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer,
                contentColor = if (vehicle.isClimateOn) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun RefreshButton(vin: String) {
    CircleIconButton(
        imageProvider = ImageProvider(R.drawable.ic_refresh),
        contentDescription = "Refresh",
        onClick = refresh(vin),
        backgroundColor = null,
        contentColor = GlanceTheme.colors.onSurfaceVariant,
        modifier = GlanceModifier.size(32.dp),
    )
}

@Composable
private fun MediumLayout(vehicle: WidgetVehicle, image: Bitmap?) {
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Title(vehicle.name)
            }
            if (image != null) {
                Image(ImageProvider(image), contentDescription = vehicle.model, contentScale = ContentScale.Fit, modifier = GlanceModifier.height(44.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Percent(vehicle, 22)
                Spacer(GlanceModifier.width(6.dp))
                Caption(vehicle.rangeText ?: "")
            }
            BatteryBar(vehicle)
            Spacer(GlanceModifier.height(4.dp))
            if (vehicle.commandText == null && (vehicle.isCharging || vehicle.isPluggedIn)) ChargingStrip(vehicle, wordy = false)
            else Caption(vehicle.statusLine)
        }
        Spacer(GlanceModifier.width(10.dp))
        Buttons(vehicle, filled = false)
    }
}

@Composable
private fun LargeLayout(vehicle: WidgetVehicle, image: Bitmap?) {
    Column(GlanceModifier.fillMaxSize()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Title(vehicle.name, 16)
                Caption(vehicle.statusLine)
            }
            RefreshButton(vehicle.vin)
        }
        Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
            if (image != null) Image(ImageProvider(image), contentDescription = vehicle.model, contentScale = ContentScale.Fit, modifier = GlanceModifier.fillMaxSize())
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Percent(vehicle, 34)
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Caption(vehicle.rangeText ?: "--")
            }
        }
        BatteryBar(vehicle)
        Spacer(GlanceModifier.height(6.dp))
        ChargingStrip(vehicle, wordy = true)
        Spacer(GlanceModifier.height(8.dp))
        Buttons(vehicle, filled = true)
    }
}

/**
 * iOS's Charge Dial widget: a 270° dial of the battery, red at 20% or
 * less, orange at 40% or less, cyan while charging; the medium size adds
 * the lock state, charging details and the two buttons.
 */
class ChargeDialWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(Small, Medium))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = widgetStates(context, id, withImage = false).map { state -> state to state.vehicle?.let { drawDial(context, it) } }
        val initial = states.first()
        provideContent {
            val (state, dial) = states.collectAsState(initial).value
            WidgetTheme(state.rivian) {
                val vehicle = state.vehicle
                Frame(vehicle?.vin) {
                    if (vehicle == null) {
                        NoVehicle()
                    } else if (LocalSize.current.width >= Medium.width) {
                        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            DialView(vehicle, dial, 86)
                            Spacer(GlanceModifier.width(12.dp))
                            Column(GlanceModifier.defaultWeight()) {
                                Title(vehicle.name)
                                Caption(
                                    when (vehicle.isLocked) {
                                        true -> "Locked"
                                        false -> "Unlocked"
                                        null -> "Lock unknown"
                                    },
                                )
                                if (vehicle.isCharging || vehicle.isPluggedIn) ChargingStrip(vehicle, wordy = false)
                            }
                            Buttons(vehicle, filled = false)
                        }
                    } else {
                        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) { DialView(vehicle, dial, 96) }
                    }
                }
            }
        }
    }
}

class ChargeDialWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ChargeDialWidget()
}

@Composable
private fun DialView(vehicle: WidgetVehicle, dial: Bitmap?, size: Int) {
    Box(GlanceModifier.size(size.dp), contentAlignment = Alignment.Center) {
        if (dial != null) Image(ImageProvider(dial), contentDescription = null, modifier = GlanceModifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Percent(vehicle, if (size > 90) 24 else 20)
            Caption(vehicle.rangeText ?: "")
        }
    }
}

/** The dial's colour, as iOS's: cyan while charging, red at 20% or less, orange at 40% or less. */
fun dialColor(percent: Int?, charging: Boolean): Color = when {
    charging -> Color(0xFF32D2E6)
    percent == null -> Color(0xFF8E8E93)
    percent <= 20 -> Color(0xFFFF5A5A)
    percent <= 40 -> Color(0xFFF08C2B)
    else -> Color(0xFF34C468)
}

/** Draws the dial: a 270° track with the charge over it. Widgets can't draw, so it's a bitmap. */
private fun drawDial(context: Context, vehicle: WidgetVehicle): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (96 * density).toInt()
    val stroke = 9 * density
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val rect = RectF(stroke, stroke, size - stroke, size - stroke)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    paint.color = Color(0x338E8E93).toArgb()
    canvas.drawArc(rect, 135f, 270f, false, paint)
    val fraction = ((vehicle.percent ?: 0) / 100f).coerceIn(0f, 1f)
    paint.color = dialColor(vehicle.percent, vehicle.isCharging).toArgb()
    if (fraction > 0) canvas.drawArc(rect, 135f, 270f * fraction, false, paint)
    return bitmap
}
