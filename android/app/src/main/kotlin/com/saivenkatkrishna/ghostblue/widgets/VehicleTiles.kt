package com.saivenkatkrishna.ghostblue.widgets

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.saivenkatkrishna.ghostblue.GhostBlueApplication
import com.saivenkatkrishna.ghostblue.R
import com.saivenkatkrishna.ghostblue.background.CommandWorker
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a tile looks for a car, and what tapping it sends. */
data class TileLook(val label: String, val subtitle: String, val active: Boolean, val icon: Int, val action: RemoteAction?)

/**
 * Quick Settings tiles for the car selected in the app: iOS's Control
 * Center controls (lock, unlock, start and stop climate and charging), as
 * Android's toggles that show the car's state. Unlocking from a locked
 * phone asks for the phone to be unlocked first.
 */
abstract class VehicleTileService : TileService() {
    private val graph get() = (application as GhostBlueApplication).graph

    abstract fun look(vehicle: BBVehicle?): TileLook

    private suspend fun vehicle(): BBVehicle? =
        WidgetVehicles.resolve(graph.db.vehicles().all(), pinned = null, selected = graph.settings.selectedVin.value)

    override fun onStartListening() {
        graph.scope.launch { render(look(vehicle())) }
    }

    override fun onClick() {
        graph.scope.launch {
            val vehicle = vehicle() ?: return@launch
            val look = look(vehicle)
            val action = look.action ?: return@launch
            val send = {
                CommandWorker.enqueue(applicationContext, vehicle.vin, action)
                graph.scope.launch { render(look.copy(subtitle = "${action.progress}…")) }
            }
            withContext(Dispatchers.Main) {
                if (action.needsDeviceUnlock && isLocked) unlockAndRun { send() } else send()
            }
        }
    }

    private suspend fun render(look: TileLook) = withContext(Dispatchers.Main) {
        val tile = qsTile ?: return@withContext
        tile.label = look.label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = look.subtitle
        tile.icon = Icon.createWithResource(this@VehicleTileService, look.icon)
        tile.state = when {
            look.action == null -> Tile.STATE_UNAVAILABLE
            look.active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}

class LockTileService : VehicleTileService() {
    override fun look(vehicle: BBVehicle?): TileLook = lockTileLook(vehicle)
}

class ClimateTileService : VehicleTileService() {
    override fun look(vehicle: BBVehicle?): TileLook = climateTileLook(vehicle)
}

class ChargeTileService : VehicleTileService() {
    override fun look(vehicle: BBVehicle?): TileLook = chargeTileLook(vehicle)
}

fun lockTileLook(vehicle: BBVehicle?): TileLook {
    val locked = vehicle?.lockStatus == VehicleStatus.LockStatus.LOCKED
    return TileLook(
        label = if (locked) "Locked" else if (vehicle?.lockStatus == VehicleStatus.LockStatus.UNLOCKED) "Unlocked" else "Lock",
        subtitle = vehicle?.displayName ?: "No vehicle",
        active = locked,
        icon = if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
        action = vehicle?.let { if (locked) RemoteAction.UNLOCK else RemoteAction.LOCK },
    )
}

fun climateTileLook(vehicle: BBVehicle?): TileLook {
    val on = vehicle?.climateStatus?.airControlOn == true
    return TileLook(
        label = if (on) "Climate on" else "Climate",
        subtitle = vehicle?.displayName ?: "No vehicle",
        active = on,
        icon = R.drawable.ic_climate,
        action = vehicle?.let { if (on) RemoteAction.STOP_CLIMATE else RemoteAction.START_CLIMATE },
    )
}

/** Charging needs an EV that's plugged in (or charging); otherwise the tile is unavailable. */
fun chargeTileLook(vehicle: BBVehicle?): TileLook {
    val ev = vehicle?.evStatus
    val charging = ev?.charging == true
    return TileLook(
        label = when {
            charging -> "Charging ${ev?.evRange?.percentage?.toInt()}%"
            ev?.pluggedIn == true -> "Charge"
            else -> "Unplugged"
        },
        subtitle = vehicle?.displayName ?: "No vehicle",
        active = charging,
        icon = R.drawable.ic_bolt,
        action = when {
            ev == null -> null
            charging -> RemoteAction.STOP_CHARGE
            ev.pluggedIn -> RemoteAction.START_CHARGE
            else -> null
        },
    )
}

object VehicleTiles {
    private val services = listOf(LockTileService::class.java, ClimateTileService::class.java, ChargeTileService::class.java)

    /** Asks each tile showing in Quick Settings to re-read the car. */
    fun requestUpdate(context: Context) {
        for (service in services) runCatching { TileService.requestListeningState(context, ComponentName(context, service)) }
    }
}
