package dev.agentknock.ui.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentknockApplication
import dev.agentknock.presentation.ParsedShellCommand
import dev.agentknock.presentation.parseShellCommand
import dev.agentknock.presentation.renderShellCommand
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.SecretUseDecisionResult
import dev.agentknock.storage.request.SecretUseRequestState
import dev.agentknock.storage.rule.ApprovalRule
import dev.agentknock.storage.rule.ApprovalRuleAction
import dev.agentknock.storage.rule.ApprovalRuleInput
import dev.agentknock.storage.rule.CommandMatch
import dev.agentknock.storage.rule.SaveApprovalRuleResult
import dev.agentknock.storage.secret.SecretSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal enum class RuleEditorOrigin {
    MANUAL,
    REQUEST,
    EXISTING,
}

internal data class RuleEditorState(
    val origin: RuleEditorOrigin,
    val ruleId: String? = null,
    val sourceRequestId: Long? = null,
    val name: String = "",
    val action: ApprovalRuleAction = ApprovalRuleAction.APPROVE,
    val clientId: String = "",
    val secretIds: Set<String> = emptySet(),
    val secretNames: List<String> = emptyList(),
    val commandText: String = "",
    val requestCommand: List<String> = emptyList(),
    val commandMatch: CommandMatch = CommandMatch.EXACT,
    val prefixLength: Int = 1,
    val executablePath: String? = null,
    val executableHash: String? = null,
    val matchExecutable: Boolean = false,
    val workingDirectory: String? = null,
    val matchWorkingDirectory: Boolean = false,
    val durationHours: Int = 4,
    val expiresAt: Long? = null,
)

