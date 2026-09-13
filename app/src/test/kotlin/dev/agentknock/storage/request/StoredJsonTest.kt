package dev.agentknock.storage.request

import dev.agentknock.protocol.ClientSoftware
import dev.agentknock.protocol.GitSignRepository
import dev.agentknock.review.ApprovalReviewEnvironmentDelivery
import dev.agentknock.review.ApprovalReviewEnvironmentSecretFacts
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.secret.SecretMetadata
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredJsonTest {
    @Test
    fun `stored request snapshots ignore obsolete fields and apply new defaults`() {
        val secrets =
            storedJson.decodeFromString<List<SecretMetadata>>(
                """[{"name":"Deployment","description":"","type":"environment","future":true}]"""
            )
        val repository =
            storedJson.decodeFromString<GitSignRepository>(
                """{"remote":"git@example.test:repo.git","head":{"type":"BRANCH","name":"main","upstream":"origin/main"},"changed_paths":[{"status":"MODIFIED","path":"README.md"}],"future":{"nested":true}}"""
            )
        val evaluation =
            storedJson.decodeFromString<ApprovalEvaluation>("""{"secrets":[],"future":"ignored"}""")
        val clientSoftware =
            storedJson.decodeFromString<ClientSoftware>(
                """{"app_info":{"name":"agentknock","version":"0.3.0","future":1},"lib_info":{"name":"agentknock","version":"0.3.0"},"future":true}"""
            )
        val upload =
            storedJson.decodeFromString<SecretUploadSummarySnapshot>(
                """{"variableNames":["TOKEN"],"future":true}"""
            )

        assertEquals("Deployment", secrets.single().name)
        assertTrue(secrets.single().environmentVariableNames.isEmpty())
        assertEquals("git@example.test:repo.git", repository.remote)
        // History written with the former sealed head type must remain readable.
        assertEquals("BRANCH", repository.head?.type)
        assertEquals("main", repository.head?.name)
        assertEquals("origin/main", repository.head?.upstream)
        assertEquals("MODIFIED", repository.changedPaths?.single()?.status)
        assertTrue(evaluation.secrets.isEmpty())
        assertEquals("agentknock", clientSoftware.application?.name)
        assertEquals(listOf("TOKEN"), upload.variableNames)
        assertTrue(upload.changedVariables.isEmpty())
    }

    @Test
    fun `stored approval facts ignore obsolete fields`() {
        val decoded =
            decodeStoredApprovalReviewSecretFacts(
                """{"Deployment":{"type":"environment","environment_variables":{"TOKEN":{"destination":{"type":"environment","name":"API_TOKEN"},"value":null}},"future":true}}"""
            )

        assertNotNull(decoded)
        val variable =
            (decoded?.get("Deployment") as ApprovalReviewEnvironmentSecretFacts)
                .variables
                .getValue("TOKEN")
        assertEquals(ApprovalReviewEnvironmentDelivery.ENVIRONMENT, variable.delivery)
        assertEquals("API_TOKEN", variable.target)
        assertEquals(null, variable.value)
    }
}
