package dev.agentknock.ui.secrets

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentknockApplication
import dev.agentknock.storage.request.InboxRequestDetails
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.SecretUploadDecisionResult
import dev.agentknock.storage.request.SecretUploadVariableValue
import dev.agentknock.storage.secret.CreateEnvironmentVariableResult
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.EnvironmentVariableMetadata
import dev.agentknock.storage.secret.EnvironmentVariableValue
import dev.agentknock.storage.secret.SecretDetails
import dev.agentknock.storage.secret.SecretSummary
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.TemporaryAccessOperation
import dev.agentknock.storage.secret.SshPrivateKey
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SaveSshSecretResult
import dev.agentknock.storage.secret.SaveEnvironmentVariableResult
import dev.agentknock.storage.secret.SaveSecretResult
import dev.agentknock.ui.pendingSecretUploads
import dev.agentknock.storage.vault.DeviceConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
internal class SecretsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AgentknockApplication).container
    private val repository = container.secrets
    private val requests = container.requests
    private val selectedSecretId = MutableStateFlow<String?>(null)
    private val selectedUploadRequestId = MutableStateFlow<Long?>(null)
    private val secretEditorState = MutableStateFlow<SecretEditorState?>(null)
    private val variableEditorState = MutableStateFlow<VariableEditorState?>(null)
    private val sshKeyEditorState = MutableStateFlow<SshKeyEditorState?>(null)

    val selection: StateFlow<String?> = selectedSecretId.asStateFlow()
    val uploadSelection: StateFlow<Long?> = selectedUploadRequestId.asStateFlow()
    val secretEditor: StateFlow<SecretEditorState?> = secretEditorState.asStateFlow()
    val variableEditor: StateFlow<VariableEditorState?> = variableEditorState.asStateFlow()
    val sshKeyEditor: StateFlow<SshKeyEditorState?> = sshKeyEditorState.asStateFlow()

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

    val configuration: StateFlow<DeviceConfiguration?> = container.vault.observeConfiguration()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    val pendingUploads: StateFlow<List<InboxRequestSummary>> = requests.observeRequests()
        .map(List<InboxRequestSummary>::pendingSecretUploads)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val selectedUpload: StateFlow<InboxRequestDetails?> = selectedUploadRequestId
        .flatMapLatest { id -> id?.let(requests::observeRequest) ?: flowOf(null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    val selectedSecret: StateFlow<SecretDetails?> = selectedSecretId
        .flatMapLatest { id -> id?.let(repository::observeSecret) ?: flowOf(null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    fun selectSecret(id: String?) {
        if (id != null) selectedUploadRequestId.value = null
        selectedSecretId.value = id
    }

    fun selectUpload(requestId: Long?) {
        if (requestId != null) {
            selectedSecretId.value = null
            secretEditorState.value = null
            variableEditorState.value = null
        }
        selectedUploadRequestId.value = requestId
    }

    fun startNewSecret() {
        selectedUploadRequestId.value = null
        secretEditorState.value = SecretEditorState(
            secret = null,
            name = "",
            description = "",
            type = dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE,
        )
    }

    fun startEditingSecret(secret: SecretDetails) {
        secretEditorState.value = SecretEditorState(
            secret = secret,
            name = secret.name,
            description = secret.description,
            type = secret.type,
        )
    }

    fun updateSecretEditor(state: SecretEditorState?) {
        secretEditorState.value = state
    }

    fun startReplacingSshKey(secret: SecretDetails) {
        val key = checkNotNull(secret.sshKey)
        sshKeyEditorState.value = SshKeyEditorState(
            secretId = secret.id,
            secretName = secret.name,
            currentKey = key,
            inputMode = SshKeyInputMode.GENERATE,
            algorithm = SshKeyAlgorithm.fromStoredName(key.algorithm)
                ?: SshKeyAlgorithm.ED25519,
            privateKeyText = "",
            comment = key.comment,
            preparedKey = null,
            error = null,
        )
    }

    fun updateSshKeyEditor(state: SshKeyEditorState?) {
        sshKeyEditorState.value = state
    }

    fun startNewEnvironmentVariable(secretId: String) {
        variableEditorState.value = VariableEditorState(
            secretId = secretId,
            variable = null,
            currentValue = null,
            name = "",
            value = "",
            valueEdited = false,
            sensitive = true,
            notes = "",
        )
    }

    fun startEditingEnvironmentVariable(
        variable: EnvironmentVariableMetadata,
        currentValue: String?,
    ) {
        variableEditorState.value = VariableEditorState(
            secretId = variable.secretId,
            variable = variable,
            currentValue = currentValue,
            name = variable.name,
            value = currentValue.orEmpty(),
            valueEdited = false,
            sensitive = variable.sensitive,
            notes = variable.notes,
        )
    }

    fun updateVariableEditor(state: VariableEditorState?) {
        variableEditorState.value = state
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
        container.vault.saveInstructions(instructions)

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
        if (deleted && selectedSecretId.value == id) selectedSecretId.value = null
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
        requestId: Long,
        approvedName: String,
    ): SecretUploadDecisionResult {
        container.localStorage.await()
        return requests.approveSecretUpload(requestId, approvedName)
    }

    suspend fun rejectSecretUpload(requestId: Long): SecretUploadDecisionResult {
        container.localStorage.await()
        return requests.rejectSecretUpload(requestId)
    }

    suspend fun readSecretUploadVariable(
        requestId: Long,
        variableId: String,
    ): SecretUploadVariableValue {
        container.localStorage.await()
        return requests.readSecretUploadVariable(requestId, variableId)
    }

    suspend fun setSecretUploadVariableSensitivity(
        requestId: Long,
        variableId: String,
        sensitive: Boolean,
    ): Boolean {
        container.localStorage.await()
        return requests.setSecretUploadVariableSensitivity(requestId, variableId, sensitive)
    }
}
