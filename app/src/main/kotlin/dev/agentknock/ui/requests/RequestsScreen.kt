package dev.agentknock.ui.requests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.R
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.request.PairingState
import dev.agentknock.storage.request.PollInboxResult
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
internal fun RequestsScreen(viewModel: RequestsViewModel = viewModel()) {
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selectedRequest by viewModel.selectedRequest.collectAsStateWithLifecycle()
    val polling by viewModel.polling.collectAsStateWithLifecycle()
    val lastPollResult by viewModel.lastPollResult.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (selection == null) {
            RequestList(
                requests = requests,
                polling = polling,
                pollProblem = lastPollResult.messageResource(),
                onRefresh = viewModel::refresh,
                onOpen = viewModel::selectRequest,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            BackHandler { viewModel.selectRequest(null) }
            val request = selectedRequest
            if (request == null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                PairingRequestDetail(
                    request = request,
                    onBack = { viewModel.selectRequest(null) },
                    onChooseSas = { index ->
                        scope.launch {
                            when (viewModel.chooseSas(request.id, index)) {
                                PairingDecisionResult.VERIFIED -> report(
                                    resources.getString(R.string.pairing_sas_verified),
                                )
                                PairingDecisionResult.REJECTED -> report(
                                    resources.getString(R.string.pairing_rejected),
                                )
                                PairingDecisionResult.NOT_PENDING -> report(
                                    resources.getString(R.string.pairing_not_pending),
                                )
                                PairingDecisionResult.NOT_FOUND -> report(
                                    resources.getString(R.string.request_not_found),
                                )
                            }
                        }
                    },
                    onReject = {
                        scope.launch {
                            when (viewModel.rejectPairing(request.id)) {
                                PairingDecisionResult.REJECTED -> report(
                                    resources.getString(R.string.pairing_rejected),
                                )
                                PairingDecisionResult.NOT_PENDING,
                                PairingDecisionResult.VERIFIED,
                                -> report(resources.getString(R.string.pairing_not_pending))
                                PairingDecisionResult.NOT_FOUND -> report(
                                    resources.getString(R.string.request_not_found),
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
        }
    }
}

@Composable
private fun RequestList(
    requests: List<InboxRequestSummary>,
    polling: Boolean,
    pollProblem: Int?,
    onRefresh: () -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.requests), style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onRefresh, enabled = !polling) {
                if (polling) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.refresh))
            }
        }
        HorizontalDivider()
        pollProblem?.let { message ->
            Text(
                text = stringResource(message),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        if (requests.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(
                        stringResource(R.string.no_requests),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(R.string.no_requests_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(requests, key = InboxRequestSummary::id) { request ->
                    RequestRow(request, onClick = { onOpen(request.id) })
                }
            }
        }
    }
}

@Composable
private fun RequestRow(request: InboxRequestSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.pairing_request),
                style = MaterialTheme.typography.titleMedium,
            )
            RequestStatus(request.pairingState)
        }
        Text(request.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(request.receivedAt)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun RequestStatus(state: PairingState) {
    val label = when (state) {
        PairingState.RECEIVING -> R.string.pairing_receiving
        PairingState.SAS_VERIFICATION_PENDING -> R.string.action_required
        PairingState.WAITING_FOR_FINISH -> R.string.waiting_for_client
        PairingState.REJECTED -> R.string.rejected
        PairingState.ACTIVE -> R.string.paired
        PairingState.VERIFICATION_FAILED -> R.string.action_required
    }
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelLarge,
        color = when (state) {
            PairingState.SAS_VERIFICATION_PENDING,
            PairingState.VERIFICATION_FAILED,
            -> MaterialTheme.colorScheme.primary
            PairingState.REJECTED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun PairingRequestDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    onChooseSas: (Int?) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pairing = request.pairing
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.pairing_request),
                style = MaterialTheme.typography.headlineMedium,
            )
            RequestStatus(pairing.pairingState)
        }

        when (pairing.pairingState) {
            PairingState.SAS_VERIFICATION_PENDING -> SasVerification(
                options = pairing.sasOptions,
                onChoose = onChooseSas,
            )
            PairingState.WAITING_FOR_FINISH -> StatusCard(
                title = stringResource(R.string.pairing_sas_verified),
                message = stringResource(R.string.pairing_waiting_for_finish_explanation),
            )
            PairingState.RECEIVING -> StatusCard(
                title = stringResource(R.string.pairing_receiving),
                message = stringResource(R.string.pairing_receiving_explanation),
            )
            PairingState.REJECTED -> StatusCard(
                title = stringResource(R.string.pairing_rejected),
                message = stringResource(R.string.pairing_rejected_explanation),
                error = true,
            )
            PairingState.ACTIVE -> StatusCard(
                title = stringResource(R.string.pairing_complete),
                message = stringResource(R.string.pairing_complete_explanation),
            )
            PairingState.VERIFICATION_FAILED -> StatusCard(
                title = stringResource(R.string.pairing_verification_failed),
                message = pairing.error ?: stringResource(R.string.pairing_verification_failed),
                error = true,
            )
        }

        if (
            pairing.pairingState == PairingState.RECEIVING ||
            pairing.pairingState == PairingState.VERIFICATION_FAILED
        ) {
            OutlinedButton(onClick = onReject, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.reject_pairing))
            }
        }

        ClientInformation(request)
    }
}

