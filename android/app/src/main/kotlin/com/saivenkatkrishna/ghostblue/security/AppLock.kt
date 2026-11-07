package com.saivenkatkrishna.ghostblue.security

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.saivenkatkrishna.ghostblue.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Whether the phone can confirm it's the owner. */
sealed interface AuthAvailability {
    data object Available : AuthAvailability

    /** No PIN, pattern or password is set, so there's nothing to ask for. */
    data object NoScreenLock : AuthAvailability

    data class Unavailable(val message: String) : AuthAvailability
}

sealed interface AuthResult {
    data object Success : AuthResult

    /** Dismissed, or interrupted by leaving the app: nothing to report. */
    data object Cancelled : AuthResult

    data class Failed(val message: String) : AuthResult
}

/** The system's own check of the owner: fingerprint, face, or the screen lock. Tests swap in a fake. */
interface DeviceAuthenticator {
    fun availability(context: Context): AuthAvailability

    /** Shows the system prompt over [activity]; [onResult] is called once, on the main thread. */
    fun authenticate(activity: Activity, title: String, subtitle: String, onResult: (AuthResult) -> Unit)
}

/**
 * androidx.biometric's `BiometricPrompt`, accepting any enrolled biometric
 * or the screen lock, as iOS's `.deviceOwnerAuthentication` accepts Face ID
 * or the passcode. Android draws the prompt and keeps the biometrics;
 * GhostBlue only learns whether it succeeded.
 */
object BiometricAuthenticator : DeviceAuthenticator {
    // Strong biometrics with the device credential aren't supported on Android 10; weak ones are, everywhere.
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    override fun availability(context: Context): AuthAvailability =
        when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> AuthAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthAvailability.NoScreenLock
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                AuthAvailability.Unavailable("Android needs a security update before it can confirm it's you.")
            else -> AuthAvailability.Unavailable("This phone can't confirm it's you right now.")
        }

    override fun authenticate(activity: Activity, title: String, subtitle: String, onResult: (AuthResult) -> Unit) {
        val host = activity as? FragmentActivity
            ?: return onResult(AuthResult.Failed("Authentication isn't available on this screen."))
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(AuthResult.Success)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED,
                    -> AuthResult.Cancelled
                    else -> AuthResult.Failed(errString.toString())
                },
            )
            // A single unrecognised finger or face: the prompt stays up and says so itself.
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        BiometricPrompt(host, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}

/**
 * The app lock: iOS's Face ID lock in `MainView`. With it on, the app opens
 * locked, and locks again when it comes back after the owner's timeout
 * ([AppSettings.appLockTimeout]; iOS locks at once). Process-wide, so
 * every window of the app shares one state, and fed by
 * [processObserver] rather than one activity, so rotating or opening
 * GhostBlue's own screens never counts as leaving. Widgets, tiles,
 * notifications and shortcuts aren't covered, as on iOS.
 */
class AppLock(
    private val settings: AppSettings,
    private val authenticator: DeviceAuthenticator,
    /** Monotonic, so changing the clock can't skip the lock. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _locked = MutableStateFlow(settings.requireAppLock.value)

    /** True while the app's screens must stay hidden. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _authenticating = MutableStateFlow(false)
    val authenticating: StateFlow<Boolean> = _authenticating.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** Why the last try failed, for the lock screen. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _promptRequests = MutableStateFlow(if (_locked.value) 1 else 0)

    /**
     * Rises whenever the lock screen should ask by itself: at launch, when
     * the app locks, and when it comes back still locked. A dismissed
     * prompt isn't shown again until one of those happens.
     */
    val promptRequests: StateFlow<Int> = _promptRequests.asStateFlow()

    private var backgroundedAt: Long? = null

    private val isEnabled: Boolean get() = settings.requireAppLock.value

    /** The app left the screen. A prompt of the app's own (the screen lock on older Androids) doesn't count. */
    fun onBackground() {
        if (isEnabled && !_locked.value && !_authenticating.value) backgroundedAt = clock()
    }

    /** The app is back: lock if it was away at least the timeout, or ask again if it's still locked. */
    fun onForeground() {
        val since = backgroundedAt
        backgroundedAt = null
        if (!isEnabled) return
        if (_locked.value) {
            if (!_authenticating.value) _promptRequests.update { it + 1 }
            return
        }
        if (since != null && clock() - since >= settings.appLockTimeout.value.seconds * 1000) lock()
    }

    fun lock() {
        if (!isEnabled || _locked.value) return
        _locked.value = true
        _message.value = null
        _promptRequests.update { it + 1 }
    }

    fun availability(context: Context): AuthAvailability = authenticator.availability(context)

    /** Asks for the owner; on success the app opens. */
    fun unlock(activity: Activity) {
        if (!_locked.value || _authenticating.value) return
        when (val availability = authenticator.availability(activity)) {
            AuthAvailability.Available -> Unit
            AuthAvailability.NoScreenLock -> {
                _message.value = NO_SCREEN_LOCK_MESSAGE
                return
            }
            is AuthAvailability.Unavailable -> {
                _message.value = availability.message
                return
            }
        }
        _authenticating.value = true
        _message.value = null
        authenticator.authenticate(activity, "Unlock GhostBlue", "Unlock your vehicles and private location data.") { result ->
            _authenticating.value = false
            when (result) {
                AuthResult.Success -> {
                    backgroundedAt = null
                    _locked.value = false
                }
                AuthResult.Cancelled -> Unit
                is AuthResult.Failed -> _message.value = result.message
            }
        }
    }

    /**
     * Settings' switch. Turning it on asks for the owner first, which also
     * shows the phone can ask at all; turning it off happens in the open app.
     */
    fun setEnabled(activity: Activity?, enabled: Boolean, onError: (String) -> Unit) {
        if (!enabled) {
            disable()
            return
        }
        if (activity == null || _authenticating.value) return
        when (val availability = authenticator.availability(activity)) {
            AuthAvailability.Available -> Unit
            AuthAvailability.NoScreenLock -> return onError("Set a screen lock in Android settings first, then turn on the app lock.")
            is AuthAvailability.Unavailable -> return onError(availability.message)
        }
        _authenticating.value = true
        authenticator.authenticate(activity, "Turn on app lock", "Confirm it's you to require it whenever GhostBlue opens.") { result ->
            _authenticating.value = false
            when (result) {
                AuthResult.Success -> {
                    settings.setRequireAppLock(true)
                    backgroundedAt = null
                    _locked.value = false
                }
                AuthResult.Cancelled -> Unit
                is AuthResult.Failed -> onError(result.message)
            }
        }
    }

    /**
     * Off, and open. Also the lock screen's way out once the phone has no
     * screen lock: whoever holds it could already open everything on it.
     */
    fun disable() {
        settings.setRequireAppLock(false)
        backgroundedAt = null
        _message.value = null
        _locked.value = false
    }

    /** Process start and stop, from `ProcessLifecycleOwner`. */
    val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = onForeground()

        override fun onStop(owner: LifecycleOwner) = onBackground()
    }

    companion object {
        const val NO_SCREEN_LOCK_MESSAGE =
            "This phone no longer has a screen lock, so GhostBlue can't ask for it. Set one in Android settings, or turn off the app lock."
    }
}
