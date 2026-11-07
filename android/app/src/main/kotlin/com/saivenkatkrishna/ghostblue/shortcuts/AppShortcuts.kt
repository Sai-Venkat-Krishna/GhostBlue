package com.saivenkatkrishna.ghostblue.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.saivenkatkrishna.ghostblue.R
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger

/** A dynamic launcher shortcut: a command for one car, with a preset for climate. */
data class ShortcutSpec(
    val id: String,
    val shortLabel: String,
    val longLabel: String,
    val icon: Int,
    val command: ShortcutCommand,
    val vin: String,
    val presetId: String? = null,
)

/**
 * The shortcuts the app adds next to the four static ones (Lock, Unlock,
 * Start climate and Refresh, in `res/xml/shortcuts.xml`, for whichever car
 * is selected), for the selected car: Start climate with its selected
 * preset and Stop climate first (launchers show two dynamic shortcuts
 * beside the first two static ones, so the long-press menu reads Lock,
 * Unlock, Start climate, Stop climate), then charging and a Start for each
 * of its other climate presets (iOS's Start Climate Control with a
 * preset). They name their car, so one pinned to the home screen keeps
 * acting on it.
 */
object AppShortcuts {
    /** Static shortcuts count against the launcher's limit. */
    private const val STATIC_COUNT = 4

    fun dynamicFor(vehicle: BBVehicle?, presets: List<ClimatePreset>): List<ShortcutSpec> {
        vehicle ?: return emptyList()
        val name = vehicle.displayName
        val vin = vehicle.vin
        val selected = presets.firstOrNull { it.isSelected }
        val specs = mutableListOf(
            ShortcutSpec(
                "climate_selected",
                "Start climate",
                selected?.let { "Start ${it.name} · $name" } ?: "Start climate · $name",
                R.drawable.shortcut_climate,
                ShortcutCommand.START_CLIMATE,
                vin,
                selected?.id?.toString(),
            ),
            ShortcutSpec("stop_climate", "Stop climate", "Stop climate · $name", R.drawable.shortcut_climate, ShortcutCommand.STOP_CLIMATE, vin),
        )
        if (vehicle.evStatus != null) {
            specs += ShortcutSpec("start_charge", "Start charging", "Start charging · $name", R.drawable.shortcut_bolt, ShortcutCommand.START_CHARGE, vin)
            specs += ShortcutSpec("stop_charge", "Stop charging", "Stop charging · $name", R.drawable.shortcut_bolt, ShortcutCommand.STOP_CHARGE, vin)
        }
        presets.filter { it.id != selected?.id }.sortedBy { it.sortOrder }.forEach { preset ->
            specs += ShortcutSpec(
                "preset_${preset.id}",
                preset.name.take(SHORT_LABEL_LIMIT).ifBlank { "Climate" },
                "Start ${preset.name} · $name",
                R.drawable.shortcut_climate,
                ShortcutCommand.START_CLIMATE,
                vin,
                preset.id.toString(),
            )
        }
        return specs
    }

    fun intent(context: Context, spec: ShortcutSpec): Intent =
        Intent(ShortcutRequest.ACTION_SHORTCUT)
            .setClassName(context, ShortcutActivity::class.java.name)
            .putExtra(ShortcutRequest.EXTRA_COMMAND, spec.command.id)
            .putExtra(ShortcutRequest.EXTRA_VIN, spec.vin)
            .putExtra(ShortcutActivity.EXTRA_SHORTCUT_ID, spec.id)
            .apply { spec.presetId?.let { putExtra(ShortcutRequest.EXTRA_PRESET, it) } }

    /**
     * Replaces the dynamic shortcuts. Pinned copies stay and act on their car; one whose preset
     * was deleted since says so when tapped.
     */
    fun publish(context: Context, specs: List<ShortcutSpec>) {
        val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - STATIC_COUNT).coerceAtLeast(0)
        val infos = specs.take(room).mapIndexed { rank, spec ->
            ShortcutInfoCompat.Builder(context, spec.id)
                .setShortLabel(spec.shortLabel)
                .setLongLabel(spec.longLabel)
                .setIcon(IconCompat.createWithResource(context, spec.icon))
                .setIntent(intent(context, spec))
                .setRank(rank)
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, infos) }
            .onFailure { BBLogger.warning(BBLogCategory.APP, "Couldn't update shortcuts: $it") }
    }

    private const val SHORT_LABEL_LIMIT = 25
}