@Composable
private fun SasVerification(options: List<String>, onChoose: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.verify_pairing_code),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(stringResource(R.string.verify_pairing_code_explanation))
        options.forEachIndexed { index, option ->
            Button(onClick = { onChoose(index) }, modifier = Modifier.fillMaxWidth()) {
                Text(option, fontFamily = FontFamily.Monospace)
            }
        }
        OutlinedButton(onClick = { onChoose(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.none_of_the_above))
        }
        Text(
            stringResource(R.string.wrong_pairing_code_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(title: String, message: String, error: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
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
private fun ClientInformation(request: InboxRequestDetails) {
    val pairing = request.pairing
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.request_information), style = MaterialTheme.typography.titleLarge)
        InformationRow(stringResource(R.string.received), formatDate(request.receivedAt))
        InformationRow(stringResource(R.string.vault_address), pairing.vaultAddress)
        pairing.hostname?.let { InformationRow(stringResource(R.string.hostname), it) }
        pairing.platform?.let { InformationRow(stringResource(R.string.platform), it) }
        pairing.architecture?.let { InformationRow(stringResource(R.string.architecture), it) }
        pairing.osVersion?.let { InformationRow(stringResource(R.string.os_version), it) }
        pairing.cliVersion?.let { InformationRow(stringResource(R.string.cli_version), it) }
        pairing.machineId?.let { InformationRow(stringResource(R.string.machine_id), it) }
        InformationRow(stringResource(R.string.pairing_id), pairing.pairingId, monospace = true)
        InformationRow(stringResource(R.string.request_id), request.relayRequestId, monospace = true)
    }
}

@Composable
private fun InformationRow(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(value, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default)
        }
    }
}

private fun formatDate(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.MEDIUM).format(Date(timestamp))

private fun PollInboxResult?.messageResource(): Int? = when (this) {
    null, PollInboxResult.Success, PollInboxResult.NoVault -> null
    PollInboxResult.VaultSecretsUnavailable -> R.string.vault_keys_unavailable
    PollInboxResult.VaultSecretsCorrupted -> R.string.vault_keys_corrupted
    PollInboxResult.UnsupportedVaultEncryption -> R.string.vault_keys_unsupported
    is PollInboxResult.RelayRejected -> R.string.request_poll_rejected
    is PollInboxResult.RelayUnavailable -> R.string.request_poll_unavailable
    PollInboxResult.InvalidRelayResponse -> R.string.vault_relay_invalid_response
}
