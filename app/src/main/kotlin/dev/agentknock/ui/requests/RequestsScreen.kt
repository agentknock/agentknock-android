package dev.agentknock.ui.requests

import android.content.res.Resources
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
import dev.agentknock.storage.profile.CredentialProfileMetadata
import dev.agentknock.storage.request.CredentialCompletionResult
import dev.agentknock.storage.request.CredentialDecision
import dev.agentknock.storage.request.CredentialDecisionResult
import dev.agentknock.storage.request.CredentialRequestDetails
import dev.agentknock.storage.request.CredentialRequestState
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestKind
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.request.PairingState
import dev.agentknock.storage.request.PollInboxResult
import dev.agentknock.storage.request.ProfileListRequestDetails
import dev.agentknock.storage.request.ProfileListRequestState
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
            return@Scaffold
        }

        BackHandler { viewModel.selectRequest(null) }
        val request = selectedRequest
        if (request == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        val detailModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when {
            request.pairing != null -> PairingRequestDetail(
                request = request,
                onBack = { viewModel.selectRequest(null) },
                onChooseSas = { index ->
                    scope.launch {
                        val message = when (viewModel.chooseSas(request.id, index)) {
                            PairingDecisionResult.VERIFIED -> R.string.pairing_sas_verified
                            PairingDecisionResult.REJECTED -> R.string.pairing_rejected
                            PairingDecisionResult.NOT_PENDING -> R.string.pairing_not_pending
                            PairingDecisionResult.NOT_FOUND -> R.string.request_not_found
                        }
                        report(resources.getString(message))
                    }
                },
                onReject = {
                    scope.launch {
                        val message = when (viewModel.rejectPairing(request.id)) {
                            PairingDecisionResult.REJECTED -> R.string.pairing_rejected
                            PairingDecisionResult.NOT_FOUND -> R.string.request_not_found
                            PairingDecisionResult.NOT_PENDING,
                            PairingDecisionResult.VERIFIED,
                            -> R.string.pairing_not_pending
                        }
                        report(resources.getString(message))
                    }
                },
                modifier = detailModifier,
            )
            request.credential != null -> CredentialRequestDetail(
                request = request,
                onBack = { viewModel.selectRequest(null) },
                onApprove = {
                    scope.launch {
                        report(viewModel.approveCredentialRequest(request.id).message(resources))
                    }
                },
                onDeny = {
                    scope.launch {
                        report(viewModel.denyCredentialRequest(request.id).message(resources))
                    }
                },
                modifier = detailModifier,
            )
            request.profileList != null -> ProfileListRequestDetail(
                request = request,
                onBack = { viewModel.selectRequest(null) },
                modifier = detailModifier,
            )
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
                stringResource(
                    when (request.kind) {
                        InboxRequestKind.PAIRING -> R.string.pairing_request
                        InboxRequestKind.CREDENTIAL -> R.string.credential_request
                        InboxRequestKind.PROFILE_LIST -> R.string.profile_list_request
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            RequestStatus(request)
        }
        Text(request.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (request.subtitle.isNotEmpty()) {
            Text(request.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
private fun RequestStatus(request: InboxRequestSummary) {
    request.pairingState?.let {
        PairingRequestStatus(it)
        return
    }
    request.profileListState?.let {
        ProfileListRequestStatus(it)
        return
    }
    CredentialRequestStatus(
        state = checkNotNull(request.credentialState),
        decision = request.credentialDecision,
        result = request.credentialResult,
    )
}

@Composable
private fun ProfileListRequestStatus(state: ProfileListRequestState) {
    StatusLabel(
        label = when (state) {
            ProfileListRequestState.WAITING_FOR_COMPLETION -> R.string.waiting_for_client
            ProfileListRequestState.COMPLETED -> R.string.delivered
            ProfileListRequestState.VERIFICATION_FAILED -> R.string.verification_failed
        },
        primary = state == ProfileListRequestState.VERIFICATION_FAILED,
        error = state == ProfileListRequestState.VERIFICATION_FAILED,
    )
}

@Composable
private fun PairingRequestStatus(state: PairingState) {
    val label = when (state) {
        PairingState.RECEIVING -> R.string.pairing_receiving
        PairingState.SAS_VERIFICATION_PENDING -> R.string.action_required
        PairingState.WAITING_FOR_FINISH -> R.string.waiting_for_client
        PairingState.REJECTED -> R.string.rejected
        PairingState.ACTIVE -> R.string.paired
        PairingState.VERIFICATION_FAILED -> R.string.action_required
    }
    StatusLabel(
        label = label,
        primary = state == PairingState.SAS_VERIFICATION_PENDING ||
            state == PairingState.VERIFICATION_FAILED,
        error = state == PairingState.REJECTED,
    )
}

@Composable
private fun CredentialRequestStatus(
    state: CredentialRequestState,
    decision: CredentialDecision?,
    result: CredentialCompletionResult?,
) {
    val label = when (state) {
        CredentialRequestState.APPROVAL_PENDING -> R.string.action_required
        CredentialRequestState.WAITING_FOR_COMPLETION -> R.string.waiting_for_client
        CredentialRequestState.VERIFICATION_FAILED -> R.string.verification_failed
        CredentialRequestState.COMPLETED -> when (result) {
            CredentialCompletionResult.APPROVED -> R.string.delivered
            CredentialCompletionResult.DENIED -> R.string.rejected
            CredentialCompletionResult.ABORTED -> R.string.aborted
            null -> R.string.completed
        }
    }
    StatusLabel(
        label = label,
        primary = state == CredentialRequestState.APPROVAL_PENDING ||
            state == CredentialRequestState.VERIFICATION_FAILED,
        error = state == CredentialRequestState.VERIFICATION_FAILED ||
            result == CredentialCompletionResult.DENIED ||
            (state == CredentialRequestState.WAITING_FOR_COMPLETION &&
                decision == CredentialDecision.DENIED),
    )
}

@Composable
private fun StatusLabel(label: Int, primary: Boolean, error: Boolean) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelLarge,
        color = when {
            error -> MaterialTheme.colorScheme.error
            primary -> MaterialTheme.colorScheme.primary
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
    val pairing = checkNotNull(request.pairing)
    DetailColumn(modifier, onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.pairing_request),
                style = MaterialTheme.typography.headlineMedium,
            )
            PairingRequestStatus(pairing.pairingState)
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

        PairingClientInformation(request)
    }
}

@Composable
private fun CredentialRequestDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val credential = checkNotNull(request.credential)
    DetailColumn(modifier, onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.credential_request),
                style = MaterialTheme.typography.headlineMedium,
            )
            CredentialRequestStatus(
                credential.state,
                credential.decision,
                credential.completionResult,
            )
        }

        CredentialStateCard(credential)

        if (credential.state == CredentialRequestState.APPROVAL_PENDING) {
            if (credential.missingProfiles.isNotEmpty()) {
                StatusCard(
                    title = stringResource(R.string.missing_profiles),
                    message = credential.missingProfiles.joinToString(),
                    error = true,
                )
            }
            Button(
                onClick = onApprove,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.release_credentials))
            }
            OutlinedButton(onClick = onDeny, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.deny_request))
            }
        }

        CredentialProfiles(credential)
        CredentialOperation(credential)
        CredentialClientInformation(request, credential)
    }
}

