// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.INLINE_REPLY_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.ComposerSmsOperation
import org.aurorasms.core.state.ComposerSmsOperationPhase
import org.aurorasms.core.state.ComposerSmsProviderBinding
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.MessageSignature

internal const val COMPOSER_SMS_OPERATIONS_TABLE: String = "composer_sms_operations"

@Entity(
    tableName = COMPOSER_SMS_OPERATIONS_TABLE,
    indices = [
        Index(value = ["provider_thread_id"], unique = true),
        Index(value = ["transport_code", "provider_message_id"], unique = true),
        Index(value = ["draft_id", "draft_revision_ms"]),
        Index(value = ["updated_timestamp_ms", "local_operation_id"]),
    ],
)
internal data class ComposerSmsOperationEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "local_operation_id")
    val localOperationId: Long = 0L,
    @ColumnInfo(name = "provider_thread_id")
    val providerThreadId: Long,
    @ColumnInfo(name = "draft_id")
    val draftId: Long,
    @ColumnInfo(name = "draft_revision_ms")
    val draftRevisionMillis: Long,
    @ColumnInfo(name = "subscription_id")
    val subscriptionId: Int,
    @ColumnInfo(name = "transport_code", defaultValue = "'sms_v1'")
    val transportCode: String = MessageTransportKind.SMS.storageCode,
    @ColumnInfo(name = "phase_code")
    val phaseCode: String,
    @ColumnInfo(name = "provider_message_id")
    val providerMessageId: Long?,
    @ColumnInfo(name = "provider_conversation_id")
    val providerConversationId: Long?,
    @ColumnInfo(name = "unit_count")
    val unitCount: Int?,
    @ColumnInfo(name = "created_timestamp_ms")
    val createdTimestampMillis: Long,
    @ColumnInfo(name = "updated_timestamp_ms")
    val updatedTimestampMillis: Long,
    @ColumnInfo(name = "signature_text")
    val signatureText: String? = null,
) {
    override fun toString(): String =
        "ComposerSmsOperationEntity(phaseCode=$phaseCode, hasProviderBinding=${providerMessageId != null}, REDACTED)"
}

internal fun ComposerSmsOperationEntity.toDomain(): ComposerSmsOperation {
    require(localOperationId > 0L && localOperationId < COMPOSER_SMS_LOCAL_ID_LIMIT_EXCLUSIVE) {
        "Stored composer SMS local operation ID is invalid"
    }
    val phase = composerSmsOperationPhaseFromStorageCode(phaseCode)
        ?: error("Stored composer SMS phase code is unknown")
    val bindingValues = listOf(providerMessageId, providerConversationId, unitCount)
    require(bindingValues.all { it == null } || bindingValues.all { it != null }) {
        "Stored composer SMS provider binding is partial"
    }
    val binding = providerMessageId?.let { providerId ->
        val transport = messageTransportKindFromStorageCode(transportCode)
            ?: error("Stored composer transport code is unknown")
        ComposerSmsProviderBinding(
            providerMessageId = ProviderMessageId(transport.providerKind, providerId),
            providerConversationId = ConversationId(checkNotNull(providerConversationId)),
            unitCount = checkNotNull(unitCount),
        )
    }
    return ComposerSmsOperation(
        operationId = MessageId(
            ProviderKind.PENDING_OPERATION,
            COMPOSER_OPERATION_ID_BOUNDARY + localOperationId,
        ),
        providerThreadId = ProviderThreadId(providerThreadId),
        draftId = DraftId(draftId),
        draftRevision = DraftRevision(draftRevisionMillis),
        subscriptionId = AuroraSubscriptionId(subscriptionId),
        phase = phase,
        providerBinding = binding,
        createdTimestampMillis = createdTimestampMillis,
        updatedTimestampMillis = updatedTimestampMillis,
        frozenSignature = signatureText?.let(MessageSignature::fromStorageValue),
        transport = messageTransportKindFromStorageCode(transportCode)
            ?: error("Stored composer transport code is unknown"),
    )
}

internal val ComposerSmsOperationPhase.storageCode: String
    get() = when (this) {
        ComposerSmsOperationPhase.RESERVED -> "reserved_v1"
        ComposerSmsOperationPhase.PREPARED -> "prepared_v1"
        ComposerSmsOperationPhase.SUBMITTING -> "submitting_v1"
        ComposerSmsOperationPhase.PLATFORM_ACCEPTED -> "platform_accepted_v1"
        ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED -> "sent_callback_succeeded_v1"
        ComposerSmsOperationPhase.SUBMISSION_UNKNOWN -> "submission_unknown_v1"
        ComposerSmsOperationPhase.KNOWN_UNSENT -> "known_unsent_v1"
    }

internal fun composerSmsOperationPhaseFromStorageCode(value: String): ComposerSmsOperationPhase? =
    when (value) {
        "reserved_v1" -> ComposerSmsOperationPhase.RESERVED
        "prepared_v1" -> ComposerSmsOperationPhase.PREPARED
        "submitting_v1" -> ComposerSmsOperationPhase.SUBMITTING
        "platform_accepted_v1" -> ComposerSmsOperationPhase.PLATFORM_ACCEPTED
        "sent_callback_succeeded_v1" -> ComposerSmsOperationPhase.SENT_CALLBACK_SUCCEEDED
        "submission_unknown_v1" -> ComposerSmsOperationPhase.SUBMISSION_UNKNOWN
        "known_unsent_v1" -> ComposerSmsOperationPhase.KNOWN_UNSENT
        else -> null
    }

internal val MessageTransportKind.storageCode: String
    get() = when (this) {
        MessageTransportKind.SMS -> "sms_v1"
        MessageTransportKind.MMS -> "mms_v1"
    }

internal fun messageTransportKindFromStorageCode(value: String): MessageTransportKind? = when (value) {
    "sms_v1" -> MessageTransportKind.SMS
    "mms_v1" -> MessageTransportKind.MMS
    else -> null
}

internal val MessageTransportKind.providerKind: ProviderKind
    get() = when (this) {
        MessageTransportKind.SMS -> ProviderKind.SMS
        MessageTransportKind.MMS -> ProviderKind.MMS
    }

internal const val COMPOSER_SMS_LOCAL_ID_LIMIT_EXCLUSIVE: Long =
    INLINE_REPLY_OPERATION_ID_BOUNDARY - COMPOSER_OPERATION_ID_BOUNDARY
