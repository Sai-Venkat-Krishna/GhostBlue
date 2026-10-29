package com.saivenkatkrishna.ghostblue.wear.ui

import android.app.RemoteInput
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import com.saivenkatkrishna.ghostblue.data.model.VehicleImageColor
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.isTestAccount
import com.saivenkatkrishna.ghostblue.wear.WearGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The watch's keyboard (or voice) for one field, through Wear's remote input. */
@Composable
private fun rememberTextEntry(label: String, onResult: (String) -> Unit): () -> Unit {
    val key = "entry_$label"
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(key)?.toString() }?.let(onResult)
    }
    return {
        val input = RemoteInput.Builder(key).setLabel(label).wearableExtender {
            setEmojisAllowed(false)
            setInputActionType(EditorInfo.IME_ACTION_DONE)
        }.build()
        val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        launcher.launch(intent)
    }
}

/**
 * Signing in on the watch: the test account in one tap, or a Hyundai
 * account typed on the watch. The phone app also hands its accounts over.
 */
@Composable
fun SignInScreen(graph: WearGraph, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val signingIn by graph.model.signingIn.collectAsStateWithLifecycle()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val enterUsername = rememberTextEntry("Email") { username = it.trim() }
    val enterPassword = rememberTextEntry("Password") { password = it }
    val enterPin = rememberTextEntry("PIN") { pin = it.filter(Char::isDigit) }
    val isTest = isTestAccount(username, password)

    fun signIn(user: String, pass: String, code: String) {
        error = null
        scope.launch {
            val failure = graph.model.signIn(user, pass, code, if (isTestAccount(user, pass)) Brand.FAKE else Brand.HYUNDAI, Region.USA)
            // Navigation needs the main thread, whichever thread the sign-in finished on.
            withContext(Dispatchers.Main.immediate) { if (failure == null) onDone() else error = failure }
        }
    }

    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.testTag("signIn")) {
            item { ListHeader { Text("Sign in") } }
            if (signingIn) {
                item { CircularProgressIndicator(Modifier.size(36.dp)) }
            }
            item {
                Button(
                    onClick = { signIn(TEST_USERNAME, TEST_PASSWORD, "") },
                    enabled = !signingIn,
                    modifier = Modifier.fillMaxWidth().testTag("testAccountButton"),
                    label = { Text("Test account") },
                    secondaryLabel = { Text("Try every feature with test cars") },
                )
            }
            item { ListHeader { Text("Hyundai USA") } }
            item {
                FilledTonalButton(onClick = enterUsername, modifier = Modifier.fillMaxWidth(), label = { Text("Email") }, secondaryLabel = {
                    Text(username.ifEmpty { "Not set" }, maxLines = 1)
                })
            }
            item {
                FilledTonalButton(onClick = enterPassword, modifier = Modifier.fillMaxWidth(), label = { Text("Password") }, secondaryLabel = {
                    Text(if (password.isEmpty()) "Not set" else "•".repeat(password.length.coerceAtMost(8)))
                })
            }
            if (!isTest) {
                item {
                    FilledTonalButton(onClick = enterPin, modifier = Modifier.fillMaxWidth(), label = { Text("PIN") }, secondaryLabel = {
                        Text(if (pin.isEmpty()) "Not set" else "•".repeat(pin.length))
                    })
                }
            }
            item {
                Button(
                    onClick = { signIn(username, password, pin) },
                    enabled = !signingIn && username.isNotEmpty() && password.isNotEmpty() && (isTest || pin.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Sign in") },
                )
            }
            error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
            }
        }
    }
}

const val TEST_USERNAME = "testaccount@betterblue.com"
const val TEST_PASSWORD = "betterblue"

/** iOS's watch settings: units, look, the car's colour; plus accounts and help. */
@Composable
fun SettingsScreen(graph: WearGraph, onSignIn: () -> Unit, onHelp: () -> Unit) {
    val unit by graph.settings.distanceUnit.collectAsStateWithLifecycle()
    val rivian by graph.settings.rivian.collectAsStateWithLifecycle()
    val selectedVin by graph.settings.selectedVin.collectAsStateWithLifecycle()
    val vehicles by graph.model.vehicles.collectAsStateWithLifecycle()
    val accounts by graph.model.accounts.collectAsStateWithLifecycle()
    val vehicle = vehicles?.firstOrNull { it.vin == selectedVin } ?: vehicles?.firstOrNull()
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.testTag("settings")) {
            item { ListHeader { Text("Settings") } }
            item {
                SwitchButton(
                    checked = unit == Distance.Units.KILOMETERS,
                    onCheckedChange = { graph.settings.setDistanceUnit(if (it) Distance.Units.KILOMETERS else Distance.Units.MILES) },
                    modifier = Modifier.fillMaxWidth().testTag("kilometres"),
                    label = { Text("Kilometres") },
                )
            }
            item {
                SwitchButton(
                    checked = rivian,
                    onCheckedChange = graph.settings::setRivian,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Rivian look") },
                )
            }
            if (vehicle != null) {
                item { ListHeader { Text("${vehicle.displayName} colour") } }
                VehicleImageColor.availableColors(vehicle.model).forEach { color ->
                    item {
                        OutlinedButton(
                            onClick = { graph.model.setColor(vehicle.vin) { it.vehicleImageColor = color } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(color.displayName) },
                            icon = {
                                if (vehicle.vehicleImageColor == color) Icon(Icons.Rounded.Check, contentDescription = "Selected")
                            },
                        )
                    }
                }
            }
            item { ListHeader { Text("Accounts") } }
            accounts.orEmpty().forEach { account ->
                item {
                    OutlinedButton(
                        onClick = { graph.model.signOut(account) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Sign out") },
                        secondaryLabel = { Text(account.username, maxLines = 1) },
                    )
                }
            }
            item { FilledTonalButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth(), label = { Text("Add account") }) }
            item { OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth(), label = { Text("Help") }) }
        }
    }
}

/** The watch's troubleshooting, as iOS's watch help puts syncing first. */
@Composable
fun HelpScreen() {
    val sections = listOf(
        "Getting your account" to "Open GhostBlue on your phone: it sends its accounts to the watch. Or sign in here, or try the test account.",
        "Signing in" to "Hyundai USA needs your Bluelink email, password and PIN. Kia and other regions aren't on Android yet.",
        "Tile and complications" to "Add the GhostBlue tile, or a complication on your watch face, to see the charge at a glance. They show the car you last looked at.",
        "Commands" to "Lock, climate and charging wait for the car to confirm. Tap the button again to stop waiting; the command may still reach the car.",
    )
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ListHeader { Text("Help") } }
            sections.forEach { (title, body) ->
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(body, style = MaterialTheme.typography.bodySmall, color = Color.Unspecified)
                    }
                }
            }
        }
    }
}
