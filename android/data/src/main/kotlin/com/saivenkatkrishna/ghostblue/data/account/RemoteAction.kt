package com.saivenkatkrishna.ghostblue.data.account

import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus

/** The commands widgets, tiles, the watch and notifications can send, as iOS's App Intents do. */
enum class RemoteAction(val title: String, val progress: String, val done: String) {
    LOCK("Lock", "Locking", "Locked"),
    UNLOCK("Unlock", "Unlocking", "Unlocked"),
    START_CLIMATE("Start climate", "Starting climate", "Climate on"),
    STOP_CLIMATE("Stop climate", "Stopping climate", "Climate off"),
    START_CHARGE("Start charging", "Starting charging", "Charging"),
    STOP_CHARGE("Stop charging", "Stopping charging", "Charging stopped");

    /** What the car must report for the command to count as done. */
    fun isConfirmed(status: VehicleStatus): Boolean = when (this) {
        LOCK -> status.lockStatus == VehicleStatus.LockStatus.LOCKED
        UNLOCK -> status.lockStatus == VehicleStatus.LockStatus.UNLOCKED
        START_CLIMATE -> status.climateStatus.airControlOn
        STOP_CLIMATE -> !status.climateStatus.airControlOn
        START_CHARGE -> status.evStatus?.charging == true
        STOP_CHARGE -> status.evStatus?.charging == false
    }

    /** The control it belongs to: commands for different controls of a car run side by side. */
    val control: String get() = when (this) {
        LOCK, UNLOCK -> "lock"
        START_CLIMATE, STOP_CLIMATE -> "climate"
        START_CHARGE, STOP_CHARGE -> "charge"
    }

    /** Unlocking from a locked phone needs the owner to unlock it first. */
    val needsDeviceUnlock: Boolean get() = this == UNLOCK

    companion object {
        fun fromName(name: String?): RemoteAction? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Sends [action] and waits for the car to report it, reporting progress as
 * iOS's intents do ("Command sent", "Waiting for vehicle (1/3)"). Climate
 * starts with the selected preset, as everywhere else.
 */
suspend fun AccountController.perform(
    vin: String,
    action: RemoteAction,
    waitSeconds: Int = 10,
    onProgress: (String) -> Unit = {},
) {
    onProgress("Sending command...")
    when (action) {
        RemoteAction.LOCK -> lockVehicle(vin)
        RemoteAction.UNLOCK -> unlockVehicle(vin)
        RemoteAction.START_CLIMATE -> startClimate(vin)
        RemoteAction.STOP_CLIMATE -> stopClimate(vin)
        RemoteAction.START_CHARGE -> startCharge(vin)
        RemoteAction.STOP_CHARGE -> stopCharge(vin)
    }
    waitForStatusChange(
        vin,
        condition = action::isConfirmed,
        statusMessageUpdater = onProgress,
        initialDelaySeconds = waitSeconds,
        retryDelaySeconds = waitSeconds,
    )
}

/** True when the command reached the car but the car didn't confirm in time. */
val Throwable.isUnconfirmed: Boolean
    get() = this is APIError && message.startsWith("Status change condition not met")
