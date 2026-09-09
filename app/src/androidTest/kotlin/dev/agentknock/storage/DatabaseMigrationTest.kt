package dev.agentknock.storage

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.storage.audit.auditJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    @After
    fun removeMigrationDatabase() {
        instrumentation.targetContext.deleteDatabase("migration-test.db")
    }

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = instrumentation,
            file = instrumentation.targetContext.getDatabasePath("migration-test.db"),
            driver = AndroidSQLiteDriver(),
            databaseClass = AgentknockDatabase::class,
        )

    @Test
    fun migrate1To2PreservesRequestsAndAddsFailureClassification() = runTest {
        helper.createDatabase(1).use { database ->
            database.execSQL(
                """
                INSERT INTO device_identities
                    (id, role, address, device_id, created_at, claim_attempted_at,
                     pairing_enabled, instructions)
                VALUES ('identity', 'active', 'address', 'device', 1, NULL, 1, '')
                """
                    .trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO inbox_requests
                    (id, parent_request_id, device_identity_id, client_id,
                     client_name_snapshot, client_software_json, kind, state, listed,
                     request_json, response_json, error, received_at, completed_at,
                     exchange_ended_at, response_outbox_finished)
                VALUES ('request', NULL, 'identity', 'client', 'Client', NULL,
                        'invocation', 'completed', 1, '{}', NULL, 'Relay unavailable',
                        2, 3, 3, 1)
                """
                    .trimIndent()
            )
        }

        helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).use { database ->
            database
                .prepare("SELECT error, failure_kind FROM inbox_requests WHERE id = 'request'")
                .use { statement ->
                    assertTrue(statement.step())
                    assertEquals("Relay unavailable", statement.getText(0))
                    assertTrue(statement.isNull(1))
                }
        }
    }

    @Test
    fun migrate2To3PreservesAuditDataAndMapsLegacyApprovalMode() = runTest {
        helper.createDatabase(2).use { database ->
            database.execSQL(
                """
                INSERT INTO secrets
                    (id, name, description, type, created_at, updated_at, revision,
                     approval_mode, instructions)
                VALUES ('secret', 'aws', '', 'environment', 1, 1, 0, 'temporary', '')
                """
                    .trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO vault_keys (id, purpose, active, created_at, backing)
                VALUES ('key', 'secret_values', 1, 1, 'android_keystore')
                """
                    .trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO environment_variables
                    (id, secret_id, name, sensitive, notes, value_updated_at,
                     encryption_format, encryption_key_id, nonce, ciphertext)
                VALUES ('variable', 'secret', 'AWS_REGION', 0, 'legacy note', 2,
                        1, 'key', X'0102', X'0304')
                """
                    .trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO audit_events
                    (id, occurred_at, event_type, subject, context, detail, outcome,
                     decision_source, expires_at, client_id, client_name, relay_request_id)
                VALUES (7, 1234, 'secret_use_decided', 'aws', 'production', 'Approved once',
                        'approved', 'user', 5678, 'client-id', 'Laptop', 'request-id')
                """
                    .trimIndent()
            )
        }

        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { database ->
            database.prepare("SELECT approval_mode FROM secrets WHERE id = 'secret'").use {
                assertTrue(it.step())
                assertEquals("ask_me", it.getText(0))
            }
            database
                .prepare(
                    "SELECT name, sensitive, value_updated_at, encryption_key_id, nonce, ciphertext " +
                        "FROM environment_variables WHERE id = 'variable'"
                )
                .use { statement ->
                    assertTrue(statement.step())
                    assertEquals("AWS_REGION", statement.getText(0))
                    assertEquals(0L, statement.getLong(1))
                    assertEquals(2L, statement.getLong(2))
                    assertEquals("key", statement.getText(3))
                    assertEquals(byteArrayOf(1, 2).toList(), statement.getBlob(4).toList())
                    assertEquals(byteArrayOf(3, 4).toList(), statement.getBlob(5).toList())
                }
            database
                .prepare(
                    "SELECT id, occurred_at, event_type, outcome, decision_source, client_id, " +
                        "relay_request_id, body_json FROM audit_events WHERE id = 7"
                )
                .use { statement ->
                    assertTrue(statement.step())
                    assertEquals(7L, statement.getLong(0))
                    assertEquals(1234L, statement.getLong(1))
                    assertEquals("secret_use_decided", statement.getText(2))
                    assertEquals("approved", statement.getText(3))
                    assertEquals("user", statement.getText(4))
                    assertEquals("client-id", statement.getText(5))
                    assertEquals("request-id", statement.getText(6))
                    val body =
                        auditJson.decodeFromString(JsonObject.serializer(), statement.getText(7))
                    assertEquals("aws", body.getValue("subject").jsonPrimitive.content)
                    assertEquals("production", body.getValue("context").jsonPrimitive.content)
                    assertEquals("Approved once", body.getValue("detail").jsonPrimitive.content)
                    assertEquals(5678L, body.getValue("expires_at").jsonPrimitive.content.toLong())
                    assertEquals("Laptop", body.getValue("client_name").jsonPrimitive.content)
                }
        }
    }
}
