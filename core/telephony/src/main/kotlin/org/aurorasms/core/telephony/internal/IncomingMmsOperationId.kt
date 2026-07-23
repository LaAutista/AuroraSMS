// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import kotlin.random.Random
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.INCOMING_MMS_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.PendingOperationNamespace
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.pendingOperationNamespaceOrNull

internal fun allocateIncomingMmsOperationId(
    nextLong: () -> Long = Random.Default::nextLong,
): MessageId {
    val value = INCOMING_MMS_OPERATION_ID_BOUNDARY or
        (nextLong() and (INCOMING_MMS_OPERATION_ID_BOUNDARY - 1L))
    check(value in INCOMING_MMS_OPERATION_ID_BOUNDARY until COMPOSER_OPERATION_ID_BOUNDARY)
    return MessageId(ProviderKind.PENDING_OPERATION, value).also { operationId ->
        check(operationId.pendingOperationNamespaceOrNull() == PendingOperationNamespace.INCOMING_MMS)
    }
}
