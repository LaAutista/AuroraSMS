// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageDirection
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.asConversationId
import org.aurorasms.core.notifications.MessageNotifier
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderStoredMessage
import org.aurorasms.core.telephony.SmsProviderDataSource

internal class NotificationReminderCoordinator(
    private val roleState: DefaultSmsRoleState,
    private val smsProvider: SmsProviderDataSource,
    private val notifier: MessageNotifier,
    private val preferences: NotificationReminderPreferenceStore,
    private val store: NotificationReminderStore,
    private val alarms: NotificationReminderAlarmDriver,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : NotificationReminderController {
    private val mutex = Mutex()

    override val delayMinutes: StateFlow<Int> = preferences.delayMinutes

    override suspend fun setDelayMinutes(value: Int): Boolean = mutex.withLock {
        if (value !in SharedPreferencesNotificationReminderPreferenceStore.ALLOWED_DELAY_MINUTES) {
            return@withLock false
        }
        if (!preferences.setDelayMinutes(value)) return@withLock false
        // A changed preference applies to future arrivals only. Existing
        // ownership is removed so stale timing cannot surprise the user.
        clearLocked()
        true
    }

    override suspend fun schedule(stored: ProviderStoredMessage): Boolean = mutex.withLock {
        val delay = preferences.delayMinutes.value
        if (
            delay == 0 ||
            stored.providerId.kind != ProviderKind.SMS ||
            !roleState.isRoleHeld()
        ) return@withLock false
        val now = nowMillis()
        val due = runCatching { Math.addExact(now, delay * 60_000L) }.getOrNull()
            ?: return@withLock false
        val creation = store.create(
            messageId = stored.providerId,
            conversationId = stored.conversationId,
            dueTimestampMillis = due,
            createdTimestampMillis = now,
        ) ?: return@withLock false
        creation.displaced.forEach { alarms.cancel(it.id) }
        if (!roleState.isRoleHeld()) {
            store.remove(creation.owner.id)
            return@withLock false
        }
        if (!alarms.arm(creation.owner.id, due)) {
            store.remove(creation.owner.id)
            return@withLock false
        }
        true
    }

    override suspend fun handleAlarm(id: NotificationReminderId): Unit = mutex.withLock {
        val owner = store.read(id) ?: run {
            alarms.cancel(id)
            return@withLock
        }
        val now = nowMillis()
        if (now < owner.dueTimestampMillis) {
            if (!alarms.arm(id, owner.dueTimestampMillis)) store.remove(id)
            return@withLock
        }
        if (!roleState.isRoleHeld() || now - owner.dueTimestampMillis > MAXIMUM_LATENESS_MILLIS) {
            store.remove(id)
            alarms.cancel(id)
            return@withLock
        }
        val row = when (val result = smsProvider.readExact(owner.messageId)) {
            is ProviderAccessResult.Success -> result.value
            else -> {
                // Provider unavailability proves neither read state nor row
                // absence. Consume only this one-shot reminder; preserve the
                // original incoming notification until an authoritative read.
                if (store.remove(id)) alarms.cancel(id)
                return@withLock
            }
        }
        if (!row.isEligibleFor(owner)) {
            if (store.remove(id)) {
                alarms.cancel(id)
                notifier.cancelIncomingConversation(
                    owner.conversationId,
                    owner.messageId.asMessageId(),
                )
            }
            return@withLock
        }
        // Removing durable ownership before notifying makes a crash fail
        // closed: a reminder is attempted at most once.
        if (!store.remove(id)) return@withLock
        alarms.cancel(id)
        try {
            notifier.notifyUnreadReminder(owner.conversationId, owner.messageId.asMessageId())
        } catch (_: RuntimeException) {
            // Ownership was already consumed; a platform notification failure
            // cannot turn this into a repeating reminder.
        }
    }

    override suspend fun cancelConversation(conversationId: ConversationId): Unit = mutex.withLock {
        val removed = store.removeConversation(conversationId) ?: return@withLock
        removed.forEach { owner ->
            alarms.cancel(owner.id)
            notifier.cancelIncomingConversation(conversationId, owner.messageId.asMessageId())
        }
    }

    override suspend fun cancelGenerationOwner(
        conversationId: ConversationId,
        messageId: org.aurorasms.core.model.ProviderMessageId,
    ): Unit = mutex.withLock {
        val owner = store.all()
            ?.singleOrNull { candidate ->
                candidate.conversationId == conversationId && candidate.messageId == messageId
            }
            ?: return@withLock
        if (store.remove(owner.id)) alarms.cancel(owner.id)
    }

    override suspend fun recover(reason: NotificationReminderRecoveryReason): Unit = mutex.withLock {
        if (
            reason == NotificationReminderRecoveryReason.BOOT_COMPLETED ||
            reason == NotificationReminderRecoveryReason.WALL_CLOCK_CHANGED ||
            reason == NotificationReminderRecoveryReason.TIMEZONE_CHANGED ||
            !roleState.isRoleHeld()
        ) {
            clearLocked()
            return@withLock
        }
        val owners = store.all() ?: return@withLock
        owners.forEach { owner ->
            val now = nowMillis()
            if (owner.dueTimestampMillis <= now) {
                handleRecoveredDueLocked(owner, now)
            } else {
                val row = (smsProvider.readExact(owner.messageId) as? ProviderAccessResult.Success)
                    ?.value
                if (!row.isEligibleFor(owner) || !alarms.arm(owner.id, owner.dueTimestampMillis)) {
                    store.remove(owner.id)
                    alarms.cancel(owner.id)
                }
            }
        }
    }

    override suspend fun fence(): Unit = mutex.withLock { clearLocked() }

    private fun handleRecoveredDueLocked(owner: NotificationReminderOwner, now: Long) {
        val remove = now - owner.dueTimestampMillis > MAXIMUM_LATENESS_MILLIS
        if (remove) {
            store.remove(owner.id)
            alarms.cancel(owner.id)
        } else if (!alarms.arm(owner.id, now + RECOVERY_RECHECK_DELAY_MILLIS)) {
            store.remove(owner.id)
        }
    }

    private fun clearLocked() {
        val owners = store.clear() ?: return
        owners.forEach { alarms.cancel(it.id) }
    }

    private fun org.aurorasms.core.telephony.SmsProviderMessage?.isEligibleFor(
        owner: NotificationReminderOwner,
    ): Boolean = this != null &&
        id == owner.messageId &&
        providerThreadId.asConversationId() == owner.conversationId &&
        direction == MessageDirection.INCOMING &&
        !read

    private companion object {
        const val MAXIMUM_LATENESS_MILLIS = 24 * 60 * 60 * 1_000L
        const val RECOVERY_RECHECK_DELAY_MILLIS = 60_000L
    }
}
