// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.app.drafts.DraftEditorContent
import org.aurorasms.app.drafts.SerializedDraftWriter
import org.aurorasms.app.appearance.wallpaper.WallpaperController
import org.aurorasms.core.index.MessageIndex
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.index.timeline.ThreadTimelineRepository
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.telephony.ContactCache
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.feature.conversations.BoundedPreviewLoader

/** Presentation dependencies used by [AuroraSmsRoot], isolated from the full app container. */
internal interface AuroraSmsRootServices {
    val conversationRepository: ConversationRepository
    val threadTimelineRepository: ThreadTimelineRepository
    val messageIndex: MessageIndex
    val contactCache: ContactCache
    val subscriptionRepository: SubscriptionRepository
    val mmsAttachmentRepository: MmsAttachmentRepository
    val previewLoader: BoundedPreviewLoader
    val wallpaperController: WallpaperController?
        get() = null

    fun createDraftWriter(
        identity: DraftIdentity,
        restoredUnacknowledged: DraftEditorContent?,
    ): SerializedDraftWriter

    fun releaseDraftWriter(writer: SerializedDraftWriter)
}

/** Production adapter; tests can provide bounded synthetic services without constructing [AppContainer]. */
internal class AppContainerAuroraSmsRootServices(
    private val container: AppContainer,
) : AuroraSmsRootServices {
    override val conversationRepository: ConversationRepository
        get() = container.conversationRepository
    override val threadTimelineRepository: ThreadTimelineRepository
        get() = container.threadTimelineRepository
    override val messageIndex: MessageIndex
        get() = container.messageIndex
    override val contactCache: ContactCache
        get() = container.contactCache
    override val subscriptionRepository: SubscriptionRepository
        get() = container.subscriptionRepository
    override val mmsAttachmentRepository: MmsAttachmentRepository
        get() = container.mmsAttachmentRepository
    override val previewLoader: BoundedPreviewLoader
        get() = container.previewLoader
    override val wallpaperController: WallpaperController
        get() = container.wallpaperController

    override fun createDraftWriter(
        identity: DraftIdentity,
        restoredUnacknowledged: DraftEditorContent?,
    ): SerializedDraftWriter = container.createDraftWriter(identity, restoredUnacknowledged)

    override fun releaseDraftWriter(writer: SerializedDraftWriter) {
        container.releaseDraftWriter(writer)
    }
}
