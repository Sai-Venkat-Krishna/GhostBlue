package com.saivenkatkrishna.ghostblue.data.account

import com.saivenkatkrishna.ghostblue.data.model.secondsBetween
import com.saivenkatkrishna.ghostblue.kit.api.APIClient
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CacheStatistics(val totalEntries: Int, val validEntries: Int, val expiredEntries: Int, val ongoingRequests: Int) {
    val description: String get() = "Cache: $validEntries valid, $expiredEntries expired, $ongoingRequests ongoing"
}

/**
 * Caches responses for 30 seconds and lets identical requests in flight
 * share one call, so a widget refresh, the app coming forward and a
 * background timer firing together cost one request. Port of GhostBlue's
 * `CachedAPIClient`.
 *
 * Shared requests run in [scope], so one caller giving up doesn't cancel
 * the request for the others.
 */
class CachedAPIClient(
    val underlyingClient: APIClient,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Instant = Instant::now,
) : APIClient {
    /** A supervisor under [scope], so a failed request fails only its callers. */
    private val requests = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private sealed interface CacheKey {
        data object Login : CacheKey
        data object FetchVehicles : CacheKey
        data class FetchVehicleStatus(val vin: String) : CacheKey
    }

    private sealed interface RequestKey {
        data object Login : RequestKey
        data object FetchVehicles : RequestKey
        data class FetchVehicleStatus(val vin: String, val cached: Boolean, val generation: Int) : RequestKey
        data class SendCommand(val vin: String, val command: String) : RequestKey
    }

    private class CacheEntry(val response: Any, val timestamp: Instant)

    private val mutex = Mutex()
    private val cache = mutableMapOf<CacheKey, CacheEntry>()
    private val ongoingRequests = mutableMapOf<RequestKey, Deferred<Any?>>()
    private val statusGenerations = mutableMapOf<String, Int>()
    private val latestStatusRequests = mutableMapOf<String, UUID>()

    override suspend fun login(): AuthToken = cachedOrShared(CacheKey.Login, RequestKey.Login, "login") {
        underlyingClient.login()
    }

    override suspend fun registerDevice(): String = underlyingClient.registerDevice()

    override suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle> =
        cachedOrShared(CacheKey.FetchVehicles, RequestKey.FetchVehicles, "fetchVehicles") {
            underlyingClient.fetchVehicles(authToken)
        }

    override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean): VehicleStatus {
        currentCoroutineContext().ensureActive()
        val vin = vehicle.vin
        val request = mutex.withLock {
            val generation = statusGenerations[vin] ?: 0
            val requestKey = RequestKey.FetchVehicleStatus(vin, cached, generation)
            val cacheKey = CacheKey.FetchVehicleStatus(vin)

            // A real-time request (manual sync, command verification) skips the
            // cache, or a recent widget refresh would swallow it, and drops the
            // entry so later readers see fresh data too.
            if (!cached) cache.remove(cacheKey)
            if (cached) {
                val entry = cache[cacheKey]
                if (entry != null && isFresh(entry)) {
                    BBLogger.debug(BBLogCategory.API, "CachedAPIClient:Using cached fetchVehicleStatus response for VIN: $vin")
                    return entry.response as VehicleStatus
                }
            }

            // A cached reader may join a real-time request; a real-time caller
            // never joins a cached read. Generations stop command verification
            // from joining a read sent before the command.
            val freshKey = RequestKey.FetchVehicleStatus(vin, false, generation)
            (ongoingRequests[freshKey] ?: ongoingRequests[requestKey])?.let {
                BBLogger.debug(BBLogCategory.API, "CachedAPIClient:Waiting for ongoing fetchVehicleStatus request for VIN: $vin")
                return@withLock it
            }

            val requestID = UUID.randomUUID()
            latestStatusRequests[vin] = requestID
            startLocked(requestKey) {
                BBLogger.debug(
                    BBLogCategory.API,
                    "CachedAPIClient:Performing new fetchVehicleStatus request for VIN: $vin (cached: $cached)",
                )
                val result = underlyingClient.fetchVehicleStatus(vehicle, authToken, cached)
                // A slower cached read can't overwrite a newer real-time one, and
                // a read from before a command can't repopulate invalidated data.
                mutex.withLock {
                    if (latestStatusRequests[vin] == requestID) cache[cacheKey] = CacheEntry(result, clock())
                }
                result
            }
        }
        return request.await() as VehicleStatus
    }

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {
        val vin = vehicle.vin
        val request = mutex.withLock {
            // Not cached, but the same command to the same car runs once.
            val requestKey = RequestKey.SendCommand(vin, command.toString())
            ongoingRequests[requestKey]?.let {
                BBLogger.debug(BBLogCategory.API, "CachedAPIClient:Waiting for ongoing sendCommand request for VIN: $vin")
                return@withLock it
            }
            // Later reads must see the command's effect.
            invalidateStatusCacheLocked(vin)
            startLocked(requestKey) {
                BBLogger.debug(BBLogCategory.API, "CachedAPIClient:Performing new sendCommand request for VIN: $vin")
                underlyingClient.sendCommand(vehicle, command, authToken)
                mutex.withLock { invalidateStatusCacheLocked(vin) }
            }
        }
        request.await()
    }

    /** Not cached. */
    override suspend fun fetchEVTripDetails(vehicle: Vehicle, authToken: AuthToken): List<EVTripDetail>? {
        BBLogger.debug(BBLogCategory.API, "CachedAPIClient:Forwarding fetchEVTripDetails request for VIN: ${vehicle.vin}")
        return underlyingClient.fetchEVTripDetails(vehicle, authToken)
    }

    suspend fun invalidateStatusCache(vin: String) {
        mutex.withLock { invalidateStatusCacheLocked(vin) }
    }

    suspend fun clearCache() {
        mutex.withLock {
            cache.clear()
            for (vin in statusGenerations.keys + latestStatusRequests.keys) {
                statusGenerations[vin] = (statusGenerations[vin] ?: 0) + 1
            }
            latestStatusRequests.clear()
        }
        BBLogger.debug(BBLogCategory.API, "CachedAPIClient:Cache cleared")
    }

    suspend fun clearExpiredCache() {
        val removed = mutex.withLock {
            val expired = cache.filterValues { !isFresh(it) }.keys
            expired.forEach(cache::remove)
            expired.size
        }
        if (removed > 0) BBLogger.debug(BBLogCategory.API, "CachedAPIClient:Cleared $removed expired cache entries")
    }

    suspend fun cacheStatistics(): CacheStatistics = mutex.withLock {
        val valid = cache.values.count(::isFresh)
        CacheStatistics(cache.size, valid, cache.size - valid, ongoingRequests.size)
    }

    private fun isFresh(entry: CacheEntry) = secondsBetween(entry.timestamp, clock()) < CACHE_TTL

    private fun invalidateStatusCacheLocked(vin: String) {
        statusGenerations[vin] = (statusGenerations[vin] ?: 0) + 1
        latestStatusRequests.remove(vin)
        if (cache.remove(CacheKey.FetchVehicleStatus(vin)) != null) {
            BBLogger.debug(BBLogCategory.API, "CachedAPIClient:Invalidated status cache for VIN: $vin")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cachedOrShared(
        cacheKey: CacheKey,
        requestKey: RequestKey,
        name: String,
        block: suspend () -> T,
    ): T {
        val request = mutex.withLock {
            val entry = cache[cacheKey]
            if (entry != null && isFresh(entry)) {
                BBLogger.debug(BBLogCategory.API, "CachedAPIClient:Using cached $name response")
                return entry.response as T
            }
            ongoingRequests[requestKey]?.let {
                BBLogger.debug(BBLogCategory.API, "CachedAPIClient:Waiting for ongoing $name request")
                return@withLock it
            }
            startLocked(requestKey) {
                BBLogger.debug(BBLogCategory.API, "CachedAPIClient:Performing new $name request")
                val result = block()
                mutex.withLock { cache[cacheKey] = CacheEntry(result, clock()) }
                result
            }
        }
        return request.await() as T
    }

    /** Starts [block] as the shared request for [key]; the caller holds [mutex]. */
    private fun startLocked(key: RequestKey, block: suspend () -> Any?): Deferred<Any?> {
        lateinit var request: Deferred<Any?>
        request = requests.async(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock { if (ongoingRequests[key] === request) ongoingRequests.remove(key) }
                }
            }
        }
        ongoingRequests[key] = request
        request.start()
        return request
    }

    private companion object {
        /** Seconds. Long enough to absorb the burst when several triggers fire together. */
        const val CACHE_TTL = 30.0
    }
}
