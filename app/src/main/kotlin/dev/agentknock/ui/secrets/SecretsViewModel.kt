package dev.agentknock.ui.secrets

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentknockActions
import dev.agentknock.ProtectedActionResult
import dev.agentknock.R
import dev.agentknock.SecretValueAction
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.SecretUploadDecisionResult
import dev.agentknock.storage.request.SecretUploadRequestState
import dev.agentknock.storage.request.SecretUploadVariableDetails
import dev.agentknock.storage.request.SecretUploadVariableValue
import dev.agentknock.storage.request.SecretUploadSensitivityResult
import dev.agentknock.storage.secret.CreateEnvironmentVariableResult
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.EnvironmentVariableMetadata
import dev.agentknock.storage.secret.EnvironmentVariableValue
import dev.agentknock.storage.secret.SecretDetails
import dev.agentknock.storage.secret.SecretSummary
import dev.agentknock.storage.secret.SecretType
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.storage.secret.SshPrivateKey
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SaveSshSecretResult
import dev.agentknock.storage.secret.SaveEnvironmentVariableResult
import dev.agentknock.storage.secret.SaveSecretResult
import dev.agentknock.ui.pendingSecretUploads
import dev.agentknock.storage.device.DeviceConfiguration
import dev.agentknock.storage.device.DeviceSettingsCoordinator
import dev.agentknock.storage.request.RequestInbox
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.runCatchingNonCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi

private fun SecretUploadDecisionResult.message(): String = when (this) {
    is SecretUploadDecisionResult.Approved -> "Secret upload approved"
    SecretUploadDecisionResult.Rejected -> "Secret upload rejected"
    SecretUploadDecisionResult.NotPending -> "This upload no longer needs a decision"
    SecretUploadDecisionResult.NotFound -> "Upload is no longer available"
    is SecretUploadDecisionResult.Invalid -> message
    SecretUploadDecisionResult.SecretUnavailable ->
        "An uploaded value is unavailable on this device"
    SecretUploadDecisionResult.SecretCorrupted ->
        "An uploaded value could not be authenticated"
    SecretUploadDecisionResult.UnsupportedEncryption ->
        "An uploaded value uses unsupported encryption"
}

internal sealed interface SecretTarget {
    data class Stored(val id: String) : SecretTarget
    data class Upload(
        val requestId: String,
        val retainResolved: Boolean = false,
    ) : SecretTarget
}

internal sealed interface SecretsContent {
    data object List : SecretsContent
    data class Loading(val target: SecretTarget) : SecretsContent
    data class Stored(val details: SecretDetails) : SecretsContent
    data class Upload(val request: InboxRequestDetails) : SecretsContent
}

internal sealed interface SecretsEditor {
    data object None : SecretsEditor

    data class Secret(
        val session: Long,
        val state: SecretEditorState,
        val phase: EditorPhase = EditorPhase.EDITING,
    ) : SecretsEditor

    data class Variable(
        val session: Long,
        val state: VariableEditorState,
        val phase: EditorPhase = EditorPhase.EDITING,
    ) : SecretsEditor

    data class SshKey(
        val session: Long,
        val state: SshKeyEditorState,
        val phase: EditorPhase = EditorPhase.EDITING,
    ) : SecretsEditor
}

internal sealed interface SecretsUiMessage {
    data class Resource(@param:StringRes val id: Int) : SecretsUiMessage
    data class Text(val value: String) : SecretsUiMessage
}

