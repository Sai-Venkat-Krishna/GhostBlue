package com.saivenkatkrishna.ghostblue.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.saivenkatkrishna.ghostblue.BuildConfig
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeVehicleRecord
import com.saivenkatkrishna.ghostblue.kit.api.fake.FakeVehicleSeeds
import com.saivenkatkrishna.ghostblue.kit.api.supportedRegions
import com.saivenkatkrishna.ghostblue.kit.model.Brand
import com.saivenkatkrishna.ghostblue.kit.model.Region
import com.saivenkatkrishna.ghostblue.kit.model.isTestAccount
import com.saivenkatkrishna.ghostblue.ui.components.cardToggleColors
import com.saivenkatkrishna.ghostblue.ui.LocalAppGraph
import com.saivenkatkrishna.ghostblue.ui.UserError
import com.saivenkatkrishna.ghostblue.ui.components.ErrorCard
import com.saivenkatkrishna.ghostblue.ui.theme.LocalGhostBlueColors
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Adding an account, as iOS's Add Account: brand and region, the
 * credentials, and for the test account (or the Fake brand) the test cars
 * it starts with. Signs in and loads the cars before closing.
 */
@Composable
fun SignInScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val garage = LocalAppGraph.current.garage
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var chosenBrand by rememberSaveable { mutableStateOf(Brand.HYUNDAI) }
    var region by rememberSaveable { mutableStateOf(Region.USA) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UserError?>(null) }
    val testCars = remember { mutableStateListOf<FakeVehicleRecord>() }
    val draftAccountId = remember { UUID.randomUUID() }

    val isTest = isTestAccount(username, password)
    val brand = if (isTest) Brand.FAKE else chosenBrand
    val brands = Brand.availableBrands(username, password, includeTestBrands = BuildConfig.DEBUG)
    // The test account starts with the three starter cars; the owner can change them before signing in.
    LaunchedEffect(brand) {
        if (brand == Brand.FAKE && testCars.isEmpty()) testCars += FakeVehicleSeeds.defaultRecords(draftAccountId)
    }
    val needsPin = brand == Brand.HYUNDAI
    val canSubmit = username.isNotBlank() && password.isNotEmpty() && (!needsPin || pin.isNotEmpty()) && !working

    fun submit() {
        if (!canSubmit) return
        working = true
        error = null
        scope.launch {
            val result = garage.signIn(username, password, pin, brand, region, if (brand == Brand.FAKE) testCars.toList() else emptyList())
            working = false
            if (result == null) onDone() else error = result
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add account") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Service", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    brands.forEachIndexed { index, option ->
                        ToggleButton(
                            colors = cardToggleColors(),
                            checked = brand == option,
                            onCheckedChange = {
                                chosenBrand = option
                                if (option == Brand.FAKE && username.isEmpty() && password.isEmpty()) {
                                    username = "fake-${UUID.randomUUID().toString().take(8)}@ghostblue.com"
                                    password = "ghostblue"
                                }
                            },
                            enabled = !isTest || option == Brand.FAKE,
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                brands.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            modifier = Modifier.weight(1f).testTag("brand_${option.rawValue}"),
                        ) { Text(if (option == Brand.FAKE) "Test car" else option.displayName) }
                    }
                }
                if (brand != Brand.FAKE) {
                    RegionPicker(region) { region = it }
                    if (region !in supportedRegions(brand)) {
                        Notice(
                            "${brand.displayName} ${region.displayName} is unsupported on Android yet.",
                            Icons.Rounded.Warning,
                            LocalGhostBlueColors.current.service,
                        )
                    }
                }

                Text("Account", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth().testTag("usernameField"),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (needsPin) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag("passwordField"),
                )
                if (needsPin) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { value -> pin = value.filter(Char::isDigit) },
                        label = { Text("PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth().testTag("pinField"),
                    )
                }
                Text(
                    if (brand == Brand.FAKE) {
                        "Using test account - fake data will be used"
                    } else {
                        "GhostBlue requires an active Hyundai BlueLink or Kia Connect subscription. Your credentials stay on this device."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (brand == Brand.FAKE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Test cars", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { testCars += FakeVehicleSeeds.record(testCars.size, draftAccountId) }) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add car")
                        }
                    }
                    if (testCars.isEmpty()) {
                        Text("No test cars", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    testCars.forEachIndexed { index, record ->
                        ListItem(
                            headlineContent = { Text(record.vehicle.model) },
                            supportingContent = { Text(record.vin) },
                            leadingContent = { Icon(Icons.Rounded.DirectionsCar, contentDescription = null) },
                            trailingContent = {
                                IconButton(onClick = { testCars.removeAt(index) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove ${record.vehicle.model}")
                                }
                            },
                        )
                    }
                }

                error?.let { ErrorCard(it) }

                Button(
                    onClick = { submit() },
                    enabled = canSubmit,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight).testTag("signInButton"),
                ) {
                    if (working) {
                        LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(12.dp))
                        Text("Signing in to ${brand.displayName}…")
                    } else {
                        Text("Sign in")
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionPicker(region: Region, onChange: (Region) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedTextField(
            value = region.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Region") },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Region.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.displayName) }, onClick = { onChange(option); expanded = false })
            }
        }
    }
}

@Composable
fun Notice(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.Info, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Surface(color = tint.copy(alpha = 0.12f), contentColor = tint, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
