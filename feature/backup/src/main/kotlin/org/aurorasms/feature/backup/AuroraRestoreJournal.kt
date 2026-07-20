// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.backup

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class AuroraRestoreProviderKind(internal val code: String) {
    SMS("S"),
    MMS("M"),
    ;

    internal companion object {
        fun decode(code: String): AuroraRestoreProviderKind? = entries.singleOrNull { it.code == code }
    }
}

data class AuroraRestoreSession(
    val value: String,
) {
    init {
        require(SESSION_PATTERN.matches(value))
    }

    override fun toString(): String = "AuroraRestoreSession(REDACTED)"
}

data class AuroraRestoreOwnership(
    val archiveMessageId: Long,
    val providerKind: AuroraRestoreProviderKind,
    val providerRowId: Long?,
    val targetBox: AuroraBackupMessageBox,
) {
    init {
        require(archiveMessageId > 0L)
        require(providerRowId == null || providerRowId > 0L)
    }

    override fun toString(): String =
        "AuroraRestoreOwnership(kind=$providerKind, hasProviderId=${providerRowId != null}, REDACTED)"
}

sealed interface AuroraRestoreJournalBeginResult {
    data class Success(val session: AuroraRestoreSession) : AuroraRestoreJournalBeginResult
    data object ExistingRecoveryRequired : AuroraRestoreJournalBeginResult
    data object StorageFailure : AuroraRestoreJournalBeginResult
}

sealed interface AuroraRestoreJournalRecoveryResult {
    data object None : AuroraRestoreJournalRecoveryResult
    data class Active(
        val session: AuroraRestoreSession,
        val createdTimestampMillis: Long,
        val messageCount: Long,
        val hasPreInsertReservation: Boolean,
    ) : AuroraRestoreJournalRecoveryResult
    data class Complete(
        val session: AuroraRestoreSession,
        val createdTimestampMillis: Long,
        val messageCount: Long,
    ) : AuroraRestoreJournalRecoveryResult
    data object Corrupt : AuroraRestoreJournalRecoveryResult
}

/**
 * Append-only, content-free restore ownership journal.
 *
 * Grammar is HEADER, then strictly paired RESERVE/INSERT events, with at most one
 * final unmatched RESERVE and an optional COMPLETE marker. Every append is
 * synchronously flushed before the provider boundary may advance.
 */
