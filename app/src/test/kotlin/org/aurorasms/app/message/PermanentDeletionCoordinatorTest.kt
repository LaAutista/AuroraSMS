// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.MessageSyncFingerprint
import org.aurorasms.core.model.ProviderKind
import org.aurorasms.core.model.ProviderMessageId
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.state.PermanentDeletionId
import org.aurorasms.core.state.PermanentDeletionOperation
import org.aurorasms.core.state.PermanentDeletionPhase
import org.aurorasms.core.state.PermanentDeletionRepository
import org.aurorasms.core.state.PermanentDeletionRequest
import org.aurorasms.core.state.PermanentDeletionResult
import org.aurorasms.core.state.PermanentDeletionReviewReason
import org.aurorasms.core.state.PermanentDeletionRevision
import org.aurorasms.core.state.PermanentDeletionTarget
import org.aurorasms.core.telephony.PermanentDeletionProvider
import org.aurorasms.core.telephony.ProviderAccessResult
import org.aurorasms.core.telephony.ProviderDeletionCommitOutcome
import org.aurorasms.core.telephony.ProviderMessageDeletionTarget
import org.aurorasms.core.telephony.ProviderThreadDeletionSnapshot
import org.aurorasms.core.testing.FakeRoleState
import org.junit.Assert.assertEquals
import org.junit.Test

class PermanentDeletionCoordinatorTest {
    @Test
    fun messageDeletionHasDurableUndoAndNeverDeletesAfterUndo() = runTest {
        val fixture = fixture(backgroundScope)

        assertEquals(PermanentDeletionAttempt.ACCEPTED, fixture.coordinator.request(messageCommand()))
        assertEquals(listOf(DUE), fixture.alarms.armedDueTimes)
        assertEquals(true, fixture.coordinator.undo(THREAD))

        fixture.clock.advanceToDue()
        fixture.coordinator.handleAlarm(PermanentDeletionId(1L))
        assertEquals(0, fixture.provider.messageDeleteCount)
        assertEquals(null, fixture.repository.operation)
    }

    @Test
    fun dueExactTargetDeletesOnceAndDuplicateAlarmIsHarmless() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.coordinator.request(messageCommand())
        fixture.clock.advanceToDue()

        fixture.coordinator.handleAlarm(PermanentDeletionId(1L))
        fixture.coordinator.handleAlarm(PermanentDeletionId(1L))

