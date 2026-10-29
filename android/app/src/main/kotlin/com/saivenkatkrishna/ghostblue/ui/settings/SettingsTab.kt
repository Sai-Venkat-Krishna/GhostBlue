package com.saivenkatkrishna.ghostblue.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Http
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.saivenkatkrishna.ghostblue.BuildConfig
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.kit.model.Temperature
import com.saivenkatkrishna.ghostblue.settings.AppRefreshInterval
import com.saivenkatkrishna.ghostblue.settings.AppTheme
import com.saivenkatkrishna.ghostblue.ui.components.cardToggleColors
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.home.VehicleSettingsContent
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Screens under Settings. */
sealed interface SettingsRoute {
    data object Root : SettingsRoute
    data object AddAccount : SettingsRoute
    data class Account(val id: UUID) : SettingsRoute
    data class Vehicle(val vin: String) : SettingsRoute
    data object Backup : SettingsRoute
    data object HttpLogs : SettingsRoute
    data class HttpLog(val id: UUID) : SettingsRoute
    data object About : SettingsRoute
}

/** Settings and the screens under it, on a Navigation 3 back stack. */
@Composable
fun SettingsTab(incomingBackup: MutableStateFlow<Uri?>) {
    val stack = remember { mutableStateListOf<SettingsRoute>(SettingsRoute.Root) }
    val pending by incomingBackup.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        if (pending != null && stack.lastOrNull() != SettingsRoute.Backup) stack += SettingsRoute.Backup
    }
    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
    NavDisplay(
        backStack = stack,
        onBack = { back() },
        entryProvider = entryProvider {
            entry<SettingsRoute.Root> { SettingsRoot(onOpen = { stack += it }) }
            entry<SettingsRoute.AddAccount> { SignInScreen(onBack = { back() }, onDone = { back() }) }
            entry<SettingsRoute.Account> { route -> AccountScreen(route.id, onBack = { back() }) }
            entry<SettingsRoute.Vehicle> { route -> VehicleScreen(route.vin, onBack = { back() }) }
            entry<SettingsRoute.Backup> { BackupScreen(incomingBackup, onBack = { back() }) }
            entry<SettingsRoute.HttpLogs> { HttpLogsScreen(onBack = { back() }, onOpen = { stack += SettingsRoute.HttpLog(it) }) }
            entry<SettingsRoute.HttpLog> { route -> HttpLogDetailScreen(route.id, onBack = { back() }) }
            entry<SettingsRoute.About> { AboutScreen(onBack = { back() }) }
        },
    )
}