internal data class SecretClipboardValue(
    val token: Long,
    val label: String,
    val value: String,
    val sensitive: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class SecretsViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val actions: AgentknockActions,
    private val secretRepository: SecretRepository,
    private val inbox: RequestInbox,
    requestSummaries: StateFlow<List<InboxRequestSummary>>,
    clientSummaries: StateFlow<List<ClientSummary>>,
    secretSummaries: StateFlow<List<SecretSummary>>,
    val configuration: StateFlow<DeviceConfiguration?>,
    private val deviceSettings: DeviceSettingsCoordinator,
) : ViewModel() {
    private val selectedTarget = MutableStateFlow(
        savedStateHandle.get<String>(SELECTED_SECRET)?.let(SecretTarget::Stored)
            ?: savedStateHandle.get<String>(SELECTED_UPLOAD)?.let { requestId ->
                SecretTarget.Upload(
                    requestId = requestId,
                    retainResolved = savedStateHandle[RETAIN_RESOLVED_UPLOAD] ?: false,
                )
            },
    )
    private val editorState = MutableStateFlow<SecretsEditor>(SecretsEditor.None)
    private val uiEvents = Channel<SecretsUiMessage>(Channel.BUFFERED)
    private val revealedEnvironmentValues = MutableStateFlow<Map<String, String>>(emptyMap())
    private val revealedUploadValuesState = MutableStateFlow<Map<String, String>>(emptyMap())
    private val clipboardState = MutableStateFlow<SecretClipboardValue?>(null)
    private var nextEditorSession = 0L
    private var nextClipboardToken = 0L
    private var secretReadEpoch = 0L

    val editor: StateFlow<SecretsEditor> = editorState.asStateFlow()
    val events = uiEvents.receiveAsFlow()
    val revealedValues: StateFlow<Map<String, String>> = revealedEnvironmentValues.asStateFlow()
    val revealedUploadValues: StateFlow<Map<String, String>> =
        revealedUploadValuesState.asStateFlow()
    val clipboard: StateFlow<SecretClipboardValue?> = clipboardState.asStateFlow()

    val secrets: StateFlow<List<SecretSummary>> = secretSummaries

    val clients: StateFlow<List<ClientSummary>> = clientSummaries

    val pendingUploads: StateFlow<List<InboxRequestSummary>> = requestSummaries
        .map(List<InboxRequestSummary>::pendingSecretUploads)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(READ_MODEL_STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )

    val content: StateFlow<SecretsContent> = selectedTarget
        .flatMapLatest { target ->
            when (target) {
                null -> flowOf(SecretsContent.List)
                is SecretTarget.Stored -> secretRepository.observeSecret(target.id)
                    .transform<SecretDetails?, SecretsContent> { details ->
                        if (details == null) {
                            if (clearSelectedTarget(target)) {
                                emit(SecretsContent.List)
                            }
                        } else {
                            emit(SecretsContent.Stored(details))
                        }
                    }
                    .onStart { emit(SecretsContent.Loading(target)) }
                is SecretTarget.Upload -> inbox.observeRequest(target.requestId)
                    .transform<InboxRequestDetails?, SecretsContent> { request ->
                        val upload = request?.content as? InboxRequestContent.SecretUpload
                        if (request == null || upload == null) {
                            if (clearSelectedTarget(target)) {
                                emit(SecretsContent.List)
                            }
                        } else if (
                            !target.retainResolved &&
                            upload.details.state != SecretUploadRequestState.REVIEW_PENDING
                        ) {
                            if (clearSelectedTarget(target)) emit(SecretsContent.List)
                        } else {
                            emit(SecretsContent.Upload(request))
                        }
                    }
                    .onStart { emit(SecretsContent.Loading(target)) }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SecretsContent.List,
        )

    fun selectSecret(id: String?) {
        setSelectedTarget(id?.let(SecretTarget::Stored))
    }

    fun selectUpload(requestId: String?, retainResolved: Boolean = false) {
        setSelectedTarget(requestId?.let { SecretTarget.Upload(it, retainResolved) })
    }

    fun startNewSecret() {
        if (selectedTarget.value is SecretTarget.Upload) setSelectedTarget(null)
        editorState.value = SecretsEditor.Secret(
            session = newEditorSession(),
            state = SecretEditorState(
                secret = null,
                name = "",
                description = "",
                type = SecretType.ENVIRONMENT,
            ),
        )
    }

    fun startEditingSecret(secret: SecretDetails) {
        editorState.value = SecretsEditor.Secret(
            session = newEditorSession(),
            state = SecretEditorState(
                secret = secret,
                name = secret.name,
                description = secret.description,
                type = secret.type,
            ),
        )
    }

    fun updateSecretEditor(session: Long, state: SecretEditorState) {
        editorState.mutateEditing(session) { editor ->
            when (editor) {
                !is SecretsEditor.Secret -> null
                else -> editor.copy(
                    state = state.copy(
                        sshKeyDraft = if (state.type == SecretType.SSH) {
                            state.sshKeyDraft
                        } else {
                            state.sshKeyDraft.copy(privateKeyText = "").withoutPreparation()
                        },
                    ),
                )
            }
        }
    }

    fun startReplacingSshKey(secret: SecretDetails) {
        val key = checkNotNull(secret.sshKey)
        editorState.value = SecretsEditor.SshKey(
            session = newEditorSession(),
            state = SshKeyEditorState(
                secretId = secret.id,
                secretName = secret.name,
                currentKey = key,
                sshKeyDraft = SshKeyDraft(
                    algorithm = key.algorithm,
                    comment = key.comment,
                ),
            ),
        )
    }

    fun updateSshKeyEditor(session: Long, state: SshKeyEditorState) {
        editorState.mutateEditing(session) { editor ->
            when (editor) {
                !is SecretsEditor.SshKey -> null
                else -> editor.copy(
                    state = state.copy(
                        sshKeyDraft = if (
                            state.sshKeyDraft.inputMode == SshKeyInputMode.GENERATE
                        ) {
                            state.sshKeyDraft.copy(privateKeyText = "")
                        } else {
                            state.sshKeyDraft
                        },
                    ),
                )
            }
        }
    }

    fun startNewEnvironmentVariable(secretId: String) {
        editorState.value = SecretsEditor.Variable(
            session = newEditorSession(),
            state = VariableEditorState(
                secretId = secretId,
                variable = null,
                currentValue = null,
                name = "",
                value = "",
                valueEdited = false,
                sensitive = true,
                notes = "",
            ),
        )
    }

    fun startEditingEnvironmentVariable(
        variable: EnvironmentVariableMetadata,
        currentValue: String?,
    ) {
        editorState.value = SecretsEditor.Variable(
            session = newEditorSession(),
            state = VariableEditorState(
                secretId = variable.secretId,
                variable = variable,
                currentValue = currentValue,
                name = variable.name,
                value = currentValue.orEmpty(),
                valueEdited = false,
                sensitive = variable.sensitive,
                notes = variable.notes,
            ),
        )
    }

    fun updateVariableEditor(session: Long, state: VariableEditorState) {
        editorState.mutateEditing(session) { editor ->
            (editor as? SecretsEditor.Variable)?.copy(state = state)
        }
    }

    fun closeEditor(session: Long): Boolean =
        editorState.mutateEditing(session) { SecretsEditor.None }

    fun prepareSecretSshKey() {
        val current = editorState.value as? SecretsEditor.Secret ?: return
        if (current.phase != EditorPhase.EDITING) return
        val source = current.state
        if (source.type != SecretType.SSH) return
        val sourceDraft = source.sshKeyDraft
        if (sourceDraft.preparing) return
        val editor = current.copy(
            session = newEditorSession(),
            state = source.copy(
                sshKeyDraft = sourceDraft.withoutPreparation().copy(preparing = true),
            ),
        )
        if (!editorState.compareAndSet(current, editor)) return
        viewModelScope.launch {
            val preparation = prepareSshKey(sourceDraft)
            editorState.update { current ->
                if (
                    current !is SecretsEditor.Secret ||
                    current.session != editor.session ||
                    current.state.type != source.type ||
                    !current.state.sshKeyDraft.hasSameSourceAs(sourceDraft)
                ) {
                    current
                } else {
                    current.copy(
                        state = current.state.copy(
                            sshKeyDraft = current.state.sshKeyDraft.copy(
                                privateKeyText = if (preparation.key == null) {
                                    current.state.sshKeyDraft.privateKeyText
                                } else {
                                    ""
                                },
                                preparedKey = preparation.key,
                                preparing = false,
                                error = preparation.error,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun prepareReplacementSshKey() {
        val current = editorState.value as? SecretsEditor.SshKey ?: return
        if (current.phase != EditorPhase.EDITING) return
        val source = current.state
        val sourceDraft = source.sshKeyDraft
        if (sourceDraft.preparing) return
        val editor = current.copy(
            session = newEditorSession(),
            state = source.copy(
                sshKeyDraft = sourceDraft.withoutPreparation().copy(preparing = true),
            ),
        )
        if (!editorState.compareAndSet(current, editor)) return
        viewModelScope.launch {
            val preparation = prepareSshKey(sourceDraft)
            editorState.update { current ->
                if (
                    current !is SecretsEditor.SshKey ||
                    current.session != editor.session ||
                    !current.state.sshKeyDraft.hasSameSourceAs(sourceDraft)
                ) {
                    current
                } else {
                    current.copy(
                        state = current.state.copy(
                            sshKeyDraft = current.state.sshKeyDraft.copy(
                                privateKeyText = if (preparation.key == null) {
                                    current.state.sshKeyDraft.privateKeyText
                                } else {
                                    ""
                                },
                                preparedKey = preparation.key,
                                preparing = false,
                                error = preparation.error,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun clearSensitiveData() {
        secretReadEpoch += 1
        revealedEnvironmentValues.value = emptyMap()
        revealedUploadValuesState.value = emptyMap()
        clipboardState.value = null
        editorState.clearSensitiveEditorState(::newEditorSession)
    }

    fun clearRevealedValues() {
        clearSecretReads()
    }

    private fun newEditorSession(): Long = ++nextEditorSession

    private fun setSelectedTarget(target: SecretTarget?) {
        clearSecretReads()
        selectedTarget.value = target
        persistSelectedTarget(target)
    }

    private fun clearSelectedTarget(expected: SecretTarget): Boolean {
        if (!selectedTarget.compareAndSet(expected, null)) return false
        clearSecretReads()
        persistSelectedTarget(null)
        return true
    }

    private fun persistSelectedTarget(target: SecretTarget?) {
        savedStateHandle[SELECTED_SECRET] = (target as? SecretTarget.Stored)?.id
        savedStateHandle[SELECTED_UPLOAD] = (target as? SecretTarget.Upload)?.requestId
        savedStateHandle[RETAIN_RESOLVED_UPLOAD] =
            (target as? SecretTarget.Upload)?.retainResolved ?: false
    }

    private suspend fun generateSshKey(
        algorithm: SshKeyAlgorithm,
        comment: String,
    ): SshPrivateKey = secretRepository.generateSshKey(algorithm, comment)

    private suspend fun importSshKey(value: String): SshPrivateKey =
        secretRepository.importSshKey(value)

    private suspend fun prepareSshKey(source: SshKeyDraft): SshKeyPreparation {
        val result = runCatchingNonCancellation {
            when (source.inputMode) {
                SshKeyInputMode.GENERATE -> generateSshKey(source.algorithm, source.comment)
                SshKeyInputMode.IMPORT -> importSshKey(source.privateKeyText)
            }
        }
        return SshKeyPreparation(
            key = result.getOrNull(),
            error = result.exceptionOrNull()?.let { failure ->
                failure.message ?: "The private key is not valid"
            },
        )
    }

    private fun launchEditorCommit(
        expected: SecretsEditor,
        action: suspend (SecretsEditorCommit) -> Unit,
    ) {
        val commit = editorState.beginEditorCommit(expected) ?: return
        viewModelScope.launch {
            try {
                action(commit)
            } finally {
                editorState.failEditorCommit(commit)
            }
        }
    }

    fun saveSecretEditor(
        expected: SecretsEditor.Secret,
        name: String,
        description: String,
    ) {
        launchEditorCommit(expected) { commit ->
            val error = if (expected.state.secret == null) {
                when (
                    val result = when (expected.state.type) {
                        SecretType.ENVIRONMENT ->
                            secretRepository.createEnvironmentSecret(name, description)
                        SecretType.SSH -> secretRepository.createSshSecret(
                            name,
                            description,
                            checkNotNull(expected.state.sshKeyDraft.preparedKey),
                        )
                    }
                ) {
                    is CreateSecretResult.Created -> {
                        if (editorState.completeEditorCommit(commit)) {
                            setSelectedTarget(SecretTarget.Stored(result.id))
                        }
                        null
                    }
                    CreateSecretResult.NameInUse -> R.string.secret_name_in_use
                }
            } else {
                when (secretRepository.saveSecret(expected.state.secret.id, name, description)) {
                    SaveSecretResult.SAVED -> {
                        editorState.completeEditorCommit(commit)
                        null
                    }
                    SaveSecretResult.NAME_IN_USE -> R.string.secret_name_in_use
                    SaveSecretResult.NOT_FOUND -> R.string.secret_not_found
                }
            }
            if (error != null && editorState.failEditorCommit(commit)) publish(error)
        }
    }

    fun replaceSshKey(expected: SecretsEditor.SshKey) {
        val privateKey = expected.state.sshKeyDraft.preparedKey ?: return
        launchEditorCommit(expected) { commit ->
            when (secretRepository.replaceSshKey(expected.state.secretId, privateKey)) {
                is SaveSshSecretResult.Saved -> {
                    editorState.completeEditorCommit(commit)
                    publish(R.string.ssh_key_replaced)
                }
                else -> if (editorState.failEditorCommit(commit)) {
                    publish(R.string.ssh_key_replace_failed)
                }
            }
        }
    }

    fun saveSshComment(id: String, comment: String) {
        viewModelScope.launch {
            publish(
                when (secretRepository.saveSshComment(id, comment)) {
                    is SaveSshSecretResult.Saved -> R.string.ssh_public_comment_updated
                    else -> R.string.ssh_public_comment_update_failed
                },
            )
        }
    }

    fun saveApprovalMode(
        id: String,
        mode: SecretApprovalMode,
    ) {
        viewModelScope.launch {
            publish(
                if (secretRepository.saveApprovalMode(id, mode) == SaveSecretResult.SAVED) {
                    R.string.default_approval_updated
                } else {
                    R.string.default_approval_update_failed
                },
            )
        }
    }

    fun saveInstructions(id: String, instructions: String) {
        viewModelScope.launch {
            publish(
                if (secretRepository.saveInstructions(id, instructions) == SaveSecretResult.SAVED) {
                    R.string.instructions_updated
                } else {
                    R.string.instructions_update_failed
                },
            )
        }
    }

    fun saveGeneralInstructions(instructions: String) {
        viewModelScope.launch {
            publish(
                if (deviceSettings.saveInstructions(instructions)) {
                    R.string.general_instructions_updated
                } else {
                    R.string.general_instructions_update_failed
                },
            )
        }
    }

    fun setClientApprovalOverride(
        secretId: String,
        clientId: String,
        mode: SecretApprovalMode?,
    ) {
        viewModelScope.launch {
            publish(
                if (
                    secretRepository.setClientApprovalOverride(secretId, clientId, mode) ==
                    SaveSecretResult.SAVED
                ) {
                    R.string.client_approval_updated
                } else {
                    R.string.client_approval_update_failed
                },
            )
        }
    }

    fun endTemporaryAccess(
        secretId: String,
        clientId: String,
        operation: TemporaryAccessOperation,
    ) {
        viewModelScope.launch {
            publish(
                if (secretRepository.endTemporaryAccess(secretId, clientId, operation)) {
                    R.string.temporary_access_ended
                } else {
                    R.string.temporary_access_already_ended
                },
            )
        }
    }

    fun deleteSecret(id: String) {
        viewModelScope.launch {
            val deleted = secretRepository.deleteSecret(id)
            if (deleted) clearSelectedTarget(SecretTarget.Stored(id))
            publish(if (deleted) R.string.secret_deleted else R.string.secret_not_found)
        }
    }

    fun saveVariableEditor(
        expected: SecretsEditor.Variable,
        name: String,
        value: String,
        sensitive: Boolean,
        notes: String,
        replaceValue: Boolean,
    ) {
        launchEditorCommit(expected) { commit ->
            val variable = expected.state.variable
            val error = if (variable == null) {
                when (
                    val action = actions.createEnvironmentVariable(
                        secretId = expected.state.secretId,
                        name = name,
                        value = value,
                        sensitive = sensitive,
                        notes = notes,
                    )
                ) {
                    is ProtectedActionResult.AuthenticationFailed -> {
                        if (editorState.failEditorCommit(commit)) publish(action.message)
                        return@launchEditorCommit
                    }
                    is ProtectedActionResult.Completed -> when (action.value) {
                        is CreateEnvironmentVariableResult.Created -> {
                            editorState.completeEditorCommit(commit)
                            null
                        }
                        CreateEnvironmentVariableResult.NameInUse ->
                            R.string.variable_name_in_use
                        CreateEnvironmentVariableResult.SecretNotFound ->
                            R.string.secret_not_found
                        CreateEnvironmentVariableResult.AuthenticationRequired ->
                            error("The action service did not resolve authentication")
                    }
                }
            } else {
                when (
                    val action = actions.saveEnvironmentVariable(
                        id = variable.id,
                        name = name,
                        sensitive = sensitive,
                        notes = notes,
                        replacementValue = value.takeIf { replaceValue },
                    )
                ) {
                    is ProtectedActionResult.AuthenticationFailed -> {
                        if (editorState.failEditorCommit(commit)) publish(action.message)
                        return@launchEditorCommit
                    }
                    is ProtectedActionResult.Completed -> when (action.value) {
                        SaveEnvironmentVariableResult.SAVED -> {
                            editorState.completeEditorCommit(commit)
                            publish(R.string.environment_variable_updated, variable.id)
                            null
                        }
                        SaveEnvironmentVariableResult.NAME_IN_USE ->
                            R.string.variable_name_in_use
                        SaveEnvironmentVariableResult.NOT_FOUND ->
                            R.string.variable_not_found
                        SaveEnvironmentVariableResult.AUTHENTICATION_REQUIRED ->
                            error("The action service did not resolve authentication")
                        SaveEnvironmentVariableResult.VALUE_UNAVAILABLE ->
                            R.string.stored_value_unavailable
                        SaveEnvironmentVariableResult.VALUE_CORRUPTED ->
                            R.string.stored_value_corrupted
                        SaveEnvironmentVariableResult.UNSUPPORTED_FORMAT ->
                            R.string.stored_value_unsupported
                    }
                }
            }
            if (error != null && editorState.failEditorCommit(commit)) publish(error)
        }
    }

    fun deleteEnvironmentVariable(expected: SecretsEditor.Variable) {
        val id = expected.state.variable?.id ?: return
        launchEditorCommit(expected) { commit ->
            val deleted = secretRepository.deleteEnvironmentVariable(id)
            if (deleted) {
                editorState.completeEditorCommit(commit)
                publish(
                    message = R.string.variable_deleted,
                    hiddenVariableId = id,
                )
            } else if (editorState.failEditorCommit(commit)) {
                publish(
                    message = R.string.variable_not_found,
                    hiddenVariableId = id,
                )
            }
        }
    }

    suspend fun readEnvironmentVariableValue(id: String): EnvironmentVariableValue =
        actions.readNonSensitiveEnvironmentVariable(id)

    fun toggleEnvironmentVariableReveal(
        variable: EnvironmentVariableMetadata,
    ) {
        if (revealedEnvironmentValues.value.containsKey(variable.id)) {
            revealedEnvironmentValues.update { it - variable.id }
            return
        }
        val epoch = secretReadEpoch
        viewModelScope.launch {
            if (!storedReadIsCurrent(variable.secretId, epoch)) return@launch
            val value = readStoredValue(variable.id, SecretValueAction.REVEAL) ?: return@launch
            if (storedReadIsCurrent(variable.secretId, epoch)) {
                revealedEnvironmentValues.update { it + (variable.id to value.value) }
            }
        }
    }

    fun copyEnvironmentVariable(
        variable: EnvironmentVariableMetadata,
    ) {
        val epoch = secretReadEpoch
        viewModelScope.launch {
            if (!storedReadIsCurrent(variable.secretId, epoch)) return@launch
            val value = readStoredValue(variable.id, SecretValueAction.COPY) ?: return@launch
            if (storedReadIsCurrent(variable.secretId, epoch)) {
                clipboardState.value = SecretClipboardValue(
                    token = ++nextClipboardToken,
                    label = value.name,
                    value = value.value,
                    sensitive = value.sensitive,
                )
            }
        }
    }

    fun editEnvironmentVariable(
        variable: EnvironmentVariableMetadata,
    ) {
        if (!variable.valueAvailable) {
            startEditingEnvironmentVariable(variable, null)
            return
        }
        val epoch = secretReadEpoch
        viewModelScope.launch {
            if (!storedReadIsCurrent(variable.secretId, epoch)) return@launch
            val value = readStoredValue(variable.id, SecretValueAction.EDIT) ?: return@launch
            if (storedReadIsCurrent(variable.secretId, epoch)) {
                startEditingEnvironmentVariable(variable, value.value)
            }
        }
    }

    fun consumeClipboard(expected: SecretClipboardValue) {
        clipboardState.compareAndSet(expected, null)
    }

    fun toggleSecretUploadVariableReveal(
        requestId: String,
        variable: SecretUploadVariableDetails,
    ) {
        if (revealedUploadValuesState.value.containsKey(variable.id)) {
            revealedUploadValuesState.update { it - variable.id }
            return
        }
        val epoch = secretReadEpoch
        viewModelScope.launch {
            if (!uploadReadIsCurrent(requestId, epoch)) return@launch
            val value = when (
                val action = actions.readSecretUploadVariable(requestId, variable.id)
            ) {
                is ProtectedActionResult.AuthenticationFailed -> null.also {
                    publish(action.message)
                }
                is ProtectedActionResult.Completed -> when (val result = action.value) {
                    is SecretUploadVariableValue.Available -> result.value
                    is SecretUploadVariableValue.AuthenticationRequired ->
                        error("The action service did not resolve authentication")
                    SecretUploadVariableValue.NotFound -> null.also {
                        publish("This environment variable is no longer available")
                    }
                    SecretUploadVariableValue.Unavailable -> null.also {
                        publish("The encryption key is unavailable")
                    }
                    SecretUploadVariableValue.Corrupted -> null.also {
                        publish("The uploaded value could not be authenticated")
                    }
                    SecretUploadVariableValue.UnsupportedEncryption -> null.also {
                        publish("The uploaded value uses unsupported encryption")
                    }
                }
            } ?: return@launch
            if (uploadReadIsCurrent(requestId, epoch)) {
                revealedUploadValuesState.update { it + (variable.id to value) }
            }
        }
    }

    fun approveSecretUpload(
        requestId: String,
        approvedName: String,
    ) {
        viewModelScope.launch {
            val result = actions.approveSecretUpload(requestId, approvedName)
            publish(result.message())
            if (result is SecretUploadDecisionResult.Approved) {
                clearDecidedUpload(requestId)
            }
        }
    }

    fun rejectSecretUpload(requestId: String) {
        viewModelScope.launch {
            val result = actions.rejectSecretUpload(requestId)
            publish(result.message())
            if (result == SecretUploadDecisionResult.Rejected) clearDecidedUpload(requestId)
        }
    }

    fun setSecretUploadVariableSensitivity(
        requestId: String,
        variable: SecretUploadVariableDetails,
        sensitive: Boolean,
    ) {
        val epoch = secretReadEpoch
        viewModelScope.launch {
            if (!uploadReadIsCurrent(requestId, epoch)) return@launch
            when (
                val result = actions.setSecretUploadVariableSensitivity(
                    requestId,
                    variable.id,
                    sensitive,
                )
            ) {
                is ProtectedActionResult.AuthenticationFailed -> publish(result.message)
                is ProtectedActionResult.Completed -> when (result.value) {
                    SecretUploadSensitivityResult.Changed -> Unit
                    SecretUploadSensitivityResult.NotFound ->
                        publish(R.string.sensitivity_update_failed)
                    is SecretUploadSensitivityResult.AuthenticationRequired ->
                        error("The action service did not resolve authentication")
                }
            }
        }
    }

    private suspend fun publish(
        @StringRes message: Int,
        hiddenVariableId: String? = null,
    ) {
        hiddenVariableId?.let { id -> revealedEnvironmentValues.update { it - id } }
        uiEvents.send(SecretsUiMessage.Resource(message))
    }

    private suspend fun publish(message: String, hiddenVariableId: String? = null) {
        hiddenVariableId?.let { id -> revealedEnvironmentValues.update { it - id } }
        uiEvents.send(SecretsUiMessage.Text(message))
    }

    private suspend fun readStoredValue(
        id: String,
        action: SecretValueAction,
    ): EnvironmentVariableValue.Available? =
        when (val result = actions.readEnvironmentVariable(id, action)) {
            is ProtectedActionResult.AuthenticationFailed -> null.also {
                publish(result.message)
            }
            is ProtectedActionResult.Completed -> when (val value = result.value) {
                is EnvironmentVariableValue.Available -> value
                is EnvironmentVariableValue.AuthenticationRequired ->
                    error("The action service did not resolve authentication")
                EnvironmentVariableValue.Unavailable ->
                    null.also { publish(R.string.value_unavailable) }
                EnvironmentVariableValue.Corrupted ->
                    null.also { publish(R.string.corrupted_value) }
                EnvironmentVariableValue.UnsupportedFormat ->
                    null.also { publish(R.string.unsupported_value) }
                EnvironmentVariableValue.NotFound ->
                    null.also { publish(R.string.missing_value) }
            }
        }

    private fun storedReadIsCurrent(secretId: String, epoch: Long): Boolean =
        epoch == secretReadEpoch && selectedTarget.value == SecretTarget.Stored(secretId)

    private fun uploadReadIsCurrent(requestId: String, epoch: Long): Boolean =
        epoch == secretReadEpoch &&
            (selectedTarget.value as? SecretTarget.Upload)?.requestId == requestId

    private fun clearSecretReads() {
        secretReadEpoch += 1
        revealedEnvironmentValues.value = emptyMap()
        revealedUploadValuesState.value = emptyMap()
        clipboardState.value = null
    }

    private fun clearDecidedUpload(requestId: String) {
        val selected = selectedTarget.value as? SecretTarget.Upload ?: return
        if (selected.requestId != requestId) return
        clearSecretReads()
        if (selected.retainResolved) return
        clearSelectedTarget(selected)
    }

    private companion object {
        const val SELECTED_SECRET = "selected_secret_id"
        const val SELECTED_UPLOAD = "selected_upload_request_id"
        const val RETAIN_RESOLVED_UPLOAD = "retain_resolved_upload"
        const val READ_MODEL_STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private data class SshKeyPreparation(
    val key: SshPrivateKey?,
    val error: String?,
)

private fun SshKeyDraft.hasSameSourceAs(other: SshKeyDraft): Boolean =
    inputMode == other.inputMode &&
        algorithm == other.algorithm &&
        privateKeyText == other.privateKeyText &&
        comment == other.comment

private fun SecretsEditor.sessionOrNull(): Long? = when (this) {
    SecretsEditor.None -> null
    is SecretsEditor.Secret -> session
    is SecretsEditor.Variable -> session
    is SecretsEditor.SshKey -> session
}

internal data class SecretsEditorCommit(
    val editing: SecretsEditor,
    val committing: SecretsEditor,
)

internal fun MutableStateFlow<SecretsEditor>.beginEditorCommit(
    expected: SecretsEditor,
): SecretsEditorCommit? {
    val committing = when (expected) {
        SecretsEditor.None -> return null
        is SecretsEditor.Secret -> {
            if (expected.phase != EditorPhase.EDITING) return null
            expected.copy(phase = EditorPhase.COMMITTING)
        }
        is SecretsEditor.Variable -> {
            if (expected.phase != EditorPhase.EDITING) return null
            expected.copy(phase = EditorPhase.COMMITTING)
        }
        is SecretsEditor.SshKey -> {
            if (expected.phase != EditorPhase.EDITING) return null
            expected.copy(phase = EditorPhase.COMMITTING)
        }
    }
    if (!compareAndSet(expected, committing)) return null
    return SecretsEditorCommit(editing = expected, committing = committing)
}

internal fun MutableStateFlow<SecretsEditor>.completeEditorCommit(
    commit: SecretsEditorCommit,
): Boolean = compareAndSet(commit.committing, SecretsEditor.None)

internal fun MutableStateFlow<SecretsEditor>.failEditorCommit(
    commit: SecretsEditorCommit,
): Boolean = compareAndSet(commit.committing, commit.editing)

internal fun MutableStateFlow<SecretsEditor>.mutateEditing(
    session: Long,
    transform: (SecretsEditor) -> SecretsEditor?,
): Boolean {
    while (true) {
        val current = value
        if (current.sessionOrNull() != session || !current.isEditing()) return false
        val updated = transform(current) ?: return false
        if (compareAndSet(current, updated)) return true
    }
}

private fun SecretsEditor.isEditing(): Boolean = when (this) {
    SecretsEditor.None -> false
    is SecretsEditor.Secret -> phase == EditorPhase.EDITING
    is SecretsEditor.Variable -> phase == EditorPhase.EDITING
    is SecretsEditor.SshKey -> phase == EditorPhase.EDITING
}

internal fun MutableStateFlow<SecretsEditor>.clearSensitiveEditorState(
    newSession: () -> Long,
) {
    update { editor ->
        when (editor) {
            SecretsEditor.None -> editor
            is SecretsEditor.Variable -> if (
                editor.state.variable?.sensitive == true || editor.state.sensitive
            ) {
                SecretsEditor.None
            } else {
                editor
            }
            is SecretsEditor.Secret -> when {
                editor.containsPrivateKeyMaterial() -> SecretsEditor.None
                editor.phase == EditorPhase.EDITING -> editor.copy(
                    session = newSession(),
                    state = editor.state.copy(
                        sshKeyDraft = editor.state.sshKeyDraft.withoutPreparation(),
                    ),
                )
                else -> editor
            }
            is SecretsEditor.SshKey -> when {
                editor.containsPrivateKeyMaterial() -> SecretsEditor.None
                editor.phase == EditorPhase.EDITING -> editor.copy(
                    session = newSession(),
                    state = editor.state.copy(
                        sshKeyDraft = editor.state.sshKeyDraft.withoutPreparation(),
                    ),
                )
                else -> editor
            }
        }
    }
}

private fun SecretsEditor.Secret.containsPrivateKeyMaterial(): Boolean =
    state.sshKeyDraft.privateKeyText.isNotEmpty() || state.sshKeyDraft.preparedKey != null

private fun SecretsEditor.SshKey.containsPrivateKeyMaterial(): Boolean =
    state.sshKeyDraft.privateKeyText.isNotEmpty() || state.sshKeyDraft.preparedKey != null
