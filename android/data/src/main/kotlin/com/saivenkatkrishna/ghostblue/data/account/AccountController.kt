package com.saivenkatkrishna.ghostblue.data.account

import androidx.room.withTransaction
import com.saivenkatkrishna.ghostblue.data.charging.SmartChargingPreference
import com.saivenkatkrishna.ghostblue.data.charging.VehicleRefreshPolicy
import com.saivenkatkrishna.ghostblue.data.charging.activeChargingOverride
import com.saivenkatkrishna.ghostblue.data.charging.chargingPreference
import com.saivenkatkrishna.ghostblue.data.charging.setChargingOverride
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.CommandActivityState
import com.saivenkatkrishna.ghostblue.data.model.DeviceType
import com.saivenkatkrishna.ghostblue.data.model.RemoteCommandActivity
import com.saivenkatkrishna.ghostblue.data.vehicle.StatusWaitingManager
import com.saivenkatkrishna.ghostblue.data.vehicle.archiveTrips
import com.saivenkatkrishna.ghostblue.data.vehicle.isAtConfiguredHome
import com.saivenkatkrishna.ghostblue.data.vehicle.isSmartChargingControllableAC
import com.saivenkatkrishna.ghostblue.data.vehicle.updateStatus
import com.saivenkatkrishna.ghostblue.kit.api.APIClient
import com.saivenkatkrishna.ghostblue.kit.api.APIClientConfiguration
import com.saivenkatkrishna.ghostblue.kit.api.MFAMethod
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogCategory
import com.saivenkatkrishna.ghostblue.kit.logging.BBLogger
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.AuthToken
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.EVTripDetail
import com.saivenkatkrishna.ghostblue.kit.model.HTTPLogSink
import com.saivenkatkrishna.ghostblue.kit.model.Vehicle
import com.saivenkatkrishna.ghostblue.kit.model.VehicleCommand
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a smart-charging run did. */
data class SmartChargingReconciliation(val action: Action, val message: String) {
    enum class Action(val rawValue: String) {
        STARTED("started"),
        STOPPED("stopped"),
        ALREADY_CORRECT("alreadyCorrect"),
        SKIPPED("skipped"),
    }
}

/**
 * Everything one account does against its vehicle service: the run-time half
 * of iOS's `BBAccount` (API client, token, re-authentication, commands,
 * trips, smart charging). The stored half is the [BBAccount] row.
 *
 * Vehicles are addressed by VIN, and every step reads the current row, so a
 * change made elsewhere (another screen, the test car's server) is never
 * overwritten with a stale copy.
 *
 * Background work (shared requests, trip archiving, smart-charging runs)
 * runs under [scope], supervised so one failure doesn't cancel the rest.
 */
