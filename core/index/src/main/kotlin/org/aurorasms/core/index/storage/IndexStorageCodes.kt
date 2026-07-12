// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.storage

import org.aurorasms.core.index.IndexFailureCode
import org.aurorasms.core.index.IndexRunState
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.ProviderKind

internal object ProviderKindCode {
    const val SMS: Int = 1
    const val MMS: Int = 2
}

internal object DirectionCode {
    const val INCOMING: Int = 1
    const val OUTGOING: Int = 2
}

internal object IndexFailureCodeValue {
    const val ROLE_REQUIRED: Int = 1
    const val PERMISSION_DENIED: Int = 2
    const val PROVIDER_UNAVAILABLE: Int = 3
    const val STORAGE_FULL: Int = 4
    const val STORAGE_UNAVAILABLE: Int = 5
    const val NON_ADVANCING_CURSOR: Int = 6
    const val UNKNOWN: Int = 7
}

internal fun ProviderKind.toIndexStorageCode(): Int = when (this) {
    ProviderKind.SMS -> ProviderKindCode.SMS
    ProviderKind.MMS -> ProviderKindCode.MMS
    else -> error("Only telephony provider kinds can be indexed")
}

internal fun Int.toIndexedProviderKind(): ProviderKind = when (this) {
    ProviderKindCode.SMS -> ProviderKind.SMS
    ProviderKindCode.MMS -> ProviderKind.MMS
    else -> error("Unknown indexed provider kind")
}

internal fun MessageDirection.toIndexStorageCode(): Int = when (this) {
    MessageDirection.INCOMING -> DirectionCode.INCOMING
    MessageDirection.OUTGOING -> DirectionCode.OUTGOING
}

internal fun Int.toIndexedMessageDirection(): MessageDirection = when (this) {
    DirectionCode.INCOMING -> MessageDirection.INCOMING
    DirectionCode.OUTGOING -> MessageDirection.OUTGOING
    else -> error("Unknown indexed message direction")
}

internal fun IndexFailureCode.toIndexStorageCode(): Int = when (this) {
    IndexFailureCode.ROLE_REQUIRED -> IndexFailureCodeValue.ROLE_REQUIRED
    IndexFailureCode.PERMISSION_DENIED -> IndexFailureCodeValue.PERMISSION_DENIED
    IndexFailureCode.PROVIDER_UNAVAILABLE -> IndexFailureCodeValue.PROVIDER_UNAVAILABLE
    IndexFailureCode.STORAGE_FULL -> IndexFailureCodeValue.STORAGE_FULL
    IndexFailureCode.STORAGE_UNAVAILABLE -> IndexFailureCodeValue.STORAGE_UNAVAILABLE
    IndexFailureCode.NON_ADVANCING_CURSOR -> IndexFailureCodeValue.NON_ADVANCING_CURSOR
    IndexFailureCode.UNKNOWN -> IndexFailureCodeValue.UNKNOWN
}

internal fun Int?.toIndexFailureCode(): IndexFailureCode = when (this) {
    IndexFailureCodeValue.ROLE_REQUIRED -> IndexFailureCode.ROLE_REQUIRED
    IndexFailureCodeValue.PERMISSION_DENIED -> IndexFailureCode.PERMISSION_DENIED
    IndexFailureCodeValue.PROVIDER_UNAVAILABLE -> IndexFailureCode.PROVIDER_UNAVAILABLE
    IndexFailureCodeValue.STORAGE_FULL -> IndexFailureCode.STORAGE_FULL
    IndexFailureCodeValue.STORAGE_UNAVAILABLE -> IndexFailureCode.STORAGE_UNAVAILABLE
    IndexFailureCodeValue.NON_ADVANCING_CURSOR -> IndexFailureCode.NON_ADVANCING_CURSOR
    else -> IndexFailureCode.UNKNOWN
}

internal fun Int.toIndexRunState(): IndexRunState = when (this) {
    GenerationStateCode.SCANNING -> IndexRunState.SCANNING
    GenerationStateCode.VERIFYING -> IndexRunState.VERIFYING
    GenerationStateCode.COMPLETE -> IndexRunState.COMPLETE
    GenerationStateCode.PAUSED -> IndexRunState.PAUSED
    GenerationStateCode.FAILED -> IndexRunState.FAILED
    else -> IndexRunState.FAILED
}
