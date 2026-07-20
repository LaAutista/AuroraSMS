// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.app.drafts.DraftRestorationToken
import org.aurorasms.app.drafts.SerializedDraftWriter
import org.aurorasms.app.appearance.wallpaper.WallpaperController
import org.aurorasms.app.message.AndroidSmsSegmentCounter
import org.aurorasms.app.message.ThreadSmsSendController
import org.aurorasms.app.message.UnavailableThreadSmsSendController
import org.aurorasms.app.message.ScheduledSmsController
import org.aurorasms.app.message.UnavailableScheduledSmsController
import org.aurorasms.app.message.SendDelayController
import org.aurorasms.app.message.SendDelayPreferenceStore
import org.aurorasms.app.message.UnavailableSendDelayController
import org.aurorasms.app.message.UnavailableSendDelayPreferenceStore
import org.aurorasms.app.message.PermanentDeletionController
import org.aurorasms.app.message.UnavailablePermanentDeletionController
import org.aurorasms.app.message.NotificationReminderController
import org.aurorasms.app.message.MessageSignaturePreferenceStore
import org.aurorasms.app.message.UnavailableMessageSignaturePreferenceStore
import org.aurorasms.core.index.MessageIndex
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.index.timeline.ThreadTimelineRepository
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.ConversationSubscriptionPreferenceRepository
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
    val conversationSubscriptionPreferenceRepository:
        ConversationSubscriptionPreferenceRepository
    val mmsAttachmentRepository: MmsAttachmentRepository
    val previewLoader: BoundedPreviewLoader
    val wallpaperController: WallpaperController?
        get() = null
    val threadSmsSendController: ThreadSmsSendController
        get() = UnavailableThreadSmsSendController
    val scheduledSmsController: ScheduledSmsController
        get() = UnavailableScheduledSmsController
    val sendDelayController: SendDelayController
        get() = UnavailableSendDelayController
    val sendDelayPreferenceStore: SendDelayPreferenceStore
        get() = UnavailableSendDelayPreferenceStore
    val permanentDeletionController: PermanentDeletionController
        get() = UnavailablePermanentDeletionController
    val notificationReminderController: NotificationReminderController?
        get() = null
    val messageSignaturePreferenceStore: MessageSignaturePreferenceStore
        get() = UnavailableMessageSignaturePreferenceStore

    fun countSmsSegments(body: String): Int? = null

    fun createDraftWriter(
        identity: DraftIdentity,
        restorationToken: DraftRestorationToken?,
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
    override val conversationSubscriptionPreferenceRepository:
        ConversationSubscriptionPreferenceRepository
        get() = container.conversationSubscriptionPreferenceRepository
    override val mmsAttachmentRepository: MmsAttachmentRepository
        get() = container.mmsAttachmentRepository
    override val previewLoader: BoundedPreviewLoader
        get() = container.previewLoader
    override val wallpaperController: WallpaperController
        get() = container.wallpaperController
    override val threadSmsSendController: ThreadSmsSendController
        get() = container.threadSmsSendController
    override val scheduledSmsController: ScheduledSmsController
        get() = container.scheduledSmsController
    override val sendDelayController: SendDelayController
        get() = container.sendDelayController
    override val sendDelayPreferenceStore: SendDelayPreferenceStore
        get() = container.sendDelayPreferenceStore
    override val permanentDeletionController: PermanentDeletionController
        get() = container.permanentDeletionController
    override val notificationReminderController: NotificationReminderController
        get() = container.notificationReminderController
    override val messageSignaturePreferenceStore: MessageSignaturePreferenceStore
        get() = container.messageSignaturePreferenceStore

    override fun countSmsSegments(body: String): Int? = AndroidSmsSegmentCounter.count(body)

    override fun createDraftWriter(
        identity: DraftIdentity,
        restorationToken: DraftRestorationToken?,
    ): SerializedDraftWriter = container.createDraftWriter(identity, restorationToken)

    override fun releaseDraftWriter(writer: SerializedDraftWriter) {
        container.releaseDraftWriter(writer)
    }
}
