// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index

import org.aurorasms.core.index.storage.IndexedMessageEntity
import org.aurorasms.core.index.storage.StoredAnchorWindow
import org.aurorasms.core.model.MessageBox
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.MessageStatus
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertFalse
import org.junit.Test

class IndexPrivacyTest {
    @Test
    fun searchAndStorageModelsDoNotRenderPrivateContentOrProviderIds() {
        val privateQuery = "private search phrase"
        val privateBody = "private message body"
        val privateAddress = "+15550000042"
        val providerId = 9_876_543L
        val request = SearchRequest(privateQuery, ProviderThreadId(42L))
        val hit = SearchHit(
            localRowId = 1L,
            providerId = ProviderMessageId(ProviderKind.SMS, providerId),
            providerThreadId = ProviderThreadId(42L),
            timestampMillis = 1L,
            sentTimestampMillis = null,
            direction = MessageDirection.INCOMING,
            box = MessageBox.INBOX,
            status = MessageStatus.COMPLETE,
            subscriptionId = null,
            senderAddress = privateAddress,
            body = privateBody,
            subject = null,
            attachmentCount = 0,
            attachmentTypeSummary = "",
            read = false,
            seen = false,
            locked = false,
        )
        val entity = IndexedMessageEntity(
            providerKind = 1,
            providerId = providerId,
            providerThreadId = 42L,
            timestampMillis = 1L,
            sentTimestampMillis = null,
            direction = 1,
            messageBox = "inbox",
            messageStatus = "complete",
            subscriptionId = null,
            senderAddress = privateAddress,
            body = privateBody,
            subject = null,
            attachmentCount = 0,
            attachmentTypeSummary = "",
            attachmentTotalBytes = null,
            isRead = false,
            isSeen = false,
            isLocked = false,
            syncFingerprint = "a".repeat(64),
            searchableText = privateAddress,
            lastSeenGeneration = 1L,
        )
        val storedWindow = StoredAnchorWindow(
            anchor = entity,
            newer = listOf(entity),
            older = listOf(entity),
            reResolvedAfterRebuild = true,
        )

        listOf(request.toString(), hit.toString(), entity.toString(), storedWindow.toString()).forEach { rendered ->
            assertFalse(rendered.contains(privateQuery))
            assertFalse(rendered.contains(privateBody))
            assertFalse(rendered.contains(privateAddress))
            assertFalse(rendered.contains(providerId.toString()))
        }
    }
}