@Composable
private fun ProfileListRequestDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profileList = checkNotNull(request.profileList)
    DetailColumn(modifier, onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.profile_list_request),
                style = MaterialTheme.typography.headlineMedium,
            )
            ProfileListRequestStatus(profileList.state)
        }
        when (profileList.state) {
            ProfileListRequestState.WAITING_FOR_COMPLETION -> StatusCard(
                title = stringResource(R.string.profile_catalog_sent),
                message = stringResource(R.string.profile_list_waiting_for_completion),
            )
            ProfileListRequestState.COMPLETED -> StatusCard(
                title = stringResource(R.string.profile_catalog_delivered),
                message = stringResource(R.string.profile_catalog_delivered_explanation),
            )
            ProfileListRequestState.VERIFICATION_FAILED -> StatusCard(
                title = stringResource(R.string.verification_failed),
                message = profileList.error
                    ?: stringResource(R.string.profile_list_verification_failed),
                error = true,
            )
        }
        DetailSection(stringResource(R.string.shared_profiles)) {
            if (profileList.profiles.isEmpty()) {
                Text(stringResource(R.string.no_profiles_shared))
            } else {
                profileList.profiles.forEach { profile ->
                    ProfileCard(profile, showStoredSource = true)
                }
            }
            Text(
                stringResource(R.string.profile_list_values_hidden),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ProfileListClientInformation(request, profileList)
    }
}

