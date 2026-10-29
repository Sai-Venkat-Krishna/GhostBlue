package com.saivenkatkrishna.ghostblue.background

import android.content.Context
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.data.account.isUnconfirmed
import com.saivenkatkrishna.ghostblue.data.account.perform
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.saivenkatkrishna.ghostblue.GhostBlueApplication
import com.saivenkatkrishna.ghostblue.notifications.Channel
import com.saivenkatkrishna.ghostblue.notifications.Notifier
import com.saivenkatkrishna.ghostblue.ui.commandErrorMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * A command from a widget, tile or notification. It runs as expedited work
 * so it finishes after the widget or tile has gone, and shows its progress
 * in an ongoing notification until the car confirms: Android's version of
 * the Live Activity iOS shows for the same commands.
 */
class CommandWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph get() = (applicationContext as GhostBlueApplication).graph

    override suspend fun doWork(): Result {
        val vin = inputData.getString(KEY_VIN) ?: return Result.failure()
        val action = RemoteAction.fromName(inputData.getString(KEY_ACTION)) ?: return Result.failure()
        val vehicle = graph.db.vehicles().byVin(vin) ?: return Result.failure()
        val controller = vehicle.ownerAccountId?.let(graph.controllers::controller) ?: return Result.failure()
        val name = vehicle.displayName
        val notifier = graph.notifier
        val tag = "$vin:command:${action.control}"

        fun show(text: String, ongoing: Boolean) {
            notifier.notify(tag, progressNotification(name, action, text, ongoing), Notifier.COMMAND_ID)
        }

        // Work out of expedited quota, or re-run after the process died, can start much later:
        // the car may have changed since, so an old tap isn't sent.
        val enqueuedAt = inputData.getLong(KEY_ENQUEUED_AT, 0)
        if (System.currentTimeMillis() - enqueuedAt > EXPIRY_MILLIS) {
            show("Not sent: it waited too long. Tap again to send it.", ongoing = false)
            return Result.failure()
        }

        return try {
            setForegroundSafely(name, action)
            controller.perform(vin, action, graph.commandWaitSeconds) { show(it, ongoing = true) }
            show(action.done, ongoing = false)
            graph.refreshWidgets()
            Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                // Stopped: leave no spinner behind, unless a newer command for this control took over the notification.
                val replaced = WorkManager.getInstance(applicationContext)
                    .getWorkInfosForUniqueWorkFlow(uniqueName(vin, action)).first()
                    .any { it.id != id && !it.state.isFinished }
                if (!replaced) notifier.cancel(tag, Notifier.COMMAND_ID)
            }
            throw error
        } catch (error: Exception) {
            show(
                if (error.isUnconfirmed) "Sent, but the car hasn't confirmed yet." else commandErrorMessage(error),
                ongoing = false,
            )
            graph.refreshWidgets()
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val vin = inputData.getString(KEY_VIN)
        val name = vin?.let { graph.db.vehicles().byVin(it)?.displayName } ?: "Vehicle"
        val action = RemoteAction.fromName(inputData.getString(KEY_ACTION)) ?: RemoteAction.LOCK
        return foregroundInfo(progressNotification(name, action, "Sending command...", ongoing = true))
    }

    private suspend fun setForegroundSafely(name: String, action: RemoteAction) {
        // Expedited work on Android 12+ needs no foreground service; older versions run it as one.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        runCatching { setForeground(foregroundInfo(progressNotification(name, action, "Sending command...", ongoing = true))) }
    }

    private fun foregroundInfo(notification: android.app.Notification) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.COMMAND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.COMMAND_ID, notification)
        }

    private fun progressNotification(name: String, action: RemoteAction, text: String, ongoing: Boolean) =
        graph.notifier.notification(Channel.COMMANDS, inputData.getString(KEY_VIN), if (ongoing) "${action.progress} $name" else name, text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (ongoing) setProgress(0, 0, true) else setTimeoutAfter(RESULT_TIMEOUT_MILLIS)
            }
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        const val KEY_VIN = "vin"
        const val KEY_ACTION = "action"
        const val KEY_ENQUEUED_AT = "enqueuedAt"
        private const val RESULT_TIMEOUT_MILLIS = 15_000L
        private const val EXPIRY_MILLIS = 2 * 60_000L

        /** One command per control of a car at a time, as in the app: Unlock replaces a waiting Lock, but not climate. */
        fun uniqueName(vin: String, action: RemoteAction) = "command-$vin-${action.control}"

        fun enqueue(context: Context, vin: String, action: RemoteAction) {
            val request = OneTimeWorkRequestBuilder<CommandWorker>()
                .setInputData(workDataOf(KEY_VIN to vin, KEY_ACTION to action.name, KEY_ENQUEUED_AT to System.currentTimeMillis()))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(vin, action), ExistingWorkPolicy.REPLACE, request)
        }

        const val TAG = "remote-command"
    }
}
