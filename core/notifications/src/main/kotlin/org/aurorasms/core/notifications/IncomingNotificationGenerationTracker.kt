// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.aurorasms.core.model.ConversationId
import org.aurorasms.core.model.MessageId
import org.aurorasms.core.model.ProviderKind

/**
 * Bounded durable ordering evidence for notification-manager replacement lag.
 *
 * Notification mutations call this tracker while [AndroidMessageNotifier]'s
 * app-wide mutation lock is held. The independent state lock also serializes
 * multiple tracker instances created during component recreation.
 */
internal class IncomingNotificationGenerationTracker(
    private val store: IncomingNotificationGenerationStore =
        InMemoryIncomingNotificationGenerationStore(),
    private val maximumTrackedConversations: Int = DEFAULT_MAXIMUM_TRACKED_CONVERSATIONS,
) {
    init {
        require(maximumTrackedConversations in 1..DEFAULT_MAXIMUM_TRACKED_CONVERSATIONS) {
            "maximumTrackedConversations is out of bounds"
        }
    }

    fun record(
        conversationId: ConversationId,
        messageId: MessageId,
    ): RecordResult = synchronized(stateMutationLock) {
        if (!messageId.kind.isTelephonyProvider) return@synchronized RecordResult.Corrupt
        val state = when (val loaded = loadState()) {
            is LoadedState.Available -> loaded.state
            LoadedState.Corrupt -> return@synchronized RecordResult.Corrupt
            LoadedState.PersistenceFailure -> return@synchronized RecordResult.PersistenceFailure
        }
        if (state.latestByConversation[conversationId] == messageId) {
            return@synchronized RecordResult.Recorded
        }
        if (
            conversationId !in state.latestByConversation &&
            state.latestByConversation.size >= maximumTrackedConversations
        ) {
            return@synchronized RecordResult.Full
        }
        val updated = state.copy(
            latestByConversation = state.latestByConversation + (conversationId to messageId),
        )
        if (persist(updated)) RecordResult.Recorded else RecordResult.PersistenceFailure
    }

    fun lookup(conversationId: ConversationId): Lookup = synchronized(stateMutationLock) {
        when (val loaded = loadState()) {
            is LoadedState.Available -> loaded.state.latestByConversation[conversationId]
                ?.let(Lookup::Tracked)
                ?: if (loaded.state.hasUntrackedOverflow) {
                    Lookup.UntrackedAfterOverflow
                } else {
                    Lookup.Untracked
                }
            LoadedState.Corrupt -> Lookup.Corrupt
            LoadedState.PersistenceFailure -> Lookup.PersistenceFailure
        }
    }

    /**
     * Removes only generations whose exact conversation tag/ID pair was
     * proven absent from one successful active-notification snapshot.
     */
    fun reconcileProvablyAbsent(
        activeConversationIds: Set<ConversationId>,
    ): MutationResult = synchronized(stateMutationLock) {
        val state = when (val loaded = loadState()) {
            is LoadedState.Available -> loaded.state
            LoadedState.Corrupt -> return@synchronized MutationResult.Corrupt
            LoadedState.PersistenceFailure -> return@synchronized MutationResult.PersistenceFailure
        }
        val retained = state.latestByConversation.filterKeys(activeConversationIds::contains)
        if (
            retained.size == state.latestByConversation.size &&
            !state.hasUntrackedOverflow
        ) {
            return@synchronized MutationResult.Success
        }
        if (
            persist(
                state.copy(
                    latestByConversation = retained,
                    hasUntrackedOverflow = false,
                ),
            )
        ) {
            MutationResult.Success
        } else {
            MutationResult.PersistenceFailure
        }
    }

    /** Persists the fact that at least one generation could not be tracked. */
    fun markUntrackedOverflow(): MutationResult = synchronized(stateMutationLock) {
        val state = when (val loaded = loadState()) {
            is LoadedState.Available -> loaded.state
            LoadedState.Corrupt -> return@synchronized MutationResult.Corrupt
            LoadedState.PersistenceFailure -> return@synchronized MutationResult.PersistenceFailure
        }
        if (state.hasUntrackedOverflow) return@synchronized MutationResult.Success
        if (persist(state.copy(hasUntrackedOverflow = true))) {
            MutationResult.Success
        } else {
            MutationResult.PersistenceFailure
        }
    }

    fun forgetIfCurrent(
        conversationId: ConversationId,
        messageId: MessageId,
    ): MutationResult = synchronized(stateMutationLock) {
        val state = when (val loaded = loadState()) {
            is LoadedState.Available -> loaded.state
            LoadedState.Corrupt -> return@synchronized MutationResult.Corrupt
            LoadedState.PersistenceFailure -> return@synchronized MutationResult.PersistenceFailure
        }
        if (state.latestByConversation[conversationId] != messageId) {
            return@synchronized MutationResult.Success
        }
        if (persist(state.copy(latestByConversation = state.latestByConversation - conversationId))) {
            MutationResult.Success
        } else {
            MutationResult.PersistenceFailure
        }
    }

    /** Rebuilds corrupt or unsupported state from one authoritative NMS snapshot. */
    fun replaceAll(
        latestByConversation: Map<ConversationId, MessageId>,
    ): RecordResult = synchronized(stateMutationLock) {
        if (latestByConversation.size > maximumTrackedConversations) {
            return@synchronized RecordResult.Full
        }
        if (
            latestByConversation.any { (conversationId, messageId) ->
                conversationId.value <= 0L || !messageId.kind.isTelephonyProvider
            }
        ) {
            return@synchronized RecordResult.Corrupt
        }
        val state = GenerationState(
            latestByConversation = latestByConversation.toMap(),
            hasUntrackedOverflow = false,
        )
        if (persist(state)) RecordResult.Recorded else RecordResult.PersistenceFailure
    }

    private fun loadState(): LoadedState = when (val result = store.read()) {
        is IncomingNotificationGenerationStore.ReadResult.Available -> {
            val encoded = result.encoded
            if (encoded == null) {
                LoadedState.Available(GenerationState(emptyMap(), hasUntrackedOverflow = false))
            } else {
                decode(encoded)?.let { decoded ->
                    if (decoded.requiresMigration) {
                        // Preserve a valid legacy record if this best-effort rewrite
                        // fails; a later read or mutation will retry migration.
                        store.write(encode(decoded.state))
                    }
                    LoadedState.Available(decoded.state)
                } ?: LoadedState.Corrupt
            }
        }
        IncomingNotificationGenerationStore.ReadResult.Corrupt -> LoadedState.Corrupt
        IncomingNotificationGenerationStore.ReadResult.PersistenceFailure ->
            LoadedState.PersistenceFailure
    }

    private fun persist(state: GenerationState): Boolean =
        store.write(encode(state))

    private fun encode(state: GenerationState): String {
        val payload = canonicalPayload(state, FORMAT_VERSION)
        val firstLineEnd = payload.indexOf(ENTRY_SEPARATOR)
        val header = if (firstLineEnd < 0) payload else payload.substring(0, firstLineEnd)
        val entries = if (firstLineEnd < 0) "" else payload.substring(firstLineEnd)
        return buildString(payload.length + CHECKSUM_FIELD_CHARACTERS) {
            append(header)
            append(SEPARATOR)
            append(checksum(payload))
            append(entries)
        }
    }

    private fun canonicalPayload(state: GenerationState, version: String): String = buildString {
        append(version)
        append(SEPARATOR)
        append(if (state.hasUntrackedOverflow) OVERFLOW_PRESENT else OVERFLOW_ABSENT)
        state.latestByConversation
            .toSortedMap(compareBy(ConversationId::value))
            .forEach { (conversationId, messageId) ->
                append(ENTRY_SEPARATOR)
                append(conversationId.value)
                append(SEPARATOR)
                append(messageId.kind.name)
                append(SEPARATOR)
                append(messageId.value)
            }
    }

    private fun decode(encoded: String): DecodedGenerationState? {
        if (encoded.length > maximumEncodedStateCharacters()) return null
        val lines = encoded.split(ENTRY_SEPARATOR)
        if (lines.size > maximumTrackedConversations + 1) return null
        val header = lines.firstOrNull()?.split(SEPARATOR) ?: return null
        val version = header.firstOrNull() ?: return null
        when (version) {
            FORMAT_VERSION -> {
                if (header.size != HEADER_FIELD_COUNT) return null
                val actualChecksum = header[2].takeIf(SHA_256_PATTERN::matches) ?: return null
                val payload = buildString(encoded.length - CHECKSUM_FIELD_CHARACTERS) {
                    append(version)
                    append(SEPARATOR)
                    append(header[1])
                    lines.drop(1).forEach { line ->
                        append(ENTRY_SEPARATOR)
                        append(line)
                    }
                }
                val expectedChecksum = checksum(payload)
                if (!MessageDigest.isEqual(
                        actualChecksum.toByteArray(StandardCharsets.US_ASCII),
                        expectedChecksum.toByteArray(StandardCharsets.US_ASCII),
                    )
                ) {
                    return null
                }
            }
            LEGACY_FORMAT_VERSION -> if (header.size != LEGACY_HEADER_FIELD_COUNT) return null
            else -> return null
        }
        val hasOverflow = when (header[1]) {
            OVERFLOW_ABSENT -> false
            OVERFLOW_PRESENT -> true
            else -> return null
        }
        val entries = linkedMapOf<ConversationId, MessageId>()
        lines.drop(1).forEach { line ->
            val fields = line.split(SEPARATOR)
            if (fields.size != ENTRY_FIELD_COUNT) return null
            val conversationValue = fields[0].toCanonicalPositiveLongOrNull() ?: return null
            val kind = when (fields[1]) {
                ProviderKind.SMS.name -> ProviderKind.SMS
                ProviderKind.MMS.name -> ProviderKind.MMS
                else -> return null
            }
            val messageValue = fields[2].toCanonicalPositiveLongOrNull() ?: return null
            val conversationId = ConversationId(conversationValue)
            if (entries.put(conversationId, MessageId(kind, messageValue)) != null) return null
        }
        val state = GenerationState(entries, hasOverflow)
        val canonical = if (version == FORMAT_VERSION) {
            encode(state)
        } else {
            canonicalPayload(state, LEGACY_FORMAT_VERSION)
        }
        if (canonical != encoded) return null
        return DecodedGenerationState(
            state = state,
            requiresMigration = version != FORMAT_VERSION,
        )
    }

    private fun checksum(payload: String): String = MessageDigest.getInstance("SHA-256").apply {
        update(CHECKSUM_DOMAIN)
        update(CHECKSUM_BOUNDARY)
        update(payload.toByteArray(StandardCharsets.UTF_8))
    }.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun maximumEncodedStateCharacters(): Int =
        HEADER_MAXIMUM_CHARACTERS +
            maximumTrackedConversations * ENTRY_MAXIMUM_CHARACTERS

    sealed interface Lookup {
        data class Tracked(val messageId: MessageId) : Lookup

        data object Untracked : Lookup

        data object UntrackedAfterOverflow : Lookup

        data object Corrupt : Lookup

        data object PersistenceFailure : Lookup
    }

    enum class RecordResult {
        Recorded,
        Full,
        Corrupt,
        PersistenceFailure,
    }

    enum class MutationResult {
        Success,
        Corrupt,
        PersistenceFailure,
    }

    private sealed interface LoadedState {
        data class Available(val state: GenerationState) : LoadedState

        data object Corrupt : LoadedState

        data object PersistenceFailure : LoadedState
    }

    private data class GenerationState(
        val latestByConversation: Map<ConversationId, MessageId>,
        val hasUntrackedOverflow: Boolean,
    )

    private data class DecodedGenerationState(
        val state: GenerationState,
        val requiresMigration: Boolean,
    )

    private companion object {
        const val DEFAULT_MAXIMUM_TRACKED_CONVERSATIONS = 4_096
        const val FORMAT_VERSION = "2"
        const val LEGACY_FORMAT_VERSION = "1"
        const val OVERFLOW_ABSENT = "0"
        const val OVERFLOW_PRESENT = "1"
        const val SEPARATOR = '|'
        const val ENTRY_SEPARATOR = '\n'
        const val HEADER_FIELD_COUNT = 3
        const val LEGACY_HEADER_FIELD_COUNT = 2
        const val ENTRY_FIELD_COUNT = 3
        const val HEADER_MAXIMUM_CHARACTERS = 69
        const val ENTRY_MAXIMUM_CHARACTERS = 48
        const val CHECKSUM_FIELD_CHARACTERS = 65
        val CHECKSUM_DOMAIN =
            "AuroraSMS.INCOMING_NOTIFICATION_GENERATION.v2"
                .toByteArray(StandardCharsets.US_ASCII)
        val CHECKSUM_BOUNDARY = byteArrayOf(0)
        val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
        val stateMutationLock = Any()
    }
}

