package com.saivenkatkrishna.ghostblue.background

import android.content.Context
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
        val tag = "$vin:command"

        fun show(text: String, ongoing: Boolean) {
            notifier.notify(tag, progressNotification(name, action, text, ongoing), Notifier.COMMAND_ID)
        }

        return try {
            setForegroundSafely(name, action)
            controller.perform(vin, action, graph.commandWaitSeconds) { show(it, ongoing = true) }
            show(action.done, ongoing = false)
            graph.refreshWidgets()
            Result.success()
        } catch (error: CancellationException) {
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
        private const val RESULT_TIMEOUT_MILLIS = 15_000L

        /** One command per car at a time; a new one replaces a waiting one. */
        fun enqueue(context: Context, vin: String, action: RemoteAction) {
            val request = OneTimeWorkRequestBuilder<CommandWorker>()
                .setInputData(workDataOf(KEY_VIN to vin, KEY_ACTION to action.name))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("command-$vin", ExistingWorkPolicy.REPLACE, request)
        }

        const val TAG = "remote-command"
    }
}
