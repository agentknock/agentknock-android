@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.agentknock.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentknock.R
import dev.agentknock.protocol.DeviceProtocol
import dev.agentknock.storage.vault.ClaimPairingAddressResult
import dev.agentknock.storage.vault.DeviceConfiguration
import dev.agentknock.storage.vault.DeviceIdentity
import dev.agentknock.ui.theme.agentknockColors
import kotlinx.coroutines.launch

@Composable
internal fun DeviceSetupScreen(
    configuration: DeviceConfiguration,
    onDone: (() -> Unit)?,
    changeAddressInitially: Boolean = false,
    onDeviceClaimed: () -> Unit,
    viewModel: DeviceSetupViewModel,
) {
    val claiming by viewModel.claiming.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastClaimResult.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editing by rememberSaveable(changeAddressInitially) {
        mutableStateOf(
            changeAddressInitially ||
                (configuration.active == null && configuration.candidate == null),
        )
    }
    var address by rememberSaveable { mutableStateOf(viewModel.generateAddress()) }
    var confirmChange by remember { mutableStateOf(false) }
    val active = configuration.active
    val candidate = configuration.candidate

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun performClaim() {
        scope.launch {
            editing = false
            val result = viewModel.stageAndClaim(address)
            reportClaimResult(result, resources::getString, ::report)
            if (result == ClaimPairingAddressResult.Claimed) {
                onDeviceClaimed()
                if (changeAddressInitially) onDone?.invoke()
            }
        }
    }

    fun claim() {
        if (active == null) performClaim() else confirmChange = true
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.navigationBars,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TopAppBar(
                title = {
                    Text(
                        if (changeAddressInitially) {
                            stringResource(R.string.change_pairing_address)
                        } else {
                            stringResource(R.string.device_setup)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (changeAddressInitially && onDone != null) {
                        IconButton(onClick = onDone) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (!changeAddressInitially) {
                    active?.let { ActiveDeviceCard(it) }
                }

                if (active != null && !active.credentialsAvailable) {
                    WarningCard(
                        title = stringResource(R.string.device_keys_unavailable),
                        message = stringResource(R.string.device_keys_unavailable_explanation),
                    )
                }

                if (candidate != null && !editing) {
                    CandidateCard(
                        candidate = candidate,
                        result = lastResult,
                        claiming = claiming,
                        onRetry = {
                            scope.launch {
                                val result = viewModel.retryClaim()
                                reportClaimResult(
                                    result,
                                    resources::getString,
                                    ::report,
                                )
                                if (result == ClaimPairingAddressResult.Claimed) {
                                    onDeviceClaimed()
                                    if (changeAddressInitially) onDone?.invoke()
                                }
                            }
                        },
                        onChooseAnother = {
                            viewModel.clearClaimResult()
                            address = viewModel.generateAddress()
                            editing = true
                        },
                        onDiscard = active?.let {
                            {
                                scope.launch {
                                    viewModel.discardCandidate()
                                }
                            }
                        },
                    )
                } else if (editing || active == null) {
                    AddressEditor(
                        address = address,
                        activeAddress = active?.address,
                        claiming = claiming,
                        onAddressChange = { address = it },
                        onGenerate = { address = viewModel.generateAddress() },
                        onClaim = ::claim,
                        onCancel = active?.let { { editing = false } },
                    )
                } else {
                    Button(onClick = { confirmChange = true }) {
                        Text(stringResource(R.string.change_pairing_address))
                    }
                }
            }
        }
    }

    if (confirmChange) {
        AlertDialog(
            onDismissRequest = { confirmChange = false },
            title = { Text(stringResource(R.string.change_pairing_address_question)) },
            text = { Text(stringResource(R.string.change_pairing_address_explanation)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmChange = false
                        if (editing) {
                            performClaim()
                        } else {
                            viewModel.clearClaimResult()
                            address = viewModel.generateAddress()
                            editing = true
                        }
                    },
                ) {
                    Text(
                        if (editing) stringResource(R.string.claim_pairing_address)
                        else stringResource(R.string.continue_action),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmChange = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ActiveDeviceCard(identity: DeviceIdentity) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (identity.credentialsAvailable) {
                MaterialTheme.agentknockColors.successContainer
            } else {
                MaterialTheme.agentknockColors.dangerContainer
            },
            contentColor = if (identity.credentialsAvailable) {
                MaterialTheme.agentknockColors.onSuccessContainer
            } else {
                MaterialTheme.agentknockColors.onDangerContainer
            },
        ),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (identity.credentialsAvailable) {
                    stringResource(R.string.pairing_ready)
                } else {
                    stringResource(R.string.device_keys_unavailable_title)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                identity.address,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
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
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message)
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: DeviceIdentity,
    result: ClaimPairingAddressResult?,
    claiming: Boolean,
    onRetry: () -> Unit,
    onChooseAnother: () -> Unit,
    onDiscard: (() -> Unit)?,
) {
    val unavailable = result == ClaimPairingAddressResult.AddressUnavailable
    Card {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (unavailable) {
                    stringResource(R.string.pairing_address_unavailable)
                } else {
                    stringResource(R.string.pairing_address_claim_incomplete)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(candidate.address, fontFamily = FontFamily.Monospace)
            Text(
                if (unavailable) {
                    stringResource(R.string.pairing_address_unavailable_explanation)
                } else {
                    stringResource(R.string.pairing_address_claim_incomplete_explanation)
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!unavailable) {
                    Button(
                        onClick = onRetry,
                        enabled = !claiming,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (claiming) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.retry))
                    }
                }
                OutlinedButton(
                    onClick = onChooseAnother,
                    enabled = !claiming,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.choose_another_address))
                }
                onDiscard?.let { discard ->
                    TextButton(onClick = discard, enabled = !claiming) {
                        Text(stringResource(R.string.discard))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressEditor(
    address: String,
    activeAddress: String?,
    claiming: Boolean,
    onAddressChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onClaim: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    val valid = DeviceProtocol.validPairingAddress(address) && address != activeAddress
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(address, selection = TextRange(address.length)))
    }
    LaunchedEffect(address) {
        if (fieldValue.text != address) {
            fieldValue = TextFieldValue(address, selection = TextRange(address.length))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (activeAddress == null) {
                stringResource(R.string.choose_pairing_address)
            } else {
                stringResource(R.string.choose_new_pairing_address)
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(stringResource(R.string.pairing_address_description))
        if (activeAddress != null) {
            Text(
                stringResource(R.string.change_pairing_address_existing_clients),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    if (address == activeAddress) {
                        stringResource(R.string.pairing_address_unchanged)
                    } else {
                        stringResource(R.string.pairing_address_format)
                    },
                )
            },
            isError = address.isNotEmpty() && !valid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onClaim,
                enabled = valid && !claiming,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (claiming) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (activeAddress == null) {
                            R.string.claim_pairing_address
                        } else {
                            R.string.change_pairing_address
                        },
                    ),
                )
            }
            OutlinedButton(
                onClick = onGenerate,
                enabled = !claiming,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.another_suggestion))
            }
            onCancel?.let { cancel ->
                TextButton(onClick = cancel, enabled = !claiming) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

private fun reportClaimResult(
    result: ClaimPairingAddressResult,
    getString: (Int) -> String,
    report: (String) -> Unit,
) {
    val message = when (result) {
        ClaimPairingAddressResult.Claimed -> getString(R.string.pairing_address_claimed)
        ClaimPairingAddressResult.AddressUnavailable -> getString(R.string.pairing_address_unavailable)
        ClaimPairingAddressResult.SameAddress -> getString(R.string.pairing_address_unchanged)
        ClaimPairingAddressResult.NoCandidate -> getString(R.string.pairing_address_claim_missing)
        ClaimPairingAddressResult.CredentialsUnavailable -> getString(R.string.device_keys_unavailable)
        ClaimPairingAddressResult.CredentialsCorrupted -> getString(R.string.device_keys_corrupted)
        ClaimPairingAddressResult.UnsupportedEncryption -> getString(R.string.device_keys_unsupported)
        is ClaimPairingAddressResult.RelayRejected -> getString(R.string.device_setup_relay_rejected)
        is ClaimPairingAddressResult.RelayUnavailable -> getString(R.string.device_setup_relay_unavailable)
        ClaimPairingAddressResult.InvalidRelayResponse -> getString(R.string.device_setup_relay_invalid_response)
    }
    report(message)
}