@Composable
private fun SettingsRoot(onOpen: (SettingsRoute) -> Unit) {
    val graph = LocalAppGraph.current
    val settings = graph.settings
    val garage = graph.garage
    val accounts by garage.accounts.collectAsStateWithLifecycle()
    val vehicles by garage.vehicles.collectAsStateWithLifecycle()
    val distanceUnit by settings.distanceUnit.collectAsStateWithLifecycle()
    val temperatureUnit by settings.temperatureUnit.collectAsStateWithLifecycle()
    val theme by settings.theme.collectAsStateWithLifecycle()
    val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle()
    val refreshInterval by settings.refreshInterval.collectAsStateWithLifecycle()
    val debugMode by settings.debugMode.collectAsStateWithLifecycle()
    val hideLocation by settings.hideVehicleLocation.collectAsStateWithLifecycle()
    val redact by settings.redactPrivateLocations.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text("Settings") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.padding(padding).fillMaxSize().testTag("settingsList"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.widthIn(max = 720.dp)) {
                    Section("Accounts")
                    accounts.orEmpty().forEach { account ->
                        NavRow(account.username, account.brandEnum.displayName, Icons.Rounded.AccountCircle) { onOpen(SettingsRoute.Account(account.id)) }
                    }
                    NavRow("Add account", null, Icons.Rounded.Add, tag = "addAccount") { onOpen(SettingsRoute.AddAccount) }

                    if (!vehicles.isNullOrEmpty()) {
                        Section("Vehicles")
                        vehicles.orEmpty().forEach { vehicle ->
                            NavRow(vehicle.displayName, vehicle.model.takeIf { it != vehicle.displayName }, Icons.Rounded.DirectionsCar) {
                                onOpen(SettingsRoute.Vehicle(vehicle.vin))
                            }
                        }
                    }

                    Section("Appearance")
                    ChoiceRow("Theme", Icons.Rounded.Palette, AppTheme.entries, theme, { it.title }, settings::setTheme, tag = "theme")
                    if (theme == AppTheme.MATERIAL) {
                        SwitchListRow("Wallpaper colours", "Material You colours from your wallpaper", dynamicColor, settings::setDynamicColor)
                    }

                    Section("Units")
                    ChoiceRow("Distance", Icons.Rounded.Straighten, Distance.Units.entries, distanceUnit, { it.displayName }, settings::setDistanceUnit, tag = "distance")
                    ChoiceRow("Temperature", Icons.Rounded.Thermostat, Temperature.Units.entries, temperatureUnit, { it.symbol }, settings::setTemperatureUnit, tag = "temperature")

                    Section("App")
                    MenuRow("Background refresh", refreshInterval.displayName, Icons.Rounded.Refresh, AppRefreshInterval.entries, { it.displayName }, settings::setRefreshInterval)
                    Footer("How often the app refreshes the selected vehicle while it's open.")

                    Section("Privacy")
                    SwitchListRow("Hide exact vehicle location", null, hideLocation, settings::setHideVehicleLocation, icon = Icons.Rounded.VisibilityOff)
                    SwitchListRow("Redact private locations in exports", null, redact, settings::setRedactPrivateLocations)

                    Section("Data")
                    NavRow("Backup & Restore", "Move everything to another phone or an iPhone", Icons.Rounded.Backup, tag = "backupRow") { onOpen(SettingsRoute.Backup) }

                    Section("Debug")
                    SwitchListRow("Debug mode", "Keeps a log of API requests", debugMode, settings::setDebugMode, icon = Icons.Rounded.BugReport)
                    if (debugMode) NavRow("HTTP Logs", null, Icons.Rounded.Http, tag = "httpLogsRow") { onOpen(SettingsRoute.HttpLogs) }
                    ListItem(
                        headlineContent = { Text("Clear All Data", color = MaterialTheme.colorScheme.error) },
                        leadingContent = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable { confirmClear = true },
                        colors = transparentListColors(),
                    )

                    Section("About")
                    NavRow("About GhostBlue", "Version ${BuildConfig.VERSION_NAME}", Icons.Rounded.Info) { onOpen(SettingsRoute.About) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear All Data") },
            text = { Text("Deletes every account, vehicle, preset, charging session, trip and log on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        runCatching { garage.clearAllData() }
                            .onSuccess { garage.post("All data cleared") }
                            .onFailure { garage.post("Error: ${it.message}") }
                    }
                }) { Text("Clear All", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun transparentListColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun NavRow(title: String, subtitle: String?, icon: ImageVector, tag: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        colors = transparentListColors(),
        modifier = Modifier.clickable(onClick = onClick).let { if (tag != null) it.testTag(tag) else it },
    )
}

@Composable
private fun SwitchListRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = transparentListColors(),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

/** A setting with two or three values as a connected button group. */
@Composable
private fun <T> ChoiceRow(
    title: String,
    icon: ImageVector,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    tag: String,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, start = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            options.forEachIndexed { index, option ->
                ToggleButton(
                    colors = cardToggleColors(),
                    checked = option == selected,
                    onCheckedChange = { onSelect(option) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f).testTag("${tag}_$index"),
                ) { Text(label(option)) }
            }
        }
    }
}

@Composable
private fun <T> MenuRow(title: String, value: String, icon: ImageVector, options: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(value) },
            leadingContent = { Icon(icon, contentDescription = null) },
            colors = transparentListColors(),
            modifier = Modifier.clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

/** A screen under Settings: a small app bar with a back arrow. */
@Composable
fun SubScreen(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = { actions() },
            )
        },
        content = content,
    )
}

