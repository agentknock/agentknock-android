package dev.agentknock.ui.clients

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.agentknock.presentation.formatPlatformName
import dev.agentknock.presentation.renderSoftware
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestState
import dev.agentknock.storage.request.PairingRequestDetails
import dev.agentknock.storage.request.PairingState
import dev.agentknock.ui.components.DetailPage
import dev.agentknock.ui.components.DetailValue
import dev.agentknock.ui.components.Disclosure
import dev.agentknock.ui.components.InformationSurface
import dev.agentknock.ui.components.Notice
import dev.agentknock.ui.components.NoticeTone
import dev.agentknock.ui.components.rememberDateTimeFormatter
import dev.agentknock.ui.requests.Identity
import dev.agentknock.ui.requests.StatusHeader
import dev.agentknock.ui.requests.StatusSummary
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
    val dates = rememberDateTimeFormatter()
    val pairing = (request.content as InboxRequestContent.Pairing).details
    val context = LocalContext.current
    DetailPage(
        title = "Pairing",
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        scrollResetKey = pairing.pairingState,
    ) {
        StatusHeader(
            StatusSummary(
                pairing.pairingState.label(),
                when {
                    pairing.pairingState.usesErrorStatus -> NoticeTone.DANGER
                    request.state == InboxRequestState.ACTION_REQUIRED -> NoticeTone.ATTENTION
                    pairing.pairingState == PairingState.REJECTED -> NoticeTone.SUBDUED
                    pairing.pairingState == PairingState.COMPLETED -> NoticeTone.SUCCESS
                    else -> NoticeTone.NEUTRAL
                },
                icon = Icons.Outlined.ErrorOutline.takeIf { pairing.pairingState.usesErrorStatus },
                detail =
                    if (pairing.pairingState == PairingState.EXCHANGE_FAILED) {
                        pairing.error
                            ?: "The secure exchange ended before the pairing could be verified."
                    } else {
                        null
                    },
            ),
            dates.timestamp(request.receivedAt),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Identity(Icons.Outlined.Computer, "Client", listOf(pairing.clientName))
            val reported =
                listOfNotNull(
                        pairing.hostname,
                        pairing.platform?.let(::formatPlatformName),
                    )
                    .joinToString(" · ")
            if (reported.isNotEmpty()) {
                Text(
                    reported,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 26.dp),
                )
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Which verification code is shown by the client?",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Choose the exact same verification code to accept this client. " +
                            "A wrong choice rejects the pairing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pairing.sasOptions.forEachIndexed { index, sas ->
                        FilledTonalButton(
                            onClick = { onChooseSas(index) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Text(
                                sas,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { onChooseSas(null) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.agentknockColors.danger
                            ),
                        border = BorderStroke(1.dp, MaterialTheme.agentknockColors.danger),
                    ) {
                        Text("None of the above")
                    }
                }
            }
            PairingState.WAITING_FOR_FINISH -> {
                Text("Verification code confirmed", style = MaterialTheme.typography.titleMedium)
                Text("Run this command on the client to finish pairing:")
                val command = "agentknock pairing finish"
                InformationSurface {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(command, fontFamily = FontFamily.Monospace)
                        }
                        IconButton(
                            onClick = {
                                context
                                    .getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(
                                        ClipData.newPlainText("Pairing command", command)
                                    )
                            }
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "Copy pairing finish command",
                            )
                        }
                    }
                }
            }
            PairingState.COMPLETED ->
                Notice(
                    "Pairing completed",
                    "Access was granted to this client.",
                    NoticeTone.SUCCESS,
                )
            PairingState.REJECTED ->
                Notice(
                    "Pairing rejected",
                    "No access was granted.",
                    NoticeTone.SUBDUED,
                )
            PairingState.EXCHANGE_PENDING ->
                Text(
                    "Waiting for the client's secure exchange before a verification code can be shown. " +
                        "Reject this attempt to allow another pairing."
                )
            PairingState.EXCHANGE_FAILED ->
                Text(
                    "Reject this attempt to allow another pairing, then start pairing again " +
                        "from the client.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        if (
            pairing.pairingState.isRejectable &&
                pairing.pairingState != PairingState.SAS_VERIFICATION_PENDING
        ) {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.agentknockColors.danger
                    ),
                border = BorderStroke(1.dp, MaterialTheme.agentknockColors.danger),
            ) {
                Text("Reject pairing")
            }
        }
        Disclosure("Technical details") {
            DetailValue("Received", dates.timestamp(request.receivedAt, includeSeconds = true))
            pairing.decidedAt
                ?.takeIf { pairing.pairingState.hasAcceptedSas }
                ?.let {
                    DetailValue(
                        "Verification code accepted",
                        dates.timestamp(it, includeSeconds = true),
                    )
                }
            request.completedAt?.let {
                DetailValue("Completed", dates.timestamp(it, includeSeconds = true))
            }
            DetailValue("Pairing address", pairing.pairingAddress, true)
            pairing.osVersion?.let { DetailValue("OS version", it) }
            pairing.architecture?.let { DetailValue("Architecture", it) }
            pairing.clientSoftware?.let { software ->
                renderSoftware(software.application)?.let { DetailValue("Client software", it) }
                if (software.library != software.application) {
                    renderSoftware(software.library)?.let { DetailValue("Agentknock library", it) }
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

private fun PairingState.label(): String =
    when (this) {
        PairingState.EXCHANGE_PENDING -> "Waiting"
        PairingState.EXCHANGE_FAILED -> "Pairing could not continue"
        PairingState.SAS_VERIFICATION_PENDING -> "Compare verification code"
        PairingState.WAITING_FOR_FINISH -> "Waiting for client"
        PairingState.REJECTED -> "Rejected"
        PairingState.COMPLETED -> "Completed"
    }

internal data class PairingWarningNotice(
    val title: String,
    val message: String,
)

internal val PairingRequestDetails.warningNotice: PairingWarningNotice?
    get() =
        error
            ?.takeIf {
                pairingState == PairingState.SAS_VERIFICATION_PENDING
            }
            ?.let {
                PairingWarningNotice(
                    title = "Some client details could not be read",
                    message = it,
                )
            }

internal val PairingState.usesErrorStatus: Boolean
    get() = this == PairingState.EXCHANGE_FAILED
