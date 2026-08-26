package dev.agentknock.storage

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.execSQL
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentknockMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val databaseName = "agentknock-migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = instrumentation.targetContext.getDatabasePath(databaseName),
        driver = AndroidSQLiteDriver(),
        databaseClass = AgentknockDatabase::class,
    )

    @Before
    fun removePreviousDatabase() {
        instrumentation.targetContext.deleteDatabase(databaseName)
    }

    @Test
    fun migration6To7PreservesSecretsAndPendingUploads() = runTest {
        helper.createDatabase(6).use { database ->
            database.execSQL(
                "INSERT INTO vault_keys " +
                    "(id, purpose, active, created_at, backing) " +
                    "VALUES ('secret-key', 'secret_values', 1, 1, 'ANDROID_KEYSTORE')",
            )
            database.execSQL(
                "INSERT INTO secrets " +
                    "(id, name, description, type, created_at, updated_at) " +
                    "VALUES ('secret-1', 'aws-read-only', 'Logs', 'environment', 2, 3)",
            )
            database.execSQL(
                "INSERT INTO environment_variables " +
                    "(id, secret_id, name, sensitive, notes, encryption_format, " +
                    "encryption_key_id, nonce, ciphertext, created_at, updated_at, " +
                    "value_updated_at) VALUES " +
                    "('variable-1', 'secret-1', 'AWS_REGION', 0, '', 1, 'secret-key', " +
                    "X'0102', X'0304', 4, 5, 6)",
            )
            database.execSQL(
                "INSERT INTO inbox_requests " +
                    "(id, relay_request_id, parent_request_id, kind, state, listed, request_json, " +
                    "received_at, updated_at) VALUES " +
                    "(10, 'request-1', NULL, 'secret_upload', 'action_required', 1, '{}', 7, 8)",
            )
            database.execSQL(
                "INSERT INTO secret_upload_requests " +
                    "(request_id, pairing_request_id, client_id, client_name, state, cli_version, " +
                    "mode, uploaded_name, approved_name, description_provided, description, " +
                    "secret_type, variable_names_json, added_variables_json, " +
                    "changed_variables_json, unchanged_variables_json, removed_variables_json, " +
                    "error, transport_result, transport_message, created_at, updated_at, decided_at, " +
                    "transport_completed_at) VALUES " +
                    "(10, NULL, 'client-1', 'Laptop', 'review_pending', '0.1.0', 'CREATE', " +
                    "'incoming', NULL, 1, 'Incoming values', 'environment', " +
                    "'[\"TOKEN\"]', '[\"TOKEN\"]', '[]', '[]', '[]', NULL, 'RECEIVED', " +
                    "NULL, 9, 10, NULL, NULL)",
            )
            database.execSQL(
                "INSERT INTO secret_upload_variables " +
                    "(id, request_id, name, sensitive, encryption_format, encryption_key_id, " +
                    "nonce, ciphertext, created_at) VALUES " +
                    "('upload-variable-1', 10, 'TOKEN', 1, 1, 'secret-key', " +
                    "X'0506', X'0708', 11)",
            )
        }

        helper.runMigrationsAndValidate(7, listOf(MIGRATION_6_7)).use { database ->
            database.prepare(
                "SELECT name, description, type FROM secrets WHERE id = 'secret-1'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("aws-read-only", statement.getText(0))
                assertEquals("Logs", statement.getText(1))
                assertEquals("environment", statement.getText(2))
            }
            database.prepare(
                "SELECT name, sensitive, nonce, ciphertext FROM environment_variables " +
                    "WHERE id = 'variable-1'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("AWS_REGION", statement.getText(0))
                assertEquals(0, statement.getLong(1))
                assertArrayEquals(byteArrayOf(1, 2), statement.getBlob(2))
                assertArrayEquals(byteArrayOf(3, 4), statement.getBlob(3))
            }
            database.prepare(
                "SELECT summary_json FROM secret_upload_requests WHERE request_id = 10",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals(
                    "{\"type\":\"environment\",\"variableNames\":[\"TOKEN\"]," +
                        "\"addedVariables\":[\"TOKEN\"],\"changedVariables\":[]," +
                        "\"unchangedVariables\":[],\"removedVariables\":[]}",
                    statement.getText(0),
                )
            }
            database.prepare(
                "SELECT name, sensitive, nonce, ciphertext " +
                    "FROM secret_upload_environment_variables WHERE id = 'upload-variable-1'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("TOKEN", statement.getText(0))
                assertEquals(1, statement.getLong(1))
                assertArrayEquals(byteArrayOf(5, 6), statement.getBlob(2))
                assertArrayEquals(byteArrayOf(7, 8), statement.getBlob(3))
            }
        }
    }

    @Test
    fun migration7To8PreservesSecretUseAndAddsCorrelatedSigningRequests() = runTest {
        helper.createDatabase(7).use { database ->
            database.execSQL(
                "INSERT INTO inbox_requests " +
                    "(id, relay_request_id, parent_request_id, kind, state, listed, request_json, " +
                    "received_at, updated_at) VALUES " +
                    "(20, 'invocation-1', NULL, 'secret_use', 'completed', 1, '{}', 1, 2)",
            )
            database.execSQL(
                "INSERT INTO secret_use_requests " +
                    "(request_id, pairing_request_id, client_id, client_name, pairing_address, " +
                    "state, cli_version, secrets_json, secret_details_json, " +
                    "missing_secrets_json, command, arguments_json, working_directory, " +
                    "executable_path, executable_mode, stdin_kind, stdout_kind, stderr_kind, " +
                    "launcher_chain_json, created_at, updated_at) VALUES " +
                    "(20, NULL, 'client-1', 'Laptop', 'three-word-address', 'completed', " +
                    "'{\"app_info\":{\"name\":\"agentknock\",\"version\":\"1\"}," +
                    "\"lib_info\":{\"name\":\"agentknock\",\"version\":\"1\"}}', " +
                    "'[\"git-signing\"]', '[]', '[]', 'git', '[]', '/work', '/bin/git', " +
                    "'BINARY', 'TERMINAL', 'TERMINAL', 'TERMINAL', '[]', 1, 2)",
            )
        }

        helper.runMigrationsAndValidate(8, listOf(MIGRATION_7_8)).use { database ->
            database.prepare(
                "SELECT invocation_token_hash, contains_sensitive_material " +
                    "FROM secret_use_requests WHERE request_id = 20",
            ).use { statement ->
                assertTrue(statement.step())
                assertTrue(statement.isNull(0))
                assertEquals(1, statement.getLong(1))
            }
            database.execSQL(
                "INSERT INTO inbox_requests " +
                    "(id, relay_request_id, parent_request_id, kind, state, listed, request_json, " +
                    "received_at, updated_at) VALUES " +
                    "(21, 'signature-1', 20, 'git_sign', 'action_required', 1, '{}', 3, 3)",
            )
            database.execSQL(
                "INSERT INTO git_sign_requests " +
                    "(request_id, state, secret_name, message, created_at, updated_at) " +
                    "VALUES (21, 'approval_pending', 'git-signing', X'0102', 3, 3)",
            )
            database.prepare(
                "SELECT secret_name, message FROM git_sign_requests " +
                    "WHERE request_id = 21",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("git-signing", statement.getText(0))
                assertArrayEquals(byteArrayOf(1, 2), statement.getBlob(1))
            }
        }
    }
}
