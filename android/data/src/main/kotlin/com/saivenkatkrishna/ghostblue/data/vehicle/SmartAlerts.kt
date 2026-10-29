package com.saivenkatkrishna.ghostblue.data.vehicle

import com.saivenkatkrishna.ghostblue.data.charging.ChargingDisplayState
import com.saivenkatkrishna.ghostblue.data.charging.chargingDisplayState
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomationKind
import com.saivenkatkrishna.ghostblue.kit.model.KitJson
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import kotlin.math.floor
import kotlinx.serialization.Serializable

/** Where vehicle alerts go. The app installs one that posts notifications (phase 4); until then they are dropped. */
fun interface VehicleAlertSink {
    fun post(vin: String, title: String, body: String)
}

object VehicleAlerts {
    @Volatile
    var sink: VehicleAlertSink = VehicleAlertSink { _, _, _ -> }
}

/** What the alert rules compare between two statuses. */
@Serializable
internal data class VehicleAlertSnapshot(
    val charging: Boolean,
    val pluggedIn: Boolean,
    val batteryPercentage: Int? = null,
    val locked: Boolean,
    val hasOpenPanel: Boolean,
    val battery12V: Int? = null,
    val tireWarning: Boolean,
)

/** Swift's `.rounded()`: halves away from zero. */
internal fun swiftRounded(value: Double): Double = if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)

/**
 * Alerts on changes since the previous status (charge complete or
 * interrupted, unlocked, open panels, low batteries, tyre pressure), runs the
 * car's status automations and checks maintenance reminders. Port of iOS's
 * `evaluateSmartAlerts(with:)`.
 */
internal suspend fun BBVehicle.evaluateSmartAlerts(status: VehicleStatus, db: GhostBlueDatabase?, now: Instant = Instant.now()) {
    val snapshot = VehicleAlertSnapshot(
        charging = status.evStatus?.charging ?: false,
        pluggedIn = status.evStatus?.pluggedIn ?: false,
        batteryPercentage = status.evStatus?.let { swiftRounded(it.evRange.percentage).toInt() },
        locked = status.lockStatus == VehicleStatus.LockStatus.LOCKED,
        hasOpenPanel = (status.doorOpen?.anyOpen ?: false) || (status.trunkOpen ?: false) || (status.hoodOpen ?: false),
        battery12V = status.battery12V,
        tireWarning = status.tirePressureWarning?.hasWarning ?: false,
    )
    val previous = alertSnapshotData?.let {
        runCatching { KitJson.decodeFromString(VehicleAlertSnapshot.serializer(), it) }.getOrNull()
    }
    alertSnapshotData = KitJson.encodeToString(VehicleAlertSnapshot.serializer(), snapshot)
    evaluateMaintenanceReminders(db, now)
    if (previous == null) return

    val alerts = mutableListOf<Pair<String, String>>()
    val target = status.evStatus?.currentTargetSOC?.let { swiftRounded(it).toInt() }
    val percentage = snapshot.batteryPercentage ?: 100

    if (notifyChargeComplete && previous.charging && !snapshot.charging &&
        (target?.let { percentage >= it - 1 } ?: (percentage >= 99))
    ) {
        alerts += "Charging complete" to "$displayName reached $percentage%."
    } else if (notifyChargeInterrupted && previous.charging && !snapshot.charging && snapshot.pluggedIn &&
        chargingDisplayState(status.syncDate ?: status.lastUpdated) != ChargingDisplayState.SCHEDULED_PAUSE &&
        target?.let { percentage < it - 1 } == true
    ) {
        alerts += "Charging interrupted" to "$displayName stopped charging at $percentage%."
    }

    if (notifyUnlocked && previous.locked && !snapshot.locked) {
        alerts += "Vehicle unlocked" to "$displayName is now unlocked."
    }
    if (notifyOpenPanels && !previous.hasOpenPanel && snapshot.hasOpenPanel) {
        alerts += "Door or panel open" to "$displayName reports an open door, hood, or trunk."
    }
    val oldLevel = previous.batteryPercentage
    if (notifyLowEVBattery && oldLevel != null && oldLevel > lowEVBatteryThreshold && percentage <= lowEVBatteryThreshold) {
        alerts += "Low EV battery" to "$displayName is at $percentage%."
    }
    val old12V = previous.battery12V
    val new12V = snapshot.battery12V
    if (notifyLow12VBattery && old12V != null && new12V != null &&
        old12V > low12VBatteryThreshold && new12V <= low12VBatteryThreshold
    ) {
        alerts += "Low 12V battery" to "$displayName's 12V battery is at $new12V%."
    }
    if (notifyTirePressure && !previous.tireWarning && snapshot.tireWarning) {
        alerts += "Tire pressure warning" to "$displayName reports a tire pressure warning."
    }

    evaluateStatusAutomations(snapshot, previous, alerts, db, now)
    for ((title, body) in alerts) VehicleAlerts.sink.post(vin, title, body)
}

/** Announces open reminders whose odometer has been reached, once each. */
suspend fun BBVehicle.evaluateMaintenanceReminders(db: GhostBlueDatabase?, now: Instant = Instant.now()) {
    db ?: return
    for (reminder in db.maintenanceReminders().pendingForVin(vin)) {
        val dueOdometer = reminder.dueOdometer ?: continue
        if (odometer.length < dueOdometer) continue
        VehicleAlerts.sink.post(vin, "Maintenance due", "${reminder.title} is due for $displayName.")
        reminder.lastNotifiedAt = now
        db.maintenanceReminders().update(reminder)
    }
}

private suspend fun BBVehicle.evaluateStatusAutomations(
    snapshot: VehicleAlertSnapshot,
    previous: VehicleAlertSnapshot,
    alerts: MutableList<Pair<String, String>>,
    db: GhostBlueDatabase?,
    now: Instant,
) {
    db ?: return
    for (rule in db.automations().enabledForVin(vin)) {
        val body = when (rule.kind) {
            VehicleAutomationKind.LOW_BATTERY -> {
                val old = previous.batteryPercentage ?: 100
                val new = snapshot.batteryPercentage ?: 100
                if (old > rule.threshold && new <= rule.threshold) {
                    "$displayName is at $new%, at or below your ${rule.threshold}% rule."
                } else {
                    null
                }
            }
            VehicleAutomationKind.UNPLUGGED ->
                if (previous.pluggedIn && !snapshot.pluggedIn) {
                    "$displayName was unplugged" + (snapshot.batteryPercentage?.let { " at $it%." } ?: ".")
                } else {
                    null
                }
            // Time-based; scheduled separately.
            VehicleAutomationKind.PRECONDITION_REMINDER -> null
        } ?: continue
        rule.lastTriggeredAt = now
        db.automations().update(rule)
        alerts += rule.title to body
    }
}
