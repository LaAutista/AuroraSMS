// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.ConversationSubscriptionParticipantSetKey
import org.aurorasms.core.state.ConversationSubscriptionPreference
import org.aurorasms.core.state.ConversationSubscriptionRevision
import org.aurorasms.core.state.ConversationSubscriptionScope

internal const val CONVERSATION_SUBSCRIPTION_PREFERENCES_TABLE: String =
    "conversation_subscription_preferences"

@Entity(
    tableName = CONVERSATION_SUBSCRIPTION_PREFERENCES_TABLE,
    indices = [Index(value = ["provider_thread_id"])],
)
internal data class ConversationSubscriptionPreferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "participant_set_key")
    val participantSetKey: String,
    @ColumnInfo(name = "provider_thread_id")
    val providerThreadId: Long,
    @ColumnInfo(name = "subscription_id")
    val subscriptionId: Int,
    @ColumnInfo(name = "revision")
    val revision: Long,
    @ColumnInfo(name = "updated_timestamp_ms")
    val updatedTimestampMillis: Long,
) {
    override fun toString(): String = "ConversationSubscriptionPreferenceEntity(REDACTED)"
}

internal fun ConversationSubscriptionPreferenceEntity.toDomain(
    requestedScope: ConversationSubscriptionScope? = null,
): ConversationSubscriptionPreference {
    val storedKey = ConversationSubscriptionParticipantSetKey.fromStorageValue(participantSetKey)
    val scope = requestedScope ?: ConversationSubscriptionScope(
        participantSetKey = storedKey,
        providerThreadId = ProviderThreadId(providerThreadId),
    )
    require(scope.participantSetKey == storedKey) {
        "Stored conversation subscription identity does not match its requested scope"
    }
    return ConversationSubscriptionPreference(
        scope = scope.copy(providerThreadId = ProviderThreadId(providerThreadId)),
        subscriptionId = AuroraSubscriptionId(subscriptionId),
        revision = ConversationSubscriptionRevision(revision),
        updatedTimestampMillis = updatedTimestampMillis,
    )
}

internal fun ConversationSubscriptionPreference.toEntity(): ConversationSubscriptionPreferenceEntity =
    ConversationSubscriptionPreferenceEntity(
        participantSetKey = scope.participantSetKey.toStorageValue(),
        providerThreadId = scope.providerThreadId.value,
        subscriptionId = subscriptionId.value,
        revision = revision.value,
        updatedTimestampMillis = updatedTimestampMillis,
    )
