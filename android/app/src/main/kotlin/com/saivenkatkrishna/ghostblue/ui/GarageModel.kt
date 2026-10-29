package com.saivenkatkrishna.ghostblue.ui

import androidx.room.withTransaction
import com.saivenkatkrishna.ghostblue.data.account.AccountController
import com.saivenkatkrishna.ghostblue.data.account.AccountControllers
import com.saivenkatkrishna.ghostblue.data.account.createAPIClient
import com.saivenkatkrishna.ghostblue.kit.api.APIClientConfiguration
import com.saivenkatkrishna.ghostblue.data.backup.BackupArchive
import com.saivenkatkrishna.ghostblue.data.backup.BackupMergeSummary
import com.saivenkatkrishna.ghostblue.data.backup.BackupTransfer
import com.saivenkatkrishna.ghostblue.data.charging.SmartChargingOverride
import com.saivenkatkrishna.ghostblue.data.charging.VehicleRefreshPolicy
import com.saivenkatkrishna.ghostblue.data.charging.setChargingOverride
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBHTTPLog
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.model.ChargeSession
import com.saivenkatkrishna.ghostblue.data.model.ClimatePreset
import com.saivenkatkrishna.ghostblue.data.model.TripRecord
import com.saivenkatkrishna.ghostblue.data.vehicle.StatusWaitingManager
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeVehicleRecord
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.ClimateOptions
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.VehicleStatus
import com.saivenkatkrishna.ghostblue.kit.model.isTestAccount
import com.saivenkatkrishna.ghostblue.settings.AppSettings
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The remote controls a screen shows progress for. */
enum class CommandKind { LOCK, CLIMATE, CHARGE, CHARGE_LIMITS }

/** One control's command in flight: its progress line, or the error it ended with. */
data class CommandState(val running: Boolean = false, val message: String? = null, val error: String? = null)

data class TripsLoadState(val loading: Boolean = false, val error: UserError? = null)

/**
 * The app's state and actions: the garage of cars, the selected one, the
 * remote commands in flight and the refreshes, for every screen. It holds
 * no copy of the data: lists come from Room, and actions go through
 * [AccountController] (by VIN) or straight to the database for the owner's
 * own records. The counterpart of iOS's `MainView` state, the command
 * centers and the `modelContext` edits the views make.
 */
