package dev.agentknock.storage.secret

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.request.PairingEntity
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "secrets",
    indices = [Index(value = ["name"], unique = true)],
)
internal data class SecretEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "description")
    val description: String,
    @ColumnInfo(name = "type")
    val type: String = "environment",
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "approval_mode")
    val approvalMode: String = "ask_me",
    @ColumnInfo(name = "instructions")
    val instructions: String = "",
)

@Entity(
    tableName = "secret_client_approval_overrides",
    primaryKeys = ["secret_id", "client_id"],
    foreignKeys = [
        ForeignKey(
            entity = SecretEntity::class,
            parentColumns = ["id"],
            childColumns = ["secret_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = PairingEntity::class,
            parentColumns = ["client_id"],
            childColumns = ["client_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["client_id"])],
)
internal data class SecretClientApprovalOverrideEntity(
    @ColumnInfo(name = "secret_id")
    val secretId: String,
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "approval_mode")
    val approvalMode: String,
)

@Entity(
    tableName = "environment_variables",
    foreignKeys = [
        ForeignKey(
            entity = SecretEntity::class,
            parentColumns = ["id"],
            childColumns = ["secret_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = VaultKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["encryption_key_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["secret_id", "name"], unique = true),
        Index(value = ["encryption_key_id"]),
    ],
)
internal data class EnvironmentVariableEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "secret_id")
    val secretId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "sensitive")
    val sensitive: Boolean,
    @ColumnInfo(name = "notes")
    val notes: String,
    @ColumnInfo(name = "encryption_format")
    val encryptionFormat: Int,
    @ColumnInfo(name = "encryption_key_id")
    val encryptionKeyId: String,
    @ColumnInfo(name = "nonce")
    val nonce: ByteArray,
    @ColumnInfo(name = "ciphertext")
    val ciphertext: ByteArray,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "value_updated_at")
    val valueUpdatedAt: Long,
)

@Entity(
    tableName = "ssh_keys",
    foreignKeys = [
        ForeignKey(
            entity = SecretEntity::class,
            parentColumns = ["id"],
            childColumns = ["secret_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = VaultKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["encryption_key_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["encryption_key_id"])],
)
internal data class SshKeyEntity(
    @PrimaryKey
    @ColumnInfo(name = "secret_id")
    val secretId: String,
    @ColumnInfo(name = "algorithm")
    val algorithm: String,
    @ColumnInfo(name = "public_key")
    val publicKey: ByteArray,
    @ColumnInfo(name = "comment")
    val comment: String,
    @ColumnInfo(name = "private_key_format")
    val privateKeyFormat: String,
    @ColumnInfo(name = "encryption_format")
    val encryptionFormat: Int,
    @ColumnInfo(name = "encryption_key_id")
    val encryptionKeyId: String,
    @ColumnInfo(name = "nonce")
    val nonce: ByteArray,
    @ColumnInfo(name = "ciphertext")
    val ciphertext: ByteArray,
    @ColumnInfo(name = "material_updated_at")
    val materialUpdatedAt: Long,
)

internal data class SecretSummaryRow(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "description")
    val description: String,
    @ColumnInfo(name = "type")
    val type: String = "environment",
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "environment_variable_count")
    val environmentVariableCount: Int,
    @ColumnInfo(name = "ssh_algorithm")
    val sshAlgorithm: String? = null,
    @ColumnInfo(name = "ssh_public_key")
    val sshPublicKey: ByteArray? = null,
    @ColumnInfo(name = "ssh_comment")
    val sshComment: String? = null,
    @ColumnInfo(name = "ssh_encryption_key_id")
    val sshEncryptionKeyId: String? = null,
    @ColumnInfo(name = "ssh_material_updated_at")
    val sshMaterialUpdatedAt: Long? = null,
)

internal data class EnvironmentVariableMetadataRow(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "secret_id")
    val secretId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "sensitive")
    val sensitive: Boolean,
    @ColumnInfo(name = "notes")
    val notes: String,
    @ColumnInfo(name = "encryption_key_id")
    val encryptionKeyId: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "value_updated_at")
    val valueUpdatedAt: Long,
)

