package com.saivenkatkrishna.ghostblue.wear.tile

import android.content.ComponentName
import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.MultiButtonLayout
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.wear.R
import com.saivenkatkrishna.ghostblue.wear.WatchCommand
import com.saivenkatkrishna.ghostblue.wear.WatchControl
import com.saivenkatkrishna.ghostblue.wear.WearMainActivity
import com.saivenkatkrishna.ghostblue.wear.wearGraph

/** What the tile shows for a car, and the buttons it offers. */
data class TileContent(
    val title: String,
    val percent: String,
    val detail: String,
    val lockAction: RemoteAction?,
    val climateAction: RemoteAction?,
    val vin: String?,
)

fun tileContent(vehicle: BBVehicle?, unit: Distance.Units, command: WatchCommand?): TileContent {
    if (vehicle == null) return TileContent("GhostBlue", "--", "Open GhostBlue to sign in", null, null, null)
    val range = vehicle.evStatus?.evRange ?: vehicle.gasRange
    val locked = vehicle.lockStatus == VehicleStatus.LockStatus.LOCKED
    val state = command?.text ?: when {
        vehicle.evStatus?.charging == true -> "Charging"
        locked -> "Locked"
        vehicle.lockStatus == VehicleStatus.LockStatus.UNLOCKED -> "Unlocked"
        else -> "Parked"
    }
    return TileContent(
        title = vehicle.displayName,
        percent = range?.let { "${it.percentage.toInt()}%" } ?: "--",
        detail = listOfNotNull(range?.let { it.range.units.format(it.range.length, unit) }, state).joinToString(" · "),
        lockAction = if (locked) RemoteAction.UNLOCK else RemoteAction.LOCK,
        climateAction = if (vehicle.climateStatus?.airControlOn == true) RemoteAction.STOP_CLIMATE else RemoteAction.START_CLIMATE,
        vin = vehicle.vin,
    )
}

/**
 * A Wear tile for the selected car: the charge, range and state, with lock
 * and climate buttons and a refresh, as iOS's watch puts them a swipe away.
 * A button press arrives as the next tile request, which sends the command.
 */
class VehicleTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture { tile(requestParams) }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(ICON_LOCK, image(R.drawable.ic_lock))
                .addIdToImageMapping(ICON_UNLOCK, image(R.drawable.ic_lock_open))
                .addIdToImageMapping(ICON_CLIMATE, image(R.drawable.ic_climate))
                .build()
        }

    private fun image(id: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(id).build())
        .build()

    private suspend fun tile(request: RequestBuilders.TileRequest): TileBuilders.Tile {
        val graph = applicationContext.wearGraph
        val vehicles = graph.db.vehicles().all().filter { !it.isHidden }
        val vehicle = vehicles.firstOrNull { it.vin == graph.settings.selectedVin.value } ?: vehicles.firstOrNull()
        // A button press: send it now; the tile updates as the car confirms.
        if (vehicle != null) {
            when (val clicked = request.currentState.lastClickableId) {
                ID_REFRESH -> graph.model.refresh(vehicle.vin, forced = true)
                else -> RemoteAction.fromName(clicked)?.let { action ->
                    val control = if (action == RemoteAction.LOCK || action == RemoteAction.UNLOCK) WatchControl.LOCK else WatchControl.CLIMATE
                    if (graph.model.commands.value[vehicle.vin to control]?.running != true) graph.model.send(vehicle.vin, control, action)
                }
            }
        }
        val command = vehicle?.let { v ->
            graph.model.commands.value[v.vin to WatchControl.LOCK] ?: graph.model.commands.value[v.vin to WatchControl.CLIMATE]
        }
        val content = tileContent(vehicle, graph.settings.distanceUnit.value, command)
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // While a command runs the tile asks again soon, to show its progress.
            .setFreshnessIntervalMillis(if (command?.running == true) 5_000 else 30 * 60_000)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(this, request.deviceConfiguration, content)))
            .build()
    }

    companion object {
        const val RESOURCES_VERSION = "1"
        const val ID_REFRESH = "refresh"
        private const val ICON_LOCK = "lock"
        private const val ICON_UNLOCK = "unlock"
        private const val ICON_CLIMATE = "climate"

        private val colors = Colors(0xFFB2C5FF.toInt(), 0xFF002B74.toInt(), 0xFF1E1F25.toInt(), 0xFFE3E2E9.toInt())

        private fun load(id: String) = ModifiersBuilders.Clickable.Builder()
            .setId(id)
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()

        private fun open(context: Context, vin: String?) = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(context.packageName)
                            .setClassName(WearMainActivity::class.java.name)
                            .apply { vin?.let { addKeyToExtraMapping(WearMainActivity.EXTRA_VIN, ActionBuilders.stringExtra(it)) } }
                            .build(),
                    )
                    .build(),
            )
            .build()

        fun layout(context: Context, device: DeviceParameters, content: TileContent): LayoutElementBuilders.LayoutElement {
            val body = LayoutElementBuilders.Column.Builder()
                .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open(context, content.vin)).build())
                .addContent(Text.Builder(context, content.percent).setTypography(Typography.TYPOGRAPHY_DISPLAY2).setColor(argb(colors.primary)).build())
                .addContent(Text.Builder(context, content.detail).setTypography(Typography.TYPOGRAPHY_CAPTION2).setColor(argb(colors.onSurface)).setMaxLines(2).build())
                .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            if (content.lockAction != null && content.climateAction != null) {
                val buttons = MultiButtonLayout.Builder()
                    .addButtonContent(
                        Button.Builder(context, load(content.lockAction.name))
                            .setIconContent(if (content.lockAction == RemoteAction.UNLOCK) ICON_UNLOCK else ICON_LOCK)
                            .setContentDescription(content.lockAction.title)
                            .setButtonColors(ButtonColors.secondaryButtonColors(colors))
                            .build(),
                    )
                    .addButtonContent(
                        Button.Builder(context, load(content.climateAction.name))
                            .setIconContent(ICON_CLIMATE)
                            .setContentDescription(content.climateAction.title)
                            .setButtonColors(
                                if (content.climateAction == RemoteAction.STOP_CLIMATE) ButtonColors.primaryButtonColors(colors)
                                else ButtonColors.secondaryButtonColors(colors),
                            )
                            .build(),
                    )
                    .build()
                body.addContent(buttons)
            }
            return PrimaryLayout.Builder(device)
                .setResponsiveContentInsetEnabled(true)
                .setPrimaryLabelTextContent(
                    Text.Builder(context, content.title).setTypography(Typography.TYPOGRAPHY_CAPTION1).setColor(argb(colors.onSurface)).build(),
                )
                .setContent(body.build())
                .setPrimaryChipContent(
                    CompactChip.Builder(context, "Refresh", load(ID_REFRESH), device)
                        .setChipColors(ChipColors.secondaryChipColors(colors))
                        .build(),
                )
                .build()
        }

        fun requestUpdate(context: Context) {
            runCatching { getUpdater(context).requestUpdate(VehicleTileService::class.java) }
        }

        fun component(context: Context) = ComponentName(context, VehicleTileService::class.java)
    }
}
