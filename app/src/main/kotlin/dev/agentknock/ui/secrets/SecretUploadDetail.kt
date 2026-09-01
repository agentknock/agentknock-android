package dev.agentknock.ui.secrets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails

@Composable
internal fun SecretUploadSelectionDetail(
    request: InboxRequestDetails?,
    revealedValues: Map<String, String>,
    viewModel: SecretsViewModel,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier,
) {
    if (request?.content !is InboxRequestContent.SecretUpload) {
        Loading(modifier)
        return
    }
    SecretUploadRequestDetail(
        request = request,
        onBack = onBack,
        showBack = showBack,
        onApprove = { name ->
            viewModel.approveSecretUpload(request.id, name)
        },
        onReject = {
            viewModel.rejectSecretUpload(request.id)
        },
        revealedValues = revealedValues,
        onReveal = { variable ->
            viewModel.toggleSecretUploadVariableReveal(
                request.id,
                variable,
            )
        },
        onSensitivityChange = { variable, sensitive ->
            viewModel.setSecretUploadVariableSensitivity(
                requestId = request.id,
                variable = variable,
                sensitive = sensitive,
            )
        },
        modifier = modifier,
    )
}