internal data class SshKeyMetadataRow(
    @ColumnInfo(name = "secret_id")
    val secretId: String,
    @ColumnInfo(name = "algorithm")
    val algorithm: String,
    @ColumnInfo(name = "public_key")
    val publicKey: ByteArray,
    @ColumnInfo(name = "comment")
    val comment: String,
    @ColumnInfo(name = "encryption_key_id")
    val encryptionKeyId: String,
    @ColumnInfo(name = "material_updated_at")
    val materialUpdatedAt: Long,
)

@Dao
internal interface SecretDao {
    @Query(
        """
        SELECT secrets.id,
               secrets.name,
               secrets.description,
               secrets.type,
               secrets.created_at,
               secrets.updated_at,
               count(environment_variables.id) AS environment_variable_count,
               ssh_keys.algorithm AS ssh_algorithm,
               ssh_keys.public_key AS ssh_public_key,
               ssh_keys.comment AS ssh_comment,
               ssh_keys.encryption_key_id AS ssh_encryption_key_id,
               ssh_keys.material_updated_at AS ssh_material_updated_at
        FROM secrets
        LEFT JOIN environment_variables ON environment_variables.secret_id = secrets.id
        LEFT JOIN ssh_keys ON ssh_keys.secret_id = secrets.id
        GROUP BY secrets.id
        ORDER BY secrets.name COLLATE NOCASE, secrets.id
        """,
    )
    fun observeSecrets(): Flow<List<SecretSummaryRow>>

    @Query("SELECT * FROM secrets WHERE id = :id")
    fun observeSecret(id: String): Flow<SecretEntity?>

    @Query(
        "SELECT * FROM secret_client_approval_overrides " +
            "WHERE secret_id = :secretId ORDER BY client_id",
    )
    fun observeClientApprovalOverrides(
        secretId: String,
    ): Flow<List<SecretClientApprovalOverrideEntity>>

    @Query(
        """
        SELECT id,
               secret_id,
               name,
               sensitive,
               notes,
               encryption_key_id,
               created_at,
               updated_at,
               value_updated_at
        FROM environment_variables
        WHERE secret_id = :secretId
        ORDER BY name COLLATE NOCASE, id
        """,
    )
    fun observeEnvironmentVariables(
        secretId: String,
    ): Flow<List<EnvironmentVariableMetadataRow>>

    @Query(
        """
        SELECT secret_id,
               algorithm,
               public_key,
               comment,
               encryption_key_id,
               material_updated_at
        FROM ssh_keys
        WHERE secret_id = :secretId
        """,
    )
    fun observeSshKey(secretId: String): Flow<SshKeyMetadataRow?>

    @Query("SELECT * FROM secrets WHERE id = :id")
    suspend fun getSecret(id: String): SecretEntity?

    @Query("SELECT * FROM environment_variables WHERE id = :id")
    suspend fun getEnvironmentVariable(id: String): EnvironmentVariableEntity?

    @Query("SELECT * FROM ssh_keys WHERE secret_id = :secretId")
    suspend fun getSshKey(secretId: String): SshKeyEntity?

    @Query("SELECT * FROM secrets ORDER BY name COLLATE NOCASE, id")
    suspend fun getSecrets(): List<SecretEntity>

    @Query("SELECT * FROM environment_variables ORDER BY secret_id, name COLLATE NOCASE, id")
    suspend fun getEnvironmentVariables(): List<EnvironmentVariableEntity>

    @Query("SELECT * FROM secrets WHERE name IN (:names)")
    suspend fun getSecretsByName(names: List<String>): List<SecretEntity>

    @Query(
        "SELECT * FROM secret_client_approval_overrides " +
            "WHERE client_id = :clientId AND secret_id IN (:secretIds)",
    )
    suspend fun getClientApprovalOverrides(
        clientId: String,
        secretIds: List<String>,
    ): List<SecretClientApprovalOverrideEntity>

    @Query("SELECT * FROM environment_variables WHERE secret_id IN (:secretIds)")
    suspend fun getEnvironmentVariablesForSecrets(
        secretIds: List<String>,
    ): List<EnvironmentVariableEntity>

