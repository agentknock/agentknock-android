package dev.agentknock

import android.content.Context
import dev.agentknock.storage.request.PairingDecisionResult
import dev.agentknock.storage.request.RequestDecision
import dev.agentknock.storage.request.RequestDecisionResult
import dev.agentknock.storage.request.RequestRepository
import dev.agentknock.storage.request.SecretUploadDecisionResult
import dev.agentknock.storage.request.SecretUploadSensitivityResult
import dev.agentknock.storage.request.SecretUploadVariableValue
import dev.agentknock.storage.secret.CreateEnvironmentVariableResult
import dev.agentknock.storage.secret.EnvironmentVariableValue
import dev.agentknock.storage.secret.SaveEnvironmentVariableResult
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.ui.auth.DeviceAuthenticationResult

internal enum class SecretValueAction {
    REVEAL,
    COPY,
    EDIT,
}

internal sealed interface ProtectedActionResult<out T> {
    data class Completed<T>(val value: T) : ProtectedActionResult<T>
    data class AuthenticationFailed(val message: String) : ProtectedActionResult<Nothing>
}

/** Complete user operations whose policy or sequencing must not depend on an input surface. */
internal class AgentknockActions(
    private val context: Context,
    private val authorize: suspend (String) -> DeviceAuthenticationResult,
    private val requests: RequestRepository,
    private val secrets: SecretRepository,
    private val awaitStorageReady: suspend () -> Unit,
) {
    suspend fun decideRequest(
        requestId: String,
        decision: RequestDecision,
    ): RequestDecisionResult {
        awaitStorageReady()
        return requests.decideRequest(requestId, decision)
    }

    suspend fun choosePairingCode(
        requestId: String,
        selectedIndex: Int?,
    ): ProtectedActionResult<PairingDecisionResult> {
        awaitStorageReady()
        if (selectedIndex != null) {
            requests.matchingPendingSas(requestId, selectedIndex)?.let { pairing ->
                authenticate(
                    context.getString(
                        R.string.accept_client,
                        pairing.clientLabel ?: context.getString(R.string.unnamed_client),
                    ),
                )?.let { return it }
            }
        }
        return ProtectedActionResult.Completed(requests.chooseSas(requestId, selectedIndex))
    }

    suspend fun rejectPairing(requestId: String): PairingDecisionResult {
        awaitStorageReady()
        return requests.rejectPairing(requestId)
    }

    suspend fun createEnvironmentVariable(
        secretId: String,
        name: String,
        value: String,
        sensitive: Boolean,
        notes: String,
    ): ProtectedActionResult<CreateEnvironmentVariableResult> {
        awaitStorageReady()
        return authenticateAndRetry(
            operation = { authorized ->
                secrets.createEnvironmentVariable(
                    secretId,
                    name,
                    value,
                    sensitive,
                    notes,
                    nonSensitiveCreationAuthorized = authorized,
                )
            },
            challenge = {
                if (it == CreateEnvironmentVariableResult.AuthenticationRequired) {
                    context.getString(R.string.confirm_mark_variable_non_sensitive, name)
                } else {
                    null
                }
            },
        )
    }

    suspend fun saveEnvironmentVariable(
        id: String,
        name: String,
        sensitive: Boolean,
        notes: String,
        replacementValue: String?,
    ): ProtectedActionResult<SaveEnvironmentVariableResult> {
        awaitStorageReady()
        return authenticateAndRetry(
            operation = { authorized ->
                secrets.saveEnvironmentVariable(
                    id,
                    name,
                    sensitive,
                    notes,
                    replacementValue,
                    sensitivityReductionAuthorized = authorized,
                )
            },
            challenge = {
                if (it == SaveEnvironmentVariableResult.AUTHENTICATION_REQUIRED) {
                    context.getString(R.string.confirm_mark_variable_non_sensitive, name)
                } else {
                    null
                }
            },
        )
    }

    suspend fun readEnvironmentVariable(
        id: String,
        action: SecretValueAction,
    ): ProtectedActionResult<EnvironmentVariableValue> {
        awaitStorageReady()
        return authenticateAndRetry(
            operation = { authorized ->
                secrets.readEnvironmentVariableValue(id, sensitiveAccessAuthorized = authorized)
            },
            challenge = {
                (it as? EnvironmentVariableValue.AuthenticationRequired)?.let { required ->
                    secretValueTitle(action, required.name)
                }
            },
        )
    }

    suspend fun readNonSensitiveEnvironmentVariable(id: String): EnvironmentVariableValue {
        awaitStorageReady()
        return secrets.readEnvironmentVariableValue(id, sensitiveAccessAuthorized = false)
    }

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
    ): ProtectedActionResult<SecretUploadVariableValue> {
        awaitStorageReady()
        return authenticateAndRetry(
            operation = { authorized ->
                requests.readSecretUploadVariable(
                    requestId,
                    variableId,
                    sensitiveAccessAuthorized = authorized,
                )
            },
            challenge = {
                (it as? SecretUploadVariableValue.AuthenticationRequired)?.let { required ->
                    context.getString(R.string.reveal_sensitive_value, required.name)
                }
            },
        )
    }

    suspend fun setSecretUploadVariableSensitivity(
        requestId: String,
        variableId: String,
        sensitive: Boolean,
    ): ProtectedActionResult<SecretUploadSensitivityResult> {
        awaitStorageReady()
        return authenticateAndRetry(
            operation = { authorized ->
                requests.setSecretUploadVariableSensitivity(
                    requestId,
                    variableId,
                    sensitive,
                    sensitivityReductionAuthorized = authorized,
                )
            },
            challenge = {
                (it as? SecretUploadSensitivityResult.AuthenticationRequired)?.let { required ->
                    context.getString(
                        R.string.confirm_mark_variable_non_sensitive,
                        required.name,
                    )
                }
            },
        )
    }

    private suspend fun <T> authenticateAndRetry(
        operation: suspend (authorized: Boolean) -> T,
        challenge: (T) -> String?,
    ): ProtectedActionResult<T> {
        val result = operation(false)
        val title = challenge(result) ?: return ProtectedActionResult.Completed(result)
        authenticate(title)?.let { return it }
        return ProtectedActionResult.Completed(operation(true))
    }

    private suspend fun authenticate(title: String): ProtectedActionResult.AuthenticationFailed? =
        when (val result = authorize(title)) {
            DeviceAuthenticationResult.Success -> null
            is DeviceAuthenticationResult.Error ->
                ProtectedActionResult.AuthenticationFailed(result.message)
        }

    private fun secretValueTitle(action: SecretValueAction, name: String): String =
        context.getString(
            when (action) {
                SecretValueAction.REVEAL -> R.string.reveal_sensitive_value
                SecretValueAction.COPY -> R.string.copy_sensitive_value
                SecretValueAction.EDIT -> R.string.edit_sensitive_value
            },
            name,
        )
}
