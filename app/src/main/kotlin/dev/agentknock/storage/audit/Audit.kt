package dev.agentknock.storage.audit

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "audit_events",
    indices = [Index(value = ["occurred_at"])],
)
internal data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "outcome") val outcome: String,
    @ColumnInfo(name = "decision_source") val decisionSource: String?,
    @ColumnInfo(name = "client_id") val clientId: String?,
    @ColumnInfo(name = "relay_request_id") val relayRequestId: String?,
    @ColumnInfo(name = "body_json") val bodyJson: String,
)

@Dao
internal interface AuditDao {
    @Query("SELECT * FROM audit_events ORDER BY id DESC")
    fun observeEvents(): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_events WHERE id = :id")
    fun observeEvent(id: Long): Flow<AuditEventEntity?>

    @Insert suspend fun insertEvents(events: List<AuditEventEntity>)

    @Query("DELETE FROM audit_events WHERE occurred_at < :cutoff")
    suspend fun deleteBefore(cutoff: Long): Int

    @Transaction
    suspend fun insertAndPrune(events: List<AuditEventEntity>, cutoff: Long) {
        require(events.isNotEmpty())
        insertEvents(events)
        deleteBefore(cutoff)
    }
}