@Composable
private fun CredentialStateCard(credential: CredentialRequestDetails) {
    when (credential.state) {
        CredentialRequestState.APPROVAL_PENDING -> StatusCard(
            title = stringResource(R.string.review_credential_request),
            message = stringResource(R.string.credential_approval_explanation),
        )
        CredentialRequestState.WAITING_FOR_COMPLETION -> StatusCard(
            title = stringResource(
                if (credential.decision == CredentialDecision.APPROVED) {
                    R.string.credentials_released
                } else {
                    R.string.request_denied
                },
            ),
            message = stringResource(R.string.credential_waiting_for_completion),
            error = credential.decision == CredentialDecision.DENIED,
        )
        CredentialRequestState.COMPLETED -> when (credential.completionResult) {
            CredentialCompletionResult.APPROVED -> StatusCard(
                title = stringResource(R.string.credentials_delivered),
                message = stringResource(R.string.credentials_delivered_explanation),
            )
            CredentialCompletionResult.DENIED -> StatusCard(
                title = stringResource(R.string.request_denied),
                message = credential.completionMessage
                    ?: stringResource(R.string.request_denied_explanation),
                error = true,
            )
            CredentialCompletionResult.ABORTED -> StatusCard(
                title = stringResource(R.string.request_aborted),
                message = credential.completionMessage
                    ?: stringResource(R.string.request_aborted_explanation),
            )
            null -> StatusCard(
                title = stringResource(R.string.completed),
                message = stringResource(R.string.credential_completed_explanation),
            )
        }
        CredentialRequestState.VERIFICATION_FAILED -> StatusCard(
            title = stringResource(R.string.verification_failed),
            message = credential.error ?: stringResource(R.string.credential_verification_failed),
            error = true,
        )
    }
}

