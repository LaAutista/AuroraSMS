// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message

import android.annotation.SuppressLint
import android.content.Context
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.state.MessageSignature

internal class MessageSignatureConversationKey private constructor(
    private val storageValue: String,
) {
    internal fun toStorageValue(): String = storageValue

    override fun equals(other: Any?): Boolean =
        other is MessageSignatureConversationKey && storageValue == other.storageValue

    override fun hashCode(): Int = storageValue.hashCode()

    override fun toString(): String = "MessageSignatureConversationKey(REDACTED)"

    companion object {
        private const val PREFIX = "sha256-v1:"
        private val DOMAIN = "AuroraSMS.MessageSignatureConversation.v1".encodeToByteArray()
        private val HEX = "0123456789abcdef".toCharArray()

        fun fromParticipants(participants: Iterable<ParticipantAddress>): MessageSignatureConversationKey {
            val values = ArrayList<String>()
            participants.forEach { participant ->
                require(values.size < MAXIMUM_PARTICIPANTS)
                values += ParticipantAddress(
                    Normalizer.normalize(participant.value, Normalizer.Form.NFC),
                ).value
            }
            val canonical = values.distinct().map { value ->
                try {
                    value.encodeToByteArray(throwOnInvalidSequence = true)
                } catch (failure: CharacterCodingException) {
                    throw IllegalArgumentException("Invalid signature participant identity", failure)
                }
            }.sortedWith(::compareUnsignedBytes)
            require(canonical.isNotEmpty())
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DOMAIN)
            digest.update(0.toByte())
            digest.updateInt(canonical.size)
            canonical.forEach { bytes ->
                digest.updateInt(bytes.size)
                digest.update(bytes)
            }
            return MessageSignatureConversationKey(
                PREFIX + digest.digest().joinToString("") { byte ->
                    val value = byte.toInt() and 0xff
                    "${HEX[value ushr 4]}${HEX[value and 0x0f]}"
                },
            )
        }

        internal fun fromStorageValue(value: String): MessageSignatureConversationKey {
            require(
                value.length == PREFIX.length + 64 &&
                    value.startsWith(PREFIX) &&
                    value.drop(PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' },
            )
            return MessageSignatureConversationKey(value)
        }

        private fun MessageDigest.updateInt(value: Int) {
            require(value >= 0)
            update((value ushr 24).toByte())
            update((value ushr 16).toByte())
            update((value ushr 8).toByte())
            update(value.toByte())
        }

        private fun compareUnsignedBytes(first: ByteArray, second: ByteArray): Int {
            val shared = minOf(first.size, second.size)
            for (index in 0 until shared) {
                val compared = (first[index].toInt() and 0xff) -
                    (second[index].toInt() and 0xff)
                if (compared != 0) return compared
            }
            return first.size - second.size
        }

        private const val MAXIMUM_PARTICIPANTS = 100
    }
}

internal sealed interface ConversationSignatureOverride {
    data object Inherit : ConversationSignatureOverride
    data object Disabled : ConversationSignatureOverride
    data class Custom(val signature: MessageSignature) : ConversationSignatureOverride {
        override fun toString(): String = "ConversationSignatureOverride.Custom(REDACTED)"
    }
}

internal data class MessageSignatureSnapshot(
    val available: Boolean,
    val global: MessageSignature?,
    val conversations: Map<MessageSignatureConversationKey, ConversationSignatureOverride>,
    val sendAllowed: Boolean = true,
) {
    fun resolve(key: MessageSignatureConversationKey?): MessageSignature? {
        if (!available) return null
        return when (val override = key?.let(conversations::get)) {
            is ConversationSignatureOverride.Custom -> override.signature
            ConversationSignatureOverride.Disabled -> null
            ConversationSignatureOverride.Inherit,
            null,
            -> global
        }
    }

    override fun toString(): String =
        "MessageSignatureSnapshot(available=$available, hasGlobal=${global != null}, " +
            "conversationCount=${conversations.size}, REDACTED)"

    companion object {
        val UNAVAILABLE = MessageSignatureSnapshot(false, null, emptyMap())
        val CORRUPT = MessageSignatureSnapshot(false, null, emptyMap(), sendAllowed = false)
    }
}

