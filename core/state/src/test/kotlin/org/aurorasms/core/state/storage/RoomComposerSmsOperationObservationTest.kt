// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.state.storage

import android.database.sqlite.SQLiteException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.aurorasms.core.state.ComposerSmsOperation
import org.aurorasms.core.state.ComposerSmsOperationResult
import org.aurorasms.core.state.ComposerSmsStorageOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RoomComposerSmsOperationObservationTest {
    @Test
    fun transientQueryFailureEmitsTypedFailureThenResubscribesToSuccess() = runTest {
        var subscriptions = 0
        val emissions = flow<ComposerSmsOperationResult<ComposerSmsOperation?>> {
            subscriptions += 1
            if (subscriptions == 1) throw SQLiteException("synthetic transient query failure")
            emit(ComposerSmsOperationResult.Success(null))
        }
            .retryComposerSmsObservationFailures { 0L }
            .take(2)
            .toList()

        assertEquals(2, subscriptions)
        assertEquals(
            ComposerSmsOperationResult.StorageFailure(ComposerSmsStorageOperation.OBSERVE),
            emissions[0],
        )
        val recovered = emissions[1] as ComposerSmsOperationResult.Success
        assertNull(recovered.value)
    }

    @Test
    fun corruptionIsTerminalAndDoesNotLoop() = runTest {
        var subscriptions = 0
        val emissions = flow<ComposerSmsOperationResult<ComposerSmsOperation?>> {
            subscriptions += 1
            throw IllegalArgumentException("synthetic corrupt row")
        }
            .retryComposerSmsObservationFailures { 0L }
            .toList()

        assertEquals(1, subscriptions)
        assertEquals(listOf(ComposerSmsOperationResult.CorruptData), emissions)
    }

    @Test
    fun cancellationIsNeverConvertedIntoStorageFailure() {
        assertThrows(CancellationException::class.java) {
            runTest {
                flow<ComposerSmsOperationResult<ComposerSmsOperation?>> {
                    throw CancellationException("synthetic collector cancellation")
                }
                    .retryComposerSmsObservationFailures { 0L }
                    .toList()
            }
        }
    }
}
