package dev.agentknock.review

import dev.agentknock.protocol.ClientSoftware
import dev.agentknock.protocol.GitSignRequestMessage
import dev.agentknock.protocol.GitSignChangeStatus
import dev.agentknock.protocol.GitSignChangedPath
import dev.agentknock.protocol.GitSignHead
import dev.agentknock.protocol.GitSignRepository
import dev.agentknock.protocol.InvocationExecOperation
import dev.agentknock.protocol.InvocationExecutableMode
import dev.agentknock.protocol.InvocationRequestMessage
import dev.agentknock.protocol.InvocationStreamKind
import dev.agentknock.protocol.SoftwareInfo
import dev.agentknock.protocol.SshAuthenticationMessageDetails
import dev.agentknock.protocol.SshAuthenticationMethod
import dev.agentknock.protocol.SshSignatureAlgorithm
import dev.agentknock.storage.request.ClientEntity
import dev.agentknock.storage.request.SecretUseRequestEntity
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.approval.SecretApprovalEvaluation
import dev.agentknock.storage.secret.ENVIRONMENT_SECRET_TYPE
import dev.agentknock.storage.secret.EnvironmentVariableReviewMetadata
import dev.agentknock.storage.secret.EnvironmentVariableReviewDestination
import dev.agentknock.storage.secret.RequestedSecretDescription
import dev.agentknock.storage.secret.SSH_SECRET_TYPE
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretApprovalPolicy
import dev.agentknock.storage.secret.SecretReviewMetadata
import dev.agentknock.storage.secret.SecretValues
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalReviewContextTest {
    @Test
    fun `builds minimal invocation review with exact secret facts`() {
        val request = approvalReviewRequest(
            client = client(),
            contents = InvocationRequestMessage(
                clientSoftware = software(),
                invocationToken = ByteArray(32),
                secrets = listOf("aws-read-only", "database", "git-signing"),
                reason = "Inspect production resources",
                operation = InvocationExecOperation(
                    command = "aws",
                    arguments = listOf("s3", "ls"),
                    workingDirectory = "/work/infrastructure",
                    executablePath = "/nix/store/aws/bin/aws",
                    executableHash = "not-useful-to-the-reviewer",
                    executableMode = InvocationExecutableMode.BINARY,
                    stdin = InvocationStreamKind.TERMINAL,
                    stdout = InvocationStreamKind.PIPE,
                    stderr = InvocationStreamKind.TERMINAL,
                ),
                launcherChain = listOf("/bin/bash", "/bin/codex"),
            ),
            description = requestedDescription(),
            values = linkedMapOf(
                "aws-read-only" to SecretValues.Environment(
                    description = "private vault description",
                    environment = linkedMapOf(
                        "AWS_ACCESS_KEY_ID" to "sensitive-access-key",
                        "AWS_REGION" to "eu-north-1",
                        "AWS_SECRET_ACCESS_KEY" to "sensitive-secret-key",
                        "AWS_SESSION_TOKEN" to "sensitive-session-token",
                    ),
                ),
                "git-signing" to SecretValues.Ssh(
                    description = "private SSH description",
                    publicKey = "ssh-ed25519 public-key",
                ),
                "database" to SecretValues.Environment(
                    description = "private database description",
                    environment = mapOf("PGPASSWORD" to "sensitive-password"),
                ),
            ),
            nonSensitiveEnvironmentValues = mapOf(
                "aws-read-only" to mapOf(
                    "AWS_PROFILE" to "production-read-only",
                    "AWS_REGION" to "eu-north-1",
                ),
            ),
            evaluation = ApprovalEvaluation(
                secrets = listOf(
                    secretEvaluation("aws-id", "aws-read-only", ApprovalAction.ASK_AI),
                    secretEvaluation("database-id", "database", ApprovalAction.ASK_AI),
                    secretEvaluation("ssh-id", "git-signing", ApprovalAction.APPROVE),
                ),
            ),
            policies = listOf(
                policy(
                    id = "aws-id",
                    name = "aws-read-only",
                    mode = SecretApprovalMode.ASK_AI,
                    instructions = "Allow inspection but not changes.",
                ),
                policy(
                    id = "database-id",
                    name = "database",
                    mode = SecretApprovalMode.ASK_AI,
                    instructions = "",
                ),
                policy(
                    id = "ssh-id",
                    name = "git-signing",
                    mode = SecretApprovalMode.APPROVE,
                    instructions = "This inactive instruction must not be sent.",
                ),
            ),
            deviceInstructions = "Protect production systems.",
        )

        assertEquals("Protect production systems.", request.instructions.general)
        assertEquals("This client is used for development.", request.instructions.client)
        assertEquals(
            mapOf(
                "aws-read-only" to "Allow inspection but not changes.",
                "database" to "",
            ),
            request.instructions.secrets,
        )
        assertEquals("survo", request.facts.client)
        assertEquals(ApprovalReviewOperation.INVOCATION, request.facts.operation)
        assertNull(request.facts.secret)
        val secrets = checkNotNull(request.facts.secrets)
        assertEquals(setOf("aws-read-only", "database", "git-signing"), secrets.keys)
        assertNull(request.parentFacts)

        val environment = secrets.getValue("aws-read-only")
            as ApprovalReviewEnvironmentSecretFacts
        assertNull(environment.variables.getValue("AWS_ACCESS_KEY_ID").value)
        assertNull(environment.variables.getValue("AWS_SECRET_ACCESS_KEY").value)
        assertEquals("eu-north-1", environment.variables.getValue("AWS_REGION").value)
        assertEquals(
            ApprovalReviewEnvironmentDelivery.ENVIRONMENT,
            environment.variables.getValue("AWS_REGION").delivery,
        )
        assertEquals("AWS_DEFAULT_REGION", environment.variables.getValue("AWS_REGION").target)
        assertEquals(
            ApprovalReviewEnvironmentDelivery.OMITTED,
            environment.variables.getValue("AWS_PROFILE").delivery,
        )
        assertEquals("production-read-only", environment.variables.getValue("AWS_PROFILE").value)
        assertEquals(
            ApprovalReviewEnvironmentDelivery.STANDARD_INPUT,
            environment.variables.getValue("AWS_SESSION_TOKEN").delivery,
        )
        assertNull(environment.variables.getValue("AWS_SESSION_TOKEN").value)
        assertEquals(ApprovalReviewSshSecretFacts, secrets.getValue("git-signing"))
        assertEquals(listOf("aws", "s3", "ls"), request.evidence.command?.argv)
        assertEquals(
            "/nix/store/aws/bin/aws",
            request.evidence.command?.resolvedExecutable,
        )
        assertNull(request.evidence.signedContent)
        assertNull(request.parentEvidence)

        val wire = WIRE_JSON.encodeToString(request)
        val payload = Json.parseToJsonElement(wire).jsonObject
        assertEquals(setOf("instructions", "facts", "evidence"), payload.keys)
        assertEquals(
            setOf("aws-read-only", "database"),
            payload.getValue("instructions").jsonObject
                .getValue("secrets").jsonObject.keys,
        )
        assertEquals(
            "",
            payload.getValue("instructions").jsonObject
                .getValue("secrets").jsonObject
                .getValue("database").jsonPrimitive.content,
        )
        assertEquals(
            setOf("client", "operation", "secrets"),
            payload.getValue("facts").jsonObject.keys,
        )
        assertEquals(
            setOf("reason", "command"),
            payload.getValue("evidence").jsonObject.keys,
        )
        assertEquals(
            setOf("type"),
            payload.getValue("facts").jsonObject.getValue("secrets").jsonObject
                .getValue("git-signing").jsonObject.keys,
        )
        val wireVariables = payload.getValue("facts").jsonObject
            .getValue("secrets").jsonObject
            .getValue("aws-read-only").jsonObject
            .getValue("variables").jsonObject
        assertEquals(
            setOf("delivery", "target"),
            wireVariables.getValue("AWS_ACCESS_KEY_ID").jsonObject.keys,
        )
        assertEquals(
            "environment",
            wireVariables.getValue("AWS_ACCESS_KEY_ID").jsonObject
                .getValue("delivery").jsonPrimitive.content,
        )
        assertEquals(
            setOf("delivery", "value"),
            wireVariables.getValue("AWS_PROFILE").jsonObject.keys,
        )
        assertEquals(
            "omitted",
            wireVariables.getValue("AWS_PROFILE").jsonObject
                .getValue("delivery").jsonPrimitive.content,
        )
        assertEquals(
            setOf("delivery", "target", "value"),
            wireVariables.getValue("AWS_REGION").jsonObject.keys,
        )
        assertEquals(
            setOf("delivery"),
            wireVariables.getValue("AWS_SESSION_TOKEN").jsonObject.keys,
        )
        assertEquals(
            "standard_input",
            wireVariables.getValue("AWS_SESSION_TOKEN").jsonObject
                .getValue("delivery").jsonPrimitive.content,
        )
        assertTrue(wire.contains("\"AWS_ACCESS_KEY_ID\":{"))
        assertFalse(wire.contains("\"value\":null"))
        assertFalse(wire.contains("sensitive-access-key"))
        assertFalse(wire.contains("sensitive-secret-key"))
        assertFalse(wire.contains("sensitive-password"))
        assertFalse(wire.contains("private vault description"))
        assertFalse(wire.contains("not-useful-to-the-reviewer"))
        assertFalse(wire.contains("inactive instruction"))
    }

    @Test
    fun `builds git signing review in the same trust model`() {
        val request = approvalReviewGitSignRequest(
            client = client(),
            contents = GitSignRequestMessage(
                clientSoftware = software(),
                invocationId = "invocation-id-not-for-the-reviewer",
                invocationToken = ByteArray(32) { 7 },
                secret = "git-signing",
                message = "tree abcdef\n\nSign this commit\n".encodeToByteArray(),
                repository = GitSignRepository(
                    remote = "github.com/example/project",
                    worktree = "/home/example/project",
                    head = GitSignHead.Branch("main", "origin/main"),
                    changedPathCount = 2,
                    changedPaths = listOf(
                        GitSignChangedPath(GitSignChangeStatus.MODIFIED, "src/main.rs"),
                        GitSignChangedPath(GitSignChangeStatus.ADDED, "src/main_test.rs"),
                    ),
                ),
            ),
            signedContent = "tree abcdef\n\nSign this commit\n",
            invocation = storedInvocation(),
            invocationSecrets = linkedMapOf(
                "aws-read-only" to ApprovalReviewEnvironmentSecretFacts(
                    variables = linkedMapOf(
                        "AWS_ACCESS_KEY_ID" to environmentFact(null),
                        "AWS_REGION" to environmentFact("eu-north-1"),
                        "AWS_SECRET_ACCESS_KEY" to environmentFact(null),
                    ),
                ),
                "git-signing" to ApprovalReviewSshSecretFacts,
            ),
            parentElapsedSeconds = 12,
            evaluation = ApprovalEvaluation(
                secrets = listOf(
                    secretEvaluation("ssh-id", "git-signing", ApprovalAction.ASK_AI),
                ),
            ),
            policies = listOf(
                policy(
                    id = "ssh-id",
                    name = "git-signing",
                    mode = SecretApprovalMode.ASK_AI,
                    instructions = "Sign commits created for this repository.",
                ),
            ),
            deviceInstructions = "Protect production systems.",
        )

        assertEquals(
            mapOf("git-signing" to "Sign commits created for this repository."),
            request.instructions.secrets,
        )
        assertEquals(ApprovalReviewOperation.GIT_SIGN, request.facts.operation)
        assertEquals("git-signing", request.facts.secret)
        assertNull(request.facts.secrets)
        assertEquals(
            setOf("aws-read-only", "git-signing"),
            request.parentFacts?.secrets?.keys,
        )
        assertEquals(ApprovalReviewOperation.INVOCATION, request.parentFacts?.operation)
        assertEquals(12L, request.parentFacts?.elapsedSeconds)
        assertEquals(
            listOf("git", "commit", "-S", "-m", "Sign this commit"),
            request.parentEvidence?.command?.argv,
        )
        assertEquals(
            "tree abcdef\n\nSign this commit\n",
            request.evidence.signedContent,
        )
        assertEquals(
            "github.com/example/project",
            request.evidence.repository?.remote,
        )
        assertEquals(
            "BRANCH",
            request.evidence.repository?.head?.type,
        )
        assertEquals(
            listOf("src/main.rs", "src/main_test.rs"),
            request.evidence.repository?.changedPaths?.map { it.path },
        )

        val wire = WIRE_JSON.encodeToString(request)
        val payload = Json.parseToJsonElement(wire).jsonObject
        assertEquals(
            setOf("instructions", "facts", "evidence", "parent_facts", "parent_evidence"),
            payload.keys,
        )
        assertEquals(
            setOf("client", "operation", "secret"),
            payload.getValue("facts").jsonObject.keys,
        )
        assertEquals(
            setOf("signed_content", "repository"),
            payload.getValue("evidence").jsonObject.keys,
        )
        assertEquals(
            setOf("operation", "elapsed_seconds", "secrets"),
            payload.getValue("parent_facts").jsonObject.keys,
        )
        assertEquals(
            setOf("reason", "command"),
            payload.getValue("parent_evidence").jsonObject.keys,
        )
        assertEquals(
            "git_sign",
            payload.getValue("facts").jsonObject
                .getValue("operation").jsonPrimitive.content,
        )
        assertEquals(
            "tree abcdef\n\nSign this commit\n",
            payload.getValue("evidence").jsonObject
                .getValue("signed_content").jsonPrimitive.content,
        )
        assertEquals(
            12L,
            payload.getValue("parent_facts").jsonObject
                .getValue("elapsed_seconds").jsonPrimitive.content.toLong(),
        )
        assertTrue("parent_evidence" in payload)
        assertFalse(wire.contains("elapsed_ms"))
        assertFalse(wire.contains("invocation-id-not-for-the-reviewer"))
        assertFalse(wire.contains("public-key"))
        assertFalse(wire.contains("fingerprint"))
        assertFalse(wire.contains("private SSH description"))
    }

    @Test
    fun `builds SSH authentication review with remote identity and parent context`() {
        val request = approvalReviewSshAuthenticationRequest(
            client = client(),
            secretName = "production-ssh",
            details = SshAuthenticationMessageDetails(
                username = "deploy",
                method = SshAuthenticationMethod.HOST_BOUND,
                algorithm = SshSignatureAlgorithm.ED25519,
                hostKeyAlgorithm = "ssh-ed25519",
                hostKeyFingerprint = "SHA256:server-fingerprint",
            ),
            invocation = storedInvocation(),
            invocationSecrets = linkedMapOf(
                "production-ssh" to ApprovalReviewSshSecretFacts,
            ),
            parentElapsedSeconds = 37,
            evaluation = ApprovalEvaluation(
                secrets = listOf(
                    secretEvaluation("ssh-id", "production-ssh", ApprovalAction.ASK_AI),
                ),
            ),
            policies = listOf(
                policy(
                    id = "ssh-id",
                    name = "production-ssh",
                    mode = SecretApprovalMode.ASK_AI,
                    instructions = "Authenticate only as deploy on production hosts.",
                ),
            ),
            deviceInstructions = "Protect production systems.",
        )

        assertEquals(ApprovalReviewOperation.SSH_AUTHENTICATE, request.facts.operation)
        assertEquals("production-ssh", request.facts.secret)
        assertEquals("deploy", request.evidence.sshAuthentication?.username)
        assertEquals("publickey-hostbound-v00@openssh.com", request.evidence.sshAuthentication?.method)
        assertEquals("SHA256:server-fingerprint", request.evidence.sshAuthentication?.hostKeyFingerprint)
        assertEquals(37L, request.parentFacts?.elapsedSeconds)
        assertEquals(
            listOf("git", "commit", "-S", "-m", "Sign this commit"),
            request.parentEvidence?.command?.argv,
        )

        val wire = WIRE_JSON.encodeToString(request)
        val payload = Json.parseToJsonElement(wire).jsonObject
        assertEquals(
            setOf("instructions", "facts", "evidence", "parent_facts", "parent_evidence"),
            payload.keys,
        )
        assertEquals(
            setOf("ssh_authentication"),
            payload.getValue("evidence").jsonObject.keys,
        )
        assertFalse(wire.contains("message"))
        assertFalse(wire.contains("invocation_token"))
        assertFalse(wire.contains("private SSH description"))
    }

    private fun requestedDescription() = RequestedSecretDescription(
        secrets = emptyList(),
        reviewMetadata = listOf(
            SecretReviewMetadata(
                id = "aws-id",
                name = "aws-read-only",
                type = ENVIRONMENT_SECRET_TYPE,
                environmentVariables = listOf(
                    variable("AWS_ACCESS_KEY_ID", sensitive = true),
                    variable(
                        "AWS_PROFILE", sensitive = false,
                        destination = EnvironmentVariableReviewDestination.Omitted,
                    ),
                    variable(
                        "AWS_REGION", sensitive = false,
                        destination = EnvironmentVariableReviewDestination.Environment("AWS_DEFAULT_REGION"),
                    ),
                    variable("AWS_SECRET_ACCESS_KEY", sensitive = true),
                    variable(
                        "AWS_SESSION_TOKEN", sensitive = true,
                        destination = EnvironmentVariableReviewDestination.StandardInput,
                    ),
                ),
            ),
            SecretReviewMetadata(
                id = "database-id",
                name = "database",
                type = ENVIRONMENT_SECRET_TYPE,
                environmentVariables = listOf(variable("PGPASSWORD", sensitive = true)),
            ),
            SecretReviewMetadata(
                id = "ssh-id",
                name = "git-signing",
                type = SSH_SECRET_TYPE,
                environmentVariables = emptyList(),
            ),
        ),
        missingSecrets = emptyList(),
        containsSensitiveMaterial = true,
    )

    private fun variable(
        name: String,
        sensitive: Boolean,
        destination: EnvironmentVariableReviewDestination =
            EnvironmentVariableReviewDestination.Environment(name),
    ) =
        EnvironmentVariableReviewMetadata(
            name = name,
            sensitive = sensitive,
            destination = destination,
        )

    private fun environmentFact(value: String?) = ApprovalReviewEnvironmentVariableFacts(
        delivery = ApprovalReviewEnvironmentDelivery.ENVIRONMENT,
        target = "unchanged",
        value = value,
    )

    private fun policy(
        id: String,
        name: String,
        mode: SecretApprovalMode,
        instructions: String,
    ) = SecretApprovalPolicy(
        secretId = id,
        secretName = name,
        mode = mode,
        instructions = instructions,
        revision = 1,
    )

    private fun secretEvaluation(
        id: String,
        name: String,
        action: ApprovalAction,
    ) = SecretApprovalEvaluation(
        secretId = id,
        secretName = name,
        action = action,
    )

    private fun client() = ClientEntity(
        clientId = "client-id",
        deviceIdentityId = "device-identity",
        name = "survo",
        instructions = "This client is used for development.",
        desiredRelayClientState = "active",
        relayClientState = "active",
        clientSoftwareJson = null,
        platform = "linux",
        architecture = "x86_64",
        hostname = "reported-hostname",
        machineId = "reported-machine-id",
        osVersion = "reported-os-version",
        pairedAt = 1,
        lastSeenAt = 2,
    )

    private fun storedInvocation() = SecretUseRequestEntity(
        requestId = "invocation-request",
        hostname = "reported-hostname",
        platform = "linux",
        architecture = "x86_64",
        machineId = "reported-machine-id",
        osVersion = "reported-os-version",
        invocationTokenHash = ByteArray(32),
        containsSensitiveMaterial = false,
        secretsJson = "[\"git-signing\"]",
        secretDetailsJson = "[]",
        providedSecretsJson = null,
        missingSecretsJson = "[]",
        reason = "Create an authenticated commit",
        command = "git",
        argumentsJson = "[\"commit\",\"-S\",\"-m\",\"Sign this commit\"]",
        workingDirectory = "/work/project",
        executablePath = "/nix/store/git/bin/git",
        executableHash = "not-for-the-reviewer",
        executableMode = "BINARY",
        stdinKind = "TERMINAL",
        stdoutKind = "TERMINAL",
        stderrKind = "TERMINAL",
        launcherChainJson = "[\"/bin/bash\",\"/bin/codex\"]",
        decision = "approved",
        decisionSource = "non_sensitive",
        approvalEvaluationJson = null,
        completionResult = null,
        completionReason = null,
        completionMessage = null,
        decidedAt = 1,
    )

    private fun software() = ClientSoftware(
        application = SoftwareInfo("agentknock", "0.1.0"),
        library = SoftwareInfo("agentknock", "0.1.0"),
    )

    private companion object {
        val WIRE_JSON = Json { explicitNulls = false }
    }
}
