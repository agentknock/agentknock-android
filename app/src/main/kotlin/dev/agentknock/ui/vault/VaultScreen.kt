package dev.agentknock.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentknock.R
import dev.agentknock.protocol.VaultProtocol
import dev.agentknock.storage.vault.ClaimVaultResult
import dev.agentknock.storage.vault.VaultConfiguration
import dev.agentknock.storage.vault.VaultIdentity
import kotlinx.coroutines.launch

@Composable
internal fun VaultScreen(
    configuration: VaultConfiguration,
    authenticate: (
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
    onDone: (() -> Unit)?,
    viewModel: VaultViewModel,
) {
    val claiming by viewModel.claiming.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastClaimResult.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editing by rememberSaveable {
        mutableStateOf(configuration.active == null && configuration.candidate == null)
    }
    var address by rememberSaveable { mutableStateOf(viewModel.generateAddress()) }
    var confirmChange by remember { mutableStateOf(false) }
    val active = configuration.active
    val candidate = configuration.candidate

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun authenticateThen(title: String, action: suspend () -> Unit) {
        authenticate(title, { scope.launch { action() } }, ::report)
    }

    fun claim() {
        authenticateThen(resources.getString(R.string.confirm_claim_vault)) {
            editing = false
            reportClaimResult(viewModel.stageAndClaim(address), resources::getString, ::report)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.vault), style = MaterialTheme.typography.headlineLarge)
                onDone?.let { done ->
                    TextButton(onClick = done) { Text(stringResource(R.string.done)) }
                }
            }

            active?.let { ActiveVaultCard(it) }

            if (active != null && !active.secretsAvailable) {
                WarningCard(
                    title = stringResource(R.string.vault_keys_unavailable),
                    message = stringResource(R.string.vault_keys_unavailable_explanation),
                )
            }

            if (candidate != null && !editing) {
                CandidateCard(
                    candidate = candidate,
                    result = lastResult,
                    claiming = claiming,
                    onRetry = {
                        authenticateThen(resources.getString(R.string.confirm_claim_vault)) {
                            reportClaimResult(
                                viewModel.retryClaim(),
                                resources::getString,
                                ::report,
                            )
                        }
                    },
                    onChooseAnother = {
                        viewModel.clearClaimResult()
                        address = viewModel.generateAddress()
                        editing = true
                    },
                    onDiscard = active?.let {
                        {
                            authenticateThen(resources.getString(R.string.confirm_discard_claim)) {
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
                    Text(stringResource(R.string.change_vault_address))
                }
            }
        }
    }

    if (confirmChange) {
        AlertDialog(
            onDismissRequest = { confirmChange = false },
            title = { Text(stringResource(R.string.change_vault_address_question)) },
            text = { Text(stringResource(R.string.change_vault_address_explanation)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmChange = false
                        viewModel.clearClaimResult()
                        address = viewModel.generateAddress()
                        editing = true
                    },
                ) { Text(stringResource(R.string.continue_action)) }
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
private fun ActiveVaultCard(identity: VaultIdentity) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (identity.secretsAvailable) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (identity.secretsAvailable) {
                    stringResource(R.string.vault_ready)
                } else {
                    stringResource(R.string.vault_needs_replacement)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                identity.address,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun WarningCard(title: String, message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
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
    candidate: VaultIdentity,
    result: ClaimVaultResult?,
    claiming: Boolean,
    onRetry: () -> Unit,
    onChooseAnother: () -> Unit,
    onDiscard: (() -> Unit)?,
) {
    val unavailable = result == ClaimVaultResult.AddressUnavailable
    Card {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (unavailable) {
                    stringResource(R.string.vault_address_unavailable)
                } else {
                    stringResource(R.string.vault_claim_incomplete)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(candidate.address, fontFamily = FontFamily.Monospace)
            Text(
                if (unavailable) {
                    stringResource(R.string.vault_address_unavailable_explanation)
                } else {
                    stringResource(R.string.vault_claim_incomplete_explanation)
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
    val valid = VaultProtocol.validAddress(address) && address != activeAddress
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
                stringResource(R.string.choose_vault_address)
            } else {
                stringResource(R.string.choose_new_vault_address)
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(stringResource(R.string.vault_address_description))
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                onAddressChange(it.text)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.vault_address)) },
            supportingText = {
                Text(
                    if (address == activeAddress) {
                        stringResource(R.string.vault_address_unchanged)
                    } else {
                        stringResource(R.string.vault_address_format)
                    },
                )
            },
            isError = address.isNotEmpty() && !valid,
            singleLine = true,
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
                Text(stringResource(R.string.claim_vault_address))
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
    result: ClaimVaultResult,
    getString: (Int) -> String,
    report: (String) -> Unit,
) {
    val message = when (result) {
        ClaimVaultResult.Claimed -> getString(R.string.vault_claimed)
        ClaimVaultResult.AddressUnavailable -> getString(R.string.vault_address_unavailable)
        ClaimVaultResult.SameAddress -> getString(R.string.vault_address_unchanged)
        ClaimVaultResult.NoCandidate -> getString(R.string.vault_claim_missing)
        ClaimVaultResult.SecretsUnavailable -> getString(R.string.vault_keys_unavailable)
        ClaimVaultResult.SecretsCorrupted -> getString(R.string.vault_keys_corrupted)
        ClaimVaultResult.UnsupportedEncryption -> getString(R.string.vault_keys_unsupported)
        is ClaimVaultResult.RelayRejected -> getString(R.string.vault_relay_rejected)
        is ClaimVaultResult.RelayUnavailable -> getString(R.string.vault_relay_unavailable)
        ClaimVaultResult.InvalidRelayResponse -> getString(R.string.vault_relay_invalid_response)
    }
    report(message)
}
