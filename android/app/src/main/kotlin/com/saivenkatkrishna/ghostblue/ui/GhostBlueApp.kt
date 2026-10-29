package com.saivenkatkrishna.ghostblue.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.AppGraph
import com.saivenkatkrishna.ghostblue.ui.components.LocalRemoteImages
import com.saivenkatkrishna.ghostblue.ui.onboarding.OnboardingFlow
import com.saivenkatkrishna.ghostblue.ui.theme.GhostBlueTheme
import kotlinx.coroutines.flow.MutableStateFlow

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("No AppGraph provided") }

/**
 * The app: the onboarding flow until an account exists, then the tabs.
 * [incomingBackup] is a backup file another app opened GhostBlue with.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GhostBlueApp(
    graph: AppGraph,
    incomingBackup: MutableStateFlow<Uri?> = MutableStateFlow(null),
    mapContent: MapContent = GoogleMapContent,
    remoteImages: Boolean = true,
) {
    val settings = graph.settings
    val theme by settings.theme.collectAsStateWithLifecycle()
    val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle()
    val garage = graph.garage
    val accounts by garage.accounts.collectAsStateWithLifecycle()
    val signingIn by garage.signingIn.collectAsStateWithLifecycle()
    // Onboarding stays until a sign-in it started has finished loading the cars.
    var onboarding by remember { mutableStateOf(false) }
    val current = accounts
    if (current != null && current.isEmpty()) onboarding = true
    if (current != null && current.isNotEmpty() && !signingIn) onboarding = false

    LaunchedEffect(Unit) { garage.start() }

    CompositionLocalProvider(
        LocalAppGraph provides graph,
        LocalMapContent provides mapContent,
        LocalRemoteImages provides remoteImages,
    ) {
        GhostBlueTheme(theme = theme, dynamicColor = dynamicColor) {
            // Test tags double as resource ids, so UI Automator and adb can find controls.
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).semantics { testTagsAsResourceId = true }) {
                when {
                    current == null -> LoadingIndicator(Modifier.size(72.dp).align(Alignment.Center))
                    onboarding -> OnboardingFlow(incomingBackup)
                    else -> MainScaffold(incomingBackup)
                }
            }
        }
    }
}
