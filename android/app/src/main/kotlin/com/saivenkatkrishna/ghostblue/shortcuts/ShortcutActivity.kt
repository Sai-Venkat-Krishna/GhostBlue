package com.saivenkatkrishna.ghostblue.shortcuts

import android.app.KeyguardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import com.saivenkatkrishna.ghostblue.GhostBlueApplication
import com.saivenkatkrishna.ghostblue.data.account.RemoteAction
import kotlinx.coroutines.launch

/**
 * Runs a shortcut or automation command and closes, with no window of its
 * own; the command's progress shows in a notification, as widget commands'
 * do. Reached two ways:
 * - directly, by GhostBlue's launcher shortcuts. The activity isn't
 *   exported, so only the launcher, acting for GhostBlue, can start it;
 * - through the exported `AutomationCommandActivity` alias, by Google
 *   Assistant and apps like Tasker, only once the owner allows them in
 *   Settings, since any app could send that intent.
 * Unlock needs the phone unlocked first, either way.
 */
class ShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as GhostBlueApplication).graph
        val request = ShortcutRequest.from(intent) ?: return done("GhostBlue didn't recognise that command.")
        val fromLauncher = componentName.className == ShortcutActivity::class.java.name
        if (!fromLauncher && !graph.settings.allowAutomation.value) {
            return done("Allow Assistant and automation apps in GhostBlue › Settings first.")
        }
        if (fromLauncher) ShortcutManagerCompat.reportShortcutUsed(this, intent.getStringExtra(EXTRA_SHORTCUT_ID) ?: request.command.id)

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (request.command.action == RemoteAction.UNLOCK && keyguard.isKeyguardLocked) {
            // Shown over the lock screen, so Android can ask for the PIN, fingerprint or face once the window is up.
            setShowWhenLocked(true)
            pendingUnlock = request
            return
        }
        send(request)
    }

    /** An Unlock waiting for the phone to be unlocked. */
    private var pendingUnlock: ShortcutRequest? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val request = pendingUnlock?.takeIf { hasFocus } ?: return
        pendingUnlock = null
        // Asked only once the window shows: asked earlier, the lock screen giving way to it cancels the request.
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = send(request)

                override fun onDismissCancelled() = done(UNLOCK_NEEDS_PHONE)

                override fun onDismissError() = done(UNLOCK_NEEDS_PHONE)
            },
        )
    }

    private fun send(request: ShortcutRequest) {
        val graph = (application as GhostBlueApplication).graph
        lifecycleScope.launch {
            val plan = ShortcutPlanner.plan(request, graph.db, graph.settings.selectedVin.value)
            done(ShortcutPlanner.run(applicationContext, plan))
        }
    }

    private fun done(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        /** A dynamic shortcut's id, for the launcher's ranking. */
        const val EXTRA_SHORTCUT_ID = "shortcutId"

        const val UNLOCK_NEEDS_PHONE = "Unlock your phone to unlock the car."
    }
}
