package com.saivenkatkrishna.ghostblue.widgets

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.saivenkatkrishna.ghostblue.R
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.ui.components.Format
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/**
 * iOS's Woven Wordmark widget (`GhostBlueWovenWidget`, frame 1b of the
 * widget design specs): a large card where the charge fills the model
 * lettering left to right, the car woven in front, range and battery,
 * charging progress with the finish time (or the parked status), and Lock
 * and Unlock. The lettering, the wave bar, the battery glyph and the card's
 * glow are drawn as bitmaps, which Glance can't draw itself. Under the
 * Rivian theme it's iOS's Rivian version (`RivianSpecView`).
 */
class WovenWidget : GlanceAppWidget() {
    // Exact, so the hero can be drawn at the size it's shown.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = widgetStates(context, id, withImage = true)
        val initial = states.first()
        provideContent {
            val state by states.collectAsState(initial)
            WovenContent(state)
        }
    }
}

@Composable
internal fun WovenContent(state: WidgetState) {
    if (state.rivian) RivianWoven(state) else SpecWoven(state, state.accent)
}

class WovenWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WovenWidget()
}

private fun lockAction(vin: String, action: RemoteAction): Action =
    actionRunCallback<CommandCallback>(actionParametersOf(KeyVin to vin, KeyAction to action.name))

private fun refreshAction(vin: String): Action = actionRunCallback<RefreshCallback>(actionParametersOf(KeyVin to vin))

/** "1h 12m left", or "12m left" ([shortMinutes] false: "12 min left", as iOS's Rivian widgets). */
internal fun timeLeft(seconds: Long, shortMinutes: Boolean = true): String? {
    if (seconds <= 60) return null
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m left"
        shortMinutes -> "${minutes}m left"
        else -> "$minutes min left"
    }
}

// MARK: - The spec card

/** The spec's dark neutral surface and ink; it doesn't follow the wallpaper or dark mode, as on iOS. */
private object Spec {
    const val BG_TOP = 0xFF1D2226.toInt()
    const val BG_MID = 0xFF121417.toInt()
    const val BG_BOTTOM = 0xFF0B0C0F.toInt()
    val buttonInk = Color(0xFF08110F)

    // Fixed heights, so the hero is drawn at exactly the space left.
    val padding = 14.dp
    val gap = 10.dp
    val topBar = 16.dp
    val rangeRow = 62.dp
    val chargingModule = 88.dp
    val statusRow = 44.dp
    val buttons = 40.dp

    fun white(alpha: Float) = ColorProvider(Color.White.copy(alpha = alpha))
}

@Composable
private fun SpecWoven(state: WidgetState, accent: WidgetAccent) {
    val size = LocalSize.current
    val vehicle = state.vehicle
    val charging = vehicle?.isCharging == true
    val background = remember(size, accent, charging) { WovenArt.background(size.width.value, size.height.value, accent.argb, charging) }
    Box(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(24.dp)
            .background(ImageProvider(background), ContentScale.FillBounds),
    ) {
        if (vehicle == null) {
            Empty(Spec.white(0.9f), Spec.white(0.5f))
        } else {
            SpecLayout(vehicle, state, accent)
        }
    }
}

