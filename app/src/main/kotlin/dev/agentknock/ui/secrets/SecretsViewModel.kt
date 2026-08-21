package dev.agentknock.ui.secrets

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentknockApplication
import dev.agentknock.storage.secret.CreateEnvironmentVariableResult
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.EnvironmentVariableValue
import dev.agentknock.storage.secret.SecretDetails
import dev.agentknock.storage.secret.SecretSummary
import dev.agentknock.storage.secret.SaveEnvironmentVariableResult
import dev.agentknock.storage.secret.SaveSecretResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
internal class SecretsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as AgentknockApplication).container.secrets
    private val selectedSecretId = MutableStateFlow<String?>(null)

    val selection: StateFlow<String?> = selectedSecretId.asStateFlow()

    val secrets: StateFlow<List<SecretSummary>> = repository.observeSecrets().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    val selectedSecret: StateFlow<SecretDetails?> = selectedSecretId
        .flatMapLatest { id -> id?.let(repository::observeSecret) ?: flowOf(null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    fun selectSecret(id: String?) {
        selectedSecretId.value = id
    }

    suspend fun createSecret(name: String, description: String): CreateSecretResult =
        repository.createSecret(name, description)

    suspend fun saveSecret(
        id: String,
        name: String,
        description: String,
    ): SaveSecretResult = repository.saveSecret(id, name, description)

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
}
