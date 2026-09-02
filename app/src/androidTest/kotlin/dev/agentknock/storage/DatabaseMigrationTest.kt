package dev.agentknock.storage

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
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
                """.trimIndent(),
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
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).use { database ->
            database.prepare(
                "SELECT error, failure_kind FROM inbox_requests WHERE id = 'request'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("Relay unavailable", statement.getText(0))
                assertTrue(statement.isNull(1))
            }
        }
    }
}
