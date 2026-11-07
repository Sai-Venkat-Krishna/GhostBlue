package com.saivenkatkrishna.ghostblue.shortcuts

import android.content.Context
import android.content.Intent
import com.saivenkatkrishna.ghostblue.background.CommandWorker
import com.saivenkatkrishna.ghostblue.background.LiveRefreshWorker
import com.saivenkatkrishna.ghostblue.background.SmartChargingScheduler
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset

/**
 * What a launcher shortcut, Google Assistant or an automation app can ask
 * for: iOS's App Intents in `Widget/VehicleAppIntents.swift` (lock, unlock,
 * climate with a preset, charging, refresh) and `SmartChargingIntent.swift`.
 * [id] is the value of the intent's `command` extra; [aliases] are other
 * spellings Tasker users or Assistant might send.
 */
enum class ShortcutCommand(val id: String, val action: RemoteAction?, val title: String, val aliases: Set<String> = emptySet()) {
    LOCK("lock", RemoteAction.LOCK, "Lock", setOf("lock_vehicle", "lock_car")),
    UNLOCK("unlock", RemoteAction.UNLOCK, "Unlock", setOf("unlock_vehicle", "unlock_car")),
    START_CLIMATE("start_climate", RemoteAction.START_CLIMATE, "Start climate", setOf("climate", "climate_on", "start_vehicle_climate")),
    STOP_CLIMATE("stop_climate", RemoteAction.STOP_CLIMATE, "Stop climate", setOf("climate_off")),
    START_CHARGE("start_charge", RemoteAction.START_CHARGE, "Start charging", setOf("start_charging", "charge")),
    STOP_CHARGE("stop_charge", RemoteAction.STOP_CHARGE, "Stop charging", setOf("stop_charging")),
    REFRESH("refresh", null, "Refresh", setOf("refresh_status", "status", "update")),
    SMART_CHARGING("smart_charging", null, "Check smart charging", setOf("reconcile_smart_charging", "apply_charging_schedule"));

    companion object {
        fun parse(raw: String?): ShortcutCommand? {
            val key = raw?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_')?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.id == key || it.name.lowercase() == key || key in it.aliases }
        }
    }
}

/** A command with, optionally, the car (VIN or name) and climate preset (name or id) it's for. */
data class ShortcutRequest(val command: ShortcutCommand, val vehicle: String? = null, val preset: String? = null) {
    companion object {
        /** GhostBlue's own launcher shortcuts. */
        const val ACTION_SHORTCUT = "com.saivenkatkrishna.ghostblue.action.SHORTCUT"

        /** The public command intent, for Assistant and automation apps. */
        const val ACTION_COMMAND = "com.saivenkatkrishna.ghostblue.action.COMMAND"

        const val EXTRA_COMMAND = "command"

        /** A VIN, or a car's name as GhostBlue shows it. */
        const val EXTRA_VEHICLE = "vehicle"
        const val EXTRA_VIN = "vin"

        /** A climate preset's name or id, for start_climate. */
        const val EXTRA_PRESET = "preset"

        /** App Actions' `OPEN_APP_FEATURE` parameter. */
        private const val EXTRA_FEATURE = "feature"

        fun from(intent: Intent): ShortcutRequest? {
            val command = ShortcutCommand.parse(intent.getStringExtra(EXTRA_COMMAND) ?: intent.getStringExtra(EXTRA_FEATURE))
                ?: return null
            return ShortcutRequest(
                command,
                vehicle = (intent.getStringExtra(EXTRA_VIN) ?: intent.getStringExtra(EXTRA_VEHICLE))?.trim()?.takeIf { it.isNotEmpty() },
                preset = intent.getStringExtra(EXTRA_PRESET)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** What a request comes to once the car and preset are found, or why it can't run. */
sealed interface ShortcutPlan {
    data class Command(val vehicle: BBVehicle, val action: RemoteAction, val preset: ClimatePreset? = null) : ShortcutPlan
    data class Refresh(val vehicle: BBVehicle) : ShortcutPlan
    data class SmartCharging(val vehicles: List<BBVehicle>) : ShortcutPlan
    data class Refused(val message: String) : ShortcutPlan
}

object ShortcutPlanner {
    /**
     * The car is the one named, else the car selected in the app, else the
     * first, as widgets and tiles choose. A named preset must belong to it;
     * without one, Start climate uses the selected preset (iOS's Start
     * Vehicle Climate). Smart charging checks every car that has it on, as
     * iOS's Reconcile Smart Charging does.
     */
    suspend fun plan(request: ShortcutRequest, db: GhostBlueDatabase, selectedVin: String?): ShortcutPlan {
        val all = db.vehicles().all()
        val visible = all.filter { !it.isHidden }
        if (visible.isEmpty()) return ShortcutPlan.Refused("Sign in to GhostBlue first.")
        if (request.command == ShortcutCommand.SMART_CHARGING) {
            val enabled = all.filter { it.smartChargingEnabled }
            return if (enabled.isEmpty()) ShortcutPlan.Refused("No vehicles have Smart Charging enabled in GhostBlue.")
            else ShortcutPlan.SmartCharging(enabled)
        }
        val vehicle = when (val named = request.vehicle) {
            null -> visible.firstOrNull { it.vin == selectedVin } ?: visible.first()
            else -> all.firstOrNull { it.vin.equals(named, ignoreCase = true) }
                ?: visible.firstOrNull { it.displayName.equals(named, ignoreCase = true) }
                ?: return ShortcutPlan.Refused("GhostBlue has no car named “$named”.")
        }
        if (request.command == ShortcutCommand.REFRESH) return ShortcutPlan.Refresh(vehicle)
        val action = request.command.action ?: return ShortcutPlan.Refused("GhostBlue didn't recognise that command.")
        if ((action == RemoteAction.START_CHARGE || action == RemoteAction.STOP_CHARGE) && vehicle.evStatus == null) {
            return ShortcutPlan.Refused("${vehicle.displayName} doesn't report charging.")
        }
        val preset = request.preset?.takeIf { action == RemoteAction.START_CLIMATE }?.let { named ->
            val presets = db.climatePresets().forVehicle(vehicle.id)
            presets.firstOrNull { it.id.toString().equals(named, ignoreCase = true) }
                ?: presets.firstOrNull { it.name.equals(named, ignoreCase = true) }
                ?: return ShortcutPlan.Refused("${vehicle.displayName} has no climate preset named “$named”.")
        }
        return ShortcutPlan.Command(vehicle, action, preset)
    }

    /**
     * Hands the plan to the same background work widgets use, so a command
     * waits for the car and shows its progress in a notification. Returns
     * what to tell the owner.
     */
    fun run(context: Context, plan: ShortcutPlan): String = when (plan) {
        is ShortcutPlan.Command -> {
            CommandWorker.enqueue(context, plan.vehicle.vin, plan.action, plan.preset?.id?.toString())
            val preset = plan.preset?.let { " (${it.name})" }.orEmpty()
            "${plan.action.progress}$preset · ${plan.vehicle.displayName}"
        }
        is ShortcutPlan.Refresh -> {
            LiveRefreshWorker.enqueue(context, plan.vehicle.vin)
            "Refreshing ${plan.vehicle.displayName}"
        }
        is ShortcutPlan.SmartCharging -> {
            SmartChargingScheduler.runNow(context)
            if (plan.vehicles.size == 1) "Checking smart charging · ${plan.vehicles.single().displayName}"
            else "Checking smart charging for ${plan.vehicles.size} cars"
        }
        is ShortcutPlan.Refused -> plan.message
    }
}
