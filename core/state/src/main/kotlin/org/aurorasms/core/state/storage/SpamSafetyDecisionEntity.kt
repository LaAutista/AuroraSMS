// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.BlockedSenderKey
import org.aurorasms.core.state.SpamClassification
import org.aurorasms.core.state.SpamParticipantSetKey
import org.aurorasms.core.state.SpamSafetyDecision
import org.aurorasms.core.state.SpamSafetyRevision
import org.aurorasms.core.state.SpamSafetyScope

internal const val SPAM_SAFETY_DECISIONS_TABLE = "spam_safety_decisions"

@Entity(
    tableName = SPAM_SAFETY_DECISIONS_TABLE,
    indices = [
        Index(value = ["provider_thread_id"], unique = true),
        Index(value = ["single_sender_key"]),
    ],
)
internal data class SpamSafetyDecisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "participant_set_key") val participantSetKey: String,
    @ColumnInfo(name = "provider_thread_id") val providerThreadId: Long,
    @ColumnInfo(name = "single_sender_key") val singleSenderKey: String?,
    @ColumnInfo(name = "classification_code") val classificationCode: String,
    @ColumnInfo(name = "blocked") val blocked: Boolean,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "updated_timestamp_ms") val updatedTimestampMillis: Long,
) {
    override fun toString(): String =
        "SpamSafetyDecisionEntity(classificationCode=$classificationCode, blocked=$blocked, REDACTED)"
}

internal fun SpamSafetyDecisionEntity.toDomain(
    requestedScope: SpamSafetyScope? = null,
): SpamSafetyDecision {
    val storedParticipantKey = SpamParticipantSetKey.fromStorageValue(participantSetKey)
    val storedSenderKey = singleSenderKey?.let(BlockedSenderKey::fromStorageValue)
    val scope = requestedScope ?: SpamSafetyScope(
        participantSetKey = storedParticipantKey,
        providerThreadId = ProviderThreadId(providerThreadId),
        singleSenderKey = storedSenderKey,
    )
    require(scope.participantSetKey == storedParticipantKey)
    require(scope.singleSenderKey == storedSenderKey)
    return SpamSafetyDecision(
        scope = scope.copy(providerThreadId = ProviderThreadId(providerThreadId)),
        classification = spamClassificationFromCode(classificationCode),
        blocked = blocked,
        revision = SpamSafetyRevision(revision),
        updatedTimestampMillis = updatedTimestampMillis,
    )
}

internal fun SpamSafetyDecision.toEntity(): SpamSafetyDecisionEntity = SpamSafetyDecisionEntity(
    participantSetKey = scope.participantSetKey.toStorageValue(),
    providerThreadId = scope.providerThreadId.value,
    singleSenderKey = scope.singleSenderKey?.toStorageValue(),
    classificationCode = classification.storageCode,
    blocked = blocked,
    revision = revision.value,
    updatedTimestampMillis = updatedTimestampMillis,
)

internal val SpamClassification.storageCode: String
    get() = when (this) {
        SpamClassification.NEUTRAL -> "neutral_v1"
        SpamClassification.SPAM -> "spam_v1"
        SpamClassification.NOT_SPAM -> "not_spam_v1"
    }

private fun spamClassificationFromCode(code: String): SpamClassification = when (code) {
    "neutral_v1" -> SpamClassification.NEUTRAL
    "spam_v1" -> SpamClassification.SPAM
    "not_spam_v1" -> SpamClassification.NOT_SPAM
    else -> error("Unknown spam classification")
}