        assertEquals(1, fixture.provider.messageDeleteCount)
        assertEquals(1, fixture.providerChangedCount())
        assertEquals(null, fixture.repository.operation)
    }

    @Test
    fun changedTargetAndLostRoleFailClosedWithoutProviderMutation() = runTest {
        val changed = fixture(backgroundScope)
        changed.coordinator.request(messageCommand())
        changed.provider.message = MESSAGE_TARGET.copy(syncFingerprint = fingerprint(9))
        changed.clock.advanceToDue()
        changed.coordinator.handleAlarm(PermanentDeletionId(1L))
        assertEquals(PermanentDeletionReviewReason.TARGET_CHANGED, changed.repository.operation?.reviewReason)
        assertEquals(0, changed.provider.messageDeleteCount)

        val roleLost = fixture(backgroundScope)
        roleLost.coordinator.request(messageCommand())
        roleLost.role.held = false
        roleLost.clock.advanceToDue()
        roleLost.coordinator.handleAlarm(PermanentDeletionId(1L))
        assertEquals(
            PermanentDeletionReviewReason.PRECONDITION_FAILED,
            roleLost.repository.operation?.reviewReason,
        )
        assertEquals(0, roleLost.provider.messageDeleteCount)
    }

    @Test
    fun interruptedCommitIsInspectedAndNeverBlindlyReplayed() = runTest {
        val stillPresent = fixture(backgroundScope)
        stillPresent.repository.seed(operation(PermanentDeletionPhase.COMMITTING))
        stillPresent.coordinator.recover(PermanentDeletionRecoveryReason.APP_STARTUP)
        assertEquals(
            PermanentDeletionReviewReason.INTERRUPTED_DURING_COMMIT,
            stillPresent.repository.operation?.reviewReason,
        )
        assertEquals(0, stillPresent.provider.messageDeleteCount)

        stillPresent.coordinator.recover(PermanentDeletionRecoveryReason.APP_STARTUP)
        assertEquals(null, stillPresent.repository.operation)
        assertEquals(0, stillPresent.provider.messageDeleteCount)

        val absent = fixture(backgroundScope)
        absent.repository.seed(operation(PermanentDeletionPhase.COMMITTING))
        absent.provider.message = null
        absent.coordinator.recover(PermanentDeletionRecoveryReason.APP_STARTUP)
        assertEquals(null, absent.repository.operation)
        assertEquals(1, absent.providerChangedCount())
        assertEquals(0, absent.provider.messageDeleteCount)
    }

    @Test
    fun threadRequestSnapshotsProviderAndAlarmFailureBecomesSafeReview() = runTest {
        val fixture = fixture(backgroundScope, armResult = false)

        assertEquals(
            PermanentDeletionAttempt.ACCEPTED,
            fixture.coordinator.request(PermanentDeletionCommand.Thread(THREAD)),
        )
        assertEquals(
            PermanentDeletionReviewReason.ARMING_FAILED,
            fixture.repository.operation?.reviewReason,
        )
        assertEquals(0, fixture.provider.threadDeleteCount)
        assertEquals(THREAD_SNAPSHOT, fixture.repository.operation?.target?.toProviderSnapshotForTest())
    }

    private fun fixture(scope: CoroutineScope, armResult: Boolean = true): Fixture {
        val repository = RecordingDeletionRepository()
        val provider = RecordingDeletionProvider()
        val alarms = RecordingDeletionAlarmDriver(armResult)
        val role = FakeRoleState()
        val clock = MutableDeletionClock(START, 500L)
        var providerChanged = 0
        return Fixture(
            coordinator = PermanentDeletionCoordinator(
                applicationScope = scope,
                roleState = role,
                repository = repository,
                provider = provider,
                alarms = alarms,
                onProviderChanged = { providerChanged += 1 },
                clock = clock,
            ),
            repository = repository,
            provider = provider,
            alarms = alarms,
            role = role,
            clock = clock,
            providerChangedCount = { providerChanged },
        )
    }

    private fun messageCommand() = PermanentDeletionCommand.Message(
        providerMessageId = MESSAGE_ID,
        providerThreadId = THREAD,
        syncFingerprint = FINGERPRINT,
    )

    private fun operation(phase: PermanentDeletionPhase): PermanentDeletionOperation =
        PermanentDeletionOperation(
            id = PermanentDeletionId(1L),
            target = PermanentDeletionTarget.Message(MESSAGE_ID, THREAD, FINGERPRINT),
            dueTimestampMillis = DUE,
            phase = phase,
            reviewReason = null,
            armedWallTimestampMillis = START,
            armedElapsedRealtimeMillis = 500L,
            createdTimestampMillis = START,
            updatedTimestampMillis = if (phase == PermanentDeletionPhase.PENDING) START else START + 1L,
        )

    private data class Fixture(
        val coordinator: PermanentDeletionCoordinator,
        val repository: RecordingDeletionRepository,
        val provider: RecordingDeletionProvider,
        val alarms: RecordingDeletionAlarmDriver,
        val role: FakeRoleState,
        val clock: MutableDeletionClock,
        val providerChangedCount: () -> Int,
    )

    companion object {
        private const val START = 1_000L
        private const val DUE = 6_000L
        private val THREAD = ProviderThreadId(7L)
        private val MESSAGE_ID = ProviderMessageId(ProviderKind.SMS, 8L)
        private val FINGERPRINT = fingerprint(1)
        internal val MESSAGE_TARGET = ProviderMessageDeletionTarget(MESSAGE_ID, THREAD, FINGERPRINT)
        internal val THREAD_SNAPSHOT = ProviderThreadDeletionSnapshot(
            providerThreadId = THREAD,
            smsCount = 1L,
            latestSmsId = MESSAGE_ID,
            mmsCount = 1L,
            latestMmsId = ProviderMessageId(ProviderKind.MMS, 9L),
        )

        private fun fingerprint(value: Int) =
            MessageSyncFingerprint.fromSha256(ByteArray(32) { value.toByte() })
    }
}

