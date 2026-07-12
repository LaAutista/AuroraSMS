// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.Draft
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftParticipantSetKey
import org.aurorasms.core.state.NewDraft

@Entity(
    tableName = "drafts",
    indices = [
        Index(value = ["provider_thread_id"], unique = true),
        Index(value = ["participant_set_key"], unique = true),
        Index(value = ["updated_timestamp_ms", "draft_id"]),
    ],
)
internal data class DraftEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "draft_id")
    val draftId: Long = 0L,
    @ColumnInfo(name = "provider_thread_id")
    val providerThreadId: Long?,
    @ColumnInfo(name = "participant_set_key")
    val participantSetKey: String?,
    @ColumnInfo(name = "body")
    val body: String?,
    @ColumnInfo(name = "subject")
    val subject: String?,
    @ColumnInfo(name = "created_timestamp_ms")
    val createdTimestampMillis: Long,
    @ColumnInfo(name = "updated_timestamp_ms")
    val updatedTimestampMillis: Long,
) {
    init {
        require(draftId >= 0L) { "A draft entity ID cannot be negative" }
        require((providerThreadId != null) xor (participantSetKey != null)) {
            "A draft entity must have exactly one identity"
        }
        providerThreadId?.let {
            require(it > 0L) { "A draft provider thread ID must be positive" }
        }
        participantSetKey?.let(DraftParticipantSetKey::fromStorageValue)
        require(body == null || body.length <= Draft.MAX_BODY_CHARACTERS) {
            "A draft entity body is too large"
        }
        require(subject == null || subject.length <= Draft.MAX_SUBJECT_CHARACTERS) {
            "A draft entity subject is too large"
        }
        require(createdTimestampMillis >= 0L) {
            "A draft entity creation timestamp cannot be negative"
        }
        require(updatedTimestampMillis >= createdTimestampMillis) {
            "A draft entity update timestamp cannot precede creation"
        }
    }

    override fun toString(): String = "DraftEntity(REDACTED)"
}

internal fun NewDraft.toEntity(): DraftEntity {
    val identity = identity.toColumns()
    return DraftEntity(
        providerThreadId = identity.providerThreadId,
        participantSetKey = identity.participantSetKey,
        body = body,
        subject = subject,
        createdTimestampMillis = createdTimestampMillis,
        updatedTimestampMillis = updatedTimestampMillis,
    )
}

internal fun Draft.toEntity(): DraftEntity {
    val identity = identity.toColumns()
    return DraftEntity(
        draftId = id.value,
        providerThreadId = identity.providerThreadId,
        participantSetKey = identity.participantSetKey,
        body = body,
        subject = subject,
        createdTimestampMillis = createdTimestampMillis,
        updatedTimestampMillis = updatedTimestampMillis,
    )
}

internal fun DraftEntity.toDomain(): Draft {
    require(draftId > 0L) { "A stored draft ID must be positive" }
    val identity = when {
        providerThreadId != null && participantSetKey == null ->
            DraftIdentity.ProviderThread(ProviderThreadId(providerThreadId))
        providerThreadId == null && participantSetKey != null ->
            DraftIdentity.ParticipantSet(DraftParticipantSetKey.fromStorageValue(participantSetKey))
        else -> error("A stored draft must have exactly one identity")
    }
    return Draft(
        id = DraftId(draftId),
        identity = identity,
        body = body,
        subject = subject,
        createdTimestampMillis = createdTimestampMillis,
        updatedTimestampMillis = updatedTimestampMillis,
    )
}

private data class DraftIdentityColumns(
    val providerThreadId: Long?,
    val participantSetKey: String?,
) {
    override fun toString(): String = "DraftIdentityColumns(REDACTED)"
}

private fun DraftIdentity.toColumns(): DraftIdentityColumns = when (this) {
    is DraftIdentity.ProviderThread -> DraftIdentityColumns(
        providerThreadId = providerThreadId.value,
        participantSetKey = null,
    )
    is DraftIdentity.ParticipantSet -> DraftIdentityColumns(
        providerThreadId = null,
        participantSetKey = key.toStorageValue(),
    )
}
