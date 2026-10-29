package com.saivenkatkrishna.ghostblue.wear

import androidx.room.withTransaction
import com.saivenkatkrishna.ghostblue.data.account.AccountController
import com.saivenkatkrishna.ghostblue.data.account.AccountControllers
import com.saivenkatkrishna.ghostblue.data.account.AccountHandoff
import com.saivenkatkrishna.ghostblue.data.account.AccountSetup
import com.saivenkatkrishna.ghostblue.data.account.HandoffAccount
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import com.saivenkatkrishna.ghostblue.data.account.isUnconfirmed
import com.saivenkatkrishna.ghostblue.data.account.perform
import com.saivenkatkrishna.ghostblue.data.charging.VehicleRefreshPolicy
import com.saivenkatkrishna.ghostblue.data.db.GhostBlueDatabase
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.data.vehicle.StatusWaitingManager
import com.saivenkatkrishna.ghostblue.kit.model.APIError
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Region
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which of a car's buttons a command belongs to. */
enum class WatchControl { LOCK, CLIMATE, CHARGE }

/** A button's command in flight: its progress, or how it ended. */
data class WatchCommand(val action: RemoteAction, val running: Boolean, val text: String)

/**
 * The watch app's state and actions, the counterpart of iOS's
 * `WatchVehicleView` logic: the cars, a command per button with its
 * progress (tapping again stops waiting), refreshes, signing in, and the
 * accounts the phone hands over.
 */
class WatchModel(
    private val db: GhostBlueDatabase,
    private val settings: WearSettings,
    private val controllers: AccountControllers,
    private val scope: CoroutineScope,
    private val commandWaitSeconds: Int = 10,
) {
    val accounts: StateFlow<List<BBAccount>?> = db.accounts().observeAll().stateIn(scope, SharingStarted.Eagerly, null)

    val vehicles: StateFlow<List<BBVehicle>?> =
        db.vehicles().observeAll().map { list -> list.filter { !it.isHidden } }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _commands = MutableStateFlow<Map<Pair<String, WatchControl>, WatchCommand>>(emptyMap())
    val commands: StateFlow<Map<Pair<String, WatchControl>, WatchCommand>> = _commands.asStateFlow()
    private val jobs = ConcurrentHashMap<Pair<String, WatchControl>, Job>()

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()

    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    private fun controller(vehicle: BBVehicle): AccountController? = vehicle.ownerAccountId?.let(controllers::controller)

    /** Opening a car reads the server's snapshot when its data is five minutes old; only the refresh button asks the car. */
    fun refreshIfStale(vin: String) {
        val vehicle = vehicles.value?.firstOrNull { it.vin == vin } ?: return
        if (VehicleRefreshPolicy.shouldRefresh(vehicle.lastUpdated, interval = STALE_SECONDS)) refresh(vin, forced = false)
    }

    fun refresh(vin: String, forced: Boolean) {
        if (vin in _refreshing.value) return
        scope.launch {
            _refreshing.update { it + vin }
            try {
                val vehicle = db.vehicles().byVin(vin) ?: return@launch
                StatusWaitingManager.shared.clearWakeUp(vehicle.id)
                controller(vehicle)?.fetchAndUpdateVehicleStatus(vin, cached = !forced)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The page keeps the last status and its time.
            } finally {
                _refreshing.update { it - vin }
            }
        }
    }

    /** Sends [action] for the button's [control]; tapping again while it waits stops waiting, as iOS's stop button. */
    fun send(vin: String, control: WatchControl, action: RemoteAction) {
        val key = vin to control
        jobs.remove(key)?.let { running ->
            if (running.isActive) {
                running.cancel()
                _commands.update { it - key }
                return
            }
        }
        fun set(command: WatchCommand?) = _commands.update { if (command == null) it - key else it + (key to command) }
        // Before launching, so the tile that sent it already draws it running.
        set(WatchCommand(action, running = true, text = "${action.progress}…"))
        jobs[key] = scope.launch {
            try {
                val vehicle = db.vehicles().byVin(vin) ?: throw APIError("Vehicle not found")
                val controller = controller(vehicle) ?: throw APIError("Vehicle not found")
                controller.perform(vin, action, commandWaitSeconds) { set(WatchCommand(action, running = true, text = it)) }
                set(WatchCommand(action, running = false, text = action.done))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                set(WatchCommand(action, running = false, text = if (error.isUnconfirmed) "Not confirmed yet" else (error.message ?: "Failed")))
            }
            delay(RESULT_MILLIS)
            set(null)
            jobs.remove(key)
        }
    }

    /** Signs in on the watch, as the phone does; returns why it failed, or null. */
    suspend fun signIn(username: String, password: String, pin: String, brand: Brand, region: Region): String? {
        _signingIn.value = true
        return try {
            scope.async {
                val account = AccountSetup.addAccount(db, controllers, username, password, pin, brand, region)
                db.vehicles().ownedBy(account.id).firstOrNull()?.let { settings.setSelectedVin(it.vin) }
            }.await()
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.message ?: error.toString()
        } finally {
            _signingIn.value = false
        }
    }

    /** Accounts from the phone: new ones are signed in here, known ones get the phone's credentials. */
    suspend fun receive(accounts: List<HandoffAccount>): Int {
        val failures = AccountHandoff.signInNew(accounts, db, controllers)
        if (settings.selectedVin.value == null) db.vehicles().all().firstOrNull()?.let { settings.setSelectedVin(it.vin) }
        return failures.size
    }

    fun signOut(account: BBAccount) {
        scope.launch { AccountSetup.forget(db, controllers, account) }
    }

    fun setColor(vin: String, change: (BBVehicle) -> Unit) {
        scope.launch {
            db.withTransaction {
                val vehicle = db.vehicles().byVin(vin) ?: return@withTransaction
                change(vehicle)
                db.vehicles().update(vehicle)
            }
        }
    }

    companion object {
        const val STALE_SECONDS = 300.0
        const val RESULT_MILLIS = 3_000L
    }
}