private class MutableDeletionClock(var wall: Long, var elapsed: Long) : PermanentDeletionClock {
    override fun wallMillis() = wall
    override fun elapsedMillis() = elapsed
    fun advanceToDue() {
        elapsed += 5_000L
        wall += 5_000L
    }
}

private class RecordingDeletionAlarmDriver(private val result: Boolean) : PermanentDeletionAlarmDriver {
    val armedDueTimes = mutableListOf<Long>()
    override fun arm(id: PermanentDeletionId, dueTimestampMillis: Long): Boolean {
        armedDueTimes += dueTimestampMillis
        return result
    }
    override fun cancel(id: PermanentDeletionId) = Unit
}

private class RecordingDeletionProvider : PermanentDeletionProvider {
    var message: ProviderMessageDeletionTarget? = PermanentDeletionCoordinatorTest.MESSAGE_TARGET
    var thread: ProviderThreadDeletionSnapshot = PermanentDeletionCoordinatorTest.THREAD_SNAPSHOT
    var messageDeleteCount = 0
    var threadDeleteCount = 0

    override suspend fun inspectMessage(providerMessageId: ProviderMessageId) =
        ProviderAccessResult.Success(message?.takeIf { it.providerMessageId == providerMessageId })

    override suspend fun inspectThread(providerThreadId: ProviderThreadId) =
        ProviderAccessResult.Success(
            thread.takeIf { it.providerThreadId == providerThreadId }
                ?: ProviderThreadDeletionSnapshot(providerThreadId, 0L, null, 0L, null),
        )

    override suspend fun deleteMessage(expected: ProviderMessageDeletionTarget):
        ProviderAccessResult<ProviderDeletionCommitOutcome> {
        messageDeleteCount += 1
        message = null
        return ProviderAccessResult.Success(ProviderDeletionCommitOutcome.DELETED)
    }

    override suspend fun deleteThread(expected: ProviderThreadDeletionSnapshot):
        ProviderAccessResult<ProviderDeletionCommitOutcome> {
        threadDeleteCount += 1
        thread = ProviderThreadDeletionSnapshot(expected.providerThreadId, 0L, null, 0L, null)
        return ProviderAccessResult.Success(ProviderDeletionCommitOutcome.DELETED)
    }
}

private class RecordingDeletionRepository : PermanentDeletionRepository {
    private val observed = MutableStateFlow<PermanentDeletionOperation?>(null)
    var operation: PermanentDeletionOperation? = null
        private set

    fun seed(value: PermanentDeletionOperation) = publish(value)

    override suspend fun create(request: PermanentDeletionRequest):
        PermanentDeletionResult<PermanentDeletionOperation> {
        if (operation != null) return PermanentDeletionResult.StaleWrite
        val created = PermanentDeletionOperation(
            id = PermanentDeletionId(1L),
            target = request.target,
            dueTimestampMillis = request.dueTimestampMillis,
            phase = PermanentDeletionPhase.PENDING,
            reviewReason = null,
            armedWallTimestampMillis = request.createdTimestampMillis,
            armedElapsedRealtimeMillis = request.armedElapsedRealtimeMillis,
            createdTimestampMillis = request.createdTimestampMillis,
            updatedTimestampMillis = request.createdTimestampMillis,
        )
        publish(created)
        return PermanentDeletionResult.Success(created)
    }

