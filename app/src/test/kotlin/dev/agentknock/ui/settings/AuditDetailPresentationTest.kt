package dev.agentknock.ui.settings

import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditEvent
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditDetailPresentationTest {
    @Test
    fun `invocation detail renders command and resolved secret delivery`() {
        val event = auditEvent(
            type = AuditEventType.SECRET_USE_DECIDED,
            data = buildJsonObject {
                put("command", "psql")
                put("arguments", buildJsonArray {
                    add(JsonPrimitive("-h"))
                    add(JsonPrimitive("production database"))
                })
                put("working_directory", "/srv/example")
                put(
                    "provided_secrets",
                    buildJsonObject {
                        put(
                            "postgres",
                            buildJsonObject {
                                put("type", "environment")
                                put(
                                    "variables",
                                    buildJsonObject {
                                        put(
                                            "PGPASSWORD",
                                            buildJsonObject {
                                                put("delivery", "environment")
                                                put("target", "PGPASSWORD")
                                            },
                                        )
                                        put(
                                            "PGHOST",
                                            buildJsonObject {
                                                put("delivery", "omitted")
                                                put("value", "db.example.com")
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        val fields = event.relevantDetailFields().associateBy(AuditDetailField::label)

        assertEquals("psql -h 'production database'", fields.getValue("Command").value)
        assertEquals("/srv/example", fields.getValue("Working directory").value)
        assertEquals(
            "postgres\n  PGPASSWORD → PGPASSWORD\n  PGHOST · not delivered = db.example.com",
            fields.getValue("Secret delivery").value,
        )
        assertTrue(fields.getValue("Secret delivery").monospace)
    }

    @Test
    fun `readable detail omits unknown data while technical json preserves it`() {
        val event = auditEvent(
            type = AuditEventType.REQUEST_REJECTED,
            data = buildJsonObject {
                put("rejection_code", "INVALID_REQUEST")
                put("future_field", buildJsonObject { put("nested", true) })
            },
        )

        assertFalse(event.relevantDetailFields().any { it.label == "Future field" })
        val json = Json.parseToJsonElement(event.technicalJson()).jsonObject
        assertEquals("request_rejected", json.getValue("event_type").jsonPrimitive.content)
        assertEquals("request-id", json.getValue("request_id").jsonPrimitive.content)
        assertTrue(
            json.getValue("data").jsonObject
                .getValue("future_field").jsonObject
                .getValue("nested").jsonPrimitive.content.toBoolean(),
        )
    }

    @Test
    fun `ai explanation already used as event detail is not repeated`() {
        val explanation = "This use matches the instructions."
        val event = auditEvent(
            type = AuditEventType.SECRET_USE_AI_REVIEWED,
            detail = explanation,
            data = buildJsonObject {
                put("ai_decision", "approve")
                put("ai_explanation", explanation)
            },
        )

        val fields = event.relevantDetailFields()

        assertEquals("Approve", fields.single { it.label == "AI decision" }.value)
        assertFalse(fields.any { it.label == "AI explanation" })
    }
}

private fun auditEvent(
    type: AuditEventType,
    detail: String? = null,
    data: kotlinx.serialization.json.JsonObject,
) = AuditEvent(
    id = 42,
    occurredAt = 1_788_528_000_000,
    type = type,
    outcome = when (type) {
        AuditEventType.REQUEST_REJECTED -> AuditOutcome.REJECTED
        AuditEventType.SECRET_USE_AI_REVIEWED -> AuditOutcome.APPROVED
        else -> AuditOutcome.APPROVED
    },
    decisionSource = AuditDecisionSource.AI_REVIEW,
    subject = "postgres",
    context = null,
    detail = detail,
    expiresAt = null,
    clientId = "client-id",
    clientName = "Workstation",
    relayRequestId = "request-id",
    data = data,
)
