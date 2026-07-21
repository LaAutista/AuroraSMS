// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.app.drafts.DraftRestorationToken
import org.aurorasms.app.drafts.SerializedDraftWriterLease
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
import org.aurorasms.app.voice.VoiceMemoController
import org.aurorasms.app.message.NotificationReminderController
import org.aurorasms.app.message.MessageSignaturePreferenceStore
import org.aurorasms.app.message.UnavailableMessageSignaturePreferenceStore
import org.aurorasms.core.index.MessageIndex
import org.aurorasms.core.index.conversation.ConversationRepository
import org.aurorasms.core.index.timeline.ThreadTimelineRepository
import org.aurorasms.core.state.DraftIdentity
import org.aurorasms.core.state.DraftAttachment
import org.aurorasms.core.state.DraftAttachmentRepository
import org.aurorasms.core.state.DraftId
import org.aurorasms.core.state.DraftRepositoryResult
import org.aurorasms.core.state.DraftRevision
import org.aurorasms.core.state.ConversationSubscriptionPreferenceRepository
import org.aurorasms.core.state.SpamSafetyRepository
import org.aurorasms.core.state.SpamClassification
import org.aurorasms.core.state.SpamSafetyDecision
import org.aurorasms.core.state.SpamSafetyRepositoryResult
import org.aurorasms.core.state.SpamSafetyRevision
import org.aurorasms.core.state.SpamSafetyScope
import org.aurorasms.core.state.SpamSafetySnapshot
import org.aurorasms.core.state.SpamSafetyStorageOperation
import org.aurorasms.core.model.ParticipantAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.aurorasms.core.telephony.ContactCache
import org.aurorasms.core.telephony.MmsAttachmentRepository
import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.feature.conversations.BoundedPreviewLoader
import org.aurorasms.feature.backup.AuroraBackupDocumentController
import org.aurorasms.feature.backup.AuroraBackupStartupRecoveryResult

/** Presentation dependencies used by [AuroraSmsRoot], isolated from the full app container. */
internal interface AuroraSmsRootServices {
    val conversationRepository: ConversationRepository
    val threadTimelineRepository: ThreadTimelineRepository
    val messageIndex: MessageIndex
    val contactCache: ContactCache
    val subscriptionRepository: SubscriptionRepository
    val conversationSubscriptionPreferenceRepository:
        ConversationSubscriptionPreferenceRepository
    val draftAttachmentRepository: DraftAttachmentRepository
        get() = EmptyRootDraftAttachmentRepository
    val spamSafetyRepository: SpamSafetyRepository
        get() = UnavailableRootSpamSafetyRepository
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
    val voiceMemoController: VoiceMemoController?
        get() = null
    val backupDocumentController: AuroraBackupDocumentController?
        get() = null
    val backupStartupRecovery: Flow<AuroraBackupStartupRecoveryResult?>
        get() = flowOf(null)

    fun countSmsSegments(body: String): Int? = null

    fun acquireDraftWriter(
        identity: DraftIdentity,
        restorationToken: DraftRestorationToken?,
        participantRouteOwner: String? = null,
    ): SerializedDraftWriterLease
}

private object EmptyRootDraftAttachmentRepository : DraftAttachmentRepository {
    override suspend fun read(
        draftId: DraftId,
    ): DraftRepositoryResult<List<DraftAttachment>> = DraftRepositoryResult.Success(emptyList())

    override suspend fun replace(
        draftId: DraftId,
        expectedRevision: DraftRevision,
        attachments: List<DraftAttachment>,
    ): DraftRepositoryResult<List<DraftAttachment>> = DraftRepositoryResult.Success(attachments)
}

private object UnavailableRootSpamSafetyRepository : SpamSafetyRepository {
    override val snapshots: Flow<SpamSafetySnapshot> = flowOf(SpamSafetySnapshot.Unavailable)

    override suspend fun read(
        scope: SpamSafetyScope,
    ): SpamSafetyRepositoryResult<SpamSafetyDecision> =
        SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.READ)

    override suspend fun set(
        scope: SpamSafetyScope,
        classification: SpamClassification,
        blocked: Boolean,
        expectedRevision: SpamSafetyRevision?,
        updatedTimestampMillis: Long,
    ): SpamSafetyRepositoryResult<SpamSafetyDecision?> =
        SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.WRITE)

    override suspend fun isSenderBlocked(
        sender: ParticipantAddress,
    ): SpamSafetyRepositoryResult<Boolean> =
        SpamSafetyRepositoryResult.StorageFailure(SpamSafetyStorageOperation.BLOCK_LOOKUP)
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
    override val draftAttachmentRepository: DraftAttachmentRepository
        get() = container.draftAttachmentRepository
    override val spamSafetyRepository: SpamSafetyRepository
        get() = container.spamSafetyRepository
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
    override val voiceMemoController: VoiceMemoController
        get() = container.voiceMemoController
    override val backupDocumentController: AuroraBackupDocumentController
        get() = container.backupDocumentController
    override val backupStartupRecovery: Flow<AuroraBackupStartupRecoveryResult?>
        get() = container.backupStartupRecovery

    override fun countSmsSegments(body: String): Int? = AndroidSmsSegmentCounter.count(body)

    override fun acquireDraftWriter(
        identity: DraftIdentity,
        restorationToken: DraftRestorationToken?,
        participantRouteOwner: String?,
    ): SerializedDraftWriterLease = container.acquireDraftWriter(
        identity,
        restorationToken,
        participantRouteOwner,
    )
}
