package dev.agentknock.ui.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.presentation.formatTimestamp
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.PairingRequestDetails
import dev.agentknock.storage.request.PairingState
import dev.agentknock.ui.components.InformationRow
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun PairingRequestDetail(
    request: InboxRequestDetails,
    onBack: () -> Unit,
    showBack: Boolean,
    onChooseSas: (Int?) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier,
) {
    val pairing = (request.content as InboxRequestContent.Pairing).details
    DetailPage(
        title = "Pairing",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = pairing.pairingState,
    ) {
        InformationSurface {
            StatusLine(
                pairing.pairingState.label(),
                pairing.pairingState.usesErrorStatus,
                attention = request.state == InboxRequestState.ACTION_REQUIRED,
                subdued = pairing.pairingState == PairingState.REJECTED,
            )
            InformationRow("Client", pairing.clientName)
            val reported = listOfNotNull(
                pairing.hostname,
                pairing.platform?.let(::formatPlatformName),
                pairing.architecture,
            ).joinToString(" · ")
            if (reported.isNotEmpty()) {
                InformationRow("Machine", reported)
            }
            InformationRow("Received", formatTimestamp(request.receivedAt))
            pairing.decidedAt?.takeIf { pairing.pairingState.hasAcceptedSas }?.let {
                InformationRow("Code accepted", formatTimestamp(it))
            }
            request.completedAt?.let {
                InformationRow("Completed", formatTimestamp(it))
            }
        }

        when (pairing.pairingState) {
            PairingState.SAS_VERIFICATION_PENDING -> {
                pairing.warningNotice?.let { warning ->
                    Notice(
                        warning.title,
                        warning.message,
                        NoticeTone.ATTENTION,
                    )
                }
                Text(
                    "Which code is shown by the client?",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Choose the exact same code to accept this client. " +
                        "A wrong choice rejects the pairing.",
                )
                pairing.sasOptions.forEachIndexed { index, sas ->
                    FilledTonalButton(
                        onClick = { onChooseSas(index) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(sas, fontFamily = FontFamily.Monospace)
                    }
                }
                OutlinedButton(
                    onClick = { onChooseSas(null) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.agentknockColors.danger,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.agentknockColors.danger),
                ) {
                    Text("None of the above")
                }
            }
            PairingState.WAITING_FOR_FINISH -> Notice(
                "Code verified; waiting for the client",
                "The client must run agentknock pairing finish to activate this pairing.",
                NoticeTone.SUCCESS,
            )
            PairingState.COMPLETED -> Notice(
                "Pairing completed",
                "Access was granted to this client.",
                NoticeTone.SUCCESS,
            )
            PairingState.REJECTED -> Notice(
                "Pairing rejected",
                "No access was granted.",
                NoticeTone.SUBDUED,
            )
            PairingState.EXCHANGE_PENDING -> Notice(
                "Waiting for secure exchange",
                "The client is completing the secure pairing exchange.",
                NoticeTone.ATTENTION,
            )
            PairingState.EXCHANGE_FAILED -> Notice(
                "Pairing could not continue",
                pairing.error
                    ?: "The secure exchange ended before the pairing could be verified.",
                NoticeTone.DANGER,
            )
        }
        if (
            pairing.pairingState.isRejectable &&
            pairing.pairingState != PairingState.SAS_VERIFICATION_PENDING
        ) {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.agentknockColors.danger,
                ),
                border = BorderStroke(1.dp, MaterialTheme.agentknockColors.danger),
            ) {
                Text("Reject pairing")
            }
        }
        Disclosure("Technical details") {
            DetailValue("Pairing address", pairing.pairingAddress, true)
            pairing.osVersion?.let { DetailValue("OS version", it) }
            pairing.clientSoftware?.let { software ->
                DetailValue("Client software", renderSoftware(software.application))
                if (software.library != software.application) {
                    DetailValue("Agentknock library", renderSoftware(software.library))
                }
            }
            pairing.machineId?.let {
                DetailValue("Machine ID reported by client", it, true)
            }
            DetailValue("Client ID", pairing.clientId, true)
            DetailValue("Request ID", request.id, true)
        }
    }
}

private fun PairingState.label(): String = when (this) {
    PairingState.EXCHANGE_PENDING -> "Waiting"
    PairingState.EXCHANGE_FAILED -> "Pairing could not continue"
    PairingState.SAS_VERIFICATION_PENDING -> "Verify security code"
    PairingState.WAITING_FOR_FINISH -> "Waiting for client"
    PairingState.REJECTED -> "Rejected"
    PairingState.COMPLETED -> "Completed"
}

internal data class PairingWarningNotice(
    val title: String,
    val message: String,
)

internal val PairingRequestDetails.warningNotice: PairingWarningNotice?
    get() = error?.takeIf {
        pairingState == PairingState.SAS_VERIFICATION_PENDING
    }?.let {
        PairingWarningNotice(
            title = "Some client details could not be read",
            message = it,
        )
    }

internal val PairingState.usesErrorStatus: Boolean
    get() = this == PairingState.EXCHANGE_FAILED