internal interface MessageSignaturePreferenceStore {
    val snapshot: StateFlow<MessageSignatureSnapshot>
    fun setGlobal(signature: MessageSignature?): Boolean
    fun setConversation(
        key: MessageSignatureConversationKey,
        override: ConversationSignatureOverride,
    ): Boolean
}

internal object UnavailableMessageSignaturePreferenceStore : MessageSignaturePreferenceStore {
    private val state = MutableStateFlow(MessageSignatureSnapshot.UNAVAILABLE)
    override val snapshot: StateFlow<MessageSignatureSnapshot> = state.asStateFlow()
    override fun setGlobal(signature: MessageSignature?): Boolean = false
    override fun setConversation(
        key: MessageSignatureConversationKey,
        override: ConversationSignatureOverride,
    ): Boolean = false
}

@SuppressLint("ApplySharedPref", "UseKtx")
internal class SharedPreferencesMessageSignaturePreferenceStore(
    context: Context,
    private val maximumConversationOverrides: Int = DEFAULT_MAXIMUM_CONVERSATION_OVERRIDES,
) : MessageSignaturePreferenceStore {
    init {
        require(maximumConversationOverrides in 1..ABSOLUTE_MAXIMUM_CONVERSATION_OVERRIDES)
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val state = MutableStateFlow(readSnapshot())

    override val snapshot: StateFlow<MessageSignatureSnapshot> = state.asStateFlow()

    @Synchronized
    override fun setGlobal(signature: MessageSignature?): Boolean {
        if (!state.value.available) return false
        val editor = preferences.edit()
        if (signature == null) editor.remove(GLOBAL_KEY)
        else editor.putString(GLOBAL_KEY, encodeGlobal(signature))
        if (!editor.commit()) return false
        state.value = readSnapshot()
        return state.value.available
    }

    @Synchronized
    override fun setConversation(
        key: MessageSignatureConversationKey,
        override: ConversationSignatureOverride,
    ): Boolean {
        val current = state.value
        if (!current.available) return false
        val storageKey = conversationStorageKey(key)
        if (
            override !is ConversationSignatureOverride.Inherit &&
            key !in current.conversations &&
            current.conversations.size >= maximumConversationOverrides
        ) return false
        val editor = preferences.edit()
        when (override) {
            ConversationSignatureOverride.Inherit -> editor.remove(storageKey)
            ConversationSignatureOverride.Disabled ->
                editor.putString(storageKey, encodeConversation(key, override))
            is ConversationSignatureOverride.Custom ->
                editor.putString(storageKey, encodeConversation(key, override))
        }
        if (!editor.commit()) return false
        state.value = readSnapshot()
        return state.value.available
    }

    private fun readSnapshot(): MessageSignatureSnapshot = try {
        val stored = preferences.all
        val global = (stored[GLOBAL_KEY] as? String)?.let(::decodeGlobal)
            ?: if (GLOBAL_KEY in stored) return MessageSignatureSnapshot.CORRUPT else null
        val conversations = LinkedHashMap<
            MessageSignatureConversationKey,
            ConversationSignatureOverride,
        >()
        stored.entries.sortedBy { it.key }.forEach { (key, value) ->
            if (!key.startsWith(CONVERSATION_KEY_PREFIX)) return@forEach
            if (conversations.size >= maximumConversationOverrides) {
                return MessageSignatureSnapshot.CORRUPT
            }
            val token = key.removePrefix(CONVERSATION_KEY_PREFIX)
            val conversationKey = MessageSignatureConversationKey.fromStorageValue(token)
            val encoded = value as? String ?: return MessageSignatureSnapshot.CORRUPT
            val decoded = decodeConversation(conversationKey, encoded)
                ?: return MessageSignatureSnapshot.CORRUPT
            if (conversations.put(conversationKey, decoded) != null) {
                return MessageSignatureSnapshot.CORRUPT
            }
        }
        MessageSignatureSnapshot(true, global, conversations.toMap())
    } catch (_: IllegalArgumentException) {
        MessageSignatureSnapshot.CORRUPT
    } catch (_: IllegalStateException) {
        MessageSignatureSnapshot.CORRUPT
    }

    private fun encodeGlobal(signature: MessageSignature): String = encodeFields(
        listOf(FORMAT_VERSION, GLOBAL_MODE, encodeText(signature.value)),
    )

    private fun decodeGlobal(encoded: String): MessageSignature? {
        val fields = verifiedFields(encoded, EXPECTED_FIELD_COUNT) ?: return null
        if (fields[0] != FORMAT_VERSION || fields[1] != GLOBAL_MODE) return null
        return decodeText(fields[2])?.toStoredSignature()
    }

    private fun encodeConversation(
        key: MessageSignatureConversationKey,
        override: ConversationSignatureOverride,
    ): String {
        val mode: String
        val text: String
        when (override) {
            ConversationSignatureOverride.Inherit -> error("Inherited overrides are not stored")
            ConversationSignatureOverride.Disabled -> {
                mode = DISABLED_MODE
                text = ""
            }
            is ConversationSignatureOverride.Custom -> {
                mode = CUSTOM_MODE
                text = encodeText(override.signature.value)
            }
        }
        return encodeFields(listOf(FORMAT_VERSION, key.toStorageValue(), mode, text))
    }

    private fun decodeConversation(
        key: MessageSignatureConversationKey,
        encoded: String,
    ): ConversationSignatureOverride? {
        val fields = verifiedFields(encoded, EXPECTED_CONVERSATION_FIELD_COUNT) ?: return null
        if (fields[0] != FORMAT_VERSION || fields[1] != key.toStorageValue()) return null
        return when (fields[2]) {
            DISABLED_MODE -> ConversationSignatureOverride.Disabled.takeIf { fields[3].isEmpty() }
            CUSTOM_MODE -> decodeText(fields[3])
                ?.toStoredSignature()
                ?.let(ConversationSignatureOverride::Custom)
            else -> null
        }
    }

    private fun encodeFields(fields: List<String>): String =
        (fields + checksum(fields)).joinToString(SEPARATOR)

    private fun verifiedFields(encoded: String, expectedCount: Int): List<String>? {
        val fields = encoded.split(SEPARATOR)
        if (fields.size != expectedCount) return null
        val expected = checksum(fields.dropLast(1)).toByteArray(Charsets.US_ASCII)
        val actual = fields.last().toByteArray(Charsets.US_ASCII)
        return fields.takeIf { MessageDigest.isEqual(expected, actual) }
    }

    private fun checksum(fields: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(fields.joinToString(SEPARATOR).toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String? = try {
        Base64.getUrlDecoder().decode(value).decodeToString(throwOnInvalidSequence = true)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: CharacterCodingException) {
        null
    }

    private fun String.toStoredSignature(): MessageSignature? =
        MessageSignature.fromUserInput(this)?.takeIf { it.value == this }

    private fun conversationStorageKey(key: MessageSignatureConversationKey): String =
        CONVERSATION_KEY_PREFIX + key.toStorageValue()

    private companion object {
        const val PREFERENCES_NAME = "aurora_message_signatures_v1"
        const val GLOBAL_KEY = "global"
        const val CONVERSATION_KEY_PREFIX = "conversation."
        const val FORMAT_VERSION = "1"
        const val GLOBAL_MODE = "global"
        const val DISABLED_MODE = "disabled"
        const val CUSTOM_MODE = "custom"
        const val SEPARATOR = "|"
        const val EXPECTED_FIELD_COUNT = 4
        const val EXPECTED_CONVERSATION_FIELD_COUNT = 5
        const val DEFAULT_MAXIMUM_CONVERSATION_OVERRIDES = 256
        const val ABSOLUTE_MAXIMUM_CONVERSATION_OVERRIDES = 1_024
    }
}
