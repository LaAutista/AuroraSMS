// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.DefaultSmsRoleState
import org.aurorasms.core.telephony.ProviderThreadResolution
import org.aurorasms.core.telephony.RecipientSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProviderThreadResolverTest {
    @Test
    fun preflightFailuresDoNotEnterAllocatorOrQuery() = runBlocking {
        assertPreflightFailure(
            role = FakeRoleState(available = false, held = false),
            permission = true,
            expected = ProviderThreadResolution.PlatformUnavailable,
        )
        assertPreflightFailure(
            role = FakeRoleState(available = true, held = false),
            permission = true,
            expected = ProviderThreadResolution.RoleRequired,
        )
        assertPreflightFailure(
            role = FakeRoleState(available = true, held = true),
            permission = false,
            expected = ProviderThreadResolution.PermissionDenied,
        )
    }

    @Test
    fun exactReorderedSemanticReadbackVerifiesOneProviderThread() = runBlocking {
        var allocatedRecipients: Set<String>? = null
        var queryCount = 0
        val resolver = resolver(
            allocator = ProviderThreadAllocator { recipients ->
                allocatedRecipients = recipients
                41L
            },
            query = ProviderThreadParticipantQuery { threadId, _ ->
                queryCount += 1
                ProviderThreadParticipantSnapshot(
                    providerThreadId = threadId,
                    addresses = listOf("Local@example.com", "+15550102020"),
                )
            },
        )

        val result = resolver.resolveExact(valid("+1 (555) 010-2020", "Local@Example.COM"))

        assertEquals(
            ProviderThreadResolution.Verified(ProviderThreadId(41), participantCount = 2),
            result,
        )
        assertEquals(setOf("+1 (555) 010-2020", "Local@Example.COM"), allocatedRecipients)
        assertEquals(1, queryCount)
    }

    @Test
    fun cleanAbsentOrMismatchedReadbackIsExactParticipantsUnverified() = runBlocking {
        val absent = resolver(
            query = ProviderThreadParticipantQuery { _, _ -> null },
        ).resolveExact(valid("+15550102020"))
        val mismatch = resolver(
            query = ProviderThreadParticipantQuery { threadId, _ ->
                ProviderThreadParticipantSnapshot(threadId, listOf("+15550102021"))
            },
        ).resolveExact(valid("+15550102020"))
        val wrongThread = resolver(
            query = ProviderThreadParticipantQuery { _, _ ->
                ProviderThreadParticipantSnapshot(ProviderThreadId(42), listOf("+15550102020"))
            },
        ).resolveExact(valid("+15550102020"))

        assertSame(ProviderThreadResolution.ExactParticipantsUnverified, absent)
        assertSame(ProviderThreadResolution.ExactParticipantsUnverified, mismatch)
        assertSame(ProviderThreadResolution.ExactParticipantsUnverified, wrongThread)
    }

    @Test
    fun postEntryExceptionAndCancellationBecomeMutationOutcomeUnknown() = runBlocking {
        val allocatorFailure = resolver(
            allocator = ProviderThreadAllocator { throw IllegalStateException("synthetic") },
        ).resolveExact(valid("+15550102020"))
        val queryFailure = resolver(
            query = ProviderThreadParticipantQuery { _, _ -> throw IllegalStateException("synthetic") },
        ).resolveExact(valid("+15550102020"))
        val queryCancellation = resolver(
            query = ProviderThreadParticipantQuery { _, _ -> throw CancellationException("synthetic") },
        ).resolveExact(valid("+15550102020"))

        assertSame(ProviderThreadResolution.MutationOutcomeUnknown, allocatorFailure)
        assertSame(ProviderThreadResolution.ExactParticipantsUnverified, queryFailure)
        assertSame(ProviderThreadResolution.MutationOutcomeUnknown, queryCancellation)
    }

    @Test
    fun authorityLossAfterAllocatorEntryBecomesMutationOutcomeUnknown() = runBlocking {
        val role = FakeRoleState(available = true, held = true)
        var queryCount = 0
        val resolver = resolver(
            role = role,
            allocator = ProviderThreadAllocator {
                role.held = false
                41L
            },
            query = ProviderThreadParticipantQuery { threadId, _ ->
                queryCount += 1
                ProviderThreadParticipantSnapshot(threadId, listOf("+15550102020"))
            },
        )

        val result = resolver.resolveExact(valid("+15550102020"))

        assertSame(ProviderThreadResolution.MutationOutcomeUnknown, result)
        assertEquals(0, queryCount)
    }

    @Test
    fun roleOrPermissionLossAfterParticipantReadbackBecomesMutationOutcomeUnknown() =
        runBlocking {
            val role = FakeRoleState(available = true, held = true)
            var roleReadbackCount = 0
            val roleLoss = resolver(
                role = role,
                query = ProviderThreadParticipantQuery { threadId, _ ->
                    roleReadbackCount += 1
                    role.held = false
                    ProviderThreadParticipantSnapshot(threadId, listOf("+15550102020"))
                },
            ).resolveExact(valid("+15550102020"))

            var permission = true
            var permissionReadbackCount = 0
            val permissionLoss = resolver(
                permissionChecker = ProviderThreadPermissionChecker { permission },
                query = ProviderThreadParticipantQuery { threadId, _ ->
                    permissionReadbackCount += 1
                    permission = false
                    ProviderThreadParticipantSnapshot(threadId, listOf("+15550102020"))
                },
            ).resolveExact(valid("+15550102020"))

            assertSame(ProviderThreadResolution.MutationOutcomeUnknown, roleLoss)
            assertEquals(1, roleReadbackCount)
            assertSame(ProviderThreadResolution.MutationOutcomeUnknown, permissionLoss)
            assertEquals(1, permissionReadbackCount)
        }

    @Test
    fun cancellationBeforeBoundaryEntryPropagatesWithoutAllocatorCall() = runBlocking {
        var allocatorCalls = 0
        val resolver = resolver(
            allocator = ProviderThreadAllocator {
                allocatorCalls += 1
                41L
            },
        )
        val cancelledJob = Job().also(Job::cancel)
        var cancelled = false

        try {
            withContext(cancelledJob) {
                resolver.resolveExact(valid("+15550102020"))
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(0, allocatorCalls)
    }

    @Test
    fun callerCancellationAfterAllocatorEntryCancelsQuerySignalAndClassifiesUnknown() = runBlocking {
        val queryStarted = CompletableDeferred<Unit>()
        val cancellationObserved = CountDownLatch(1)
        var result: ProviderThreadResolution? = null
        val resolver = resolver(
            query = ProviderThreadParticipantQuery { _, signal ->
                signal.setOnCancelListener { cancellationObserved.countDown() }
                queryStarted.complete(Unit)
                cancellationObserved.await(CANCELLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                throw CancellationException("synthetic provider cancellation")
            },
        )
        val caller = launch(Dispatchers.Default) {
            result = resolver.resolveExact(valid("+15550102020"))
        }
        queryStarted.await()

        caller.cancelAndJoin()

        assertEquals(0L, cancellationObserved.count)
        assertSame(ProviderThreadResolution.MutationOutcomeUnknown, result)
    }

    @Test
    fun participantProjectionRejectsMalformedDuplicateCollapsedAndOversizedReadback() {
        val one = valid("+15550102020")
        val two = valid("+15550102020", "+15550102021")

        assertTrue(
            !providerParticipantsExactlyMatch(
                one,
                ProviderThreadParticipantSnapshot(ProviderThreadId(41), listOf("+")),
            ),
        )
        assertTrue(
            !providerParticipantsExactlyMatch(
                two,
                ProviderThreadParticipantSnapshot(
                    ProviderThreadId(41),
                    listOf("+15550102020", "+1 (555) 010-2020"),
                ),
            ),
        )
        assertTrue(
            !providerParticipantsExactlyMatch(
                one,
                ProviderThreadParticipantSnapshot(
                    ProviderThreadId(41),
                    List(RecipientSet.MAX_RECIPIENTS + 1) { "+1555${it.toString().padStart(7, '0')}" },
                ),
            ),
        )
    }

    @Test
    fun canonicalIdParserIsBoundedStrictPositiveAndIncreasing() {
        assertEquals(listOf(3L, 9L), parseCanonicalIds("3 9"))
        assertEquals(null, parseCanonicalIds("3  9"))
        assertEquals(null, parseCanonicalIds("9 3"))
        assertEquals(null, parseCanonicalIds("9 9"))
        assertEquals(null, parseCanonicalIds("0"))
        assertEquals(null, parseCanonicalIds(" 9"))
        assertEquals(
            null,
            parseCanonicalIds(List(RecipientSet.MAX_RECIPIENTS + 1) { (it + 1).toString() }.joinToString(" ")),
        )
    }

    private suspend fun assertPreflightFailure(
        role: FakeRoleState,
        permission: Boolean,
        expected: ProviderThreadResolution,
    ) {
        var allocatorCalls = 0
        var queryCalls = 0
        val resolver = resolver(
            role = role,
            permission = permission,
            allocator = ProviderThreadAllocator {
                allocatorCalls += 1
                41L
            },
            query = ProviderThreadParticipantQuery { threadId, _ ->
                queryCalls += 1
                ProviderThreadParticipantSnapshot(threadId, listOf("+15550102020"))
            },
        )

        assertSame(expected, resolver.resolveExact(valid("+15550102020")))
        assertEquals(0, allocatorCalls)
        assertEquals(0, queryCalls)
    }

    private fun resolver(
        role: FakeRoleState = FakeRoleState(available = true, held = true),
        permission: Boolean = true,
        permissionChecker: ProviderThreadPermissionChecker =
            ProviderThreadPermissionChecker { permission },
        allocator: ProviderThreadAllocator = ProviderThreadAllocator { 41L },
        query: ProviderThreadParticipantQuery = ProviderThreadParticipantQuery { threadId, _ ->
            ProviderThreadParticipantSnapshot(threadId, listOf("+15550102020"))
        },
    ): AndroidProviderThreadResolver = AndroidProviderThreadResolver(
        roleState = role,
        permissionChecker = permissionChecker,
        allocator = allocator,
        participantQuery = query,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun valid(vararg addresses: String): RecipientSet =
        (RecipientSet.from(addresses.map(::ParticipantAddress)) as RecipientSet.CreationResult.Valid)
            .recipients

    private class FakeRoleState(
        private val available: Boolean,
        var held: Boolean,
    ) : DefaultSmsRoleState {
        override fun isRoleAvailable(): Boolean = available

        override fun isRoleHeld(): Boolean = held
    }

    private companion object {
        const val CANCELLATION_TIMEOUT_SECONDS: Long = 5L
    }
}
