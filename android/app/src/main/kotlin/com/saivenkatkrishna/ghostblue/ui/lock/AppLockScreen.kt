package com.saivenkatkrishna.ghostblue.ui.lock

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.security.AppLock
import com.saivenkatkrishna.ghostblue.security.AuthAvailability
import kotlinx.coroutines.flow.first

/**
 * What the app shows while locked, in place of every screen (iOS's
 * `AppLockView`). It asks by itself when the app opens or comes back, and
 * the Unlock button asks again after the prompt is dismissed.
 */
@Composable
fun AppLockScreen(lock: AppLock) {
    val activity = LocalActivity.current
    val authenticating by lock.authenticating.collectAsStateWithLifecycle()
    val message by lock.message.collectAsStateWithLifecycle()
    val requests by lock.promptRequests.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(requests) {
        // The prompt needs the activity in front.
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        activity?.let(lock::unlock)
    }

    Surface(Modifier.fillMaxSize().testTag("appLock"), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedContent(authenticating, label = "lockMark") { busy ->
                    if (busy) {
                        ContainedLoadingIndicator(Modifier.size(112.dp))
                    } else {
                        Box(
                            Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(52.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("GhostBlue is locked", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(
                    "Unlock to use your cars' controls and see their trips and location.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("appLockMessage"),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { activity?.let(lock::unlock) },
                    enabled = !authenticating,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.MediumContentPadding,
                    modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight).testTag("unlockApp")
                        .semantics { contentDescription = "Unlock GhostBlue" },
                ) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(ButtonDefaults.MediumIconSize))
                    Spacer(Modifier.size(ButtonDefaults.MediumIconSpacing))
                    Text("Unlock", style = MaterialTheme.typography.titleMedium)
                }
                if (message != null && activity != null && lock.availability(activity) == AuthAvailability.NoScreenLock) {
                    TextButton(onClick = lock::disable, modifier = Modifier.testTag("turnOffAppLock")) { Text("Turn off app lock") }
                }
            }
        }
    }
}