@Composable
private fun CredentialProfiles(credential: CredentialRequestDetails) {
    DetailSection(stringResource(R.string.requested_profiles)) {
        credential.profileDetails.forEach { profile -> ProfileCard(profile) }
        Text(
            stringResource(R.string.credential_values_hidden),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileCard(
    profile: CredentialProfileMetadata,
    showStoredSource: Boolean = false,
) {
    val storedSource = if (showStoredSource) {
        stringResource(R.string.stored_value_source)
    } else {
        ""
    }
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium)
            if (profile.description.isNotEmpty()) {
                Text(profile.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                profile.environmentVariableNames.joinToString(separator = "\n") { name ->
                    if (showStoredSource) {
                        "$name · $storedSource"
                    } else {
                        name
                    }
                },
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun CredentialOperation(credential: CredentialRequestDetails) {
    DetailSection(stringResource(R.string.requested_operation)) {
        credential.reason?.let {
            InformationRow(stringResource(R.string.reported_reason), it)
            Text(
                stringResource(R.string.reported_information_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InformationRow(stringResource(R.string.command), credential.command, monospace = true)
        credential.arguments.forEachIndexed { index, argument ->
            InformationRow(
                stringResource(R.string.argument_number, index + 1),
                argument,
                monospace = true,
            )
        }
        InformationRow(
            stringResource(R.string.working_directory),
            credential.workingDirectory,
            monospace = true,
        )
        credential.resolvedPath?.let {
            InformationRow(stringResource(R.string.resolved_path), it, monospace = true)
        }
        InformationRow(stringResource(R.string.standard_input), credential.stdinKind)
        InformationRow(stringResource(R.string.standard_output), credential.stdoutKind)
        InformationRow(stringResource(R.string.standard_error), credential.stderrKind)
        if (credential.launcherChain.isNotEmpty()) {
            InformationRow(
                stringResource(R.string.launcher_chain),
                credential.launcherChain.joinToString(separator = "\n"),
                monospace = true,
            )
        }
    }
}

@Composable
private fun CredentialClientInformation(
    request: InboxRequestDetails,
    credential: CredentialRequestDetails,
) {
    DetailSection(stringResource(R.string.reported_client_information)) {
        Text(
            stringResource(R.string.reported_information_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InformationRow(stringResource(R.string.received), formatDate(request.receivedAt))
        InformationRow(stringResource(R.string.vault_address), credential.vaultAddress)
        credential.hostname?.let { InformationRow(stringResource(R.string.hostname), it) }
        credential.platform?.let { InformationRow(stringResource(R.string.platform), it) }
        credential.architecture?.let { InformationRow(stringResource(R.string.architecture), it) }
        credential.osVersion?.let { InformationRow(stringResource(R.string.os_version), it) }
        InformationRow(stringResource(R.string.cli_version), credential.cliVersion)
        credential.machineId?.let { InformationRow(stringResource(R.string.machine_id), it) }
        InformationRow(stringResource(R.string.pairing_id), credential.pairingId, monospace = true)
        InformationRow(stringResource(R.string.request_id), request.relayRequestId, monospace = true)
    }
}

@Composable
private fun ProfileListClientInformation(
    request: InboxRequestDetails,
    profileList: ProfileListRequestDetails,
) {
    DetailSection(stringResource(R.string.reported_client_information)) {
        Text(
            stringResource(R.string.reported_information_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InformationRow(stringResource(R.string.received), formatDate(request.receivedAt))
        InformationRow(stringResource(R.string.vault_address), profileList.vaultAddress)
        profileList.hostname?.let { InformationRow(stringResource(R.string.hostname), it) }
        profileList.platform?.let { InformationRow(stringResource(R.string.platform), it) }
        profileList.architecture?.let { InformationRow(stringResource(R.string.architecture), it) }
        profileList.osVersion?.let { InformationRow(stringResource(R.string.os_version), it) }
        InformationRow(stringResource(R.string.cli_version), profileList.cliVersion)
        profileList.machineId?.let { InformationRow(stringResource(R.string.machine_id), it) }
        InformationRow(stringResource(R.string.pairing_id), profileList.pairingId, monospace = true)
        InformationRow(stringResource(R.string.request_id), request.relayRequestId, monospace = true)
    }
}

@Composable
private fun DetailColumn(
    modifier: Modifier,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        content()
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
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
private fun PairingClientInformation(request: InboxRequestDetails) {
    val pairing = checkNotNull(request.pairing)
    DetailSection(stringResource(R.string.request_information)) {
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

private fun CredentialDecisionResult.message(resources: Resources): String = when (this) {
    CredentialDecisionResult.Decided -> resources.getString(R.string.decision_sent)
    CredentialDecisionResult.ProfilesChanged -> {
        resources.getString(R.string.credential_profiles_changed)
    }
    CredentialDecisionResult.NotPending -> resources.getString(R.string.credential_not_pending)
    CredentialDecisionResult.NotFound -> resources.getString(R.string.request_not_found)
    is CredentialDecisionResult.MissingProfiles -> resources.getString(
        R.string.credential_missing_profiles,
        names.joinToString(),
    )
    is CredentialDecisionResult.ConflictingVariable -> resources.getString(
        R.string.credential_conflicting_variable,
        name,
    )
    CredentialDecisionResult.SecretUnavailable -> {
        resources.getString(R.string.credential_value_unavailable)
    }
    CredentialDecisionResult.SecretCorrupted -> {
        resources.getString(R.string.credential_value_corrupted)
    }
    CredentialDecisionResult.UnsupportedEncryption -> {
        resources.getString(R.string.credential_value_unsupported)
    }
    CredentialDecisionResult.PairingUnavailable -> {
        resources.getString(R.string.credential_pairing_unavailable)
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
