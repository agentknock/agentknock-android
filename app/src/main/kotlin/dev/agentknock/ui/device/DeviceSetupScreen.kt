@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.device

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentknock.R
import dev.agentknock.protocol.DeviceProtocol
import dev.agentknock.storage.device.ClaimPairingAddressResult
import dev.agentknock.storage.device.DeviceConfiguration
import dev.agentknock.storage.device.DeviceIdentity
import dev.agentknock.ui.auth.DeviceAuthenticationChoices
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import dev.agentknock.ui.components.NavigationBackButton
import dev.agentknock.ui.requests.SelectableFact
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun DeviceSetupScreen(
    configuration: DeviceConfiguration,
    onDone: (() -> Unit)?,
    authenticationMode: DeviceAuthenticationMode,
    onAuthenticationModeChange: (DeviceAuthenticationMode) -> Unit,
    changeAddressInitially: Boolean = false,
    onOpenSettings: (() -> Unit)? = null,
    viewModel: DeviceSetupViewModel,
) {
    val claiming by viewModel.claiming.collectAsStateWithLifecycle()
    val result by viewModel.lastClaimResult.collectAsStateWithLifecycle()
    val active = configuration.active
    val candidate = configuration.candidate
    var welcomeComplete by rememberSaveable {
        mutableStateOf(changeAddressInitially || active != null || candidate != null)
    }
    var address by rememberSaveable(active?.id, candidate?.id, changeAddressInitially) {
        mutableStateOf(
            candidate?.address
                ?: active?.address?.takeIf { changeAddressInitially }
                ?: viewModel.generateAddress(),
        )
    }
    LaunchedEffect(candidate?.address) {
        candidate?.address?.let { candidateAddress ->
            if (result == null) address = candidateAddress
        }
    }

    fun submit() {
        if (candidate?.address == address) viewModel.retryClaim()
        else viewModel.stageAndClaim(address)
    }

    fun leaveAddressEditor() {
        if (candidate != null) viewModel.discardCandidate()
        onDone?.invoke()
    }

    BackHandler(enabled = changeAddressInitially && onDone != null, onBack = ::leaveAddressEditor)

    if (!welcomeComplete) {
        WelcomeScreen(onContinue = { welcomeComplete = true })
        return
    }

    DeviceSetupContent(
        active = active, candidate = candidate, address = address,
        claiming = claiming, result = result, changeAddressInitially = changeAddressInitially,
        authenticationMode = authenticationMode,
        onAuthenticationModeChange = onAuthenticationModeChange,
        onBack = (::leaveAddressEditor).takeIf { onDone != null },
        onOpenSettings = onOpenSettings,
        onAddressChange = {
            address = it
            viewModel.clearClaimResult()
        },
        onGenerate = {
            address = viewModel.generateAddress()
            viewModel.clearClaimResult()
        },
        onSubmit = ::submit,
    )
}