/** One account, as iOS's `AccountInfoView`: details, credentials, hidden cars and removal. */
@Composable
private fun AccountScreen(id: UUID, onBack: () -> Unit) {
    val garage = LocalAppGraph.current.garage
    val accounts by garage.accounts.collectAsStateWithLifecycle()
    val allVehicles by garage.allVehicles.collectAsStateWithLifecycle()
    val account = accounts?.firstOrNull { it.id == id }
    LaunchedEffect(account == null) { if (account == null && accounts != null) onBack() }
    account ?: return
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SubScreen(account.username, onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Section("Account")
            ListItem(headlineContent = { Text("Username") }, supportingContent = { Text(account.username) }, colors = transparentListColors())
            ListItem(headlineContent = { Text("Brand") }, supportingContent = { Text(account.brandEnum.displayName) }, colors = transparentListColors())
            ListItem(headlineContent = { Text("Region") }, supportingContent = { Text(account.regionEnum.displayName) }, colors = transparentListColors())
            Section("Credentials")
            ListItem(headlineContent = { Text("Change password") }, modifier = Modifier.clickable { editing = "password" }, colors = transparentListColors())
            if (account.brandEnum != Brand.KIA && account.brandEnum != Brand.FAKE) {
                ListItem(headlineContent = { Text("Change PIN") }, modifier = Modifier.clickable { editing = "pin" }, colors = transparentListColors())
            }
            val hidden = allVehicles.orEmpty().filter { it.ownerAccountId == id && it.isHidden }
            if (hidden.isNotEmpty()) {
                Section("Hidden vehicles")
                hidden.forEach { vehicle ->
                    ListItem(
                        headlineContent = { Text(vehicle.displayName) },
                        supportingContent = { Text("VIN: ${vehicle.vin}") },
                        trailingContent = { TextButton(onClick = { garage.editVehicle(vehicle.vin) { it.isHidden = false } }) { Text("Show") } },
                        colors = transparentListColors(),
                    )
                }
            }
            OutlinedButton(
                onClick = { confirmRemove = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
            ) { Text("Remove account") }
        }
    }
    editing?.let { field ->
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (field == "pin") "Change PIN" else "Change password") },
            text = { OutlinedTextField(value, { value = it }, singleLine = true, visualTransformation = PasswordVisualTransformation()) },
            confirmButton = {
                TextButton(onClick = {
                    editing = null
                    scope.launch {
                        val error = garage.updateCredentials(
                            id,
                            password = if (field == "password") value else account.password,
                            pin = if (field == "pin") value else account.pin,
                        )
                        garage.post(
                            error?.let { "${it.action} failed: ${it.summary}" }
                                ?: if (field == "pin") "PIN updated successfully" else "Password updated successfully",
                        )
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove account?") },
            text = { Text("Its vehicles and their presets are removed from this device. Charging and trip history stay.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; garage.removeAccount(account) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VehicleScreen(vin: String, onBack: () -> Unit) {
    val garage = LocalAppGraph.current.garage
    val vehicles by garage.allVehicles.collectAsStateWithLifecycle()
    val vehicle = vehicles?.firstOrNull { it.vin == vin } ?: return
    SubScreen(vehicle.displayName, onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            VehicleSettingsContent(vehicle)
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    SubScreen("About", onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(headlineContent = { Text("Version") }, supportingContent = { Text(BuildConfig.VERSION_NAME) }, colors = transparentListColors())
            ListItem(headlineContent = { Text("Build") }, supportingContent = { Text("${BuildConfig.VERSION_CODE}") }, colors = transparentListColors())
            ListItem(
                headlineContent = { Text("App source code") },
                supportingContent = { Text("github.com/schmidtwmark/GhostBlue") },
                leadingContent = { Icon(Icons.Rounded.Code, null) },
                modifier = Modifier.clickable { open("https://github.com/schmidtwmark/GhostBlue") },
                colors = transparentListColors(),
            )
            ListItem(
                headlineContent = { Text("Client source code") },
                supportingContent = { Text("github.com/schmidtwmark/BetterBlueKit") },
                leadingContent = { Icon(Icons.Rounded.Code, null) },
                modifier = Modifier.clickable { open("https://github.com/schmidtwmark/BetterBlueKit") },
                colors = transparentListColors(),
            )
            Footer("GhostBlue for Android, built with Jetpack Compose and Material 3 Expressive. Not affiliated with Hyundai or Kia.")
        }
    }
}