class AccountController(
    val accountId: UUID,
    private val db: GhostBlueDatabase,
    scope: CoroutineScope,
    private val logSinks: HTTPLogSinkManager? = null,
    /** Shown in the command log, as iOS shows "iPhone" or "Apple Watch". */
    private val sourceName: String = "Phone",
    /** Off for widgets and tiles, which should return promptly (iOS skips this in extensions). */
    private val archivesTripsInBackground: Boolean = true,
    private val waiter: StatusWaitingManager = StatusWaitingManager.shared,
    private val clientFactory: (APIClientConfiguration) -> APIClient = { createAPIClient(it, db, scope) },
) {
    private val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    @Volatile private var api: APIClient? = null
    @Volatile private var cachedAuthToken: AuthToken? = null
    @Volatile private var pendingMFAError: APIError? = null

    /** Kia's vehicle keys, from the last vehicle list; not stored, as on iOS. */
    private val vehicleKeys = ConcurrentHashMap<String, String>()
    private val lock = Mutex()
    private val tripArchiveJobs = mutableMapOf<String, Job>()
    private val reconciliations = mutableMapOf<String, Deferred<SmartChargingReconciliation>>()

    suspend fun account(): BBAccount =
        db.accounts().byId(accountId) ?: throw APIError("Account not found", apiName = "BBAccount")

    // MARK: - API client

    /** Creates the client and logs in, unless a stored token is still valid. A pending MFA challenge is rethrown. */
    suspend fun initialize(deviceType: DeviceType? = null) {
        pendingMFAError?.let { mfaError ->
            BBLogger.info(BBLogCategory.AUTH, "BBAccount: MFA is pending, re-throwing MFA error")
            throw mfaError
        }
        val account = account()

        // A requested device type always gets a fresh client, logging as that device.
        if (api == null || deviceType != null) {
            api = clientFactory(
                APIClientConfiguration(
                    region = account.regionEnum,
                    brand = account.brandEnum,
                    username = account.username,
                    password = account.password,
                    pin = account.pin,
                    accountId = account.id,
                    logSink = logSink(deviceType),
                    rememberMeToken = account.rememberMeToken,
                    refreshToken = account.refreshToken.ifEmpty { null },
                    deviceId = account.deviceId,
                ),
            )
        }

        authToken(account)?.let { existing ->
            if (existing.isValid) {
                BBLogger.info(BBLogCategory.AUTH, "BBAccount: Using persisted auth token (expires: ${existing.expiresAt})")
                return
            }
        }
        BBLogger.info(BBLogCategory.AUTH, "BBAccount: No valid token, performing login...")
        if (account.password.isEmpty() && account.refreshToken.isEmpty()) {
            BBLogger.warning(
                BBLogCategory.AUTH,
                "BBAccount: account has no password and no refresh token — login will fail. " +
                    "Re-enter credentials in Settings → Accounts.",
            )
        }

        try {
            val api = api ?: throw APIError("API client not initialized")
            // A no-op for brands without server-side registration.
            if (account.deviceId == null) {
                val deviceId = try {
                    api.registerDevice()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    UUID.randomUUID().toString().uppercase()
                }
                editAccount { it.deviceId = deviceId }
            }
            val token = api.login()
            setAuthToken(token)
            // Kept so the next session can re-authenticate with it where the brand allows.
            if (token.refreshToken.isNotEmpty()) editAccount { it.refreshToken = token.refreshToken }
            pendingMFAError = null
        } catch (error: APIError) {
            // Other operations in flight see the challenge too.
            if (error.errorType == APIError.ErrorType.REQUIRES_MFA) pendingMFAError = error
            throw error
        }
    }

    suspend fun sendMFA(otpKey: String, xid: String, method: MFAMethod = MFAMethod.SMS) {
        BBLogger.info(BBLogCategory.MFA, "BBAccount: sendMFA requested")
        // Not re-initialised: a new login would reset the OTP key.
        val client = underlyingClient() ?: run {
            BBLogger.error(BBLogCategory.API, "BBAccount: API not initialized during MFA request")
            throw APIError("API not initialized. Please try logging in again.", apiName = "BBAccount")
        }
        if (!client.supportsMFA()) {
            BBLogger.error(BBLogCategory.API, "BBAccount: MFA not supported for this API")
            throw APIError("MFA not supported for this brand", apiName = "BBAccount")
        }
        client.sendMFACode(xid, otpKey, method)
    }

    suspend fun verifyMFA(otpKey: String, xid: String, otp: String) {
        val client = underlyingClient()
            ?: throw APIError("API not initialized. Please try logging in again.", apiName = "BBAccount")
        if (!client.supportsMFA()) throw APIError("MFA not supported for this brand", apiName = "BBAccount")
        // Verify the code, then finish the login on the same client.
        val verification = client.verifyMFACode(xid, otpKey, otp)
        editAccount { it.rememberMeToken = verification.rememberMeToken }
        setAuthToken(client.completeMFALogin(verification.sid, verification.rememberMeToken))
        pendingMFAError = null
        BBLogger.info(BBLogCategory.API, "BBAccount: MFA complete")
    }

    suspend fun clearAPICache() {
        (api as? CachedAPIClient)?.clearCache()
    }

    suspend fun cacheStatistics(): CacheStatistics? = (api as? CachedAPIClient)?.cacheStatistics()

    private fun underlyingClient(): APIClient? = (api as? CachedAPIClient)?.underlyingClient ?: api

    private fun logSink(deviceType: DeviceType?): HTTPLogSink? =
        logSinks?.let { if (deviceType != null) it.createLogSink(deviceType) else it.createLogSink() }

    private fun authToken(account: BBAccount): AuthToken? =
        cachedAuthToken ?: account.serializedAuthToken?.let(SwiftAuthToken::decode)?.also { cachedAuthToken = it }

    private suspend fun setAuthToken(token: AuthToken?) {
        cachedAuthToken = token
        editAccount { it.serializedAuthToken = token?.let(SwiftAuthToken::encode) }
    }

    /** The client and a token, logging in first when either is missing. */
    private suspend fun ready(): Pair<APIClient, AuthToken> {
        current()?.let { return it }
        initialize()
        return current() ?: throw APIError.failedRetryLogin()
    }

    private suspend fun current(): Pair<APIClient, AuthToken>? {
        val api = api ?: return null
        val token = authToken(account()) ?: return null
        return api to token
    }

    /** Runs [call]; when the error means the session is bad, logs in again once and retries. */
    private suspend fun <T> withReauthentication(call: suspend (APIClient, AuthToken) -> T): T {
        val (api, token) = ready()
        return try {
            call(api, token)
        } catch (error: APIError) {
            if (!shouldReauthenticate(error)) throw error
            handleAPIError(error)
            val (retryApi, retryToken) = current() ?: throw APIError.failedRetryLogin()
            call(retryApi, retryToken)
        }
    }

    private suspend fun handleAPIError(error: APIError) {
        BBLogger.info(
            BBLogCategory.API,
            "BBAccount: API error (${error.errorType.rawValue}) detected, performing full re-initialization...",
        )
        clearAPICache()
        api = null
        setAuthToken(null)
        initialize()
        val (api, token) = current() ?: throw APIError.failedRetryLogin()
        updateVehicles(api.fetchVehicles(token))
        BBLogger.info(BBLogCategory.API, "BBAccount: Re-initialization complete")
    }

    // MARK: - Vehicles

    suspend fun vehicles(): List<BBVehicle> = db.vehicles().ownedBy(accountId)

    private suspend fun vehicle(vin: String): BBVehicle =
        db.vehicles().byVin(vin) ?: throw APIError.logError("Vehicle not found: $vin", apiName = "BBAccount")

    private fun BBVehicle.toApiVehicle(): Vehicle = toVehicle().copy(vehicleKey = vehicleKeys[vin])

    suspend fun loadVehicles() {
        updateVehicles(withReauthentication { api, token -> api.fetchVehicles(token) })
    }

    /**
     * Syncs the fetched list into the account's vehicles: existing cars get
     * the API's fields and keep the owner's settings, new cars go to the end
     * of the sort order, cars no longer returned are removed.
     *
     * A VIN already stored without an account is adopted. One stored under
     * another account stays there (VINs are unique, as iOS's store
     * reconciler makes them).
     */
    suspend fun updateVehicles(vehicles: List<Vehicle>) {
        db.withTransaction {
            val owned = db.vehicles().ownedBy(accountId)
            val existing = owned.associateBy { it.vin }
            val maxSortOrder = owned.maxOfOrNull { it.sortOrder }?.coerceAtLeast(0) ?: 0
            val processed = mutableSetOf<String>()

            for ((index, vehicle) in vehicles.withIndex()) {
                processed += vehicle.vin
                vehicle.vehicleKey?.let { vehicleKeys[vehicle.vin] = it } ?: vehicleKeys.remove(vehicle.vin)
                val stored = existing[vehicle.vin]
                    ?: db.vehicles().byVin(vehicle.vin)?.takeIf { it.ownerAccountId == null }
                if (stored != null) {
                    stored.ownerAccountId = accountId
                    stored.regId = vehicle.regId
                    stored.model = vehicle.model
                    stored.fuelType = vehicle.fuelType
                    stored.generation = vehicle.generation
                    stored.odometer = vehicle.odometer
                    db.vehicles().update(stored)
                } else if (db.vehicles().byVin(vehicle.vin) != null) {
                    BBLogger.warning(BBLogCategory.API, "BBAccount: ${vehicle.vin} belongs to another account; not added")
                } else {
                    db.vehicles().insert(
                        BBVehicle(vehicle).apply {
                            sortOrder = maxSortOrder + index + 1
                            ownerAccountId = accountId
                        },
                    )
                }
            }
            for (vehicle in owned.reversed()) {
                if (vehicle.vin !in processed) db.vehicles().delete(vehicle)
            }
        }
    }

    /**
     * Fetches and applies a status. [cached] false asks the car itself:
     * for manual refreshes and checks after a command. Background and widget
     * reads leave it true so the modem isn't woken each time.
     */
    suspend fun fetchVehicleStatus(vin: String, cached: Boolean = true): VehicleStatus {
        val account = account()
        // Kia needs the vehicle key from the vehicle list.
        if (account.brandEnum == Brand.KIA && vehicleKeys[vin] == null) {
            BBLogger.debug(BBLogCategory.API, "BBAccount: Kia vehicle missing vehicleKey, fetching fresh data...")
            loadVehicles()
        }
        val target = vehicle(vin).toApiVehicle()
        val status = withReauthentication { api, token -> api.fetchVehicleStatus(target, token, cached) }
        currentCoroutineContext().ensureActive()
        return try {
            db.withTransaction { (db.vehicles().byVin(vin) ?: vehicle(vin)).updateStatus(status, db) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The fetch succeeded; a storage error must not turn it into a failure and a retry.
            BBLogger.error(BBLogCategory.APP, "BBAccount: Failed to save fetched status for $vin: $error")
            status
        }
    }

    suspend fun fetchAndUpdateVehicleStatus(vin: String, cached: Boolean = true) {
        fetchVehicleStatus(vin, cached)
        scheduleTripArchiveSync(vin)
    }

    /**
     * Polls until [condition] holds, after a command: an initial settle delay,
     * then up to [maxAttempts] real-time reads. A status arriving for the car
     * from elsewhere ends a wait early.
     */
    suspend fun waitForStatusChange(
        vin: String,
        condition: (VehicleStatus) -> Boolean,
        statusMessageUpdater: ((String) -> Unit)? = null,
        maxAttempts: Int = 3,
        initialDelaySeconds: Int = 10,
        retryDelaySeconds: Int = 10,
    ) {
        val vehicleId = vehicle(vin).id
        statusMessageUpdater?.invoke("Command sent")
        waiter.wait(vehicleId, initialDelaySeconds)
        var attempt = 0
        while (attempt < maxAttempts) {
            currentCoroutineContext().ensureActive()
            // Verification needs the car's actual state, not the server's snapshot.
            if (condition(fetchVehicleStatus(vin, cached = false))) {
                BBLogger.info(BBLogCategory.VEHICLE, "✅ [BBVehicle] Status condition met for vehicle $vin")
                return
            }
            attempt++
            if (attempt < maxAttempts) {
                statusMessageUpdater?.invoke("Waiting for vehicle ($attempt/$maxAttempts)")
                waiter.wait(vehicleId, retryDelaySeconds)
            }
        }
        throw APIError("Status change condition not met after $maxAttempts attempts")
    }

    // MARK: - Trips

    /** The API's recent trips, each saved to the archive; null where the brand has no trip endpoint. */
    suspend fun fetchEVTripDetails(vin: String): List<EVTripDetail>? {
        val target = vehicle(vin).toApiVehicle()
        val trips = withReauthentication { api, token -> api.fetchEVTripDetails(target, token) }
        // Every caller adds to the archive, not just the Trips screen.
        if (trips != null) vehicle(vin).archiveTrips(trips, db)
        return trips
    }

    /** Captures the rolling trip window at most every five minutes; never fails a refresh. */
    suspend fun syncTripArchiveIfNeeded(vin: String, now: Instant = Instant.now()) {
        if (!account().supportsEVTripDetails) return
        val lastAttempt = vehicle(vin).lastTripArchiveAttemptDate
        if (!VehicleRefreshPolicy.shouldRefresh(lastAttempt, now, TRIP_ARCHIVE_INTERVAL)) return
        editVehicle(vin) { it.lastTripArchiveAttemptDate = now }
        try {
            fetchEVTripDetails(vin)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            BBLogger.debug(BBLogCategory.API, "Trip archive sync skipped for $vin: $error")
        }
    }

    private suspend fun scheduleTripArchiveSync(vin: String) {
        if (!archivesTripsInBackground || !account().supportsEVTripDetails) return
        val lastAttempt = db.vehicles().byVin(vin)?.lastTripArchiveAttemptDate
        if (!VehicleRefreshPolicy.shouldRefresh(lastAttempt, interval = TRIP_ARCHIVE_INTERVAL)) return
        lock.withLock {
            if (tripArchiveJobs[vin] != null) return
            tripArchiveJobs[vin] = work.launch(start = CoroutineStart.LAZY) {
                try {
                    syncTripArchiveIfNeeded(vin)
                } finally {
                    withContext(NonCancellable) { lock.withLock { tripArchiveJobs.remove(vin) } }
                }
            }.also { it.start() }
        }
    }

    // MARK: - Commands

    /** Sends [command] and records it in the command log, logging in again once if the session expired. */
    suspend fun sendCommand(
        vin: String,
        command: VehicleCommand,
        climatePresetName: String? = null,
        climatePresetIcon: String? = null,
    ) {
        val vehicle = vehicle(vin)
        val activity = RemoteCommandActivity(vin, vehicle.displayName, command.activityDisplayName, sourceName)
        db.remoteCommands().insert(activity)
        try {
            val account = account()
            if (account.brandEnum == Brand.KIA && vehicleKeys[vin] == null) {
                BBLogger.debug(BBLogCategory.API, "BBAccount: Kia vehicle missing vehicleKey, fetching fresh data...")
                val (api, token) = ready()
                val match = api.fetchVehicles(token).firstOrNull { it.vin == vin }
                    ?: throw APIError.logError("Vehicle not found in fetched data", apiName = "BBAccount")
                match.vehicleKey?.let { vehicleKeys[vin] = it }
            }
            // Ongoing notifications for long-running commands arrive in phase 4.
            val target = db.vehicles().byVin(vin)?.toApiVehicle() ?: vehicle.toApiVehicle()
            withReauthentication { api, token -> api.sendCommand(target, command, token) }
            activity.finish(CommandActivityState.SUCCEEDED)
            db.remoteCommands().update(activity)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                activity.finish(CommandActivityState.FAILED, error)
                db.remoteCommands().update(activity)
            }
            throw error
        }
    }

    suspend fun lockVehicle(vin: String) = sendCommand(vin, VehicleCommand.Lock)

    suspend fun unlockVehicle(vin: String) = sendCommand(vin, VehicleCommand.Unlock)

    /** Starts climate with [options], else the selected preset, else the first preset, else defaults. */
    suspend fun startClimate(
        vin: String,
        options: ClimateOptions? = null,
        presetName: String? = null,
        presetIcon: String? = null,
    ) {
        var name = presetName
        var icon = presetIcon
        val climateOptions = options ?: run {
            val presets = db.climatePresets().forVehicle(vehicle(vin).id)
            val preset = presets.firstOrNull { it.isSelected } ?: presets.firstOrNull()
            if (preset != null) {
                name = name ?: preset.name
                icon = icon ?: preset.iconName
                preset.climateOptions
            } else {
                ClimateOptions()
            }
        }
        sendCommand(vin, VehicleCommand.StartClimate(climateOptions), name, icon)
    }

    suspend fun stopClimate(vin: String) = sendCommand(vin, VehicleCommand.StopClimate)

    suspend fun startCharge(vin: String) {
        sendCommand(vin, VehicleCommand.StartCharge)
        editVehicle(vin) { it.chargeStopRequestedAt = null }
    }

    suspend fun stopCharge(vin: String) {
        sendCommand(vin, VehicleCommand.StopCharge)
        // The verification read that follows expects "not charging"; it confirms without a second sample.
        editVehicle(vin) { it.chargeStopRequestedAt = Instant.now() }
    }

    suspend fun setTargetSOC(vin: String, acLevel: Int, dcLevel: Int) =
        sendCommand(vin, VehicleCommand.SetTargetSOC(acLevel, dcLevel))

    // MARK: - Smart charging

    /**
     * Brings the car in line with SDG&E's current super-off-peak period. The
     * car's own 12 AM–2 PM schedule is the fail-safe; running this at 6 AM and
     * 10 AM on weekdays adds the morning pause. Safe to run at any time:
     * every branch is idempotent. A run already going for the car is joined.
     */
    suspend fun reconcileSmartCharging(
        vin: String,
        at: Instant = Instant.now(),
        refreshStatus: Boolean = true,
    ): SmartChargingReconciliation {
        var created = false
        val run = lock.withLock {
            reconciliations[vin] ?: work.async(start = CoroutineStart.LAZY) {
                performSmartChargingReconciliation(vin, at, refreshStatus)
            }.also {
                reconciliations[vin] = it
                created = true
                it.start()
            }
        }
        if (!created) return run.await()
        try {
            return run.await()
        } finally {
            withContext(NonCancellable) { lock.withLock { if (reconciliations[vin] === run) reconciliations.remove(vin) } }
        }
    }

    private suspend fun performSmartChargingReconciliation(
        vin: String,
        date: Instant,
        refreshStatus: Boolean,
    ): SmartChargingReconciliation {
        var vehicle = vehicle(vin)
        if (vehicle.activeChargingOverride(date) == null && vehicle.smartChargingOverrideRaw != null) {
            vehicle = editVehicle(vin) { it.setChargingOverride(null) }
        }
        if (!vehicle.smartChargingEnabled) return record(vin, date, SKIPPED, "Smart charging is disabled.")

        if (refreshStatus) {
            try {
                fetchAndUpdateVehicleStatus(vin, cached = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                record(vin, date, SKIPPED, "Could not refresh the vehicle: ${error.message}")
                throw error
            }
        }
        vehicle = vehicle(vin)

        val evStatus = vehicle.evStatus ?: return record(vin, date, SKIPPED, "No EV charging status is available.")
        if (!evStatus.pluggedIn) return record(vin, date, SKIPPED, "Vehicle is not plugged in.")
        if (vehicle.smartChargingHomeOnly) {
            if (vehicle.homeLatitude == null || vehicle.homeLongitude == null) {
                return record(vin, date, SKIPPED, "Set a home location before using home-only smart charging.")
            }
            if (!vehicle.isAtConfiguredHome) return record(vin, date, SKIPPED, "Vehicle is outside the saved home area.")
        }
        if (!vehicle.isSmartChargingControllableAC(evStatus)) {
            return record(vin, date, SKIPPED, "The vehicle reports DC fast charging, so Smart Charging left it unchanged.")
        }

        val preference = vehicle.chargingPreference(date)
        val batteryPercentage = floor(evStatus.evRange.percentage).toInt()
        val targetSOC = min(100, max(50, vehicle.smartChargingTargetSOC))
        val shouldCharge = preference == SmartChargingPreference.CHARGE && batteryPercentage < targetSOC

        if (shouldCharge == evStatus.charging) {
            editVehicle(vin) { it.smartChargingLastConfirmedAt = date }
            val detail = when {
                shouldCharge -> "Charging is already running as requested."
                preference == SmartChargingPreference.CHARGE -> "Battery is already at the $targetSOC% target."
                else -> "Charging is already paused as requested."
            }
            return record(vin, date, ALREADY_CORRECT, detail)
        }

        if (shouldCharge) startCharge(vin) else stopCharge(vin)
        val action = if (shouldCharge) STARTED else STOPPED
        try {
            waitForStatusChange(
                vin,
                condition = { it.evStatus?.charging == shouldCharge },
                maxAttempts = 2,
                initialDelaySeconds = 8,
                retryDelaySeconds = 8,
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                record(vin, date, action, "Command was sent, but the vehicle did not confirm the change.")
            }
            throw error
        }
        editVehicle(vin) { it.smartChargingLastConfirmedAt = date }
        return record(
            vin,
            date,
            action,
            if (shouldCharge) "Charging started and confirmed." else "Charging paused and confirmed.",
        )
    }

    private suspend fun record(
        vin: String,
        date: Instant,
        action: SmartChargingReconciliation.Action,
        message: String,
    ): SmartChargingReconciliation {
        editVehicle(vin) {
            it.smartChargingLastRunAt = date
            it.smartChargingLastResult = message
        }
        return SmartChargingReconciliation(action, message)
    }

    // MARK: - Storage

    /** Read, change and save the current row in one transaction. */
    private suspend fun editVehicle(vin: String, change: (BBVehicle) -> Unit): BBVehicle = db.withTransaction {
        vehicle(vin).also {
            change(it)
            db.vehicles().update(it)
        }
    }

    private suspend fun editAccount(change: (BBAccount) -> Unit) {
        db.withTransaction {
            val account = db.accounts().byId(accountId) ?: return@withTransaction
            change(account)
            db.accounts().update(account)
        }
    }

    companion object {
        private val SKIPPED = SmartChargingReconciliation.Action.SKIPPED
        private val ALREADY_CORRECT = SmartChargingReconciliation.Action.ALREADY_CORRECT
        private val STARTED = SmartChargingReconciliation.Action.STARTED
        private val STOPPED = SmartChargingReconciliation.Action.STOPPED

        /** Seconds between trip-archive attempts. */
        private const val TRIP_ARCHIVE_INTERVAL = 5.0 * 60

        /**
         * Whether an error means the session itself is bad. Transient failures
         * (the modem not answering a real-time read, rate limits, no network)
         * keep the token: dropping it forced a new login everywhere, and the
         * retry of a real-time read woke the car again. Unrecognised failures
         * still re-authenticate, so an unexpected session error can't leave
         * the app stuck.
         */
        fun shouldReauthenticate(error: APIError): Boolean = when (error.errorType) {
            APIError.ErrorType.INVALID_CREDENTIALS, APIError.ErrorType.INVALID_VEHICLE_SESSION -> true
            APIError.ErrorType.REQUIRES_MFA, APIError.ErrorType.SERVER_ERROR, APIError.ErrorType.CONCURRENT_REQUEST,
            APIError.ErrorType.INVALID_PIN, APIError.ErrorType.REGION_NOT_SUPPORTED,
            APIError.ErrorType.FAILED_RETRY_LOGIN, APIError.ErrorType.KIA_INVALID_REQUEST,
            -> false
            APIError.ErrorType.GENERAL -> {
                val code = error.code
                // A request that never reached the server has this prefix and no code.
                if (code == null) !error.message.startsWith("Network error:")
                // Negative codes are transport failures; 429 and 5xx are the server's problem.
                else code > 0 && code != 429 && code < 500
            }
        }

        /** Deletes the account and, with it, its vehicles and their presets. */
        suspend fun removeAccount(account: BBAccount, db: GhostBlueDatabase) {
            db.accounts().delete(account)
            BBLogger.info(BBLogCategory.API, "BBAccount: Removed account")
        }

        suspend fun updateAccount(
            accountId: UUID,
            password: String,
            pin: String,
            refreshToken: String = "",
            db: GhostBlueDatabase,
        ) {
            db.withTransaction {
                val account = db.accounts().byId(accountId) ?: return@withTransaction
                account.password = password
                account.pin = pin
                if (refreshToken.isNotEmpty()) account.refreshToken = refreshToken
                db.accounts().update(account)
            }
        }

        /** Sort orders follow the list's order. */
        suspend fun updateVehicleSortOrders(vins: List<String>, db: GhostBlueDatabase) {
            db.withTransaction {
                for ((index, vin) in vins.withIndex()) {
                    val vehicle = db.vehicles().byVin(vin) ?: continue
                    vehicle.sortOrder = index
                    db.vehicles().update(vehicle)
                }
            }
        }
    }
}

/** The name the command log shows. */
val VehicleCommand.activityDisplayName: String
    get() = when (this) {
        VehicleCommand.Lock -> "Lock"
        VehicleCommand.Unlock -> "Unlock"
        is VehicleCommand.StartClimate -> "Start climate"
        VehicleCommand.StopClimate -> "Stop climate"
        VehicleCommand.StartCharge -> "Start charging"
        VehicleCommand.StopCharge -> "Stop charging"
        is VehicleCommand.SetTargetSOC -> "Update charge limit"
    }

/**
 * One [AccountController] per account for the process, so the client, token
 * and in-flight work are shared by every screen, widget and worker.
 */
class AccountControllers(
    private val db: GhostBlueDatabase,
    private val scope: CoroutineScope,
    private val logSinks: HTTPLogSinkManager? = null,
    private val sourceName: String = "Phone",
) {
    private val controllers = ConcurrentHashMap<UUID, AccountController>()

    fun controller(accountId: UUID): AccountController =
        controllers.getOrPut(accountId) { AccountController(accountId, db, scope, logSinks, sourceName) }

    /** Forgets the run-time state of a removed account. */
    fun forget(accountId: UUID) {
        controllers.remove(accountId)
    }
}