@Composable
internal fun DeviceSetupContent(
    active: DeviceIdentity?,
    candidate: DeviceIdentity?,
    address: String,
    claiming: Boolean,
    result: ClaimPairingAddressResult?,
    changeAddressInitially: Boolean,
    authenticationMode: DeviceAuthenticationMode,
    onAuthenticationModeChange: (DeviceAuthenticationMode) -> Unit,
    onBack: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    onAddressChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onSubmit: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.navigationBars) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TopAppBar(
                title = {
                    Text(
                        if (changeAddressInitially) {
                            stringResource(R.string.change_pairing_address)
                        } else {
                            "Set up Agentknock"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (changeAddressInitially && onBack != null) {
                        NavigationBackButton(checkNotNull(onBack))
                    }
                },
                actions = {
                    onOpenSettings?.takeIf { active != null }?.let { openSettings ->
                        IconButton(onClick = openSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
            Column(
                modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (active != null && !active.credentialsAvailable) {
                    WarningCard(
                        title = stringResource(R.string.device_keys_unavailable_title),
                        message = stringResource(R.string.device_keys_unavailable_explanation),
                    )
                }
                PairingAddressEditor(
                    address = address,
                    activeAddress = active?.address,
                    candidateAddress = candidate?.address,
                    claiming = claiming,
                    result = result,
                    onAddressChange = onAddressChange,
                    onGenerate = onGenerate,
                    onSubmit = onSubmit,
                    beforeSubmit = {
                        if (active == null) {
                            Text("Device authentication", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(top = 8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = MaterialTheme.shapes.large,
                            ) {
                                DeviceAuthenticationChoices(
                                    selected = authenticationMode,
                                    enabled = !claiming,
                                    onSelect = onAuthenticationModeChange,
                                )
                            }
                        }
                    },
                )
            }
        }
    }

}

@Composable
internal fun WelcomeScreen(onContinue: () -> Unit) {
    // No top bar here, so the status bar inset must come from the scaffold.
    Scaffold(contentWindowInsets = WindowInsets.systemBars) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = Color.Black, shape = CircleShape, modifier = Modifier.size(88.dp)) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Text("Welcome to Agentknock", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Developer secrets stay on this phone and are provided only to approved commands.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    WelcomeItem(
                        Icons.Outlined.PhoneAndroid,
                        "Add your secrets",
                        "Create secrets on this phone or upload them from your computer.",
                    )
                    WelcomeItem(
                        Icons.Outlined.Computer,
                        "Pair your computer",
                        "Connect the Agentknock command-line client and compare the verification code.",
                    )
                    WelcomeItem(
                        Icons.Outlined.SyncAlt,
                        "Approve their use",
                        "Review requests on your phone. Optional paid AI review can decide for you, " +
                            "using your instructions without receiving sensitive values or private keys.",
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun WelcomeItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.size(42.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PairingAddressEditor(
    address: String,
    activeAddress: String?,
    candidateAddress: String?,
    claiming: Boolean,
    result: ClaimPairingAddressResult?,
    onAddressChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onSubmit: () -> Unit,
    beforeSubmit: @Composable () -> Unit,
) {
    val valid = DeviceProtocol.validPairingAddress(address) && address != activeAddress
    val addressUnavailable = result == ClaimPairingAddressResult.AddressUnavailable
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(address, selection = TextRange(address.length)))
    }
    LaunchedEffect(address) {
        if (fieldValue.text != address) {
            fieldValue = TextFieldValue(address, selection = TextRange(address.length))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (activeAddress == null) {
            Text("Pairing address", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            stringResource(R.string.pairing_address_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (activeAddress != null) {
            Text(
                stringResource(R.string.change_pairing_address_existing_clients),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectableFact(
                icon = Icons.Outlined.Link,
                label = "Current pairing address",
                value = activeAddress,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                onAddressChange(it.text)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.pairing_address)) },
            supportingText = {
                Text(
                    when {
                        address == activeAddress -> stringResource(R.string.pairing_address_unchanged)
                        addressUnavailable -> result.explanation()
                        else -> stringResource(R.string.pairing_address_format)
                    },
                )
            },
            isError = addressUnavailable ||
                (address.isNotEmpty() && !DeviceProtocol.validPairingAddress(address)),
            enabled = !claiming,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
        )
        result?.takeUnless {
            it == ClaimPairingAddressResult.Claimed || it == ClaimPairingAddressResult.AddressUnavailable
        }?.let {
            Text(it.explanation(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onGenerate, enabled = !claiming) {
                Icon(
                    Icons.Outlined.Autorenew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.another_suggestion))
            }
        }
        beforeSubmit()
        Button(
            onClick = onSubmit,
            enabled = valid && !claiming,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (claiming) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                when {
                    claiming -> if (activeAddress == null) "Claiming address…" else "Changing address…"
                    candidateAddress == address && result != null -> "Try again"
                    activeAddress == null -> stringResource(R.string.claim_pairing_address)
                    else -> stringResource(R.string.change_pairing_address)
                },
            )
        }
    }
}

@Composable
private fun WarningCard(title: String, message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.agentknockColors.dangerContainer,
            contentColor = MaterialTheme.agentknockColors.onDangerContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message)
        }
    }
}

private fun ClaimPairingAddressResult.explanation(): String = when (this) {
    ClaimPairingAddressResult.Claimed -> "Pairing address claimed."
    ClaimPairingAddressResult.AddressUnavailable ->
        "That pairing address is already in use. Edit it or choose another suggestion."
    ClaimPairingAddressResult.SameAddress -> "This is already your pairing address."
    ClaimPairingAddressResult.NoCandidate -> "The claim could not be resumed. Try again."
    ClaimPairingAddressResult.CredentialsCorrupted -> "The device credentials could not be read."
    ClaimPairingAddressResult.UnsupportedEncryption ->
        "The device credentials use an unsupported encryption format."
    is ClaimPairingAddressResult.RelayRejected ->
        message ?: "The relay rejected the claim (${code ?: status})."
    is ClaimPairingAddressResult.RelayUnavailable ->
        message ?: "The relay could not be reached. Try again."
    ClaimPairingAddressResult.InvalidRelayResponse ->
        "The relay returned an invalid response. Try again."
}