class AuroraRestoreJournal internal constructor(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newSession: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(context: Context) : this(context.applicationContext.noBackupFilesDir)

    private val journalFile = File(directory, JOURNAL_FILE)
    private val creatingFile = File(directory, CREATING_FILE)
    private var active: ActiveAppendState? = null

    @Synchronized
    fun begin(): AuroraRestoreJournalBeginResult {
        if (journalFile.exists()) return AuroraRestoreJournalBeginResult.ExistingRecoveryRequired
        val session = runCatching { AuroraRestoreSession(newSession()) }.getOrNull()
            ?: return AuroraRestoreJournalBeginResult.StorageFailure
        val created = nowMillis().takeIf { it >= 0L }
            ?: return AuroraRestoreJournalBeginResult.StorageFailure
        return try {
            if (!directory.exists() && !directory.mkdirs()) {
                return AuroraRestoreJournalBeginResult.StorageFailure
            }
            if (creatingFile.exists() && !creatingFile.delete()) {
                return AuroraRestoreJournalBeginResult.StorageFailure
            }
            writeNewFile(creatingFile, encodeHeader(session, created))
            if (!creatingFile.renameTo(journalFile)) {
                creatingFile.delete()
                return AuroraRestoreJournalBeginResult.StorageFailure
            }
            active = ActiveAppendState(session, nextSequence = 1L, lastMessageId = 0L, pending = null)
            AuroraRestoreJournalBeginResult.Success(session)
        } catch (_: IOException) {
            creatingFile.delete()
            AuroraRestoreJournalBeginResult.StorageFailure
        } catch (_: RuntimeException) {
            creatingFile.delete()
            AuroraRestoreJournalBeginResult.StorageFailure
        }
    }

    /** Must succeed before the matching Telephony insert is attempted. */
    @Synchronized
    fun reserve(
        session: AuroraRestoreSession,
        archiveMessageId: Long,
        providerKind: AuroraRestoreProviderKind,
        targetBox: AuroraBackupMessageBox,
    ): Boolean {
        val state = active?.takeIf { it.session == session } ?: return false
        if (state.pending != null || archiveMessageId <= state.lastMessageId) return false
        val ownership = AuroraRestoreOwnership(
            archiveMessageId,
            providerKind,
            providerRowId = null,
            targetBox = targetBox,
        )
        if (!append(encodeEvent(session, state.nextSequence, EVENT_RESERVE, ownership))) return false
        active = state.copy(
            nextSequence = state.nextSequence + 1L,
            lastMessageId = archiveMessageId,
            pending = ownership,
        )
        return true
    }

    /** Must succeed immediately after the provider returns the inserted row ID. */
    @Synchronized
    fun recordInserted(
        session: AuroraRestoreSession,
        archiveMessageId: Long,
        providerKind: AuroraRestoreProviderKind,
        providerRowId: Long,
    ): Boolean {
        val state = active?.takeIf { it.session == session } ?: return false
        val expected = state.pending ?: return false
        if (
            providerRowId <= 0L ||
            expected.archiveMessageId != archiveMessageId ||
            expected.providerKind != providerKind
        ) {
            return false
        }
        val ownership = expected.copy(providerRowId = providerRowId)
        if (!append(encodeEvent(session, state.nextSequence, EVENT_INSERT, ownership))) return false
        active = state.copy(nextSequence = state.nextSequence + 1L, pending = null)
        return true
    }

    /** Durable boundary after every staged row has been made historical. */
    @Synchronized
    fun markComplete(session: AuroraRestoreSession): Boolean {
        val state = active?.takeIf { it.session == session } ?: return false
        if (state.pending != null) return false
        val complete = AuroraRestoreOwnership(
            archiveMessageId = state.lastMessageId.coerceAtLeast(1L),
            providerKind = AuroraRestoreProviderKind.SMS,
            providerRowId = null,
            targetBox = AuroraBackupMessageBox.FAILED,
        )
        if (!append(encodeEvent(session, state.nextSequence, EVENT_COMPLETE, complete))) return false
        active = null
        return true
    }

    @Synchronized
    fun recoverySnapshot(): AuroraRestoreJournalRecoveryResult {
        if (!journalFile.exists()) {
            // A creating file cannot have crossed a successful begin() boundary.
            if (creatingFile.exists()) creatingFile.delete()
            active = null
            return AuroraRestoreJournalRecoveryResult.None
        }
        return when (val parsed = parse(null)) {
            is ParsedJournal.Valid -> {
                active = null
                if (parsed.complete) {
                    AuroraRestoreJournalRecoveryResult.Complete(
                        parsed.session,
                        parsed.createdTimestampMillis,
                        parsed.lastMessageId,
                    )
                } else {
                    AuroraRestoreJournalRecoveryResult.Active(
                        parsed.session,
                        parsed.createdTimestampMillis,
                        parsed.lastMessageId,
                        parsed.pending != null,
                    )
                }
            }
            ParsedJournal.Corrupt -> {
                active = null
                AuroraRestoreJournalRecoveryResult.Corrupt
            }
        }
    }

    /** Streams exact ownership in archive order without collecting a large restore in memory. */
    @Synchronized
    fun forEachOwnership(
        session: AuroraRestoreSession,
        consumer: (AuroraRestoreOwnership) -> Unit,
    ): Boolean = when (val parsed = parse { parsedSession, ownership ->
        if (parsedSession != session) throw JournalMismatchException()
        consumer(ownership)
    }) {
        is ParsedJournal.Valid -> parsed.session == session
        ParsedJournal.Corrupt -> false
    }

    /** Deletes only a fully parsed journal for the exact session after rollback or completion. */
    @Synchronized
    fun clear(session: AuroraRestoreSession): Boolean {
        val parsed = parse(null) as? ParsedJournal.Valid ?: return false
        if (parsed.session != session) return false
        active = null
        return !journalFile.exists() || journalFile.delete()
    }

    private fun parse(
        ownershipConsumer: ((AuroraRestoreSession, AuroraRestoreOwnership) -> Unit)?,
    ): ParsedJournal = try {
        BufferedReader(
            InputStreamReader(FileInputStream(journalFile), StandardCharsets.UTF_8),
            BUFFER_BYTES,
        ).use { reader ->
            val headerLine = reader.readLine()?.takeIf { it.length <= MAX_LINE_CHARACTERS }
                ?: return ParsedJournal.Corrupt
            val header = decodeHeader(headerLine) ?: return ParsedJournal.Corrupt
            var expectedSequence = 1L
            var lastMessageId = 0L
            var pending: AuroraRestoreOwnership? = null
            var complete = false
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty() || line.length > MAX_LINE_CHARACTERS || complete) {
                    return ParsedJournal.Corrupt
                }
                val event = decodeEvent(line) ?: return ParsedJournal.Corrupt
                if (event.session != header.session || event.sequence != expectedSequence) {
                    return ParsedJournal.Corrupt
                }
                expectedSequence += 1L
                when (event.type) {
                    EVENT_RESERVE -> {
                        if (
                            pending != null ||
                            event.ownership.providerRowId != null ||
                            event.ownership.archiveMessageId <= lastMessageId
                        ) {
                            return ParsedJournal.Corrupt
                        }
                        pending = event.ownership
                        lastMessageId = event.ownership.archiveMessageId
                    }
                    EVENT_INSERT -> {
                        val reserved = pending ?: return ParsedJournal.Corrupt
                        if (
                            event.ownership.providerRowId == null ||
                            event.ownership.archiveMessageId != reserved.archiveMessageId ||
                            event.ownership.providerKind != reserved.providerKind ||
                            event.ownership.targetBox != reserved.targetBox
                        ) {
                            return ParsedJournal.Corrupt
                        }
                        ownershipConsumer?.invoke(header.session, event.ownership)
                        pending = null
                    }
                    EVENT_COMPLETE -> {
                        if (
                            pending != null ||
                            event.ownership.providerRowId != null ||
                            event.ownership.targetBox != AuroraBackupMessageBox.FAILED
                        ) {
                            return ParsedJournal.Corrupt
                        }
                        val encodedCount = if (lastMessageId == 0L) 1L else lastMessageId
                        if (event.ownership.archiveMessageId != encodedCount) {
                            return ParsedJournal.Corrupt
                        }
                        complete = true
                    }
                    else -> return ParsedJournal.Corrupt
                }
            }
            pending?.let { ownershipConsumer?.invoke(header.session, it) }
            ParsedJournal.Valid(
                session = header.session,
                createdTimestampMillis = header.createdTimestampMillis,
                lastMessageId = lastMessageId,
                pending = pending,
                complete = complete,
            )
        }
    } catch (_: IOException) {
        ParsedJournal.Corrupt
    } catch (_: RuntimeException) {
        ParsedJournal.Corrupt
    }

    private fun append(line: String): Boolean = try {
        FileOutputStream(journalFile, true).use { output ->
            output.write(line.toByteArray(StandardCharsets.US_ASCII))
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
        true
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun writeNewFile(file: File, line: String) {
        FileOutputStream(file, false).use { output ->
            output.write(line.toByteArray(StandardCharsets.US_ASCII))
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
    }

    private data class ActiveAppendState(
        val session: AuroraRestoreSession,
        val nextSequence: Long,
        val lastMessageId: Long,
        val pending: AuroraRestoreOwnership?,
    )

    private data class Header(
        val session: AuroraRestoreSession,
        val createdTimestampMillis: Long,
    )

    private data class Event(
        val session: AuroraRestoreSession,
        val sequence: Long,
        val type: String,
        val ownership: AuroraRestoreOwnership,
    )

    private sealed interface ParsedJournal {
        data class Valid(
            val session: AuroraRestoreSession,
            val createdTimestampMillis: Long,
            val lastMessageId: Long,
            val pending: AuroraRestoreOwnership?,
            val complete: Boolean,
        ) : ParsedJournal
        data object Corrupt : ParsedJournal
    }

    companion object {
        private const val VERSION = "R1"
        private const val JOURNAL_FILE = "aurora_restore_journal_v1.log"
        private const val CREATING_FILE = "$JOURNAL_FILE.creating"
        private const val EVENT_RESERVE = "R"
        private const val EVENT_INSERT = "I"
        private const val EVENT_COMPLETE = "C"
        private const val SEPARATOR = "|"
        private const val HEADER_FIELDS = 4
        private const val EVENT_FIELDS = 9
        private const val MAX_LINE_CHARACTERS = 256
        private const val BUFFER_BYTES = 16 * 1_024

        private fun encodeHeader(session: AuroraRestoreSession, created: Long): String {
            val payload = listOf(VERSION, session.value, created).joinToString(SEPARATOR)
            return "$payload$SEPARATOR${checksum(payload)}"
        }

        private fun decodeHeader(line: String): Header? {
            val fields = line.split(SEPARATOR)
            if (fields.size != HEADER_FIELDS || fields[0] != VERSION) return null
            val payload = fields.take(HEADER_FIELDS - 1).joinToString(SEPARATOR)
            if (!constantEquals(checksum(payload), fields.last())) return null
            val session = runCatching { AuroraRestoreSession(fields[1]) }.getOrNull() ?: return null
            val created = fields[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
            return Header(session, created)
        }

        private fun encodeEvent(
            session: AuroraRestoreSession,
            sequence: Long,
            type: String,
            ownership: AuroraRestoreOwnership,
        ): String {
            val payload = listOf(
                sequence,
                type,
                ownership.archiveMessageId,
                ownership.providerKind.code,
                ownership.providerRowId ?: 0L,
                ownership.targetBox.code,
                session.value,
                VERSION,
            ).joinToString(SEPARATOR)
            return "$payload$SEPARATOR${checksum(payload)}"
        }

        private fun decodeEvent(line: String): Event? {
            val fields = line.split(SEPARATOR)
            if (fields.size != EVENT_FIELDS || fields[7] != VERSION) return null
            val payload = fields.take(EVENT_FIELDS - 1).joinToString(SEPARATOR)
            if (!constantEquals(checksum(payload), fields.last())) return null
            val sequence = fields[0].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val type = fields[1].takeIf { it == EVENT_RESERVE || it == EVENT_INSERT || it == EVENT_COMPLETE }
                ?: return null
            val archiveMessageId = fields[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val kind = AuroraRestoreProviderKind.decode(fields[3]) ?: return null
            val providerValue = fields[4].toLongOrNull() ?: return null
            val providerId = when {
                providerValue == 0L -> null
                providerValue > 0L -> providerValue
                else -> return null
            }
            val targetBox = fields[5].toIntOrNull()?.let { AuroraBackupMessageBox.decode(it) }
                ?: return null
            val session = runCatching { AuroraRestoreSession(fields[6]) }.getOrNull() ?: return null
            return Event(
                session,
                sequence,
                type,
                AuroraRestoreOwnership(archiveMessageId, kind, providerId, targetBox),
            )
        }

        private fun checksum(payload: String): String = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.US_ASCII))
            .joinToString("") { byte -> "%02x".format(byte) }

        private fun constantEquals(first: String, second: String): Boolean = MessageDigest.isEqual(
            first.toByteArray(StandardCharsets.US_ASCII),
            second.toByteArray(StandardCharsets.US_ASCII),
        )
    }
}

private class JournalMismatchException : RuntimeException()

internal object AuroraRestorePlaceholder {
    fun smsAddress(session: AuroraRestoreSession, archiveMessageId: Long): String {
        require(archiveMessageId > 0L)
        return "aurora.restore.${session.value}.$archiveMessageId"
    }

    fun mmsTransactionId(session: AuroraRestoreSession, archiveMessageId: Long): String {
        require(archiveMessageId > 0L)
        return "ar1-${session.value.replace("-", "")}-${archiveMessageId.toString(16)}"
    }
}

private val SESSION_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
