package com.saivenkatkrishna.ghostblue.data.account

import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBHTTPLog
import com.saivenkatkrishna.ghostblue.data.model.DeviceType
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLog
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLogSink
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Stores API request logs for Settings › HTTP Logs while debug mode is on,
 * keeping at most [MAX_LOGS]. Port of GhostBlue's `HTTPLogSinkManager`.
 */
class HTTPLogSinkManager(
    private val db: GhostBlueDatabase,
    private val scope: CoroutineScope,
    private val deviceType: DeviceType = DeviceType.PHONE,
    private val isEnabled: () -> Boolean = { true },
) {
    fun createLogSink(deviceType: DeviceType = this.deviceType): HTTPLogSink = { log ->
        if (isEnabled()) scope.launch { store(log, deviceType) }
    }

    /** Saves one log; what each sink does in the background. */
    suspend fun store(log: HTTPLog, deviceType: DeviceType = this.deviceType) {
        try {
            db.httpLogs().insert(BBHTTPLog(log, deviceType))
            // Widgets return promptly; the app tidies up.
            if (deviceType != DeviceType.WIDGET && deviceType != DeviceType.LIVE_ACTIVITY) cleanupOldLogs()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            BBLogger.error(BBLogCategory.APP, "HTTPLog: Failed to save HTTP log: $error")
        }
    }

    /** Over [MAX_LOGS], the oldest [DELETE_COUNT] go. */
    suspend fun cleanupOldLogs() {
        val count = db.httpLogs().count()
        if (count > MAX_LOGS) {
            db.httpLogs().deleteOldest(DELETE_COUNT)
            BBLogger.info(
                BBLogCategory.APP,
                "HTTPLog: Cleaned up $DELETE_COUNT old logs (now have ${count - DELETE_COUNT} logs)",
            )
        }
    }

    companion object {
        const val MAX_LOGS = 100
        const val DELETE_COUNT = 50
    }
}