    @Query("SELECT * FROM ssh_keys WHERE secret_id IN (:secretIds)")
    suspend fun getSshKeysForSecrets(secretIds: List<String>): List<SshKeyEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM secrets WHERE name = :name AND id != :excludingId)")
    suspend fun secretNameInUse(name: String, excludingId: String): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM environment_variables
            WHERE secret_id = :secretId AND name = :name AND id != :excludingId
        )
        """,
    )
    suspend fun environmentVariableNameInUse(
        secretId: String,
        name: String,
        excludingId: String,
    ): Boolean

    @Insert
    suspend fun insertSecret(secret: SecretEntity)

    @Update
    suspend fun updateSecret(secret: SecretEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClientApprovalOverride(override: SecretClientApprovalOverrideEntity)

    @Query(
        "DELETE FROM secret_client_approval_overrides " +
            "WHERE secret_id = :secretId AND client_id = :clientId",
    )
    suspend fun deleteClientApprovalOverride(secretId: String, clientId: String): Int

    @Delete
    suspend fun deleteSecret(secret: SecretEntity)

    @Insert
    suspend fun insertEnvironmentVariableRow(variable: EnvironmentVariableEntity)

    @Update
    suspend fun updateEnvironmentVariableRow(variable: EnvironmentVariableEntity): Int

    @Delete
    suspend fun deleteEnvironmentVariableRow(variable: EnvironmentVariableEntity)

    @Insert
    suspend fun insertSshKeyRow(key: SshKeyEntity)

    @Update
    suspend fun updateSshKeyRow(key: SshKeyEntity): Int

    @Query("UPDATE secrets SET updated_at = :updatedAt WHERE id = :secretId")
    suspend fun touchSecret(secretId: String, updatedAt: Long)

    @Query("DELETE FROM environment_variables WHERE secret_id = :secretId")
    suspend fun deleteAllEnvironmentVariables(secretId: String): Int

    @Query(
        "DELETE FROM environment_variables WHERE secret_id = :secretId AND name NOT IN (:names)",
    )
    suspend fun deleteEnvironmentVariablesExcept(secretId: String, names: List<String>): Int

    @Transaction
    suspend fun insertEnvironmentVariable(variable: EnvironmentVariableEntity) {
        insertEnvironmentVariableRow(variable)
        touchSecret(variable.secretId, variable.updatedAt)
    }

    @Transaction
    suspend fun updateEnvironmentVariable(variable: EnvironmentVariableEntity): Int {
        val updated = updateEnvironmentVariableRow(variable)
        if (updated == 1) touchSecret(variable.secretId, variable.updatedAt)
        return updated
    }

    @Transaction
    suspend fun deleteEnvironmentVariable(
        variable: EnvironmentVariableEntity,
        secretUpdatedAt: Long,
    ) {
        deleteEnvironmentVariableRow(variable)
        touchSecret(variable.secretId, secretUpdatedAt)
    }

    @Transaction
    suspend fun applyEnvironmentSecret(
        secret: SecretEntity,
        variables: List<EnvironmentVariableEntity>,
        replaceVariables: Boolean,
    ) {
        if (getSecret(secret.id) == null) {
            insertSecret(secret)
        } else {
            check(updateSecret(secret) == 1)
        }
        if (replaceVariables) {
            if (variables.isEmpty()) {
                deleteAllEnvironmentVariables(secret.id)
            } else {
                deleteEnvironmentVariablesExcept(secret.id, variables.map { it.name })
            }
        }
        variables.forEach { variable ->
            if (getEnvironmentVariable(variable.id) == null) {
                insertEnvironmentVariableRow(variable)
            } else {
                check(updateEnvironmentVariableRow(variable) == 1)
            }
        }
    }

    @Transaction
    suspend fun insertSshSecret(secret: SecretEntity, key: SshKeyEntity) {
        insertSecret(secret)
        insertSshKeyRow(key)
    }

    @Transaction
    suspend fun applySshSecret(secret: SecretEntity, key: SshKeyEntity) {
        if (getSecret(secret.id) == null) {
            insertSecret(secret)
            insertSshKeyRow(key)
        } else {
            check(updateSecret(secret) == 1)
            if (getSshKey(secret.id) == null) {
                insertSshKeyRow(key)
            } else {
                check(updateSshKeyRow(key) == 1)
            }
        }
    }

    @Transaction
    suspend fun updateSshKey(key: SshKeyEntity, secretUpdatedAt: Long) {
        check(updateSshKeyRow(key) == 1)
        touchSecret(key.secretId, secretUpdatedAt)
    }
}
