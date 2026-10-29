package com.saivenkatkrishna.ghostblue.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.saivenkatkrishna.ghostblue.GhostBlueApplication
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.MaintenanceReminder
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomation
import com.saivenkatkrishna.ghostblue.data.model.VehicleAutomationKind
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.launch

/** A reminder due at a time: a daily precondition reminder or a maintenance item with a date. */
sealed interface DueReminder {
    val at: Instant

    data class Precondition(val automation: VehicleAutomation, override val at: Instant) : DueReminder
    data class Maintenance(val reminder: MaintenanceReminder, override val at: Instant) : DueReminder
}

/**
 * The time-based reminders iOS schedules: each enabled precondition
 * reminder daily at its time (`AutomationReminderScheduler`), and dated
 * maintenance at 9 AM on its day (odometer-due ones are checked as statuses
 * arrive, in the data layer). One alarm is kept, for the soonest. Unlike
 * iOS, a reminder completed or deleted is dropped from the schedule.
 */
object ReminderScheduler {
    private const val REQUEST_CODE = 4200

    /** iOS notifies dated maintenance at 9 AM local on the due day. */
    const val MAINTENANCE_HOUR = 9

    fun maintenanceTime(dueDate: Instant, zone: ZoneId): Instant =
        dueDate.atZone(zone).toLocalDate().atTime(MAINTENANCE_HOUR, 0).atZone(zone).toInstant()

    /** Today's [hour]:[minute] if still ahead of [now], else tomorrow's. */
    fun nextDaily(hour: Int, minute: Int, now: Instant, zone: ZoneId): Instant {
        val local = now.atZone(zone)
        val today = local.toLocalDate().atTime(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))).atZone(zone)
        return (if (today.toInstant() > now) today else today.plusDays(1)).toInstant()
    }

    /** Every reminder's next time, soonest first. */
    suspend fun upcoming(db: GhostBlueDatabase, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<DueReminder> {
        val preconditions = db.automations().all()
            .filter { it.isEnabled && it.kind == VehicleAutomationKind.PRECONDITION_REMINDER }
            .map { automation ->
                val next = nextDaily(automation.hour, automation.minute, now, zone)
                // Already posted for this time (an early alarm): the day after.
                val posted = automation.lastTriggeredAt?.let { it > next.minusSeconds(12 * 3600) } == true
                DueReminder.Precondition(automation, if (posted) next.plusSeconds(24 * 3600) else next)
            }
        val maintenance = db.maintenanceReminders().all()
            .filter { !it.isCompleted && it.lastNotifiedAt == null }
            .mapNotNull { reminder -> reminder.dueDate?.let { DueReminder.Maintenance(reminder, maxOf(maintenanceTime(it, zone), now)) } }
        return (preconditions + maintenance).filter { db.vehicles().byVin(vinOf(it)) != null }.sortedBy { it.at }
    }

    suspend fun schedule(context: Context) {
        val graph = (context.applicationContext as GhostBlueApplication).graph
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReminderAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = upcoming(graph.db).firstOrNull()
        if (next == null) {
            alarms.cancel(intent)
            return
        }
        if (SmartChargingScheduler.canScheduleExact(alarms)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at.toEpochMilli(), intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at.toEpochMilli(), intent)
        }
    }

    /** Reminders due at [now]: today's precondition times up to two hours late, and dated maintenance. */
    suspend fun due(db: GhostBlueDatabase, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<DueReminder> {
        // Alarms can arrive a little early, or late when the phone dozes.
        val early = now.plusSeconds(60)
        val late = now.minusSeconds(2 * 3600)
        val today = now.atZone(zone).toLocalDate()
        val preconditions = db.automations().all()
            .filter { it.isEnabled && it.kind == VehicleAutomationKind.PRECONDITION_REMINDER }
            .mapNotNull { automation ->
                val at = today.atTime(LocalTime.of(automation.hour.coerceIn(0, 23), automation.minute.coerceIn(0, 59))).atZone(zone).toInstant()
                val recentlyPosted = automation.lastTriggeredAt?.let { it > now.minusSeconds(12 * 3600) } == true
                if (at in late..early && !recentlyPosted) DueReminder.Precondition(automation, at) else null
            }
        val maintenance = db.maintenanceReminders().all()
            .filter { !it.isCompleted && it.lastNotifiedAt == null }
            .mapNotNull { reminder ->
                reminder.dueDate?.let { maintenanceTime(it, zone) }?.takeIf { it <= early }?.let { DueReminder.Maintenance(reminder, it) }
            }
        return (preconditions + maintenance).filter { db.vehicles().byVin(vinOf(it)) != null }
    }

    /** Posts what's due and marks it, so it posts once; returns how many were posted. */
    suspend fun deliverDue(context: Context, now: Instant = Instant.now()): Int {
        val graph = (context.applicationContext as GhostBlueApplication).graph
        val db = graph.db
        val due = due(db, now)
        for (reminder in due) {
            when (reminder) {
                is DueReminder.Precondition -> {
                    val automation = reminder.automation
                    val name = db.vehicles().byVin(automation.vin)?.displayName ?: "your vehicle"
                    graph.notifier.postReminder(
                        automation.vin,
                        automation.title.ifBlank { "Precondition $name" },
                        "Open GhostBlue to review conditions and start climate.",
                        tag = "automation-${automation.id}",
                    )
                    automation.lastTriggeredAt = now
                    db.automations().update(automation)
                }
                is DueReminder.Maintenance -> {
                    val item = reminder.reminder
                    val name = db.vehicles().byVin(item.vin)?.displayName ?: "your vehicle"
                    graph.notifier.postReminder(item.vin, "Maintenance due", "${item.title} is due for $name.", tag = "maintenance-${item.id}")
                    item.lastNotifiedAt = now
                    db.maintenanceReminders().update(item)
                }
            }
        }
        return due.size
    }
}

private fun vinOf(reminder: DueReminder): String = when (reminder) {
    is DueReminder.Precondition -> reminder.automation.vin
    is DueReminder.Maintenance -> reminder.reminder.vin
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "reminders",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReminderWorker>().build(),
        )
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ReminderScheduler.deliverDue(applicationContext)
        ReminderScheduler.schedule(applicationContext)
        return Result.success()
    }
}

/** Alarms don't survive a reboot or an app update, and a clock change moves them: set them again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as GhostBlueApplication).graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                graph.rescheduleBackgroundWork()
            } finally {
                pending.finish()
            }
        }
    }
}
