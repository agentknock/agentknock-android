package dev.agentknock.storage.profile

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import dev.agentknock.storage.crypto.LocalEncryptionKeyEntity
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "profiles",
    indices = [Index(value = ["name"], unique = true)],
)
internal data class ProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "description")
    val description: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "environment_variables",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = LocalEncryptionKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["encryption_key_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["profile_id", "name"], unique = true),
        Index(value = ["encryption_key_id"]),
    ],
)
internal data class EnvironmentVariableEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
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

internal data class ProfileSummaryRow(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "description")
    val description: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "environment_variable_count")
    val environmentVariableCount: Int,
)

internal data class EnvironmentVariableMetadataRow(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
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

@Dao
internal interface ProfileDao {
    @Query(
        """
        SELECT profiles.id,
               profiles.name,
               profiles.description,
               profiles.created_at,
               profiles.updated_at,
               count(environment_variables.id) AS environment_variable_count
        FROM profiles
        LEFT JOIN environment_variables ON environment_variables.profile_id = profiles.id
        GROUP BY profiles.id
        ORDER BY profiles.name COLLATE NOCASE, profiles.id
        """,
    )
    fun observeProfiles(): Flow<List<ProfileSummaryRow>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observeProfile(id: String): Flow<ProfileEntity?>

    @Query(
        """
        SELECT id,
               profile_id,
               name,
               sensitive,
               notes,
               encryption_key_id,
               created_at,
               updated_at,
               value_updated_at
        FROM environment_variables
        WHERE profile_id = :profileId
        ORDER BY name COLLATE NOCASE, id
        """,
    )
    fun observeEnvironmentVariables(
        profileId: String,
    ): Flow<List<EnvironmentVariableMetadataRow>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfile(id: String): ProfileEntity?

    @Query("SELECT * FROM environment_variables WHERE id = :id")
    suspend fun getEnvironmentVariable(id: String): EnvironmentVariableEntity?

    @Query("SELECT * FROM profiles ORDER BY name COLLATE NOCASE, id")
    suspend fun getProfiles(): List<ProfileEntity>

    @Query("SELECT * FROM environment_variables ORDER BY profile_id, name COLLATE NOCASE, id")
    suspend fun getEnvironmentVariables(): List<EnvironmentVariableEntity>

    @Query("SELECT * FROM profiles WHERE name IN (:names)")
    suspend fun getProfilesByName(names: List<String>): List<ProfileEntity>

    @Query("SELECT * FROM environment_variables WHERE profile_id IN (:profileIds)")
    suspend fun getEnvironmentVariablesForProfiles(
        profileIds: List<String>,
    ): List<EnvironmentVariableEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM profiles WHERE name = :name AND id != :excludingId)")
    suspend fun profileNameInUse(name: String, excludingId: String): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM environment_variables
            WHERE profile_id = :profileId AND name = :name AND id != :excludingId
        )
        """,
    )
    suspend fun environmentVariableNameInUse(
        profileId: String,
        name: String,
        excludingId: String,
    ): Boolean

    @Insert
    suspend fun insertProfile(profile: ProfileEntity)

    @Update
    suspend fun updateProfile(profile: ProfileEntity): Int

    @Delete
    suspend fun deleteProfile(profile: ProfileEntity)

    @Insert
    suspend fun insertEnvironmentVariableRow(variable: EnvironmentVariableEntity)

    @Update
    suspend fun updateEnvironmentVariableRow(variable: EnvironmentVariableEntity): Int

    @Delete
    suspend fun deleteEnvironmentVariableRow(variable: EnvironmentVariableEntity)

    @Query("UPDATE profiles SET updated_at = :updatedAt WHERE id = :profileId")
    suspend fun touchProfile(profileId: String, updatedAt: Long)

    @Transaction
    suspend fun insertEnvironmentVariable(variable: EnvironmentVariableEntity) {
        insertEnvironmentVariableRow(variable)
        touchProfile(variable.profileId, variable.updatedAt)
    }

    @Transaction
    suspend fun updateEnvironmentVariable(variable: EnvironmentVariableEntity): Int {
        val updated = updateEnvironmentVariableRow(variable)
        if (updated == 1) touchProfile(variable.profileId, variable.updatedAt)
        return updated
    }

    @Transaction
    suspend fun deleteEnvironmentVariable(
        variable: EnvironmentVariableEntity,
        profileUpdatedAt: Long,
    ) {
        deleteEnvironmentVariableRow(variable)
        touchProfile(variable.profileId, profileUpdatedAt)
    }
}
