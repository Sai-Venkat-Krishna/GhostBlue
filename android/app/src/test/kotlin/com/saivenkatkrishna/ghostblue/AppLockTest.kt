package com.saivenkatkrishna.ghostblue

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.saivenkatkrishna.ghostblue.security.AppLock
import com.saivenkatkrishna.ghostblue.security.AuthAvailability
import com.saivenkatkrishna.ghostblue.security.AuthResult
import com.saivenkatkrishna.ghostblue.settings.AppLockTimeout
import com.saivenkatkrishna.ghostblue.settings.AppSettings
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** iOS's Face ID lock as Android's app lock: when it locks, what it hides, and turning it on and off. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
class AppLockTest {
    @get:Rule val compose = createComposeRule()

    private fun settings(lockOn: Boolean, timeout: AppLockTimeout = AppLockTimeout.IMMEDIATELY): AppSettings {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AppSettings(context.getSharedPreferences("lock-${UUID.randomUUID()}", Context.MODE_PRIVATE)).apply {
            setRequireAppLock(lockOn)
            setAppLockTimeout(timeout)
        }
    }

    @Test fun theAppOpensLockedOnlyWhenTheLockIsOn() {
        assertTrue(AppLock(settings(lockOn = true), FakeAuthenticator()).locked.value)
        assertFalse(AppLock(settings(lockOn = false), FakeAuthenticator()).locked.value)
    }

    @Test fun itLocksAgainOnlyAfterTheTimeout() {
        var now = 0L
        val lock = AppLock(settings(lockOn = true, timeout = AppLockTimeout.ONE_MINUTE), FakeAuthenticator()) { now }
        val activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        lock.unlock(activity)
        assertFalse(lock.locked.value)

        lock.onBackground()
        now += 59_000
        lock.onForeground()
        assertFalse("back within the minute", lock.locked.value)

        lock.onBackground()
        now += 60_000
        lock.onForeground()
        assertTrue("away a minute", lock.locked.value)
    }

    @Test fun immediatelyLocksOnEveryReturnAndOffNeverDoes() {
        var now = 0L
        val appSettings = settings(lockOn = true)
        val lock = AppLock(appSettings, FakeAuthenticator()) { now }
        val activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        lock.unlock(activity)
        lock.onBackground()
        now += 500
        lock.onForeground()
        assertTrue(lock.locked.value)

        lock.disable()
        assertFalse(appSettings.requireAppLock.value)
        lock.onBackground()
        now += 3_600_000
        lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test fun returningWhileLockedAsksAgainButADismissedPromptDoesNot() {
        val auth = FakeAuthenticator(result = AuthResult.Cancelled)
        val lock = AppLock(settings(lockOn = true), auth)
        val requests = lock.promptRequests.value
        val activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        lock.unlock(activity)
        assertTrue(lock.locked.value)
        assertEquals(requests, lock.promptRequests.value)
        lock.onBackground()
        lock.onForeground()
        assertEquals(requests + 1, lock.promptRequests.value)
    }

    @Test fun lockedTheScreensAreGoneUntilTheOwnerAuthenticates() {
        val auth = FakeAuthenticator(result = AuthResult.Cancelled)
        val graph = testGraph(auth) { it.setRequireAppLock(true) }.signInTestAccount()
        compose.setApp(graph)
        compose.waitForTag("appLock")
        // It asks by itself as the app opens; dismissed, the app stays locked with nothing behind it.
        compose.waitUntil(5_000) { auth.prompts == 1 }
        compose.onNodeWithTag("homeContent").assertDoesNotExist()

        auth.result = AuthResult.Success
        compose.onNodeWithTag("unlockApp").performClick()
        compose.waitForTag("homeContent")
        assertFalse(graph.appLock.locked.value)
    }

    @Test fun aFailedPromptSaysWhy() {
        val auth = FakeAuthenticator(result = AuthResult.Failed("Too many attempts. Try again later."))
        val graph = testGraph(auth) { it.setRequireAppLock(true) }
        compose.setApp(graph)
        compose.waitForTag("appLockMessage")
        compose.waitForText("Too many attempts. Try again later.")
    }

    @Test fun withNoScreenLockLeftTheOwnerCanTurnTheLockOff() {
        val auth = FakeAuthenticator(availability = AuthAvailability.NoScreenLock)
        val graph = testGraph(auth) { it.setRequireAppLock(true) }.signInTestAccount()
        compose.setApp(graph)
        compose.waitForTag("turnOffAppLock")
        assertEquals(0, auth.prompts)
        compose.onNodeWithTag("turnOffAppLock").performClick()
        compose.waitForTag("homeContent")
        assertFalse(graph.settings.requireAppLock.value)
    }

    @Test fun turningItOnInSettingsAsksFirst() {
        val auth = FakeAuthenticator(result = AuthResult.Cancelled)
        val graph = testGraph(auth).signInTestAccount()
        compose.setApp(graph)
        compose.waitForTag("homeContent")
        compose.onNodeWithTag("tab_SETTINGS").performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("appLockSwitch"))
        compose.onNodeWithTag("appLockSwitch").performClick()
        compose.waitUntil(5_000) { auth.prompts == 1 }
        assertFalse("cancelled, so still off", graph.settings.requireAppLock.value)

        auth.result = AuthResult.Success
        compose.onNodeWithTag("appLockSwitch").performClick()
        compose.waitUntil(5_000) { graph.settings.requireAppLock.value }
        // Turned on in the open app, it doesn't lock straight away.
        assertFalse(graph.appLock.locked.value)
        compose.onNodeWithTag("settingsList").assertExists()
    }

    @Test fun aPhoneWithNoScreenLockCantTurnItOn() {
        val auth = FakeAuthenticator(availability = AuthAvailability.NoScreenLock)
        val graph = testGraph(auth).signInTestAccount()
        var error: String? = null
        val activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().get()
        graph.appLock.setEnabled(activity, true) { error = it }
        assertFalse(graph.settings.requireAppLock.value)
        assertEquals(0, auth.prompts)
        assertTrue(error!!.contains("screen lock"))
    }
}