class GarageModel(
    private val db: GhostBlueDatabase,
    val settings: AppSettings,
    private val controllers: AccountControllers,
    private val scope: CoroutineScope,
    /** Seconds between checks after a command; iOS waits 10. */
    private val commandWaitSeconds: Int = 10,
) {
    val accounts: StateFlow<List<BBAccount>?> = db.accounts().observeAll().stateIn(scope, SharingStarted.Eagerly, null)

    /** Every car, hidden ones included, by sort order. Null until the database has answered. */
    val allVehicles: StateFlow<List<BBVehicle>?> = db.vehicles().observeAll().stateIn(scope, SharingStarted.Eagerly, null)

    val vehicles: StateFlow<List<BBVehicle>?> =
        allVehicles.map { list -> list?.filter { !it.isHidden } }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The selected car, else the first; shared by every tab as on iOS. */
    val selectedVehicle: StateFlow<BBVehicle?> = combine(vehicles, settings.selectedVin) { list, vin ->
        list?.firstOrNull { it.vin == vin } ?: list?.firstOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _commands = MutableStateFlow<Map<Pair<String, CommandKind>, CommandState>>(emptyMap())
    val commands: StateFlow<Map<Pair<String, CommandKind>, CommandState>> = _commands.asStateFlow()
    private val commandJobs = ConcurrentHashMap<Pair<String, CommandKind>, Job>()

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())

    /** VINs with a status refresh in flight. */
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()
    private val refreshJobs = ConcurrentHashMap<String, Job>()

    private val _garageLoading = MutableStateFlow(false)

    /** Signing in and loading the vehicle lists at launch. */
    val garageLoading: StateFlow<Boolean> = _garageLoading.asStateFlow()

    private val _lastError = MutableStateFlow<UserError?>(null)

    /** The last refresh failure, cleared by the next success. */
    val lastError: StateFlow<UserError?> = _lastError.asStateFlow()

    private val _tripLoads = MutableStateFlow<Map<String, TripsLoadState>>(emptyMap())
    val tripLoads: StateFlow<Map<String, TripsLoadState>> = _tripLoads.asStateFlow()

    private val _pendingTripDeletions = MutableStateFlow<Set<UUID>>(emptySet())

    /** Trips deleted but still undoable; screens leave them out. */
    val pendingTripDeletions: StateFlow<Set<UUID>> = _pendingTripDeletions.asStateFlow()
    private var pendingTripDelete: Pair<TripRecord, Job>? = null

    private val _signingIn = MutableStateFlow(false)

    /** An account is being added; the onboarding flow stays up until it's done. */
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-off notices for a snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _commandSucceeded = MutableSharedFlow<CommandKind>(extraBufferCapacity = 8)

    /** A confirmed command, for haptics. */
    val commandSucceeded: SharedFlow<CommandKind> = _commandSucceeded.asSharedFlow()

    private var started = false

    fun selectVehicle(vin: String) = settings.setSelectedVin(vin)

    // Room's flows, for the screens to observe.
    fun presets(vehicleId: UUID): Flow<List<ClimatePreset>> = db.climatePresets().observeForVehicle(vehicleId)

    /** Newest first. */
    fun sessions(vin: String): Flow<List<ChargeSession>> = db.chargeSessions().observeForVin(vin)

    /** Newest first. */
    fun trips(vin: String): Flow<List<TripRecord>> = db.trips().observeForVin(vin)

    fun httpLogs(): Flow<List<BBHTTPLog>> = db.httpLogs().observeAll()

    fun account(id: UUID?): BBAccount? = accounts.value?.firstOrNull { it.id == id }

    fun commandState(vin: String, kind: CommandKind): CommandState = _commands.value[vin to kind] ?: CommandState()

    private fun controller(vehicle: BBVehicle): AccountController? = vehicle.ownerAccountId?.let(controllers::controller)

    private suspend fun controller(vin: String): AccountController =
        db.vehicles().byVin(vin)?.let(::controller) ?: throw APIError("Vehicle not found: $vin")

    // MARK: - Loading and refresh

    /**
     * Launch: each account signs in (a stored token is reused) and loads its
     * vehicles, then cars not refreshed in five minutes get a cached read.
     * Runs once per process.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch { loadGarage() }
    }

    suspend fun loadGarage() {
        _garageLoading.value = true
        try {
            for (account in db.accounts().all()) {
                val controller = controllers.controller(account.id)
                try {
                    controller.initialize()
                    controller.loadVehicles()
                    _lastError.value = null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _lastError.value = UserError("Load vehicles", refreshErrorMessage(error), error.toString(), error.isMFA)
                }
            }
        } finally {
            _garageLoading.value = false
        }
        val now = Instant.now()
        for (vehicle in db.vehicles().all()) {
            if (VehicleRefreshPolicy.shouldRefresh(vehicle.lastUpdated, now, STALE_SECONDS)) refresh(vehicle.vin, forced = false)
        }
    }

    /** While the app is in the foreground: the selected car every refresh interval, when its data is stale. */
    suspend fun runAutoRefresh() {
        VehicleRefreshPolicy.run(interval = { settings.refreshInterval.value.seconds }) {
            if (_lastError.value == null) {
                selectedVehicle.value?.let { vehicle ->
                    if (VehicleRefreshPolicy.shouldRefresh(vehicle.lastUpdated, interval = STALE_SECONDS)) {
                        refresh(vehicle.vin, forced = false)
                    }
                }
            }
        }
    }

    /** On switching cars or opening Home: a cached read when the data is five minutes old. */
    fun refreshIfStale(vin: String) {
        val vehicle = vehicles.value?.firstOrNull { it.vin == vin } ?: return
        if (VehicleRefreshPolicy.shouldRefresh(vehicle.lastUpdated, interval = STALE_SECONDS)) refresh(vin, forced = false)
    }

    /** [forced] (pull to refresh, the refresh button) asks the car itself; otherwise the server's snapshot. */
    fun refresh(vin: String, forced: Boolean): Job {
        refreshJobs[vin]?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch {
            _refreshing.update { it + vin }
            try {
                val vehicle = db.vehicles().byVin(vin) ?: return@launch
                StatusWaitingManager.shared.clearWakeUp(vehicle.id)
                controller(vin).fetchAndUpdateVehicleStatus(vin, cached = !forced)
                _lastError.value = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _lastError.value = UserError("Refresh", refreshErrorMessage(error), error.toString(), error.isMFA)
            } finally {
                _refreshing.update { it - vin }
                refreshJobs.remove(vin)
            }
        }
        refreshJobs[vin] = job
        return job
    }

    fun dismissError() {
        _lastError.value = null
    }

    // MARK: - Commands

    /**
     * Sends a command and waits for the car to report the change, as iOS's
     * buttons do. The drawn state always comes from the car's reported
     * status; this only carries the progress line. Tapping again while a
     * command waits cancels it.
     */
    private fun runCommand(
        vin: String,
        kind: CommandKind,
        maxAttempts: Int = 3,
        waitSeconds: Int = commandWaitSeconds,
        timeoutIsSuccess: Boolean = false,
        send: suspend (AccountController) -> Unit,
        condition: (VehicleStatus) -> Boolean,
    ) {
        val key = vin to kind
        commandJobs.remove(key)?.let { running ->
            if (running.isActive) {
                running.cancel()
                _commands.update { it - key }
                return
            }
        }
        val job = scope.launch {
            fun set(state: CommandState?) = _commands.update { if (state == null) it - key else it + (key to state) }
            set(CommandState(running = true, message = "Sending command..."))
            try {
                val controller = controller(vin)
                send(controller)
                try {
                    controller.waitForStatusChange(
                        vin,
                        condition,
                        statusMessageUpdater = { message -> set(CommandState(running = true, message = message)) },
                        maxAttempts = maxAttempts,
                        initialDelaySeconds = waitSeconds,
                        retryDelaySeconds = waitSeconds,
                    )
                } catch (error: APIError) {
                    if (!timeoutIsSuccess || !error.message.startsWith("Status change condition not met")) throw error
                }
                set(null)
                _commandSucceeded.tryEmit(kind)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                set(CommandState(error = commandErrorMessage(error)))
                delay(ERROR_DISPLAY_MILLIS)
                set(null)
            } finally {
                commandJobs.remove(key)
            }
        }
        commandJobs[key] = job
    }

    fun toggleLock(vehicle: BBVehicle) {
        if (vehicle.lockStatus == VehicleStatus.LockStatus.LOCKED) unlock(vehicle.vin) else lock(vehicle.vin)
    }

    fun lock(vin: String) = runCommand(vin, CommandKind.LOCK, send = { it.lockVehicle(vin) }) {
        it.lockStatus == VehicleStatus.LockStatus.LOCKED
    }

    fun unlock(vin: String) = runCommand(vin, CommandKind.LOCK, send = { it.unlockVehicle(vin) }) {
        it.lockStatus == VehicleStatus.LockStatus.UNLOCKED
    }

    fun toggleClimate(vehicle: BBVehicle) {
        if (vehicle.climateStatus?.airControlOn == true) stopClimate(vehicle.vin) else startClimate(vehicle.vin)
    }

    /** With [preset], that preset's settings; otherwise the selected preset, else the first, else defaults. */
    fun startClimate(vin: String, preset: ClimatePreset? = null) = runCommand(
        vin,
        CommandKind.CLIMATE,
        send = { controller ->
            if (preset != null) {
                controller.startClimate(vin, preset.climateOptions, preset.name, preset.iconName)
            } else {
                controller.startClimate(vin)
            }
        },
    ) { it.climateStatus.airControlOn }

    fun stopClimate(vin: String) = runCommand(vin, CommandKind.CLIMATE, send = { it.stopClimate(vin) }) {
        !it.climateStatus.airControlOn
    }

    fun toggleCharging(vehicle: BBVehicle) {
        val shouldStart = vehicle.evStatus?.charging != true
        runCommand(
            vehicle.vin,
            CommandKind.CHARGE,
            send = { if (shouldStart) it.startCharge(vehicle.vin) else it.stopCharge(vehicle.vin) },
        ) { it.evStatus?.charging == shouldStart }
    }

    /** iOS reports a limit change the car is slow to confirm as saved. */
    fun setChargeLimits(vin: String, ac: Int, dc: Int) = runCommand(
        vin,
        CommandKind.CHARGE_LIMITS,
        waitSeconds = minOf(5, commandWaitSeconds),
        timeoutIsSuccess = true,
        send = { it.setTargetSOC(vin, ac, dc) },
    ) { it.evStatus?.targetSocAC?.toInt() == ac && it.evStatus?.targetSocDC?.toInt() == dc }

    // MARK: - Accounts

    /**
     * Adds an account and loads its cars, as iOS's Add Account: the test
     * account (or the Fake brand) gets [testCars]. The account row comes
     * first because the controller reads it; on failure it is removed again.
     * Returns the error to show, or null.
     */
    suspend fun signIn(
        username: String,
        password: String,
        pin: String,
        brand: Brand,
        region: Region,
        testCars: List<FakeVehicleRecord> = emptyList(),
    ): UserError? {
        // Runs in the app's scope: the sign-in screen leaves composition when the account row appears.
        _signingIn.value = true
        return try {
            scope.async { performSignIn(username, password, pin, brand, region, testCars) }.await()
        } finally {
            _signingIn.value = false
        }
    }

    private suspend fun performSignIn(
        username: String,
        password: String,
        pin: String,
        brand: Brand,
        region: Region,
        testCars: List<FakeVehicleRecord>,
    ): UserError? {
        val effectiveBrand = if (isTestAccount(username, password)) Brand.FAKE else brand
        val account = BBAccount(username.trim(), password, pin, effectiveBrand, region)
        db.accounts().insert(account)
        try {
            if (effectiveBrand == Brand.FAKE) addTestCars(account.id, testCars)
            val controller = controllers.controller(account.id)
            controller.initialize()
            controller.loadVehicles()
            db.vehicles().ownedBy(account.id).firstOrNull()?.let { settings.setSelectedVin(it.vin) }
            _lastError.value = null
            for (vehicle in db.vehicles().ownedBy(account.id)) refresh(vehicle.vin, forced = false)
            return null
        } catch (error: CancellationException) {
            withContext(NonCancellable) { forgetAccount(account) }
            throw error
        } catch (error: Exception) {
            forgetAccount(account)
            return if (error.isMFA) {
                UserError(
                    "Sign in to ${effectiveBrand.displayName}",
                    "This account asks for a verification code, which the Android app doesn't support yet.",
                    error.toString(),
                    requiresMFA = true,
                )
            } else {
                userError("Sign in to ${effectiveBrand.displayName}", error)
            }
        }
    }

    private suspend fun addTestCars(accountId: UUID, cars: List<FakeVehicleRecord>) {
        db.withTransaction {
            for ((index, record) in cars.withIndex()) {
                val vehicle = BBVehicle(record.vehicle.copy(accountId = accountId)).apply {
                    ownerAccountId = accountId
                    sortOrder = index
                    evStatus = record.evStatus
                    gasRange = record.gasRange
                    location = record.location
                    lockStatus = record.lockStatus
                    climateStatus = record.climateStatus
                    battery12V = record.battery12V ?: 82
                    doorOpen = record.doorOpen
                    trunkOpen = record.trunkOpen
                    hoodOpen = record.hoodOpen
                    tirePressureWarning = record.tirePressureWarning
                    syncDate = record.syncDate
                    lastUpdated = record.lastUpdated
                    backgroundColorName = if (index % 2 == 0) "darkBlue" else "darkGreen"
                }
                // A VIN is unique; a test car already stored elsewhere stays there.
                if (db.vehicles().byVin(vehicle.vin) == null) db.vehicles().insert(vehicle)
            }
        }
    }

    private suspend fun forgetAccount(account: BBAccount) {
        AccountController.removeAccount(account, db)
        controllers.forget(account.id)
    }

    /**
     * New credentials are tried with a sign-in of their own before they're
     * saved, as iOS does; the account's client then starts over with them.
     */
    suspend fun updateCredentials(accountId: UUID, password: String, pin: String): UserError? {
        val account = db.accounts().byId(accountId) ?: return null
        val action = if (pin != account.pin) "Update PIN" else "Update password"
        try {
            val configuration = APIClientConfiguration(
                region = account.regionEnum,
                brand = account.brandEnum,
                username = account.username,
                password = password,
                pin = pin,
                accountId = account.id,
                deviceId = account.deviceId,
            )
            createAPIClient(configuration, db, scope).login()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return userError(action, error)
        }
        AccountController.updateAccount(accountId, password, pin, db = db)
        db.withTransaction {
            db.accounts().byId(accountId)?.let {
                it.serializedAuthToken = null
                db.accounts().update(it)
            }
        }
        controllers.forget(accountId)
        return null
    }

    /** Deleting an account deletes its cars, presets and their run-time state. */
    fun removeAccount(account: BBAccount) {
        scope.launch { forgetAccount(account) }
    }

    /** Settings › Clear All Data: every table, as iOS's debug section. */
    suspend fun clearAllData() {
        val accountIds = db.accounts().all().map { it.id }
        withContext(Dispatchers.IO) { db.clearAllTables() }
        accountIds.forEach(controllers::forget)
        settings.setSelectedVin(null)
        _lastError.value = null
    }

    // MARK: - The owner's records

    /** Reads, changes and saves the current row, so a newer change elsewhere isn't overwritten. */
    fun editVehicle(vin: String, change: (BBVehicle) -> Unit) {
        scope.launch {
            db.withTransaction {
                val vehicle = db.vehicles().byVin(vin) ?: return@withTransaction
                change(vehicle)
                db.vehicles().update(vehicle)
            }
        }
    }

    fun setSmartChargingOverride(vin: String, override: SmartChargingOverride?) =
        editVehicle(vin) { it.setChargingOverride(override) }

    /** Runs the schedule check now; returns its message. */
    suspend fun checkSmartCharging(vin: String): String = try {
        controller(vin).reconcileSmartCharging(vin).message
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        commandErrorMessage(error)
    }

    /** A car with no presets gets "Default", as iOS's climate settings do. */
    suspend fun ensureDefaultPreset(vehicleId: UUID) {
        db.withTransaction {
            // The car may have just been removed (an account rolled back or deleted).
            if (db.vehicles().byId(vehicleId) != null && db.climatePresets().forVehicle(vehicleId).isEmpty()) {
                db.climatePresets().insert(ClimatePreset("Default", "fan", ClimateOptions(), isSelected = true, vehicleId = vehicleId))
            }
        }
    }

    fun addPreset(vehicleId: UUID) {
        scope.launch {
            db.withTransaction {
                val presets = db.climatePresets().forVehicle(vehicleId)
                val preset = ClimatePreset(
                    name = "Preset ${presets.size + 1}",
                    iconName = ClimatePreset.availableIcons.random().first,
                    climateOptions = ClimateOptions(),
                    isSelected = presets.isEmpty(),
                    vehicleId = vehicleId,
                ).apply { sortOrder = (presets.maxOfOrNull { it.sortOrder } ?: -1) + 1 }
                db.climatePresets().insert(preset)
            }
        }
    }

    fun updatePreset(preset: ClimatePreset) {
        scope.launch { db.climatePresets().update(preset) }
    }

    fun selectPreset(preset: ClimatePreset) {
        val vehicleId = preset.vehicleId ?: return
        scope.launch {
            db.withTransaction {
                for (other in db.climatePresets().forVehicle(vehicleId)) {
                    val selected = other.id == preset.id
                    if (other.isSelected != selected) {
                        other.isSelected = selected
                        db.climatePresets().update(other)
                    }
                }
            }
        }
    }

    /** Deleting the selected preset selects the first one left. */
    fun deletePreset(preset: ClimatePreset) {
        val vehicleId = preset.vehicleId ?: return
        scope.launch {
            db.withTransaction {
                db.climatePresets().delete(preset)
                val remaining = db.climatePresets().forVehicle(vehicleId)
                if (preset.isSelected && remaining.none { it.isSelected }) {
                    remaining.firstOrNull()?.let {
                        it.isSelected = true
                        db.climatePresets().update(it)
                    }
                }
            }
        }
    }

    /** Saves a session from the editor: [session] is a new one (inserted) or a stored one (updated). */
    suspend fun saveSession(session: ChargeSession, isNew: Boolean) {
        if (isNew) db.chargeSessions().insert(session) else db.chargeSessions().update(session)
    }

    // MARK: - Trips

    /** Fetches the API's recent trips into the archive; the screen shows the archive. */
    fun loadTrips(vin: String) {
        if (_tripLoads.value[vin]?.loading == true) return
        scope.launch {
            _tripLoads.update { it + (vin to TripsLoadState(loading = true)) }
            val state = try {
                val account = controller(vin).account()
                if (account.supportsEVTripDetails) controller(vin).fetchEVTripDetails(vin)
                TripsLoadState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                TripsLoadState(error = userError("Load trips", error))
            }
            _tripLoads.update { it + (vin to state) }
        }
    }

    suspend fun supportsTrips(vin: String): Boolean = try {
        controller(vin).account().supportsEVTripDetails
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    /** Empty or blank clears the name. */
    fun renameTrip(trip: TripRecord, name: String) {
        scope.launch {
            trip.customName = name.trim().ifEmpty { null }
            db.trips().update(trip)
        }
    }

    fun toggleFavorite(trip: TripRecord) {
        scope.launch {
            trip.isFavorite = !trip.isFavorite
            db.trips().update(trip)
        }
    }

    /** Hides the trip now and deletes it in five seconds unless undone; a new delete commits the last one. */
    fun deleteTrip(trip: TripRecord) {
        commitPendingTripDelete()
        _pendingTripDeletions.update { it + trip.id }
        val job = scope.launch {
            delay(UNDO_MILLIS)
            commitPendingTripDelete()
        }
        pendingTripDelete = trip to job
        _messages.tryEmit(TRIP_DELETED_MESSAGE)
    }

    fun undoTripDelete() {
        val (trip, job) = pendingTripDelete ?: return
        job.cancel()
        pendingTripDelete = null
        _pendingTripDeletions.update { it - trip.id }
    }

    private fun commitPendingTripDelete() {
        val (trip, job) = pendingTripDelete ?: return
        pendingTripDelete = null
        job.cancel()
        scope.launch {
            db.trips().delete(trip)
            _pendingTripDeletions.update { it - trip.id }
        }
    }

    // MARK: - Backups and logs

    suspend fun writeBackup(deviceName: String, directory: File): File =
        BackupTransfer.writeBackup(db, deviceName, directory)

    fun readBackup(input: InputStream): BackupArchive = BackupTransfer.readArchive(input)

    /** Merges, then loads any new account's cars. */
    suspend fun importBackup(archive: BackupArchive): BackupMergeSummary {
        val summary = BackupTransfer.importArchive(archive, db)
        if (summary.accountsAdded > 0) scope.launch { loadGarage() }
        return summary
    }

    fun clearHTTPLogs() {
        scope.launch { db.httpLogs().deleteAll() }
    }

    fun post(message: String) {
        _messages.tryEmit(message)
    }

    private val Throwable.isMFA: Boolean
        get() = (this as? APIError)?.errorType == APIError.ErrorType.REQUIRES_MFA

    companion object {
        /** Data older than this is refreshed on open and by the timer, as on iOS. */
        const val STALE_SECONDS = 300.0
        const val ERROR_DISPLAY_MILLIS = 4_000L
        const val UNDO_MILLIS = 5_000L
        const val TRIP_DELETED_MESSAGE = "Trip deleted"
    }
}
