package com.saivenkatkrishna.ghostblue.notifications

import androidx.core.app.NotificationCompat
import com.saivenkatkrishna.ghostblue.data.charging.vehicleSampleDate
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.settings.AppSettings
import com.saivenkatkrishna.ghostblue.ui.components.Format
import com.saivenkatkrishna.ghostblue.ui.home.chargeTarget
import java.time.Instant

/** What the charging notification says, from the car's last status (iOS's charging Live Activity). */
data class ChargingProgressText(val title: String, val text: String, val percent: Int, val target: Int)

fun chargingProgressText(vehicle: BBVehicle, distanceUnit: Distance.Units, now: Instant = Instant.now()): ChargingProgressText? {
    val ev = vehicle.evStatus?.takeIf { it.charging } ?: return null
    val percent = ev.evRange.percentage.toInt()
    val target = chargeTarget(ev)
    val parts = buildList {
        add("$percent%")
        if (ev.chargeSpeed > 0) add("${Format.oneDecimal(ev.chargeSpeed)} kW")
        if (ev.chargeTimeSeconds > 0) add("${Format.duration(ev.chargeTimeSeconds.toDouble())} to $target%")
        // Anchored to the reading, as iOS's Live Activity and widgets do.
        val sample = vehicle.vehicleSampleDate
        if (sample != null && ev.chargeTimeSeconds > 60) {
            val finish = sample.plusSeconds(ev.chargeTimeSeconds)
            if (finish > now) add(Format.finishTime(finish, now))
        }
        add("${Format.distance(ev.evRange.range, distanceUnit)} est. range")
    }
    val plug = if (ev.plugType == VehicleStatus.PlugType.DC_CHARGER) "DC" else "AC"
    return ChargingProgressText("Charging · ${vehicle.displayName} · $plug", parts.joinToString(" · "), percent, target)
}

/**
 * An ongoing notification while a car charges, kept current by every status
 * update: Android's counterpart of iOS's charging Live Activity. Off unless
 * the owner turns on live progress, as iOS's beta switch.
 */
class ChargingProgress(private val notifier: Notifier, private val settings: AppSettings, private val db: GhostBlueDatabase) {
    suspend fun update(vin: String) {
        val vehicle = db.vehicles().byVin(vin)
        val tag = "$vin:charging"
        val text = vehicle?.let { chargingProgressText(it, settings.distanceUnit.value) }
        if (text == null || !settings.liveProgress.value || !notifier.allows()) {
            notifier.cancel(tag, Notifier.CHARGING_ID)
            return
        }
        val notification = notifier.notification(Channel.CHARGING, vin, text.title, text.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(false)
            .setProgress(100, text.percent, false)
            .setSubText("Target ${text.target}%")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText("${text.percent}%")
            .build()
        notifier.notify(tag, notification, Notifier.CHARGING_ID)
    }

    /** Any car charging with live progress on: refreshes come every five minutes, as iOS's wake-ups do. */
    suspend fun anyCharging(): Boolean = settings.liveProgress.value && db.vehicles().all().any { it.evStatus?.charging == true }
}
