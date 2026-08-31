package dev.agentknock.ui.secrets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.ui.requests.SecretUploadRequestDetail
import dev.agentknock.ui.requests.message
import kotlinx.coroutines.launch

@Composable
internal fun SecretUploadSelectionDetail(
    request: InboxRequestDetails?,
    authorizeProtectedAction: (String, () -> Unit, (String) -> Unit) -> Unit,
    viewModel: SecretsViewModel,
    report: (String) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    if (request?.secretUpload == null) {
        Loading(modifier)
        return
    }
    SecretUploadRequestDetail(
        request = request,
        onBack = onBack,
        showBack = showBack,
        onApprove = { name ->
            scope.launch {
                report(viewModel.approveSecretUpload(request.id, name).message())
                viewModel.selectUpload(null)
            }
        },
        onReject = {
            scope.launch {
                report(viewModel.rejectSecretUpload(request.id).message())
                viewModel.selectUpload(null)
            }
        },
        authorizeProtectedAction = authorizeProtectedAction,
        onReveal = { variableId ->
            viewModel.readSecretUploadVariable(request.id, variableId)
        },
        onSensitivityChange = { variableId, sensitive ->
            viewModel.setSecretUploadVariableSensitivity(request.id, variableId, sensitive)
        },
        report = report,
        modifier = modifier,
    )
}