internal interface IncomingNotificationGenerationStore {
    fun read(): ReadResult

    fun write(encoded: String): Boolean

    sealed interface ReadResult {
        data class Available(val encoded: String?) : ReadResult

        data object Corrupt : ReadResult

        data object PersistenceFailure : ReadResult
    }
}

internal class InMemoryIncomingNotificationGenerationStore(
    var encoded: String? = null,
) : IncomingNotificationGenerationStore {
    override fun read(): IncomingNotificationGenerationStore.ReadResult =
        IncomingNotificationGenerationStore.ReadResult.Available(encoded)

    override fun write(encoded: String): Boolean {
        this.encoded = encoded
        return true
    }
}

/** Private, synchronously committed storage for the notify/cancel ordering boundary. */
// The application backup rules exclude the complete shared-preference domain.
// This boundary deliberately uses commit() so persistence precedes NMS notify().
@SuppressLint("ApplySharedPref", "UseKtx")
internal class SharedPreferencesIncomingNotificationGenerationStore(
    context: Context,
    preferenceName: String = INCOMING_NOTIFICATION_GENERATION_PREFERENCE_NAME,
) : IncomingNotificationGenerationStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )

    override fun read(): IncomingNotificationGenerationStore.ReadResult = try {
        val all = preferences.all
        when {
            all.isEmpty() -> IncomingNotificationGenerationStore.ReadResult.Available(null)
            all.size != 1 || STATE_KEY !in all ->
                IncomingNotificationGenerationStore.ReadResult.Corrupt
            all[STATE_KEY] !is String -> IncomingNotificationGenerationStore.ReadResult.Corrupt
            else -> IncomingNotificationGenerationStore.ReadResult.Available(
                all.getValue(STATE_KEY) as String,
            )
        }
    } catch (_: RuntimeException) {
        IncomingNotificationGenerationStore.ReadResult.PersistenceFailure
    }

    override fun write(encoded: String): Boolean = try {
        preferences.edit()
            .clear()
            .putString(STATE_KEY, encoded)
            .commit()
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        const val STATE_KEY = "state"
    }
}

internal const val INCOMING_NOTIFICATION_GENERATION_PREFERENCE_NAME =
    "aurora_incoming_notification_generations"

internal fun String.toCanonicalPositiveLongOrNull(): Long? =
    toLongOrNull()
        ?.takeIf { value -> value > 0L && value.toString() == this }