@Composable
private fun SpecLayout(vehicle: WidgetVehicle, state: WidgetState, accent: WidgetAccent) {
    val context = LocalContext.current
    val size = LocalSize.current
    val accentColor = Color(accent.argb)
    val width = size.width - Spec.padding * 2
    val module = if (vehicle.isCharging) Spec.chargingModule else Spec.statusRow
    val heroHeight = max(40f, (size.height - Spec.padding * 2 - Spec.topBar - Spec.rangeRow - module - Spec.buttons - Spec.gap * 4).value).dp
    val scale = artScale(context)
    val hero = remember(vehicle, state.image, accent, width, heroHeight) {
        WovenArt.hero(width.value, heroHeight.value, scale, WovenArt.wordmark(vehicle), (vehicle.percent ?: 0) / 100f, accent.argb, state.image)
    }
    Column(GlanceModifier.fillMaxSize().padding(Spec.padding)) {
        SpecTopBar(vehicle)
        Spacer(GlanceModifier.height(Spec.gap))
        Image(
            ImageProvider(hero),
            contentDescription = vehicle.name,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxWidth().height(heroHeight).clickable(actionStartActivity(openVehicleIntent(context, vehicle.vin))),
        )
        Spacer(GlanceModifier.height(Spec.gap))
        SpecRangeRow(vehicle, accent, scale)
        Spacer(GlanceModifier.height(Spec.gap))
        if (vehicle.isCharging) SpecChargingModule(vehicle, accentColor, accent, width - 24.dp, scale) else SpecStatusRow(vehicle, accentColor)
        Spacer(GlanceModifier.height(Spec.gap))
        SpecButtons(vehicle, accentColor)
    }
}

@Composable
private fun SpecTopBar(vehicle: WidgetVehicle) {
    val caption = TextStyle(color = Spec.white(0.8f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Row(GlanceModifier.fillMaxWidth().height(Spec.topBar), verticalAlignment = Alignment.CenterVertically) {
        if (vehicle.odometer.isNotEmpty()) {
            Image(ImageProvider(R.drawable.ic_odometer), contentDescription = null, modifier = GlanceModifier.size(11.dp), colorFilter = ColorFilter.tint(Spec.white(0.8f)))
            Spacer(GlanceModifier.width(4.dp))
            Text(vehicle.odometer, style = caption, maxLines = 1)
        }
        Spacer(GlanceModifier.defaultWeight())
        (vehicle.commandText ?: vehicle.updated?.let { "Updated $it" })?.let {
            Text(it, style = caption.copy(color = Spec.white(if (vehicle.commandText != null) 0.9f else 0.55f)), maxLines = 1)
        }
        Spacer(GlanceModifier.defaultWeight())
        Image(
            ImageProvider(R.drawable.ic_refresh),
            contentDescription = "Refresh",
            modifier = GlanceModifier.size(16.dp).clickable(refreshAction(vehicle.vin)),
            colorFilter = ColorFilter.tint(Spec.white(0.55f)),
        )
    }
}

/** "253 mi" → "253" and "mi"; text that isn't a number stays whole. */
internal fun rangeParts(text: String?): Pair<String, String> {
    val parts = text.orEmpty().split(" ", limit = 2)
    return if (parts.first().firstOrNull()?.isDigit() == true) parts.first() to parts.getOrElse(1) { "" } else text.orEmpty() to ""
}

@Composable
private fun SpecRangeRow(vehicle: WidgetVehicle, accent: WidgetAccent, scale: Float) {
    val context = LocalContext.current
    val caption = TextStyle(color = Spec.white(0.42f), fontSize = 9.sp, fontWeight = FontWeight.Medium)
    val (value, unit) = rangeParts(vehicle.rangeText)
    Row(GlanceModifier.fillMaxWidth().height(Spec.rangeRow), verticalAlignment = Alignment.Bottom) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value.ifEmpty { "--" }, style = TextStyle(color = Spec.white(1f), fontSize = 34.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                if (unit.isNotEmpty()) {
                    Spacer(GlanceModifier.width(4.dp))
                    Text(unit, style = TextStyle(color = Spec.white(0.5f), fontSize = 14.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.padding(bottom = 6.dp))
                }
            }
            Text("RANGE", style = caption)
        }
        Spacer(GlanceModifier.defaultWeight())
        vehicle.percent?.let { percent ->
            val glyph = remember(percent, vehicle.isCharging, accent) {
                WovenArt.batteryGlyph(context, scale, percent, vehicle.isCharging, accent.argb)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(glyph), contentDescription = null, modifier = GlanceModifier.width(32.dp).height(14.dp))
                    Spacer(GlanceModifier.width(6.dp))
                    Text("$percent%", style = TextStyle(color = Spec.white(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold))
                }
                Text("BATTERY", style = caption)
            }
        }
    }
}

