// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/** A normalized provider status whose persisted representation never depends on enum order. */
enum class MessageStatus(
    val storageCode: String,
) {
    NONE("none"),
    PENDING("pending"),
    COMPLETE("complete"),
    FAILED("failed"),
    UNKNOWN("unknown"),
    ;

    fun toStorageCode(): String = storageCode

    companion object {
        private val byStorageCode: Map<String, MessageStatus> =
            entries.associateBy(MessageStatus::storageCode).also { codes ->
                check(codes.size == entries.size) { "Message status storage codes must be unique" }
            }

        fun fromStorageCode(storageCode: String): MessageStatus =
            requireNotNull(byStorageCode[storageCode]) { "Unknown message status storage code" }
    }
}
