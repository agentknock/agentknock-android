package dev.agentknock.protocol

import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitSignProtocolTest {
    private val protocol = GitSignProtocol()
    private val json = Json

    @Test
    fun `decodes the cli Git signing request shape`() {
        val request = protocol.decodeRequest(
            """
            {
              ${testClientSoftwareFields("0.2.0", "0.1.0")},
              "method":"GitSign",
              "invocation_id":"01K00000000000000000000000",
              "invocation_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
              "secret":"git-signing",
              "message":"dHJlZSAxMjM0Cg==",
              "repository":{
                "remote":"github.com/example/project",
                "worktree":"/home/example/project",
                "head":{"type":"BRANCH","name":"main","upstream":"origin/main"},
                "changed_path_count":2,
                "changed_paths":[
                  {"status":"MODIFIED","path":"src/main.rs"},
                  {"status":"ADDED","path":"tests/example.rs"}
                ]
              }
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertEquals(testClientSoftware("0.2.0", "0.1.0"), request.clientSoftware)
        assertEquals("01K00000000000000000000000", request.invocationId)
        assertArrayEquals(ByteArray(32), request.invocationToken)
        assertEquals("git-signing", request.secret)
        assertArrayEquals("tree 1234\n".encodeToByteArray(), request.message)
        assertEquals(
            GitSignRepository(
                remote = "github.com/example/project",
                worktree = "/home/example/project",
                head = GitSignHead.Branch(name = "main", upstream = "origin/main"),
                changedPathCount = 2,
                changedPaths = listOf(
                    GitSignChangedPath(GitSignChangeStatus.MODIFIED, "src/main.rs"),
                    GitSignChangedPath(GitSignChangeStatus.ADDED, "tests/example.rs"),
                ),
            ),
            request.repository,
        )
    }

    @Test
    fun `allows absent repository context and detached head`() {
        val withoutRepository = protocol.decodeRequest(
            """{${testClientSoftwareFields("0.2.0", "0.1.0")},"method":"GitSign","invocation_id":"01K00000000000000000000000","invocation_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","secret":"key","message":""}"""
                .encodeToByteArray(),
        )
        assertEquals(null, withoutRepository.repository)

        val detached = protocol.decodeRequest(
            """{${testClientSoftwareFields("0.2.0", "0.1.0")},"method":"GitSign","invocation_id":"01K00000000000000000000000","invocation_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","secret":"key","message":"","repository":{"head":{"type":"DETACHED"}}}"""
                .encodeToByteArray(),
        )
        assertEquals(GitSignHead.Detached, detached.repository?.head)
    }

    @Test
    fun `requires an exact 32 byte invocation token`() {
        val invalid = Base64.getEncoder().encodeToString(ByteArray(31))
        assertThrows(IllegalArgumentException::class.java) {
            protocol.decodeRequest(
                """{${testClientSoftwareFields("0.2.0", "0.1.0")},"method":"GitSign","invocation_id":"01K00000000000000000000000","invocation_token":"$invalid","secret":"key","message":""}"""
                    .encodeToByteArray(),
            )
        }
    }

    @Test
    fun `encodes responses and decodes cli completion variants`() {
        assertEquals(
            json.parseToJsonElement(
                """{"result":"APPROVED","signature":"-----BEGIN SSH SIGNATURE-----\nexample\n-----END SSH SIGNATURE-----\n"}""",
            ),
            json.parseToJsonElement(
                protocol.approvedResponse(
                    "-----BEGIN SSH SIGNATURE-----\nexample\n-----END SSH SIGNATURE-----\n",
                ).decodeToString(),
            ),
        )
        assertEquals(
            json.parseToJsonElement(
                """{"result":"DENIED","reason":"USER_DENIED","message":"Denied on device."}""",
            ),
            json.parseToJsonElement(
                protocol.deniedResponse(
                    InvocationDenialReason.USER_DENIED,
                    "Denied on device.",
                ).decodeToString(),
            ),
        )
        assertEquals(
            GitSignCompletion.Approved(testClientSoftware("0.2.0", "0.1.0")),
            protocol.decodeCompletion(
                """{${testClientSoftwareFields("0.2.0", "0.1.0")},"result":"APPROVED"}"""
                    .encodeToByteArray(),
            ),
        )
        assertEquals(
            GitSignCompletion.Denied(
                testClientSoftware("0.2.0", "0.1.0"),
                "USER_DENIED",
                "Denied on device.",
            ),
            protocol.decodeCompletion(
                """{${testClientSoftwareFields("0.2.0", "0.1.0")},"result":"DENIED","reason":"USER_DENIED","message":"Denied on device."}"""
                    .encodeToByteArray(),
            ),
        )
        assertEquals(
            GitSignCompletion.Aborted(
                testClientSoftware("0.2.0", "0.1.0"),
                "CANCELLED",
                "Command ended.",
            ),
            protocol.decodeCompletion(
                """{${testClientSoftwareFields("0.2.0", "0.1.0")},"result":"ABORTED","reason":"CANCELLED","message":"Command ended."}"""
                    .encodeToByteArray(),
            ),
        )
    }
}
