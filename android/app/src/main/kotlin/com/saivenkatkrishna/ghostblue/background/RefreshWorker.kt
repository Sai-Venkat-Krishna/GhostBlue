package com.saivenkatkrishna.ghostblue.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.saivenkatkrishna.ghostblue.GhostBlueApplication
import com.saivenkatkrishna.ghostblue.data.charging.VehicleRefreshPolicy
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.settings.WidgetRefreshInterval
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Keeps the cars' status current while the app is closed, for widgets,
 * tiles and alerts: iOS's widget timeline reloads. Every widget refresh
 * interval (at most ten minutes apart while a car is plugged in, as
 * `VehicleRefreshPolicy` says), each car gets a cached read; fresh data is
 * left alone so the modem isn't woken. Applying a status runs the alert
 * rules and archives trips, so this is also what notifies.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph get() = (applicationContext as GhostBlueApplication).graph

    override suspend fun doWork(): Result {
        val forced = inputData.getBoolean(KEY_FORCED, false)
        val interval = graph.settings.widgetRefreshInterval.value
        val now = Instant.now()
        for (vehicle in graph.db.vehicles().all()) {
            if (vehicle.isHidden) continue
            val freshness = VehicleRefreshPolicy.freshnessWindow(nextDelaySeconds(listOf(vehicle), interval))
            if (!forced && !VehicleRefreshPolicy.shouldRefresh(vehicle.lastUpdated, now, freshness)) continue
            val controller = vehicle.ownerAccountId?.let(graph.controllers::controller) ?: continue
            try {
                controller.fetchAndUpdateVehicleStatus(vehicle.vin, cached = true)
                controller.syncTripArchiveIfNeeded(vehicle.vin)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                BBLogger.warning(BBLogCategory.APP, "Background refresh failed for ${vehicle.vin}: $error")
            }
        }
        graph.refreshWidgets()
        // Last: replacing the unique work ends this run too.
        schedule(applicationContext, fromWorker = true)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "background-refresh"
        private const val KEY_FORCED = "forced"
        private const val LIVE_PROGRESS_SECONDS = 300.0
        private const val MIN_DELAY_SECONDS = 60.0

        /** Seconds until the next refresh: the owner's interval, shortened to ten minutes while any car is plugged in. */
        fun nextDelaySeconds(vehicles: List<BBVehicle>, interval: WidgetRefreshInterval): Double {
            val pluggedIn = vehicles.any { !it.isHidden && it.evStatus?.pluggedIn == true }
            return VehicleRefreshPolicy.interval(interval.seconds, pluggedIn)
        }

        /**
         * Seconds until a refresh is due: [interval] after the stalest car's
         * last update, but at least a minute away. Counting from the update
         * rather than from now keeps the app's every start (a tile, an
         * alarm, the owner opening it) from pushing the refresh back.
         */
        fun dueInSeconds(vehicles: List<BBVehicle>, interval: Double, now: Instant = Instant.now()): Double {
            val oldest = vehicles.filter { !it.isHidden }.map { it.lastUpdated ?: Instant.EPOCH }.minOrNull() ?: return interval
            val age = (now.toEpochMilli() - oldest.toEpochMilli()) / 1000.0
            return (interval - age).coerceIn(minOf(MIN_DELAY_SECONDS, interval), interval)
        }

        /**
         * Replaces the pending refresh with one when the next is due. A
         * refresh that's running is left to finish: it schedules the next
         * one itself ([fromWorker]), a whole interval on, even for a car it
         * couldn't read, and replacing it would cancel its reads.
         */
        suspend fun schedule(context: Context, fromWorker: Boolean = false) {
            val graph = (context.applicationContext as GhostBlueApplication).graph
            val workManager = WorkManager.getInstance(context)
            if (graph.db.accounts().all().isEmpty()) {
                workManager.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            if (!fromWorker && workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).first().any { it.state == WorkInfo.State.RUNNING }) {
                return
            }
            val vehicles = graph.db.vehicles().all()
            var delay = nextDelaySeconds(vehicles, graph.settings.widgetRefreshInterval.value)
            // The charging notification is kept current every five minutes, as iOS's wake-ups do.
            if (graph.chargingProgress.anyCharging()) delay = minOf(delay, LIVE_PROGRESS_SECONDS)
            if (!fromWorker) delay = dueInSeconds(vehicles, delay)
            enqueue(context, delay.toLong(), forced = false)
        }

        /** A refresh now, from a widget's refresh button. */
        fun refreshNow(context: Context) = enqueue(context, 0, forced = true)

        private fun enqueue(context: Context, delaySeconds: Long, forced: Boolean) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_FORCED to forced))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/**
 * A widget's refresh button: a read from the car itself (not the cached
 * status), then every widget redraws. A worker rather than the button's
 * broadcast, which Android stops after about a minute, as a slow modem or a
 * fresh login can take.
 */
class LiveRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph get() = (applicationContext as GhostBlueApplication).graph

    override suspend fun doWork(): Result {
        val vin = inputData.getString(KEY_VIN) ?: return Result.failure()
        val controller = graph.db.vehicles().byVin(vin)?.ownerAccountId?.let(graph.controllers::controller)
        try {
            controller?.fetchAndUpdateVehicleStatus(vin, cached = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            BBLogger.warning(BBLogCategory.APP, "Widget refresh failed for $vin: $error")
        }
        graph.refreshWidgets()
        return Result.success()
    }

    companion object {
        private const val KEY_VIN = "vin"

        /** A second tap while one is under way waits for it. */
        fun enqueue(context: Context, vin: String) {
            val request = OneTimeWorkRequestBuilder<LiveRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_VIN to vin))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("live-refresh-$vin", ExistingWorkPolicy.KEEP, request)
        }
    }
}