@Composable
private fun SpecChargingModule(vehicle: WidgetVehicle, accentColor: Color, accent: WidgetAccent, barWidth: Dp, scale: Float) {
    val title = buildString {
        append("Charging")
        vehicle.plugTypeLabel?.let { append(" · $it") }
        if (vehicle.chargeSpeed > 0) append(" · ${Format.oneDecimal(vehicle.chargeSpeed)} kW")
    }
    val bar = remember(vehicle.percent, vehicle.target, accent, barWidth) {
        WovenArt.waveBar(barWidth.value, scale, (vehicle.percent ?: 0) / 100f, vehicle.target?.let { it / 100f }, accent.argb)
    }
    val footer = TextStyle(color = Spec.white(0.72f), fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    Column(
        GlanceModifier.fillMaxWidth().height(Spec.chargingModule)
            .background(Spec.white(0.05f)).cornerRadius(14.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_bolt), contentDescription = null, modifier = GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(ColorProvider(accentColor)))
            Spacer(GlanceModifier.width(7.dp))
            Text(title, style = TextStyle(color = Spec.white(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            timeLeft(vehicle.remainingSeconds)?.let { left ->
                Box(GlanceModifier.background(ColorProvider(accentColor.copy(alpha = 0.14f))).cornerRadius(10.dp).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text(left, style = TextStyle(color = ColorProvider(accentColor), fontSize = 10.5.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                }
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        Image(ImageProvider(bar), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxWidth().height(16.dp))
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            vehicle.finish?.let {
                Image(ImageProvider(R.drawable.ic_clock), contentDescription = null, modifier = GlanceModifier.size(10.dp), colorFilter = ColorFilter.tint(Spec.white(0.72f)))
                Spacer(GlanceModifier.width(4.dp))
                Text("Full by $it", style = footer, maxLines = 1)
            }
            Spacer(GlanceModifier.defaultWeight())
            vehicle.target?.let { Text("Target $it%", style = footer.copy(color = Spec.white(0.55f)), maxLines = 1) }
        }
    }
}

@Composable
private fun SpecStatusRow(vehicle: WidgetVehicle, accentColor: Color) {
    val text = TextStyle(color = Spec.white(0.72f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Row(
        GlanceModifier.fillMaxWidth().height(Spec.statusRow)
            .background(Spec.white(0.05f)).cornerRadius(14.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            vehicle.isChargeComplete -> {
                Image(ImageProvider(R.drawable.ic_check_circle), contentDescription = null, modifier = GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(ColorProvider(accentColor)))
                Spacer(GlanceModifier.width(9.dp))
                Text("Charge Complete", style = text.copy(color = Spec.white(0.85f)), maxLines = 1)
            }
            // Plugged in and not charging: the charging state, beside its age.
            vehicle.isPluggedIn -> Text(vehicle.chargingTitle, style = text.copy(color = Spec.white(0.6f)), maxLines = 1)
            else -> {
                val (icon, status) = when (vehicle.isLocked) {
                    true -> R.drawable.ic_lock to "Locked · Parked"
                    false -> R.drawable.ic_lock_open to "Unlocked · Parked"
                    null -> R.drawable.ic_car to "Parked"
                }
                Image(ImageProvider(icon), contentDescription = null, modifier = GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(Spec.white(0.8f)))
                Spacer(GlanceModifier.width(9.dp))
                Text(status, style = text, maxLines = 1)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        vehicle.updatedAt?.let { Text(Format.relative(it), style = TextStyle(color = Spec.white(0.45f), fontSize = 11.sp), maxLines = 1) }
    }
}

/** Lock filled in the accent and Unlock outlined, as on iOS: both always there. */
@Composable
private fun SpecButtons(vehicle: WidgetVehicle, accentColor: Color) {
    Row(GlanceModifier.fillMaxWidth().height(Spec.buttons)) {
        PillButton("Lock", R.drawable.ic_lock, lockAction(vehicle.vin, RemoteAction.LOCK), ColorProvider(accentColor), ColorProvider(Spec.buttonInk), corner = 13.dp)
        Spacer(GlanceModifier.width(8.dp))
        PillButton("Unlock", R.drawable.ic_lock_open, lockAction(vehicle.vin, RemoteAction.UNLOCK), Spec.white(0.07f), Spec.white(1f), corner = 13.dp)
    }
}

@Composable
private fun androidx.glance.layout.RowScope.PillButton(title: String, icon: Int, action: Action, fill: ColorProvider, ink: ColorProvider, corner: Dp) {
    Box(
        GlanceModifier.defaultWeight().fillMaxHeight().background(fill).cornerRadius(corner).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(icon), contentDescription = null, modifier = GlanceModifier.size(15.dp), colorFilter = ColorFilter.tint(ink))
            Spacer(GlanceModifier.width(7.dp))
            Text(title, style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }
}

@Composable
private fun Empty(title: ColorProvider, detail: ColorProvider) {
    Column(GlanceModifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(ImageProvider(R.drawable.ic_car), contentDescription = null, modifier = GlanceModifier.size(32.dp), colorFilter = ColorFilter.tint(detail))
        Spacer(GlanceModifier.height(6.dp))
        Text("No Vehicle", style = TextStyle(color = title, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        Text("Open GhostBlue to add an account", style = TextStyle(color = detail, fontSize = 12.sp))
    }
}

// MARK: - The Rivian version

/** iOS's `RVTokens`, light and dark. */
private object Rivian {
    val surface = androidx.glance.color.ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF18181A))
    val surfaceMuted = androidx.glance.color.ColorProvider(day = Color(0xFFEBEBE8), night = Color(0xFF232326))
    val ink = androidx.glance.color.ColorProvider(day = Color(0xFF111113), night = Color(0xFFF2F2F0))
    val inkSecondary = androidx.glance.color.ColorProvider(day = Color(0xFF6B6B72), night = Color(0xFF9C9CA3))
    val energy = androidx.glance.color.ColorProvider(day = Color(0xFF2EB35C), night = Color(0xFF34C468))
    val energyTint = androidx.glance.color.ColorProvider(day = Color(0x1F2EB35C), night = Color(0x1F34C468))
    val danger = androidx.glance.color.ColorProvider(day = Color(0xFFD93A3A), night = Color(0xFFFF5A5A))
    val climate = androidx.glance.color.ColorProvider(day = Color(0xFF2A6BED), night = Color(0xFF3D7BFF))
    val inactiveFill = androidx.glance.color.ColorProvider(day = Color(0xFFE6E6E3), night = Color(0xFF2A2A2D))
    val gold = ColorProvider(Color(0xFFFFAC00))
    val onGold = ColorProvider(Color.Black)

    val padding = 16.dp
    val gap = 12.dp
    val header = 38.dp
    val metric = 40.dp
    val chargingRow = 56.dp
    val pills = 40.dp
}

/** iOS's one-line state, e.g. "Charging · 11 kW" or "Locked". */
internal fun rivianStatusLine(vehicle: WidgetVehicle): String = when {
    vehicle.isCharging -> if (vehicle.chargeSpeed > 0) "Charging · ${vehicle.chargeSpeed.roundToInt()} kW" else "Charging"
    vehicle.isChargeComplete -> "Charge complete"
    vehicle.isPluggedIn -> "Plugged in"
    vehicle.isClimateOn -> "Climate on"
    vehicle.isLocked == true -> "Locked"
    vehicle.isLocked == false -> "Unlocked"
    else -> "Parked"
}

@Composable
private fun RivianWoven(state: WidgetState) {
    val vehicle = state.vehicle
    Box(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(24.dp).background(Rivian.surface)) {
        if (vehicle == null) Empty(Rivian.ink, Rivian.inkSecondary) else RivianLayout(vehicle, state)
    }
}

@Composable
private fun RivianLayout(vehicle: WidgetVehicle, state: WidgetState) {
    val context = LocalContext.current
    val size = LocalSize.current
    val showsCharging = vehicle.isCharging || vehicle.isPluggedIn
    val width = size.width - Rivian.padding * 2
    val panelHeight = max(
        60f,
        (size.height - Rivian.padding * 2 - Rivian.header - Rivian.metric - 6.dp - 6.dp - Rivian.pills - Rivian.gap * 3 -
            (if (showsCharging) Rivian.chargingRow + Rivian.gap else 0.dp)).value,
    ).dp
    val scale = artScale(context)
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val wordmark = remember(vehicle, width, night) {
        WovenArt.rivianWordmark((width - 28.dp).value, 46f, scale, WovenArt.wordmark(vehicle), (vehicle.percent ?: 0) / 100f, night)
    }
    Column(GlanceModifier.fillMaxSize().padding(Rivian.padding)) {
        RivianHeader(vehicle)
        Spacer(GlanceModifier.height(Rivian.gap))
        Column(
            GlanceModifier.fillMaxWidth().height(panelHeight).background(Rivian.surfaceMuted).cornerRadius(20.dp)
                .padding(vertical = 10.dp)
                .clickable(actionStartActivity(openVehicleIntent(context, vehicle.vin))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(ImageProvider(wordmark), contentDescription = WovenArt.wordmark(vehicle), modifier = GlanceModifier.fillMaxWidth().height(46.dp).padding(horizontal = 14.dp))
            state.image?.let {
                Image(ImageProvider(it), contentDescription = null, contentScale = ContentScale.Fit, modifier = GlanceModifier.fillMaxWidth().height(min(58f, max(20f, panelHeight.value - 70f)).dp))
            }
        }
        Spacer(GlanceModifier.height(Rivian.gap))
        // One group, as iOS's: Glance draws at most ten children in a column and drops the rest.
        Column(GlanceModifier.fillMaxWidth()) {
            Row(GlanceModifier.fillMaxWidth().height(Rivian.metric), verticalAlignment = Alignment.Bottom) {
                Text(vehicle.percent?.toString() ?: "--", style = TextStyle(color = Rivian.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold))
                Text("%", style = TextStyle(color = Rivian.inkSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.padding(bottom = 5.dp))
                Spacer(GlanceModifier.width(6.dp))
                Text(vehicle.rangeText.orEmpty(), style = TextStyle(color = Rivian.inkSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.padding(bottom = 5.dp), maxLines = 1)
            }
            Spacer(GlanceModifier.height(6.dp))
            LinearProgressIndicator(
                progress = (vehicle.percent ?: 0) / 100f,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = if ((vehicle.percent ?: 100) <= 20) Rivian.danger else Rivian.energy,
                backgroundColor = Rivian.surfaceMuted,
            )
        }
        if (showsCharging) {
            Spacer(GlanceModifier.height(Rivian.gap))
            RivianChargingRow(vehicle)
        }
        Spacer(GlanceModifier.height(Rivian.gap))
        // The action that changes the current state is filled gold.
        Row(GlanceModifier.fillMaxWidth().height(Rivian.pills)) {
            val lockProminent = vehicle.isLocked != true
            PillButton("Lock", R.drawable.ic_lock, lockAction(vehicle.vin, RemoteAction.LOCK), if (lockProminent) Rivian.gold else Rivian.inactiveFill, if (lockProminent) Rivian.onGold else Rivian.ink, corner = 20.dp)
            Spacer(GlanceModifier.width(8.dp))
            PillButton("Unlock", R.drawable.ic_lock_open, lockAction(vehicle.vin, RemoteAction.UNLOCK), if (!lockProminent) Rivian.gold else Rivian.inactiveFill, if (!lockProminent) Rivian.onGold else Rivian.ink, corner = 20.dp)
        }
    }
}

@Composable
private fun RivianHeader(vehicle: WidgetVehicle) {
    val tint = when {
        vehicle.isCharging || vehicle.isChargeComplete -> Rivian.energy
        vehicle.isClimateOn -> Rivian.climate
        else -> Rivian.inkSecondary
    }
    Row(GlanceModifier.fillMaxWidth().height(Rivian.header), verticalAlignment = Alignment.Top) {
        Column(GlanceModifier.defaultWeight()) {
            Text(vehicle.name, style = TextStyle(color = Rivian.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Text(vehicle.commandText ?: listOfNotNull(rivianStatusLine(vehicle), vehicle.updated).joinToString(" · "), style = TextStyle(color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
        Spacer(GlanceModifier.width(8.dp))
        Box(
            GlanceModifier.size(30.dp).background(Rivian.inactiveFill).cornerRadius(15.dp).clickable(refreshAction(vehicle.vin)),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(R.drawable.ic_refresh), contentDescription = "Refresh", modifier = GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(Rivian.ink))
        }
    }
}

@Composable
private fun RivianChargingRow(vehicle: WidgetVehicle) {
    val title = when {
        vehicle.isChargeComplete -> "Charge complete"
        !vehicle.isCharging -> vehicle.chargingTitle
        else -> buildString {
            append("Charging")
            if (vehicle.chargeSpeed > 0) append(" · ${Format.oneDecimal(vehicle.chargeSpeed)} kW")
            vehicle.plugTypeLabel?.let { append(" · $it") }
        }
    }
    val detail = listOfNotNull(
        if (vehicle.isCharging) timeLeft(vehicle.remainingSeconds, shortMinutes = false) else null,
        if (vehicle.isCharging) vehicle.finish?.let { "Full by $it" } else null,
        vehicle.target?.let { "Target $it%" },
    ).joinToString(" · ")
    Row(
        GlanceModifier.fillMaxWidth().height(Rivian.chargingRow).background(Rivian.energyTint).cornerRadius(16.dp).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.size(28.dp).background(Rivian.energy).cornerRadius(14.dp), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(if (vehicle.isChargeComplete) R.drawable.ic_check_circle else R.drawable.ic_bolt),
                contentDescription = null,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
            )
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(title, style = TextStyle(color = Rivian.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            if (detail.isNotEmpty()) Text(detail, style = TextStyle(color = Rivian.inkSecondary, fontSize = 11.sp), maxLines = 1)
        }
    }
}

// MARK: - Drawing

/** Pixels per dp for the drawn parts: the screen's, capped to keep widget bitmaps small. */
private fun artScale(context: Context): Float = min(context.resources.displayMetrics.density, 2.5f)

internal object WovenArt {
    private val heavy: Typeface = Typeface.create(Typeface.SANS_SERIF, 800, false)
    private val black: Typeface = Typeface.create(Typeface.SANS_SERIF, 900, false)

    /** "2025 IONIQ 5" → "IONIQ 5"; the car's name when the model is blank. */
    fun wordmark(vehicle: WidgetVehicle): String =
        vehicle.model.replace(Regex("""^\d{4}\s+"""), "").trim().ifEmpty { vehicle.name }.uppercase()

    /** "IONIQ 5" → "IONIQ" and the numeral "5"; a one-word name ("KONA") has no numeral. */
    fun parts(wordmark: String): Pair<String, String> {
        val tokens = wordmark.split(" ").filter { it.isNotEmpty() }
        return if (tokens.size >= 2) tokens.dropLast(1).joinToString(" ") to tokens.last() else wordmark to ""
    }

    private fun canvas(widthDp: Float, heightDp: Float, scale: Float): Pair<Bitmap, Canvas> {
        val bitmap = createBitmap(max(1, (widthDp * scale).roundToInt()), max(1, (heightDp * scale).roundToInt()))
        return bitmap to Canvas(bitmap).apply { scale(scale, scale) }
    }

    private fun alpha(color: Int, alpha: Float): Int = ColorUtils.setAlphaComponent(color, (android.graphics.Color.alpha(color) * alpha).roundToInt())

    private fun white(alpha: Float): Int = alpha(android.graphics.Color.WHITE, alpha)

    /** The card: a radial #1d2226 → #0b0c0f surface with the accent glowing at the top, stronger while charging. */
    fun background(widthDp: Float, heightDp: Float, accent: Int, charging: Boolean): Bitmap {
        // A gradient scales up smoothly, so one pixel per dp is plenty.
        val (bitmap, canvas) = canvas(widthDp, heightDp, 1f)
        val surface = Paint().apply {
            shader = RadialGradient(widthDp / 2, -0.12f * heightDp, 520f, intArrayOf(Spec.BG_TOP, Spec.BG_MID, Spec.BG_BOTTOM), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, widthDp, heightDp, surface)
        val glow = Paint().apply {
            shader = RadialGradient(widthDp / 2, 0f, 182f, alpha(accent, if (charging) 0.22f else 0.12f), alpha(accent, 0f), Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(1f, 0.5f, widthDp / 2, 0f) })
            }
        }
        canvas.drawRect(0f, 0f, widthDp, heightDp, glow)
        return bitmap
    }

    /**
     * The hero: the model word across the top and its numeral towering
     * faintly at the right, drawn as a ghost and again in the accent up to
     * the charge level, with the car in front at the bottom.
     */
    fun hero(widthDp: Float, heightDp: Float, scale: Float, wordmark: String, fraction: Float, accent: Int, car: Bitmap?): Bitmap {
        val (bitmap, canvas) = canvas(widthDp, heightDp, scale)
        val frame = RectF(0f, 0f, widthDp, heightDp)
        canvas.drawRoundRect(frame, 16f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(widthDp / 2, -0.06f * heightDp, 260f, intArrayOf(white(0.10f), white(0.018f), white(0.008f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
        canvas.drawRoundRect(RectF(0.5f, 0.5f, widthDp - 0.5f, heightDp - 0.5f), 16f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = white(0.08f)
        })
        canvas.clipPath(Path().apply { addRoundRect(frame, 16f, 16f, Path.Direction.CW) })

        val (main, trail) = parts(wordmark)
        fun letters(color: Int) {
            if (trail.isNotEmpty()) {
                val numeral = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = heavy
                    textSize = min(118f, heightDp * 1.05f)
                    textAlign = Paint.Align.RIGHT
                    this.color = alpha(color, 0.35f)
                }
                val metrics = numeral.fontMetrics
                canvas.drawText(trail, widthDp - 12f, heightDp / 2 - (metrics.ascent + metrics.descent) / 2, numeral)
            }
            val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = heavy
                textSize = 60f
                letterSpacing = 3f / 60f
                this.color = color
            }
            // Shrunk to fit the width, to no less than 30%, as iOS's minimum scale factor.
            val available = widthDp - 20f - 26f
            val measured = word.measureText(main)
            if (measured > available) word.textSize = max(18f, 60f * available / measured)
            canvas.drawText(main, 10f, 4f - word.fontMetrics.ascent, word)
        }
        letters(white(0.22f))
        canvas.save()
        canvas.clipRect(0f, 0f, widthDp * fraction.coerceIn(0f, 1f), heightDp)
        letters(accent)
        canvas.restore()

        car?.let {
            val height = min(84f, heightDp - 8f)
            val width = height * it.width / it.height
            val left = (widthDp - width) / 2
            val top = heightDp - 4f - height
            canvas.drawBitmap(it, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return bitmap
    }

    /** The charge bar: the accent capsule with a wave through it, and a tick at the target. 16 dp tall, the bar 12 of it. */
    fun waveBar(widthDp: Float, scale: Float, progress: Float, target: Float?, accent: Int): Bitmap {
        val (bitmap, canvas) = canvas(widthDp, 16f, scale)
        val track = RectF(0f, 2f, widthDp, 14f)
        canvas.drawRoundRect(track, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white(0.08f) })
        val fillWidth = max(12f, widthDp * progress.coerceIn(0f, 1f))
        val fill = RectF(0f, 2f, fillWidth, 14f)
        canvas.drawRoundRect(fill, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, fillWidth, 0f, alpha(accent, 0.6f), accent, Shader.TileMode.CLAMP)
            setShadowLayer(4f, 0f, 0f, alpha(accent, 0.5f))
        })
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(fill, 6f, 6f, Path.Direction.CW) })
        val wave = Path().apply {
            moveTo(0f, 8f)
            var x = 0f
            var up = true
            while (x < fillWidth) {
                val next = min(x + 10f, fillWidth)
                quadTo(x + 5f, if (up) 8f - 2.6f else 8f + 2.6f, next, 8f)
                x = next
                up = !up
            }
        }
        canvas.drawPath(wave, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            color = white(0.5f)
        })
        canvas.restore()
        if (target != null && target > 0f && target < 1f) {
            val x = min(widthDp * target, widthDp - 2f)
            canvas.drawRoundRect(RectF(x, 0f, x + 2f, 16f), 1f, 1f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white(0.7f) })
        }
        return bitmap
    }

    /** iOS's battery glyph, 32 × 14 dp: filled to the charge, in the accent with a bolt while charging. */
    fun batteryGlyph(context: Context, scale: Float, percent: Int, charging: Boolean, accent: Int): Bitmap {
        val (bitmap, canvas) = canvas(32f, 14f, scale)
        canvas.drawRoundRect(RectF(0.8f, 0.8f, 27.2f, 13.2f), 3.5f, 3.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            color = white(0.45f)
        })
        val level = max(2f, 22f * percent.coerceIn(0, 100) / 100f)
        canvas.drawRoundRect(RectF(3f, 3f, 3f + level, 11f), 1.5f, 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (charging) accent else white(0.85f)
        })
        if (charging) {
            ContextCompat.getDrawable(context, R.drawable.ic_bolt)?.mutate()?.let { bolt ->
                bolt.setTint(alpha(android.graphics.Color.BLACK, 0.7f))
                bolt.setBounds(9, 2, 19, 12)
                bolt.draw(canvas)
            }
        }
        canvas.drawRoundRect(RectF(29.5f, 4f, 32f, 10f), 1f, 1f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white(0.45f) })
        return bitmap
    }

    /** The Rivian lettering: ink at 12%, filled with the battery colour (green, red when low) up to the charge. */
    fun rivianWordmark(widthDp: Float, heightDp: Float, scale: Float, wordmark: String, fraction: Float, night: Boolean): Bitmap {
        val (bitmap, canvas) = canvas(widthDp, heightDp, scale)
        val ink = if (night) 0xFFF2F2F0.toInt() else 0xFF111113.toInt()
        val fill = when {
            fraction <= 0.2f -> if (night) 0xFFFF5A5A.toInt() else 0xFFD93A3A.toInt()
            else -> if (night) 0xFF34C468.toInt() else 0xFF2EB35C.toInt()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = black
            textSize = 38f
            letterSpacing = 4f / 38f
        }
        val measured = paint.measureText(wordmark)
        if (measured > widthDp) paint.textSize = max(19f, 38f * widthDp / measured)
        val width = paint.measureText(wordmark)
        val left = (widthDp - width) / 2
        val metrics = paint.fontMetrics
        val baseline = heightDp / 2 - (metrics.ascent + metrics.descent) / 2
        canvas.drawText(wordmark, left, baseline, paint.apply { color = alpha(ink, 0.12f) })
        canvas.save()
        canvas.clipRect(left, 0f, left + width * fraction.coerceIn(0f, 1f), heightDp)
        canvas.drawText(wordmark, left, baseline, paint.apply { color = fill })
        canvas.restore()
        return bitmap
    }
}