internal sealed interface RuleOperationResult {
    data class Saved(val id: String, val message: String) : RuleOperationResult
    data class Failed(val message: String) : RuleOperationResult
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class RulesViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AgentknockApplication).container
    private val repository = container.approvalRules
    private val selectedRuleId = MutableStateFlow<String?>(null)
    private val editorState = MutableStateFlow<RuleEditorState?>(null)
    private val loadingEditorState = MutableStateFlow(false)

    val rules: StateFlow<List<ApprovalRule>> = repository.observeRules().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )
    val clients: StateFlow<List<ClientSummary>> = container.requests.observeClients().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )
    val secrets: StateFlow<List<SecretSummary>> = container.secrets.observeSecrets().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )
    val requests: StateFlow<List<InboxRequestSummary>> = container.requests.observeRequests().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )
    val selection: StateFlow<String?> = selectedRuleId.asStateFlow()
    val selectedRule: StateFlow<ApprovalRule?> = selectedRuleId.flatMapLatest { id ->
        id?.let(repository::observeRule) ?: flowOf(null)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )
    val editor: StateFlow<RuleEditorState?> = editorState.asStateFlow()
    val loadingEditor: StateFlow<Boolean> = loadingEditorState.asStateFlow()

    fun selectRule(id: String?) {
        editorState.value = null
        selectedRuleId.value = id
    }

    fun startManualRule() {
        selectedRuleId.value = null
        editorState.value = RuleEditorState(origin = RuleEditorOrigin.MANUAL)
    }

    fun startRuleFromRequest(requestId: Long) {
        selectedRuleId.value = null
        editorState.value = null
        loadingEditorState.value = true
        viewModelScope.launch {
            try {
                val request = container.requests.getRequestDetails(requestId)
                val secretUse = request?.secretUse
                if (
                    secretUse == null ||
                    secretUse.state != SecretUseRequestState.APPROVAL_PENDING ||
                    secretUse.missingSecrets.isNotEmpty()
                ) {
                    return@launch
                }
                val identities = container.secrets.identitiesForNames(secretUse.secrets)
                if (identities.size != secretUse.secrets.distinct().size) return@launch
                val command = listOf(secretUse.command) + secretUse.arguments
                editorState.value = RuleEditorState(
                    origin = RuleEditorOrigin.REQUEST,
                    sourceRequestId = requestId,
                    name = generatedName(secretUse.secrets, command),
                    action = ApprovalRuleAction.APPROVE,
                    clientId = secretUse.clientId,
                    secretIds = identities.mapTo(linkedSetOf()) { it.id },
                    secretNames = secretUse.secrets,
                    commandText = renderShellCommand(secretUse.command, secretUse.arguments),
                    requestCommand = command,
                    commandMatch = CommandMatch.EXACT,
                    prefixLength = command.size,
                    executablePath = secretUse.executablePath,
                    executableHash = secretUse.executableHash,
                    matchExecutable = secretUse.executableHash != null,
                    workingDirectory = secretUse.workingDirectory,
                    matchWorkingDirectory = true,
                    durationHours = 4,
                )
            } finally {
                loadingEditorState.value = false
            }
        }
    }

    fun startEditing(rule: ApprovalRule) {
        val secretNames = secrets.value
            .filter { it.id in rule.secretIds }
            .map(SecretSummary::name)
        selectedRuleId.value = null
        editorState.value = RuleEditorState(
            origin = RuleEditorOrigin.EXISTING,
            ruleId = rule.id,
            sourceRequestId = rule.sourceRequestId,
            name = rule.name,
            action = rule.action,
            clientId = rule.clientId,
            secretIds = rule.secretIds,
            secretNames = secretNames,
            commandText = renderShellCommand(rule.command.first(), rule.command.drop(1)),
            requestCommand = rule.command,
            commandMatch = rule.commandMatch,
            prefixLength = rule.command.size,
            executablePath = rule.executablePath,
            executableHash = rule.executableHash,
            matchExecutable = rule.executableHash != null,
            workingDirectory = rule.workingDirectory,
            matchWorkingDirectory = rule.workingDirectory != null,
            expiresAt = rule.expiresAt,
        )
    }

    fun updateEditor(editor: RuleEditorState?) {
        editorState.value = editor
    }

    fun closeEditor() {
        editorState.value = null
    }

    suspend fun saveEditor(): RuleOperationResult {
        val editor = editorState.value ?: return RuleOperationResult.Failed("No rule is being edited.")
        val command = when (editor.origin) {
            RuleEditorOrigin.REQUEST -> when (editor.commandMatch) {
                CommandMatch.EXACT -> editor.requestCommand
                CommandMatch.PREFIX -> editor.requestCommand.take(editor.prefixLength)
            }
            RuleEditorOrigin.MANUAL,
            RuleEditorOrigin.EXISTING,
            -> when (val parsed = parseShellCommand(editor.commandText)) {
                is ParsedShellCommand.Valid -> parsed.tokens
                is ParsedShellCommand.Invalid -> return RuleOperationResult.Failed(parsed.message)
            }
        }
        if (editor.secretIds.isEmpty()) {
            return RuleOperationResult.Failed("Select at least one secret.")
        }
        val selectedNames = secrets.value
            .filter { it.id in editor.secretIds }
            .map(SecretSummary::name)
        val name = editor.name.trim().ifEmpty { generatedName(selectedNames, command) }
        val expiresAt = when (editor.origin) {
            RuleEditorOrigin.REQUEST -> {
                if (editor.durationHours <= 0) {
                    return RuleOperationResult.Failed("Choose a positive duration.")
                }
                System.currentTimeMillis() + editor.durationHours * 60L * 60L * 1_000L
            }
            RuleEditorOrigin.EXISTING -> editor.expiresAt
            RuleEditorOrigin.MANUAL -> null
        }
        val input = ApprovalRuleInput(
            name = name,
            action = editor.action,
            clientId = editor.clientId,
            secretIds = editor.secretIds,
            command = command,
            commandMatch = editor.commandMatch,
            executablePath = editor.executablePath.takeIf { editor.matchExecutable },
            executableHash = editor.executableHash.takeIf { editor.matchExecutable },
            workingDirectory = editor.workingDirectory.takeIf { editor.matchWorkingDirectory },
            sourceRequestId = editor.sourceRequestId,
            expiresAt = expiresAt,
        )
        val saveResult = if (editor.ruleId == null) {
            repository.create(input, enabled = editor.origin != RuleEditorOrigin.REQUEST)
        } else {
            repository.save(editor.ruleId, input)
        }
        val id = when (saveResult) {
            is SaveApprovalRuleResult.Saved -> saveResult.id
            SaveApprovalRuleResult.NotFound -> return RuleOperationResult.Failed("That rule no longer exists.")
            is SaveApprovalRuleResult.Invalid -> return RuleOperationResult.Failed(saveResult.message)
        }
        if (editor.origin == RuleEditorOrigin.REQUEST) {
            val requestId = checkNotNull(editor.sourceRequestId)
            when (val approved = container.requests.approveSecretUseRequest(requestId)) {
                SecretUseDecisionResult.Decided -> repository.setEnabled(id, true)
                else -> {
                    repository.delete(id)
                    return RuleOperationResult.Failed(approved.message())
                }
            }
            container.requestConnection.refresh()
        }
        editorState.value = null
        selectedRuleId.value = id
        return RuleOperationResult.Saved(
            id,
            if (editor.origin == RuleEditorOrigin.REQUEST) {
                "Request approved and temporary rule created."
            } else {
                "Approval rule saved."
            },
        )
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Boolean =
        repository.setEnabled(id, enabled)

    suspend fun delete(id: String): Boolean {
        val deleted = repository.delete(id)
        if (deleted && selectedRuleId.value == id) selectedRuleId.value = null
        return deleted
    }

    suspend fun renew(id: String, durationHours: Int): RuleOperationResult {
        if (durationHours <= 0) return RuleOperationResult.Failed("Choose a positive duration.")
        val rule = repository.getRule(id)
            ?: return RuleOperationResult.Failed("That rule no longer exists.")
        val input = ApprovalRuleInput(
            name = rule.name,
            action = rule.action,
            clientId = rule.clientId,
            secretIds = rule.secretIds,
            command = rule.command,
            commandMatch = rule.commandMatch,
            executablePath = rule.executablePath,
            executableHash = rule.executableHash,
            workingDirectory = rule.workingDirectory,
            sourceRequestId = rule.sourceRequestId,
            expiresAt = System.currentTimeMillis() + durationHours * 60L * 60L * 1_000L,
        )
        val saved = repository.save(id, input)
        if (saved !is SaveApprovalRuleResult.Saved) {
            return RuleOperationResult.Failed("The rule could not be renewed.")
        }
        repository.setEnabled(id, true)
        return RuleOperationResult.Saved(id, "Approval rule renewed.")
    }

    private fun generatedName(secretNames: List<String>, command: List<String>): String {
        val secretPart = secretNames.joinToString(" + ").ifBlank { "Secrets" }
        val commandPart = command.firstOrNull()
            ?.let { executable -> renderShellCommand(executable, command.drop(1).take(1)) }
            .orEmpty()
            .ifBlank { "command" }
        return "$secretPart · $commandPart"
    }

    private fun SecretUseDecisionResult.message(): String = when (this) {
        SecretUseDecisionResult.Decided -> "Request approved."
        SecretUseDecisionResult.SecretsChanged -> "The secrets changed. Review the request again."
        SecretUseDecisionResult.NotPending -> "That request no longer needs approval."
        SecretUseDecisionResult.NotFound -> "That request no longer exists."
        is SecretUseDecisionResult.MissingSecrets -> "Missing secrets: ${names.joinToString()}"
        is SecretUseDecisionResult.ConflictingVariable ->
            "The requested secrets conflict on $name."
        is SecretUseDecisionResult.Invalid -> message
        SecretUseDecisionResult.SecretUnavailable -> "A requested secret is unavailable."
        SecretUseDecisionResult.SecretCorrupted -> "A requested secret is corrupted."
        SecretUseDecisionResult.UnsupportedEncryption ->
            "A requested secret uses unsupported encryption."
        SecretUseDecisionResult.PairingUnavailable -> "The client pairing is unavailable."
    }
}
