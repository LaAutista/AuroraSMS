// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.state.AcknowledgedComposerSmsCallbackProof
import org.aurorasms.core.state.AcknowledgedComposerSmsReceipt
import org.aurorasms.core.state.ComposerSmsProviderBinding

internal const val ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS_TABLE: String =
    "acknowledged_composer_sms_receipts"

@Entity(
    tableName = ACKNOWLEDGED_COMPOSER_SMS_RECEIPTS_TABLE,
    indices = [
        Index(value = ["provider_kind_code", "provider_message_id"], unique = true),
        Index(value = ["updated_timestamp_ms", "local_operation_id"]),
    ],
)
internal data class AcknowledgedComposerSmsEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_operation_id")
    val localOperationId: Long,
    @ColumnInfo(name = "provider_message_id")
    val providerMessageId: Long,
    @ColumnInfo(name = "provider_kind_code", defaultValue = "'sms_v1'")
    val providerKindCode: String = ProviderKind.SMS.storageCode,
    @ColumnInfo(name = "provider_conversation_id")
    val providerConversationId: Long,
    @ColumnInfo(name = "unit_count")
    val unitCount: Int,
    @ColumnInfo(name = "callback_proof_code")
    val callbackProofCode: String,
    @ColumnInfo(name = "acknowledged_timestamp_ms")
    val acknowledgedTimestampMillis: Long,
    @ColumnInfo(name = "updated_timestamp_ms")
    val updatedTimestampMillis: Long,
) {
    override fun toString(): String =
        "AcknowledgedComposerSmsEntity(callbackProofCode=$callbackProofCode, REDACTED)"
}

internal fun AcknowledgedComposerSmsEntity.toDomain(): AcknowledgedComposerSmsReceipt {
    require(localOperationId > 0L && localOperationId < COMPOSER_SMS_LOCAL_ID_LIMIT_EXCLUSIVE) {
        "Stored acknowledged composer SMS local operation ID is invalid"
    }
    return AcknowledgedComposerSmsReceipt(
        operationId = MessageId(
            ProviderKind.PENDING_OPERATION,
            COMPOSER_OPERATION_ID_BOUNDARY + localOperationId,
        ),
        providerBinding = ComposerSmsProviderBinding(
            providerMessageId = ProviderMessageId(
                providerKindFromStorageCode(providerKindCode)
                    ?: error("Stored acknowledged composer provider kind is unknown"),
                providerMessageId,
            ),
            providerConversationId = ConversationId(providerConversationId),
            unitCount = unitCount,
        ),
        callbackProof = acknowledgedComposerSmsCallbackProofFromStorageCode(callbackProofCode)
            ?: error("Stored acknowledged composer SMS callback proof is unknown"),
        acknowledgedTimestampMillis = acknowledgedTimestampMillis,
        updatedTimestampMillis = updatedTimestampMillis,
    )
}

internal val ProviderKind.storageCode: String
    get() = when (this) {
        ProviderKind.SMS -> "sms_v1"
        ProviderKind.MMS -> "mms_v1"
        else -> error("Only telephony provider kinds can be stored for composer callbacks")
    }

internal fun providerKindFromStorageCode(value: String): ProviderKind? = when (value) {
    "sms_v1" -> ProviderKind.SMS
    "mms_v1" -> ProviderKind.MMS
    else -> null
}

internal val AcknowledgedComposerSmsCallbackProof.storageCode: String
    get() = when (this) {
        AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK -> "awaiting_callback_v1"
        AcknowledgedComposerSmsCallbackProof.SENT -> "sent_v1"
        AcknowledgedComposerSmsCallbackProof.FAILED -> "failed_v1"
    }

internal fun acknowledgedComposerSmsCallbackProofFromStorageCode(
    value: String,
): AcknowledgedComposerSmsCallbackProof? = when (value) {
    "awaiting_callback_v1" -> AcknowledgedComposerSmsCallbackProof.AWAITING_CALLBACK
    "sent_v1" -> AcknowledgedComposerSmsCallbackProof.SENT
    "failed_v1" -> AcknowledgedComposerSmsCallbackProof.FAILED
    else -> null
}
