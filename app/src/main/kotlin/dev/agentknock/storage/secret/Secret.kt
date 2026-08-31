package dev.agentknock.storage.secret

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.request.ClientEntity
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
    val type: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "revision")
    val revision: Long = 1,
    @ColumnInfo(name = "approval_mode")
    val approvalMode: String = "temporary",
    @ColumnInfo(name = "instructions")
    val instructions: String = "",
)

internal val SecretEntity.secretType: SecretType
    get() = SecretType.fromStoredName(type)

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
            entity = ClientEntity::class,
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
    tableName = "temporary_access_grants",
    primaryKeys = ["secret_id", "client_id", "operation"],
    foreignKeys = [
        ForeignKey(
            entity = SecretEntity::class,
            parentColumns = ["id"],
            childColumns = ["secret_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ClientEntity::class,
            parentColumns = ["client_id"],
            childColumns = ["client_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["client_id"])],
)
internal data class TemporaryAccessGrantEntity(
    @ColumnInfo(name = "secret_id")
    val secretId: String,
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "operation")
    val operation: String,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
)

internal data class TemporaryAccessGrantRow(
    @ColumnInfo(name = "secret_id")
    val secretId: String,
    @ColumnInfo(name = "secret_name")
    val secretName: String,
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "operation")
    val operation: String,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
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
    @Embedded
    val encryptedValue: EncryptedValue,
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
    @Embedded
    val encryptedPrivateKey: EncryptedValue,
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
    val type: String,
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

/** A transactionally consistent view of every usable secret and its typed content. */
internal data class SecretSnapshot(
    val secrets: List<SecretEntity>,
    val environmentVariables: List<EnvironmentVariableEntity>,
    val sshKeys: List<SshKeyEntity>,
) {
    val secretsByName: Map<String, SecretEntity> = secrets.associateBy(SecretEntity::name)
    val variablesBySecret: Map<String, List<EnvironmentVariableEntity>> =
        environmentVariables.groupBy(EnvironmentVariableEntity::secretId)
    val sshKeysBySecret: Map<String, SshKeyEntity> = sshKeys.associateBy(SshKeyEntity::secretId)
}

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
        SELECT temporary_access_grants.secret_id,
               secrets.name AS secret_name,
               temporary_access_grants.client_id,
               temporary_access_grants.operation,
               temporary_access_grants.expires_at
        FROM temporary_access_grants
        JOIN secrets ON secrets.id = temporary_access_grants.secret_id
        ORDER BY temporary_access_grants.expires_at, secrets.name COLLATE NOCASE
        """,
    )
    fun observeTemporaryAccessGrants(): Flow<List<TemporaryAccessGrantRow>>

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

    @Query(
        "SELECT * FROM temporary_access_grants " +
            "WHERE client_id = :clientId AND secret_id IN (:secretIds) " +
            "AND operation = :operation AND expires_at > :now",
    )
    suspend fun getActiveTemporaryAccessGrants(
        clientId: String,
        secretIds: List<String>,
        operation: String,
        now: Long,
    ): List<TemporaryAccessGrantEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM clients
            WHERE client_id = :clientId
              AND relay_client_state = 'active'
              AND COALESCE(desired_relay_client_state, 'active') = 'active'
        )
        """,
    )
    suspend fun clientCanReceiveTemporaryAccess(clientId: String): Boolean

    @Query("SELECT * FROM ssh_keys ORDER BY secret_id")
    suspend fun getSshKeys(): List<SshKeyEntity>

    @Transaction
    suspend fun getSecretSnapshot(): SecretSnapshot = SecretSnapshot(
        secrets = getSecrets(),
        environmentVariables = getEnvironmentVariables(),
        sshKeys = getSshKeys(),
    )

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

    @Query(
        "UPDATE secrets SET name = :name, description = :description, updated_at = :updatedAt, " +
            "revision = revision + CASE WHEN name = :name THEN 0 ELSE 1 END " +
            "WHERE id = :secretId AND revision = :expectedRevision AND type = :expectedType",
    )
    suspend fun updateSecretMetadataIfCurrent(
        secretId: String,
        expectedRevision: Long,
        expectedType: String,
        name: String,
        description: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE secrets SET approval_mode = :approvalMode, updated_at = :updatedAt, " +
            "revision = revision + 1 WHERE id = :secretId",
    )
    suspend fun updateSecretApprovalMode(
        secretId: String,
        approvalMode: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE secrets SET instructions = :instructions, updated_at = :updatedAt, " +
            "revision = revision + 1 WHERE id = :secretId",
    )
    suspend fun updateSecretInstructions(
        secretId: String,
        instructions: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE secrets SET description = :description, updated_at = MAX(updated_at, :updatedAt), " +
            "revision = revision + 1 " +
            "WHERE id = :secretId AND revision = :expectedRevision AND name = :expectedName " +
            "AND type = :expectedType",
    )
    suspend fun reviseSecretForUploadIfCurrent(
        secretId: String,
        expectedRevision: Long,
        expectedName: String,
        expectedType: String,
        description: String,
        updatedAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClientApprovalOverride(override: SecretClientApprovalOverrideEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemporaryAccessGrants(grants: List<TemporaryAccessGrantEntity>)

    @Transaction
    suspend fun upsertTemporaryAccessGrantsIfCurrent(
        grants: List<TemporaryAccessGrantEntity>,
        expectedRevisions: Map<String, Long>,
        expectedApprovalModes: Map<String, String>,
        now: Long,
    ): Boolean {
        if (grants.isEmpty()) return true
        val secretIds = grants.mapTo(linkedSetOf(), TemporaryAccessGrantEntity::secretId)
        val clientIds = grants.mapTo(linkedSetOf(), TemporaryAccessGrantEntity::clientId)
        val operations = grants.mapTo(linkedSetOf(), TemporaryAccessGrantEntity::operation)
        require(clientIds.size == 1) { "Temporary access must belong to one client" }
        require(operations.size == 1) { "Temporary access must cover one operation" }
        require(expectedRevisions.keys == secretIds && expectedApprovalModes.keys == secretIds)
        if (grants.any { it.expiresAt <= now }) return false
        val currentSecrets = getSecrets().filter { it.id in secretIds }.associateBy(SecretEntity::id)
        if (currentSecrets.size != secretIds.size) return false
        val clientId = clientIds.single()
        val overrides = getClientApprovalOverrides(clientId, secretIds.toList())
            .associateBy(SecretClientApprovalOverrideEntity::secretId)
        if (secretIds.any { secretId ->
                currentSecrets.getValue(secretId).revision != expectedRevisions[secretId] ||
                    (overrides[secretId]?.approvalMode
                        ?: currentSecrets.getValue(secretId).approvalMode) !=
                    expectedApprovalModes[secretId]
            }
        ) {
            return false
        }
        if (!clientCanReceiveTemporaryAccess(clientId)) return false
        val activeGrants = getActiveTemporaryAccessGrants(
            clientId = clientId,
            secretIds = secretIds.toList(),
            operation = operations.single(),
            now = now,
        )
        if (activeGrants.isNotEmpty()) return false
        upsertTemporaryAccessGrants(grants)
        return true
    }

    @Query(
        "DELETE FROM secret_client_approval_overrides " +
            "WHERE secret_id = :secretId AND client_id = :clientId",
    )
    suspend fun deleteClientApprovalOverride(secretId: String, clientId: String): Int

    @Query(
        "DELETE FROM temporary_access_grants " +
            "WHERE secret_id = :secretId AND client_id = :clientId AND operation = :operation",
    )
    suspend fun deleteTemporaryAccessGrant(
        secretId: String,
        clientId: String,
        operation: String,
    ): Int

    @Query("DELETE FROM temporary_access_grants WHERE secret_id = :secretId")
    suspend fun deleteTemporaryAccessGrantsForSecret(secretId: String): Int

    @Query(
        "DELETE FROM temporary_access_grants " +
            "WHERE secret_id = :secretId AND client_id = :clientId",
    )
    suspend fun deleteTemporaryAccessGrantsForSecretClient(
        secretId: String,
        clientId: String,
    ): Int

    @Query("DELETE FROM temporary_access_grants WHERE expires_at <= :now")
    suspend fun deleteExpiredTemporaryAccessGrants(now: Long): Int

    @Query("DELETE FROM temporary_access_grants")
    suspend fun deleteAllTemporaryAccessGrants(): Int

    @Delete
    suspend fun deleteSecret(secret: SecretEntity)

    @Insert
    suspend fun insertEnvironmentVariableRow(variable: EnvironmentVariableEntity)

    @Query(
        "UPDATE environment_variables SET name = :name, sensitive = :sensitive, notes = :notes, " +
            "encryption_format = :encryptionFormat, encryption_key_id = :encryptionKeyId, " +
            "nonce = :nonce, ciphertext = :ciphertext, updated_at = :updatedAt, " +
            "value_updated_at = :valueUpdatedAt WHERE id = :variableId AND secret_id = :secretId",
    )
    suspend fun updateEnvironmentVariableMaterialRow(
        variableId: String,
        secretId: String,
        name: String,
        sensitive: Boolean,
        notes: String,
        encryptionFormat: Int,
        encryptionKeyId: String,
        nonce: ByteArray,
        ciphertext: ByteArray,
        updatedAt: Long,
        valueUpdatedAt: Long,
    ): Int

    @Query(
        "UPDATE environment_variables SET notes = :notes, updated_at = :updatedAt " +
            "WHERE id = :variableId AND secret_id = :secretId " +
            "AND EXISTS (SELECT 1 FROM secrets WHERE id = :secretId AND type = 'environment')",
    )
    suspend fun updateEnvironmentVariableNotesRow(
        variableId: String,
        secretId: String,
        notes: String,
        updatedAt: Long,
    ): Int

    @Delete
    suspend fun deleteEnvironmentVariableRow(variable: EnvironmentVariableEntity)

    @Insert
    suspend fun insertSshKeyRow(key: SshKeyEntity)

    @Query(
        "UPDATE ssh_keys SET algorithm = :algorithm, public_key = :publicKey, comment = :comment, " +
            "private_key_format = :privateKeyFormat, encryption_format = :encryptionFormat, " +
            "encryption_key_id = :encryptionKeyId, nonce = :nonce, ciphertext = :ciphertext, " +
            "material_updated_at = :materialUpdatedAt WHERE secret_id = :secretId",
    )
    suspend fun updateSshKeyMaterialRow(
        secretId: String,
        algorithm: String,
        publicKey: ByteArray,
        comment: String,
        privateKeyFormat: String,
        encryptionFormat: Int,
        encryptionKeyId: String,
        nonce: ByteArray,
        ciphertext: ByteArray,
        materialUpdatedAt: Long,
    ): Int

    @Query("UPDATE ssh_keys SET comment = :comment WHERE secret_id = :secretId")
    suspend fun updateSshKeyCommentRow(secretId: String, comment: String): Int

    @Query(
        "UPDATE environment_variables SET sensitive = :sensitive, " +
            "encryption_format = :encryptionFormat, encryption_key_id = :encryptionKeyId, " +
            "nonce = :nonce, ciphertext = :ciphertext, " +
            "updated_at = :updatedAt, value_updated_at = :updatedAt " +
            "WHERE id = :variableId AND secret_id = :secretId",
    )
    suspend fun updateUploadedEnvironmentVariableRow(
        variableId: String,
        secretId: String,
        sensitive: Boolean,
        encryptionFormat: Int,
        encryptionKeyId: String,
        nonce: ByteArray,
        ciphertext: ByteArray,
        updatedAt: Long,
    ): Int

    @Query("UPDATE secrets SET updated_at = MAX(updated_at, :updatedAt) WHERE id = :secretId")
    suspend fun touchSecret(secretId: String, updatedAt: Long)

    @Query(
        "UPDATE secrets SET updated_at = MAX(updated_at, :updatedAt), revision = revision + 1 " +
            "WHERE id = :secretId",
    )
    suspend fun reviseSecret(secretId: String, updatedAt: Long)

    @Query(
        "UPDATE secrets SET updated_at = MAX(updated_at, :updatedAt), revision = revision + 1 " +
            "WHERE id = :secretId AND revision = :expectedRevision AND type = :expectedType",
    )
    suspend fun reviseSecretIfCurrent(
        secretId: String,
        expectedRevision: Long,
        expectedType: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM environment_variables WHERE secret_id = :secretId")
    suspend fun deleteAllEnvironmentVariables(secretId: String): Int

    @Query(
        "DELETE FROM environment_variables WHERE secret_id = :secretId AND name NOT IN (:names)",
    )
    suspend fun deleteEnvironmentVariablesExcept(secretId: String, names: List<String>): Int

    @Transaction
    suspend fun setSecretApprovalMode(
        secretId: String,
        approvalMode: String,
        updatedAt: Long,
    ): Boolean {
        if (updateSecretApprovalMode(secretId, approvalMode, updatedAt) != 1) return false
        deleteTemporaryAccessGrantsForSecret(secretId)
        return true
    }

    @Transaction
    suspend fun setSecretInstructions(
        secretId: String,
        instructions: String,
        updatedAt: Long,
    ): Boolean {
        if (updateSecretInstructions(secretId, instructions, updatedAt) != 1) return false
        deleteTemporaryAccessGrantsForSecret(secretId)
        return true
    }

    @Transaction
    suspend fun updateSecretMetadata(
        secretId: String,
        name: String,
        description: String,
        updatedAt: Long,
    ): Boolean {
        val current = getSecret(secretId) ?: return false
        val nameChanged = name != current.name
        if (
            updateSecretMetadataIfCurrent(
                secretId = secretId,
                expectedRevision = current.revision,
                expectedType = current.type,
                name = name,
                description = description,
                updatedAt = updatedAt,
            ) != 1
        ) return false
        if (nameChanged) deleteTemporaryAccessGrantsForSecret(secretId)
        return true
    }

    @Transaction
    suspend fun upsertClientApprovalOverrideAndDeleteTemporaryAccess(
        override: SecretClientApprovalOverrideEntity,
        updatedAt: Long,
    ) {
        upsertClientApprovalOverride(override)
        deleteTemporaryAccessGrantsForSecretClient(override.secretId, override.clientId)
        reviseSecret(override.secretId, updatedAt)
    }

    @Transaction
    suspend fun deleteClientApprovalOverrideAndTemporaryAccess(
        secretId: String,
        clientId: String,
        updatedAt: Long,
    ) {
        val deleted = deleteClientApprovalOverride(secretId, clientId)
        deleteTemporaryAccessGrantsForSecretClient(secretId, clientId)
        if (deleted == 1) reviseSecret(secretId, updatedAt)
    }

    @Transaction
    suspend fun insertEnvironmentVariableIfCurrent(
        variable: EnvironmentVariableEntity,
        expectedSecretRevision: Long,
    ): Boolean {
        val secret = getSecret(variable.secretId) ?: return false
        if (
            secret.secretType != SecretType.ENVIRONMENT ||
            secret.revision != expectedSecretRevision
        ) return false
        insertEnvironmentVariableRow(variable)
        check(
            reviseSecretIfCurrent(
                variable.secretId,
                expectedSecretRevision,
                SecretType.ENVIRONMENT.storedName,
                variable.updatedAt,
            ) == 1,
        )
        deleteTemporaryAccessGrantsForSecret(variable.secretId)
        return true
    }

    @Transaction
    suspend fun updateEnvironmentVariableIfCurrent(
        variable: EnvironmentVariableEntity,
        expectedSecretRevision: Long,
    ): Boolean {
        val secret = getSecret(variable.secretId) ?: return false
        if (
            secret.secretType != SecretType.ENVIRONMENT ||
            secret.revision != expectedSecretRevision
        ) return false
        if (
            updateEnvironmentVariableMaterialRow(
                variableId = variable.id,
                secretId = variable.secretId,
                name = variable.name,
                sensitive = variable.sensitive,
                notes = variable.notes,
                encryptionFormat = variable.encryptedValue.formatVersion,
                encryptionKeyId = variable.encryptedValue.keyId,
                nonce = variable.encryptedValue.nonce,
                ciphertext = variable.encryptedValue.ciphertext,
                updatedAt = variable.updatedAt,
                valueUpdatedAt = variable.valueUpdatedAt,
            ) != 1
        ) return false
        check(
            reviseSecretIfCurrent(
                variable.secretId,
                expectedSecretRevision,
                SecretType.ENVIRONMENT.storedName,
                variable.updatedAt,
            ) == 1,
        )
        deleteTemporaryAccessGrantsForSecret(variable.secretId)
        return true
    }

    @Transaction
    suspend fun updateEnvironmentVariableNotes(
        variableId: String,
        secretId: String,
        notes: String,
        updatedAt: Long,
    ): Int {
        if (getSecret(secretId)?.secretType != SecretType.ENVIRONMENT) return 0
        val updated = updateEnvironmentVariableNotesRow(
            variableId = variableId,
            secretId = secretId,
            notes = notes,
            updatedAt = updatedAt,
        )
        if (updated == 1) touchSecret(secretId, updatedAt)
        return updated
    }

    @Transaction
    suspend fun deleteEnvironmentVariableIfCurrent(
        variable: EnvironmentVariableEntity,
        expectedSecretRevision: Long,
        secretUpdatedAt: Long,
    ): Boolean {
        val secret = getSecret(variable.secretId) ?: return false
        if (
            secret.secretType != SecretType.ENVIRONMENT ||
            secret.revision != expectedSecretRevision
        ) return false
        deleteEnvironmentVariableRow(variable)
        check(
            reviseSecretIfCurrent(
                variable.secretId,
                expectedSecretRevision,
                SecretType.ENVIRONMENT.storedName,
                secretUpdatedAt,
            ) == 1,
        )
        deleteTemporaryAccessGrantsForSecret(variable.secretId)
        return true
    }

    @Transaction
    suspend fun insertSshSecret(secret: SecretEntity, key: SshKeyEntity) {
        require(secret.secretType == SecretType.SSH)
        require(key.secretId == secret.id)
        insertSecret(secret)
        insertSshKeyRow(key)
    }

    @Transaction
    suspend fun updateSshKeyIfCurrent(
        key: SshKeyEntity,
        expectedSecretRevision: Long,
        secretUpdatedAt: Long,
    ): Boolean {
        val secret = getSecret(key.secretId) ?: return false
        if (secret.secretType != SecretType.SSH || secret.revision != expectedSecretRevision) {
            return false
        }
        if (
            updateSshKeyMaterialRow(
                secretId = key.secretId,
                algorithm = key.algorithm,
                publicKey = key.publicKey,
                comment = key.comment,
                privateKeyFormat = key.privateKeyFormat,
                encryptionFormat = key.encryptedPrivateKey.formatVersion,
                encryptionKeyId = key.encryptedPrivateKey.keyId,
                nonce = key.encryptedPrivateKey.nonce,
                ciphertext = key.encryptedPrivateKey.ciphertext,
                materialUpdatedAt = key.materialUpdatedAt,
            ) != 1
        ) return false
        check(
            reviseSecretIfCurrent(
                key.secretId,
                expectedSecretRevision,
                SecretType.SSH.storedName,
                secretUpdatedAt,
            ) == 1,
        )
        deleteTemporaryAccessGrantsForSecret(key.secretId)
        return true
    }

    @Transaction
    suspend fun updateSshKeyComment(
        secretId: String,
        comment: String,
        secretUpdatedAt: Long,
    ): Boolean {
        if (getSecret(secretId)?.secretType != SecretType.SSH) return false
        if (updateSshKeyCommentRow(secretId, comment) != 1) return false
        touchSecret(secretId, secretUpdatedAt)
        return true
    }

    @Transaction
    suspend fun applyEnvironmentUploadIfCurrent(
        target: SecretUploadTarget?,
        expectedName: String,
        secret: SecretEntity,
        variables: List<EnvironmentVariableEntity>,
        replaceVariables: Boolean,
        preserveCurrentDescription: Boolean,
    ): Boolean {
        require(secret.secretType == SecretType.ENVIRONMENT)
        require(variables.all { it.secretId == secret.id })
        require(!preserveCurrentDescription || target != null)
        val current = target?.let { getSecret(it.secretId) }
        if (target == null) {
            if (getSecretsByName(listOf(secret.name)).isNotEmpty()) return false
            insertSecret(secret.copy(revision = 1))
        } else {
            if (
                current == null || current.id != secret.id || current.name != expectedName ||
                current.revision != target.revision || current.secretType != SecretType.ENVIRONMENT
            ) return false
            if (
                reviseSecretForUploadIfCurrent(
                    secretId = current.id,
                    expectedRevision = target.revision,
                    expectedName = expectedName,
                    expectedType = SecretType.ENVIRONMENT.storedName,
                    description = if (preserveCurrentDescription) {
                        current.description
                    } else {
                        secret.description
                    },
                    updatedAt = secret.updatedAt,
                ) != 1
            ) return false
            deleteTemporaryAccessGrantsForSecret(current.id)
        }
        if (replaceVariables) {
            if (variables.isEmpty()) deleteAllEnvironmentVariables(secret.id)
            else deleteEnvironmentVariablesExcept(secret.id, variables.map { it.name })
        }
        variables.forEach { variable ->
            val existing = getEnvironmentVariable(variable.id)
            if (existing == null) {
                insertEnvironmentVariableRow(variable)
            } else {
                check(existing.secretId == secret.id)
                check(
                    updateUploadedEnvironmentVariableRow(
                        variableId = variable.id,
                        secretId = secret.id,
                        sensitive = variable.sensitive,
                        encryptionFormat = variable.encryptedValue.formatVersion,
                        encryptionKeyId = variable.encryptedValue.keyId,
                        nonce = variable.encryptedValue.nonce,
                        ciphertext = variable.encryptedValue.ciphertext,
                        updatedAt = variable.updatedAt,
                    ) == 1,
                )
            }
        }
        return true
    }

    @Transaction
    suspend fun applySshUploadIfCurrent(
        target: SecretUploadTarget?,
        expectedName: String,
        secret: SecretEntity,
        key: SshKeyEntity,
        preserveCurrentDescription: Boolean,
    ): Boolean {
        require(secret.secretType == SecretType.SSH)
        require(key.secretId == secret.id)
        require(!preserveCurrentDescription || target != null)
        val current = target?.let { getSecret(it.secretId) }
        if (target == null) {
            if (getSecretsByName(listOf(secret.name)).isNotEmpty()) return false
            insertSecret(secret.copy(revision = 1))
            insertSshKeyRow(key)
            return true
        }
        if (
            current == null || current.id != secret.id || current.name != expectedName ||
            current.revision != target.revision || current.secretType != SecretType.SSH
        ) return false
        val metadataUpdated = reviseSecretForUploadIfCurrent(
            secretId = current.id,
            expectedRevision = target.revision,
            expectedName = expectedName,
            expectedType = SecretType.SSH.storedName,
            description = if (preserveCurrentDescription) {
                current.description
            } else {
                secret.description
            },
            updatedAt = secret.updatedAt,
        )
        if (metadataUpdated != 1) return false
        deleteTemporaryAccessGrantsForSecret(current.id)
        check(
            updateSshKeyMaterialRow(
                secretId = key.secretId,
                algorithm = key.algorithm,
                publicKey = key.publicKey,
                comment = key.comment,
                privateKeyFormat = key.privateKeyFormat,
                encryptionFormat = key.encryptedPrivateKey.formatVersion,
                encryptionKeyId = key.encryptedPrivateKey.keyId,
                nonce = key.encryptedPrivateKey.nonce,
                ciphertext = key.encryptedPrivateKey.ciphertext,
                materialUpdatedAt = key.materialUpdatedAt,
            ) == 1,
        )
        return true
    }
}