    override suspend fun read(id: PermanentDeletionId) = result(operation?.takeIf { it.id == id })
    override suspend fun readByThread(providerThreadId: ProviderThreadId) =
        result(operation?.takeIf { it.target.providerThreadId == providerThreadId })
    override fun observeByThread(providerThreadId: ProviderThreadId):
        Flow<PermanentDeletionResult<PermanentDeletionOperation?>> = observed.map {
        PermanentDeletionResult.Success(it?.takeIf { item -> item.target.providerThreadId == providerThreadId })
    }
    override suspend fun recoverySnapshot() = PermanentDeletionResult.Success(listOfNotNull(operation))
    override suspend fun validateLocalPreconditions(operation: PermanentDeletionOperation) =
        PermanentDeletionResult.Success(true)

    override suspend fun markCommitting(
        id: PermanentDeletionId,
        expectedRevision: PermanentDeletionRevision,
        updatedTimestampMillis: Long,
    ) = update(id, expectedRevision) {
        it.copy(phase = PermanentDeletionPhase.COMMITTING, updatedTimestampMillis = updatedTimestampMillis)
    }

    override suspend fun markReviewRequired(
        id: PermanentDeletionId,
        expectedRevision: PermanentDeletionRevision,
        reason: PermanentDeletionReviewReason,
        updatedTimestampMillis: Long,
    ) = update(id, expectedRevision) {
        it.copy(
            phase = PermanentDeletionPhase.REVIEW_REQUIRED,
            reviewReason = reason,
            updatedTimestampMillis = updatedTimestampMillis,
        )
    }

    override suspend fun removeUndoable(
        id: PermanentDeletionId,
        expectedRevision: PermanentDeletionRevision,
    ) = remove(id, expectedRevision, allowCommitted = false)

    override suspend fun removeCommitted(
        id: PermanentDeletionId,
        expectedRevision: PermanentDeletionRevision,
    ) = remove(id, expectedRevision, allowCommitted = true)

    private fun remove(
        id: PermanentDeletionId,
        revision: PermanentDeletionRevision,
        allowCommitted: Boolean,
    ): PermanentDeletionResult<Unit> {
        val current = operation ?: return PermanentDeletionResult.NotFound
        if (current.id != id || current.revision != revision) return PermanentDeletionResult.StaleWrite
        if (!allowCommitted && current.phase == PermanentDeletionPhase.COMMITTING) {
            return PermanentDeletionResult.PhaseMismatch
        }
        publish(null)
        return PermanentDeletionResult.Success(Unit)
    }

    private fun update(
        id: PermanentDeletionId,
        revision: PermanentDeletionRevision,
        transform: (PermanentDeletionOperation) -> PermanentDeletionOperation,
    ): PermanentDeletionResult<PermanentDeletionOperation> {
        val current = operation ?: return PermanentDeletionResult.NotFound
        if (current.id != id || current.revision != revision) return PermanentDeletionResult.StaleWrite
        val updated = transform(current)
        publish(updated)
        return PermanentDeletionResult.Success(updated)
    }

    private fun result(value: PermanentDeletionOperation?):
        PermanentDeletionResult<PermanentDeletionOperation> =
        value?.let { PermanentDeletionResult.Success(it) } ?: PermanentDeletionResult.NotFound

    private fun publish(value: PermanentDeletionOperation?) {
        operation = value
        observed.value = value
    }
}

private fun PermanentDeletionTarget.toProviderSnapshotForTest(): ProviderThreadDeletionSnapshot? =
    (this as? PermanentDeletionTarget.Thread)?.let {
        ProviderThreadDeletionSnapshot(
            providerThreadId = it.providerThreadId,
            smsCount = it.smsCount,
            latestSmsId = it.latestSmsId,
            mmsCount = it.mmsCount,
            latestMmsId = it.latestMmsId,
        )
    }
