package com.saivenkatkrishna.ghostblue.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.saivenkatkrishna.ghostblue.GhostBlueApplication
import com.saivenkatkrishna.ghostblue.data.account.SmartChargingReconciliation
import com.saivenkatkrishna.ghostblue.data.charging.SDGESmartChargingSchedule
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.ui.commandErrorMessage
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs smart charging at each SDG&E schedule boundary, which iOS leaves to
 * Shortcuts automations at 6 AM and 10 AM: an alarm just after the next
 * boundary wakes [SmartChargingWorker], which reconciles every car that has
 * smart charging on, posts what changed and sets the next alarm. Exact when
 * the owner allows exact alarms, otherwise as close as Android allows.
 */
object SmartChargingScheduler {
    private const val REQUEST_CODE = 4100

    /** Seconds after a boundary to run, so the car's own schedule has switched first. */
    const val SETTLE_SECONDS = 60L

    /** The next run: just after the next boundary, or null with no car using smart charging. */
    suspend fun nextRun(db: GhostBlueDatabase, now: Instant = Instant.now()): Instant? {
        if (db.vehicles().all().none { it.smartChargingEnabled }) return null
        val boundary = SDGESmartChargingSchedule.transitions(now, count = 1).firstOrNull()?.date ?: return null
        return boundary.plusSeconds(SETTLE_SECONDS)
    }

    suspend fun schedule(context: Context) {
        val graph = (context.applicationContext as GhostBlueApplication).graph
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = pendingIntent(context)
        val next = nextRun(graph.db)
        if (next == null) {
            alarms.cancel(intent)
            return
        }
        if (canScheduleExact(alarms)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), intent)
        }
    }

    fun canScheduleExact(alarms: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, SmartChargingAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SmartChargingWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("smart-charging", ExistingWorkPolicy.KEEP, request)
    }
}

class SmartChargingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SmartChargingScheduler.runNow(context)
}

class SmartChargingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph get() = (applicationContext as GhostBlueApplication).graph

    override suspend fun doWork(): Result {
        try {
            for (vehicle in graph.db.vehicles().all().filter { it.smartChargingEnabled }) {
                val controller = vehicle.ownerAccountId?.let(graph.controllers::controller) ?: continue
                try {
                    val result = controller.reconcileSmartCharging(vehicle.vin)
                    // A change is news; "already correct" and skips stay in the car's last-result line.
                    if (result.action == SmartChargingReconciliation.Action.STARTED ||
                        result.action == SmartChargingReconciliation.Action.STOPPED
                    ) {
                        graph.notifier.postSmartCharging(vehicle.vin, "Smart charging · ${vehicle.displayName}", result.message)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    graph.notifier.postSmartCharging(
                        vehicle.vin,
                        "Smart charging · ${vehicle.displayName}",
                        "The scheduled check failed: ${commandErrorMessage(error)}",
                    )
                }
            }
            graph.refreshWidgets()
        } finally {
            withContext(NonCancellable) { SmartChargingScheduler.schedule(applicationContext) }
        }
        return Result.success()
    }
}
