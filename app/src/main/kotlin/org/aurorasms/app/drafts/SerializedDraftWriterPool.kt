// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.drafts

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.aurorasms.core.state.DraftIdentity

/**
 * Keeps one live participant-draft writer per route owner while UI hands off across recreation.
 *
 * An entry remains published while its last lease drains. A new owner that arrives during that
 * drain receives the same writer, so it cannot read an intermediate durable revision and discard a
 * newer accepted edit. Distinct live routes remain separate writers and therefore retain the
 * participant writer's fail-closed conflict semantics. A slow drain stays published until it
 * settles; settled zero-owner drains are removed and closed.
 */
internal class SerializedDraftWriterPool(
    private val scope: CoroutineScope,
    private val writerFactory: (DraftIdentity, DraftRestorationToken?) -> SerializedDraftWriter,
) : AutoCloseable {
    private val lock = Any()
    private val entries = HashMap<ParticipantRouteKey, Entry>()
    private val exclusiveWriters = HashSet<SerializedDraftWriter>()
    private var closed = false

    fun acquire(
        identity: DraftIdentity,
        restorationToken: DraftRestorationToken?,
        participantRouteOwner: String?,
    ): SerializedDraftWriterLease = synchronized(lock) {
        check(!closed) { "Draft writer pool is closed" }
        if (identity !is DraftIdentity.ParticipantSet) {
            val writer = writerFactory(identity, restorationToken)
            exclusiveWriters += writer
            return@synchronized SerializedDraftWriterLease(writer) {
                releaseExclusive(writer)
            }
        }
        val routeOwner = requireNotNull(participantRouteOwner) {
            "A participant draft writer requires a stable route owner"
        }
        val key = ParticipantRouteKey(identity, routeOwner)
        val existing = entries[key]
        val entry = if (existing?.writer?.status?.value is DraftWriteStatus.Failed) {
            // A terminal writer cannot recover. Replace it synchronously so a rapid
            // recreation/reopen cannot reacquire the failed instance before async drain.
            entries.remove(key)
            existing.writer.close()
            Entry(writer = writerFactory(identity, restorationToken)).also { entries[key] = it }
        } else {
            existing ?: Entry(
                writer = writerFactory(identity, restorationToken),
            ).also { entries[key] = it }
        }
        entry.references += 1
        entry.closeEpoch += 1L
        SerializedDraftWriterLease(entry.writer) { release(key, entry) }
    }

    private fun releaseExclusive(writer: SerializedDraftWriter) {
        val owned = synchronized(lock) { !closed && writer in exclusiveWriters }
        if (!owned) return
        scope.launch {
            try {
                writer.flush()
            } finally {
                val closeWriter = synchronized(lock) { exclusiveWriters.remove(writer) }
                if (closeWriter) writer.close()
            }
        }
    }

    private fun release(key: ParticipantRouteKey, entry: Entry) {
        val epoch = synchronized(lock) {
            if (closed || entries[key] !== entry || entry.references == 0) return
            entry.references -= 1
            if (entry.references != 0) return
            ++entry.closeEpoch
        }
        scope.launch {
            entry.writer.flush()
            val settledStatus = entry.writer.status.first { status ->
                status is DraftWriteStatus.Failed ||
                    (status is DraftWriteStatus.Active && status.initialized && !status.saving)
            }
            val closeWriter = synchronized(lock) {
                if (
                    !closed &&
                    entries[key] === entry &&
                    entry.references == 0 &&
                    entry.closeEpoch == epoch &&
                    (
                        settledStatus is DraftWriteStatus.Failed ||
                            settledStatus is DraftWriteStatus.Active
                        )
                ) {
                    entries.remove(key)
                    true
                } else {
                    false
                }
            }
            if (closeWriter) entry.writer.close()
        }
    }

    override fun close() {
        val writers = synchronized(lock) {
            if (closed) return
            closed = true
            (entries.values.map(Entry::writer) + exclusiveWriters).also {
                entries.clear()
                exclusiveWriters.clear()
            }
        }
        writers.forEach(SerializedDraftWriter::close)
    }

    private class Entry(
        val writer: SerializedDraftWriter,
        var references: Int = 0,
        var closeEpoch: Long = 0L,
    )

    private data class ParticipantRouteKey(
        val identity: DraftIdentity.ParticipantSet,
        val owner: String,
    )
}

internal class SerializedDraftWriterLease(
    val writer: SerializedDraftWriter,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
