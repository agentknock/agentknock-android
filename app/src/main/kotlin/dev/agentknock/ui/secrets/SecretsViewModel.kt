package dev.agentknock.ui.secrets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.storage.request.InboxRequestContent
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.SecretUploadDecisionResult
import dev.agentknock.storage.request.SecretUploadRequestState
import dev.agentknock.storage.request.SecretUploadVariableValue
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
import dev.agentknock.storage.device.DeviceIdentityRepository
import dev.agentknock.storage.request.RequestRepository
import dev.agentknock.storage.request.RequestInbox
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.runCatchingNonCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi

internal sealed interface SecretTarget {
    data class Stored(val id: String) : SecretTarget
    data class Upload(val requestId: String) : SecretTarget
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
    ) : SecretsEditor

    data class Variable(
        val session: Long,
        val state: VariableEditorState,
    ) : SecretsEditor

    data class SshKey(
        val session: Long,
        val state: SshKeyEditorState,
    ) : SecretsEditor
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class SecretsViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repository: SecretRepository,
    private val requests: RequestRepository,
    private val inbox: RequestInbox,
    private val deviceIdentity: DeviceIdentityRepository,
    private val awaitStorageReady: suspend () -> Unit,
) : ViewModel() {
    private val selectedTarget = MutableStateFlow(
        savedStateHandle.get<String>(SELECTED_SECRET)?.let(SecretTarget::Stored)
            ?: savedStateHandle.get<String>(SELECTED_UPLOAD)?.let(SecretTarget::Upload),
    )
    private val editorState = MutableStateFlow<SecretsEditor>(SecretsEditor.None)
    private var nextEditorSession = 0L
    private var secretReadEpoch = 0L

    val editor: StateFlow<SecretsEditor> = editorState.asStateFlow()

    val secrets: StateFlow<List<SecretSummary>> = repository.observeSecrets().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    val clients: StateFlow<List<ClientSummary>> = requests.observeClients().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    val configuration: StateFlow<DeviceConfiguration?> = deviceIdentity
        .observeConfiguration()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    val pendingUploads: StateFlow<List<InboxRequestSummary>> = inbox.observeRequests()
        .map(List<InboxRequestSummary>::pendingSecretUploads)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val content: StateFlow<SecretsContent> = selectedTarget
        .flatMapLatest { target ->
            when (target) {
                null -> flowOf(SecretsContent.List)
                is SecretTarget.Stored -> repository.observeSecret(target.id)
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
                        if (
                            request == null ||
                            upload == null ||
                            upload.details.state != SecretUploadRequestState.REVIEW_PENDING
                        ) {
                            if (clearSelectedTarget(target)) {
                                emit(SecretsContent.List)
                            }
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
        editorState.value = SecretsEditor.None
        setSelectedTarget(id?.let(SecretTarget::Stored))
    }

    fun selectUpload(requestId: String?) {
        editorState.value = SecretsEditor.None
        setSelectedTarget(requestId?.let(SecretTarget::Upload))
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
        editorState.update { editor ->
            when {
                editor !is SecretsEditor.Secret || editor.session != session -> editor
                state.type != SecretType.SSH -> editor.copy(
                    state = state.copy(
                        sshKeyDraft = state.sshKeyDraft.copy(
                            privateKeyText = "",
                        ).withoutPreparation(),
                    ),
                )
                else -> editor.copy(state = state)
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
        editorState.update { editor ->
            when {
                editor !is SecretsEditor.SshKey || editor.session != session -> editor
                state.sshKeyDraft.inputMode == SshKeyInputMode.GENERATE -> editor.copy(
                    state = state.copy(
                        sshKeyDraft = state.sshKeyDraft.copy(privateKeyText = ""),
                    ),
                )
                else -> editor.copy(state = state)
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
        editorState.update { editor ->
            when {
                editor !is SecretsEditor.Variable || editor.session != session -> editor
                else -> editor.copy(state = state)
            }
        }
    }

    fun editorIsCurrent(expected: SecretsEditor): Boolean = editorState.value == expected

    fun closeEditor(session: Long): Boolean {
        while (true) {
            val current = editorState.value
            if (current.sessionOrNull() != session) return false
            if (editorState.compareAndSet(current, SecretsEditor.None)) return true
        }
    }

    fun closeEditorIfCurrent(expected: SecretsEditor): Boolean =
        editorState.compareAndSet(expected, SecretsEditor.None)

    fun showCreatedSecretIfEditorCurrent(
        expected: SecretsEditor.Secret,
        secretId: String,
    ): Boolean {
        if (!editorState.compareAndSet(expected, SecretsEditor.None)) return false
        setSelectedTarget(SecretTarget.Stored(secretId))
        return true
    }

    fun beginSecretRead(secretId: String): Long? =
        secretReadEpoch.takeIf { selectedTarget.value == SecretTarget.Stored(secretId) }

    fun secretReadIsCurrent(secretId: String, epoch: Long): Boolean =
        epoch == secretReadEpoch && selectedTarget.value == SecretTarget.Stored(secretId)

    fun prepareSecretSshKey() {
        val current = editorState.value as? SecretsEditor.Secret ?: return
        val source = current.state
        if (source.type != SecretType.SSH) return
        val sourceDraft = source.sshKeyDraft
        val editor = current.copy(
            session = newEditorSession(),
            state = source.copy(
                sshKeyDraft = sourceDraft.withoutPreparation(),
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
                                preparedKey = preparation.key,
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
        val source = current.state
        val sourceDraft = source.sshKeyDraft
        val editor = current.copy(
            session = newEditorSession(),
            state = source.copy(
                sshKeyDraft = sourceDraft.withoutPreparation(),
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
                                preparedKey = preparation.key,
                                error = preparation.error,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun clearSensitiveEditor() {
        secretReadEpoch += 1
        editorState.update { editor ->
            when (editor) {
                SecretsEditor.None -> editor
                is SecretsEditor.Variable -> if (
                    editor.state.variable?.sensitive == true || editor.state.sensitive
                ) {
                    SecretsEditor.None
                } else {
                    editor
                }
                is SecretsEditor.Secret -> if (
                    editor.state.sshKeyDraft.privateKeyText.isNotEmpty() ||
                    editor.state.sshKeyDraft.preparedKey != null
                ) {
                    SecretsEditor.None
                } else {
                    editor.copy(session = newEditorSession())
                }
                is SecretsEditor.SshKey -> if (
                    editor.state.sshKeyDraft.privateKeyText.isNotEmpty() ||
                    editor.state.sshKeyDraft.preparedKey != null
                ) {
                    SecretsEditor.None
                } else {
                    editor.copy(session = newEditorSession())
                }
            }
        }
    }

    private fun newEditorSession(): Long = ++nextEditorSession

    private fun setSelectedTarget(target: SecretTarget?) {
        secretReadEpoch += 1
        selectedTarget.value = target
        persistSelectedTarget(target)
    }

    private fun clearSelectedTarget(expected: SecretTarget): Boolean {
        if (!selectedTarget.compareAndSet(expected, null)) return false
        secretReadEpoch += 1
        persistSelectedTarget(null)
        return true
    }

    private fun persistSelectedTarget(target: SecretTarget?) {
        savedStateHandle[SELECTED_SECRET] = (target as? SecretTarget.Stored)?.id
        savedStateHandle[SELECTED_UPLOAD] = (target as? SecretTarget.Upload)?.requestId
    }

    suspend fun createSecret(name: String, description: String): CreateSecretResult =
        repository.createEnvironmentSecret(name, description)

    suspend fun createSshSecret(
        name: String,
        description: String,
        privateKey: SshPrivateKey,
    ): CreateSecretResult = repository.createSshSecret(name, description, privateKey)

    suspend fun generateSshKey(
        algorithm: SshKeyAlgorithm,
        comment: String,
    ): SshPrivateKey = repository.generateSshKey(algorithm, comment)

    suspend fun importSshKey(value: String): SshPrivateKey =
        repository.importSshKey(value)

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

    suspend fun replaceSshKey(id: String, privateKey: SshPrivateKey): SaveSshSecretResult =
        repository.replaceSshKey(id, privateKey)

    suspend fun saveSshComment(id: String, comment: String): SaveSshSecretResult =
        repository.saveSshComment(id, comment)

    suspend fun saveSecret(
        id: String,
        name: String,
        description: String,
    ): SaveSecretResult = repository.saveSecret(id, name, description)

    suspend fun saveApprovalMode(
        id: String,
        mode: SecretApprovalMode,
    ): SaveSecretResult = repository.saveApprovalMode(id, mode)

    suspend fun saveInstructions(id: String, instructions: String): SaveSecretResult =
        repository.saveInstructions(id, instructions)

    suspend fun saveGeneralInstructions(instructions: String): Boolean =
        deviceIdentity.saveInstructions(instructions)

    suspend fun setClientApprovalOverride(
        secretId: String,
        clientId: String,
        mode: SecretApprovalMode?,
    ): SaveSecretResult = repository.setClientApprovalOverride(secretId, clientId, mode)

    suspend fun endTemporaryAccess(
        secretId: String,
        clientId: String,
        operation: TemporaryAccessOperation,
    ): Boolean = repository.endTemporaryAccess(secretId, clientId, operation)

    suspend fun deleteSecret(id: String): Boolean {
        val deleted = repository.deleteSecret(id)
        if (deleted) clearSelectedTarget(SecretTarget.Stored(id))
        return deleted
    }

    suspend fun createEnvironmentVariable(
        secretId: String,
        name: String,
        value: String,
        sensitive: Boolean,
        notes: String,
    ): CreateEnvironmentVariableResult = repository.createEnvironmentVariable(
        secretId = secretId,
        name = name,
        value = value,
        sensitive = sensitive,
        notes = notes,
    )

    suspend fun saveEnvironmentVariable(
        id: String,
        name: String,
        sensitive: Boolean,
        notes: String,
        replacementValue: String?,
    ): SaveEnvironmentVariableResult = repository.saveEnvironmentVariable(
        id = id,
        name = name,
        sensitive = sensitive,
        notes = notes,
        replacementValue = replacementValue,
    )

    suspend fun deleteEnvironmentVariable(id: String): Boolean =
        repository.deleteEnvironmentVariable(id)

    suspend fun readEnvironmentVariableValue(id: String): EnvironmentVariableValue =
        repository.readEnvironmentVariableValue(id)

    suspend fun approveSecretUpload(
        requestId: String,
        approvedName: String,
    ): SecretUploadDecisionResult {
        awaitStorageReady()
        return requests.approveSecretUpload(requestId, approvedName)
    }

    suspend fun rejectSecretUpload(requestId: String): SecretUploadDecisionResult {
        awaitStorageReady()
        return requests.rejectSecretUpload(requestId)
    }

    suspend fun readSecretUploadVariable(
        requestId: String,
        variableId: String,
    ): SecretUploadVariableValue {
        awaitStorageReady()
        return requests.readSecretUploadVariable(requestId, variableId)
    }

    suspend fun setSecretUploadVariableSensitivity(
        requestId: String,
        variableId: String,
        sensitive: Boolean,
    ): Boolean {
        awaitStorageReady()
        return requests.setSecretUploadVariableSensitivity(requestId, variableId, sensitive)
    }

    private companion object {
        const val SELECTED_SECRET = "selected_secret_id"
        const val SELECTED_UPLOAD = "selected_upload_request_id"
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
