// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/** A normalized provider box whose persisted representation never depends on enum order. */
enum class MessageBox(
    val storageCode: String,
) {
    INBOX("inbox"),
    SENT("sent"),
    DRAFT("draft"),
    OUTBOX("outbox"),
    FAILED("failed"),
    QUEUED("queued"),
    UNKNOWN("unknown"),
    ;

    fun toStorageCode(): String = storageCode

    companion object {
        private val byStorageCode: Map<String, MessageBox> =
            entries.associateBy(MessageBox::storageCode).also { codes ->
                check(codes.size == entries.size) { "Message box storage codes must be unique" }
            }

        fun fromStorageCode(storageCode: String): MessageBox =
            requireNotNull(byStorageCode[storageCode]) { "Unknown message box storage code" }
    }
}
