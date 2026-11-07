package com.saivenkatkrishna.ghostblue.ui.settings

import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.MaintenanceReminder
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import java.text.NumberFormat
import com.saivenkatkrishna.ghostblue.ui.components.Format
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * A maintenance reminder being added or edited: iOS's
 * `AddMaintenanceReminderView` (title, notes, a due date, an odometer
 * target, a cost), which Android also uses to change an existing one.
 */
data class MaintenanceDraft(
    val title: String = "",
    val notes: String = "",
    val hasDueDate: Boolean = true,
    val dueDate: LocalDate,
    val hasOdometer: Boolean = false,
    val odometerText: String = "",
    val costText: String = "",
) {
    val odometer: Double? get() = Format.parseDecimal(odometerText)?.takeIf { it.isFinite() && it >= 0 }

    val cost: Double? get() = Format.parseDecimal(costText)?.takeIf { it.isFinite() && it >= 0 }

    /** Why it can't be saved yet, or null. */
    val validationMessage: String?
        get() = when {
            title.isBlank() -> "Name the service or task."
            hasOdometer && odometer == null -> "Enter the odometer reading it's due at."
            costText.isNotBlank() && cost == null -> "Enter the cost as a number, or leave it empty."
            else -> null
        }

    /**
     * Writes the draft into [reminder]. A due date is stored at noon, so it
     * stays the same day in nearby time zones. Moving the date or odometer
     * target makes it due again, so it notifies again.
     */
    fun applyTo(reminder: MaintenanceReminder, zone: ZoneId = ZoneId.systemDefault()) {
        val newDue = if (hasDueDate) dueDate.atTime(LocalTime.NOON).atZone(zone).toInstant() else null
        val newOdometer = if (hasOdometer) odometer else null
        val sameDay = reminder.dueDate?.atZone(zone)?.toLocalDate() == newDue?.atZone(zone)?.toLocalDate()
        if (!sameDay || reminder.dueOdometer != newOdometer) reminder.lastNotifiedAt = null
        // An unchanged day keeps its stored time (iOS's own, from a backup).
        if (!sameDay) reminder.dueDate = newDue
        reminder.title = title.trim()
        reminder.notes = notes.trim()
        reminder.dueOdometer = newOdometer
        reminder.cost = cost
    }

    companion object {
        /** iOS's defaults: due in six months, or 7,500 past the current odometer. */
        fun forNew(vehicle: BBVehicle, today: LocalDate = LocalDate.now()) = MaintenanceDraft(
            dueDate = today.plusMonths(6),
            odometerText = (vehicle.odometer.length + 7_500).roundToLong().toString(),
        )

        fun forReminder(reminder: MaintenanceReminder, vehicle: BBVehicle, zone: ZoneId = ZoneId.systemDefault()) = MaintenanceDraft(
            title = reminder.title,
            notes = reminder.notes,
            hasDueDate = reminder.dueDate != null,
            dueDate = reminder.dueDate?.atZone(zone)?.toLocalDate() ?: LocalDate.now(zone).plusMonths(6),
            hasOdometer = reminder.dueOdometer != null,
            odometerText = (reminder.dueOdometer ?: (vehicle.odometer.length + 7_500)).roundToLong().toString(),
            costText = reminder.cost?.let { Format.decimals(it, 2).replace(',', '.') } ?: "",
        )
    }
}

/** iOS's Quick add list: common services, due by distance from the current odometer or by months from now. */
enum class MaintenancePreset(val title: String, val distanceDelta: Double? = null, val monthDelta: Long? = null) {
    TIRE_ROTATION("Tire rotation", distanceDelta = 7_500.0),
    CABIN_AIR_FILTER("Cabin air filter", distanceDelta = 15_000.0),
    ANNUAL_INSPECTION("Annual inspection", monthDelta = 12),
    REGISTRATION_RENEWAL("Registration renewal", monthDelta = 12),
    INSURANCE_RENEWAL("Insurance renewal", monthDelta = 6);

    /** In the odometer's own units, as iOS adds the distance to the reading as it is. */
    fun subtitle(units: Distance.Units): String =
        distanceDelta?.let { "In ${NumberFormat.getIntegerInstance().format(it)} ${units.abbreviation}" } ?: "In $monthDelta months"

    fun reminder(vehicle: BBVehicle, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) = MaintenanceReminder(
        vin = vehicle.vin,
        title = title,
        dueDate = monthDelta?.let { now.atZone(zone).plusMonths(it).toInstant() },
        dueOdometer = distanceDelta?.let { vehicle.odometer.length + it },
    )
}
