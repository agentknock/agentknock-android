package dev.agentknock.preview

import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.secret.*

internal val previewSecret =
    SecretDetails(
        id = "preview-database",
        name = "orders-db-prod",
        description = "PostgreSQL password for the production orders database.",
        type = SecretType.ENVIRONMENT,
        environmentVariables =
            listOf(
                EnvironmentVariableMetadata(
                    id = "preview-password",
                    secretId = "preview-database",
                    name = "PGPASSWORD",
                    sensitive = true,
                    valueAvailable = true,
                    valueUpdatedAt = previewTimestamp,
                )
            ),
        sshKey = null,
        approvalMode = SecretApprovalMode.ASK_ME,
        instructions =
            "Allow connection checks and read-only diagnostics. Ask before changing production data.",
        clientApprovalOverrides =
            listOf(SecretClientApprovalOverride("preview-server", SecretApprovalMode.DENY)),
        temporaryAccessGrants = emptyList(),
        createdAt = previewTimestamp,
        updatedAt = previewTimestamp,
    )

internal val previewClients =
    listOf(
            "preview-server" to "build-runner-01",
            "preview-laptop" to "maya-thinkpad",
        )
        .map { (id, name) ->
            ClientSummary(
                clientId = id,
                name = name,
                hostname = null,
                platform = "linux",
                architecture = "x86_64",
                state = RelayClientState.ACTIVE,
                desiredState = null,
                pairedAt = previewTimestamp,
            )
        }

// Fixed public bytes only; no private key or real credential is needed to render the UI.
private val previewPublicKey =
    SshKeyCodec()
        .publicKey(
            SshKeyAlgorithm.ED25519,
            ByteArray(32) { (it + 1).toByte() },
            "maya@maya-thinkpad",
        )
internal val previewSshKey =
    SshKeyMetadata(
        algorithm = SshKeyAlgorithm.ED25519,
        bits = 256,
        publicKey = previewPublicKey.line,
        fingerprint = previewPublicKey.fingerprint,
        fingerprintHex = previewPublicKey.fingerprintHex,
        comment = "maya@maya-thinkpad",
        privateKeyAvailable = true,
    )
internal val previewSshSecret =
    previewSecret.copy(
        id = "preview-ssh",
        name = "work-ssh",
        description = "Sign work commits and connect to application servers.",
        type = SecretType.SSH,
        environmentVariables = emptyList(),
        sshKey = previewSshKey,
        instructions =
            "Allow signing commits in orders-api. Ask before connecting to production servers.",
    )
internal val previewGrants =
    listOf(
        TemporaryAccessGrant(
            previewSecret.id,
            previewSecret.name,
            "preview-laptop",
            TemporaryAccessOperation.INVOCATION,
            previewTimestamp + 3_600_000,
        )
    )
internal val previewSecrets =
    listOf(previewSecret, previewSshSecret).map {
        SecretSummary(
            it.id,
            it.name,
            it.description,
            it.type,
            it.environmentVariables.size,
            it.sshKey,
            it.createdAt,
            it.updatedAt,
        )
    }
