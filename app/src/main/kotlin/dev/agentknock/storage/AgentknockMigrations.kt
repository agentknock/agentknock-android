package dev.agentknock.storage

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

internal val MIGRATION_6_7 = Migration(6, 7) { connection ->
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `secret_upload_requests_new` (
            `request_id` INTEGER NOT NULL,
            `pairing_request_id` INTEGER,
            `client_id` TEXT NOT NULL,
            `client_name` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `cli_version` TEXT NOT NULL,
            `mode` TEXT NOT NULL,
            `uploaded_name` TEXT NOT NULL,
            `approved_name` TEXT,
            `description_provided` INTEGER NOT NULL,
            `description` TEXT,
            `secret_type` TEXT NOT NULL,
            `summary_json` TEXT NOT NULL,
            `error` TEXT,
            `transport_result` TEXT NOT NULL,
            `transport_message` TEXT,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            `decided_at` INTEGER,
            `transport_completed_at` INTEGER,
            PRIMARY KEY(`request_id`),
            FOREIGN KEY(`request_id`) REFERENCES `inbox_requests`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`pairing_request_id`) REFERENCES `pairings`(`request_id`) ON UPDATE NO ACTION ON DELETE SET NULL
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO `secret_upload_requests_new` (
            `request_id`, `pairing_request_id`, `client_id`, `client_name`, `state`,
            `cli_version`, `mode`, `uploaded_name`, `approved_name`,
            `description_provided`, `description`, `secret_type`, `summary_json`,
            `error`, `transport_result`, `transport_message`, `created_at`, `updated_at`,
            `decided_at`, `transport_completed_at`
        )
        SELECT
            `request_id`, `pairing_request_id`, `client_id`, `client_name`, `state`,
            `cli_version`, `mode`, `uploaded_name`, `approved_name`,
            `description_provided`, `description`, `secret_type`,
            '{"type":"environment","variableNames":' || `variable_names_json` ||
            ',"addedVariables":' || `added_variables_json` ||
            ',"changedVariables":' || `changed_variables_json` ||
            ',"unchangedVariables":' || `unchanged_variables_json` ||
            ',"removedVariables":' || `removed_variables_json` || '}',
            `error`, `transport_result`, `transport_message`, `created_at`, `updated_at`,
            `decided_at`, `transport_completed_at`
        FROM `secret_upload_requests`
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `secret_upload_environment_variables_copy` (
            `id` TEXT NOT NULL,
            `request_id` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `sensitive` INTEGER NOT NULL,
            `encryption_format` INTEGER NOT NULL,
            `encryption_key_id` TEXT NOT NULL,
            `nonce` BLOB NOT NULL,
            `ciphertext` BLOB NOT NULL,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO `secret_upload_environment_variables_copy`
        SELECT `id`, `request_id`, `name`, `sensitive`, `encryption_format`,
               `encryption_key_id`, `nonce`, `ciphertext`, `created_at`
        FROM `secret_upload_variables`
        """.trimIndent(),
    )
    connection.execSQL("DROP TABLE `secret_upload_variables`")
    connection.execSQL("DROP TABLE `secret_upload_requests`")
    connection.execSQL(
        "ALTER TABLE `secret_upload_requests_new` RENAME TO `secret_upload_requests`",
    )
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `secret_upload_environment_variables` (
            `id` TEXT NOT NULL,
            `request_id` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `sensitive` INTEGER NOT NULL,
            `encryption_format` INTEGER NOT NULL,
            `encryption_key_id` TEXT NOT NULL,
            `nonce` BLOB NOT NULL,
            `ciphertext` BLOB NOT NULL,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`request_id`) REFERENCES `secret_upload_requests`(`request_id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`encryption_key_id`) REFERENCES `vault_keys`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "INSERT INTO `secret_upload_environment_variables` " +
            "SELECT * FROM `secret_upload_environment_variables_copy`",
    )
    connection.execSQL("DROP TABLE `secret_upload_environment_variables_copy`")
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_secret_upload_requests_pairing_request_id` " +
            "ON `secret_upload_requests` (`pairing_request_id`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_secret_upload_requests_state` " +
            "ON `secret_upload_requests` (`state`)",
    )
    connection.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "`index_secret_upload_environment_variables_request_id_name` " +
            "ON `secret_upload_environment_variables` (`request_id`, `name`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS " +
            "`index_secret_upload_environment_variables_encryption_key_id` " +
            "ON `secret_upload_environment_variables` (`encryption_key_id`)",
    )
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `ssh_keys` (
            `secret_id` TEXT NOT NULL,
            `algorithm` TEXT NOT NULL,
            `public_key` BLOB NOT NULL,
            `comment` TEXT NOT NULL,
            `private_key_format` TEXT NOT NULL,
            `encryption_format` INTEGER NOT NULL,
            `encryption_key_id` TEXT NOT NULL,
            `nonce` BLOB NOT NULL,
            `ciphertext` BLOB NOT NULL,
            `material_updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`secret_id`),
            FOREIGN KEY(`secret_id`) REFERENCES `secrets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`encryption_key_id`) REFERENCES `vault_keys`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_ssh_keys_encryption_key_id` " +
            "ON `ssh_keys` (`encryption_key_id`)",
    )
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `secret_upload_ssh_keys` (
            `request_id` INTEGER NOT NULL,
            `algorithm` TEXT NOT NULL,
            `public_key` BLOB NOT NULL,
            `comment` TEXT NOT NULL,
            `private_key_format` TEXT NOT NULL,
            `encryption_format` INTEGER NOT NULL,
            `encryption_key_id` TEXT NOT NULL,
            `nonce` BLOB NOT NULL,
            `ciphertext` BLOB NOT NULL,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`request_id`),
            FOREIGN KEY(`request_id`) REFERENCES `secret_upload_requests`(`request_id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`encryption_key_id`) REFERENCES `vault_keys`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_secret_upload_ssh_keys_encryption_key_id` " +
            "ON `secret_upload_ssh_keys` (`encryption_key_id`)",
    )
}

internal val MIGRATION_7_8 = Migration(7, 8) { connection ->
    connection.execSQL(
        "ALTER TABLE `secret_use_requests` ADD COLUMN `invocation_token_hash` BLOB",
    )
    connection.execSQL(
        "ALTER TABLE `secret_use_requests` ADD COLUMN " +
            "`contains_sensitive_material` INTEGER NOT NULL DEFAULT 1",
    )
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `git_sign_requests` (
            `request_id` INTEGER NOT NULL,
            `state` TEXT NOT NULL,
            `secret_name` TEXT NOT NULL,
            `message` BLOB NOT NULL,
            `decision` TEXT,
            `completion_result` TEXT,
            `completion_reason` TEXT,
            `completion_message` TEXT,
            `error` TEXT,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            `decided_at` INTEGER,
            `completed_at` INTEGER,
            PRIMARY KEY(`request_id`),
            FOREIGN KEY(`request_id`) REFERENCES `inbox_requests`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_git_sign_requests_state` " +
            "ON `git_sign_requests` (`state`)",
    )
}

internal val MIGRATION_8_9 = Migration(8, 9) { connection ->
    connection.execSQL(
        "ALTER TABLE `secrets` ADD COLUMN " +
            "`approval_mode` TEXT NOT NULL DEFAULT 'ask_me'",
    )
    connection.execSQL(
        "ALTER TABLE `secrets` ADD COLUMN `instructions` TEXT NOT NULL DEFAULT ''",
    )
    connection.execSQL(
        "ALTER TABLE `pairings` ADD COLUMN `instructions` TEXT NOT NULL DEFAULT ''",
    )
    connection.execSQL(
        "ALTER TABLE `device_identities` ADD COLUMN `instructions` TEXT NOT NULL DEFAULT ''",
    )
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `secret_client_approval_overrides` (
            `secret_id` TEXT NOT NULL,
            `client_id` TEXT NOT NULL,
            `approval_mode` TEXT NOT NULL,
            PRIMARY KEY(`secret_id`, `client_id`),
            FOREIGN KEY(`secret_id`) REFERENCES `secrets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`client_id`) REFERENCES `pairings`(`client_id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_secret_client_approval_overrides_client_id` " +
            "ON `secret_client_approval_overrides` (`client_id`)",
    )
}

internal val MIGRATION_9_10 = Migration(9, 10) { connection ->
    connection.execSQL(
        "ALTER TABLE `secret_use_requests` ADD COLUMN `provided_secrets_json` TEXT",
    )
    connection.execSQL(
        "ALTER TABLE `git_sign_requests` ADD COLUMN `repository_json` TEXT",
    )
}

internal val MIGRATION_10_11 = Migration(10, 11) { connection ->
    connection.execSQL(
        "ALTER TABLE `git_sign_requests` ADD COLUMN `rule_evaluation_json` TEXT",
    )
}

internal val MIGRATION_11_12 = Migration(11, 12) { connection ->
    connection.execSQL(
        "ALTER TABLE `secrets` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1",
    )
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `temporary_access_grants` (
            `secret_id` TEXT NOT NULL,
            `client_id` TEXT NOT NULL,
            `operation` TEXT NOT NULL,
            `expires_at` INTEGER NOT NULL,
            PRIMARY KEY(`secret_id`, `client_id`, `operation`),
            FOREIGN KEY(`secret_id`) REFERENCES `secrets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`client_id`) REFERENCES `pairings`(`client_id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_temporary_access_grants_client_id` " +
            "ON `temporary_access_grants` (`client_id`)",
    )
}
