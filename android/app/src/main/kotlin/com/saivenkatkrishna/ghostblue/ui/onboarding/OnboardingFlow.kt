package com.saivenkatkrishna.ghostblue.ui.onboarding

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saivenkatkrishna.ghostblue.ui.components.ShapedIcon
import com.saivenkatkrishna.ghostblue.ui.settings.BackupScreen
import com.saivenkatkrishna.ghostblue.ui.settings.SignInScreen
import kotlinx.coroutines.flow.MutableStateFlow

private enum class OnboardingStep { WELCOME, SIGN_IN, RESTORE }

/** Before any account: a welcome, then signing in (or the demo), or restoring a backup. */
@Composable
fun OnboardingFlow(incomingBackup: MutableStateFlow<Uri?>) {
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    val pending by incomingBackup.collectAsStateWithLifecycle()
    LaunchedEffect(pending) { if (pending != null) step = OnboardingStep.RESTORE }
    BackHandler(enabled = step != OnboardingStep.WELCOME) { step = OnboardingStep.WELCOME }
    when (step) {
        OnboardingStep.WELCOME -> Welcome(
            onConnect = { step = OnboardingStep.SIGN_IN },
            onRestore = { step = OnboardingStep.RESTORE },
        )
        OnboardingStep.SIGN_IN -> SignInScreen(onBack = { step = OnboardingStep.WELCOME }, onDone = {})
        OnboardingStep.RESTORE -> BackupScreen(incomingBackup, onBack = { step = OnboardingStep.WELCOME })
    }
}

@Composable
private fun Welcome(onConnect: () -> Unit, onRestore: () -> Unit) {
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShapedIcon(Icons.Rounded.Shield, shape = MaterialShapes.Cookie12Sided.toShape(), size = 96.dp)
            Text(
                "Your smarter garage",
                style = MaterialTheme.typography.displaySmallEmphasized,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                "Connect a vehicle or explore every feature safely with the private Fake demo.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("Controls") }, leadingIcon = { Icon(Icons.Rounded.TouchApp, null, Modifier.size(18.dp)) })
                AssistChip(onClick = {}, label = { Text("Insights") }, leadingIcon = { Icon(Icons.Rounded.Insights, null, Modifier.size(18.dp)) })
                AssistChip(onClick = {}, label = { Text("Private") }, leadingIcon = { Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp)) })
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onConnect,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight).testTag("connectButton"),
            ) {
                Text("Connect or try demo", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
            OutlinedButton(onClick = onRestore, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                Text("Restore from a backup")
            }
        }
    }
}
