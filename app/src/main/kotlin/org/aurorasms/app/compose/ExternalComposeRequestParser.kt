// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.compose

import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.aurorasms.core.model.MessageTransportKind
import org.aurorasms.core.model.ParticipantAddress
import org.aurorasms.core.state.Draft
import org.aurorasms.core.telephony.RecipientSet

/**
 * Validated, review-only input for Aurora's composer.
 *
 * [requestedTransport] records what the caller explicitly requested. It does
 * not override Aurora's later transport policy; for example, an SMS-scheme
 * request with multiple recipients still requires MMS at send time.
 */
internal data class ComposeRequest(
    val recipients: RecipientSet,
    val body: String,
    val requestedTransport: MessageTransportKind,
) {
    init {
        require(body.length <= Draft.MAX_BODY_CHARACTERS) { "Compose body is too large" }
    }

    override fun toString(): String =
        "ComposeRequest(recipients=REDACTED, body=REDACTED, requestedTransport=$requestedTransport)"
}

/** Pure parser for the externally supplied parts of an Android SENDTO intent. */
internal object ExternalComposeRequestParser {
    /**
     * Parses an external compose request without retaining or logging its raw inputs.
     *
     * The Android activity is responsible only for extracting `Intent.action`,
     * `Intent.dataString`, `sms_body`, and `Intent.EXTRA_TEXT` into these values.
     */
    fun parse(
        action: String?,
        dataUri: String?,
        smsBody: CharSequence? = null,
        extraText: CharSequence? = null,
    ): ComposeRequest? {
        if (action != ACTION_SENDTO || dataUri == null) return null
        if (dataUri.isEmpty() || dataUri.length > MAX_EXTERNAL_URI_CHARACTERS) return null
        if (dataUri.any(Char::isISOControl)) return null

        val uri = try {
            URI(dataUri)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.isOpaque || uri.rawFragment != null) return null

        val requestedTransport = when (uri.scheme?.lowercase(Locale.ROOT)) {
            "sms", "smsto" -> MessageTransportKind.SMS
            "mms", "mmsto" -> MessageTransportKind.MMS
            else -> return null
        }
        val rawSchemeSpecificPart = uri.rawSchemeSpecificPart ?: return null
        val queryDelimiter = rawSchemeSpecificPart.indexOf('?')
        val rawRecipientPart = if (queryDelimiter >= 0) {
            rawSchemeSpecificPart.substring(0, queryDelimiter)
        } else {
            rawSchemeSpecificPart
        }
        val rawQuery = if (queryDelimiter >= 0) {
            rawSchemeSpecificPart.substring(queryDelimiter + 1)
        } else {
            null
        }

        val decodedRecipientPart = decodePercentEncoded(
            raw = rawRecipientPart,
            maxDecodedCharacters = MAX_DECODED_RECIPIENT_LIST_CHARACTERS,
        ) ?: return null
        val recipients = when (val result = parseComposeRecipientList(decodedRecipientPart)) {
            is RecipientSet.CreationResult.Valid -> result.recipients
            is RecipientSet.CreationResult.Rejected -> return null
        }

        val query = parseQuery(rawQuery) ?: return null
        val body = when {
            query.hasBody -> query.body
            smsBody != null -> boundedBody(smsBody)
            extraText != null -> boundedBody(extraText)
            else -> ""
        } ?: return null

        return ComposeRequest(
            recipients = recipients,
            body = body,
            requestedTransport = requestedTransport,
        )
    }

    private fun parseQuery(rawQuery: String?): ParsedQuery? {
        if (rawQuery == null || rawQuery.isEmpty()) return ParsedQuery(hasBody = false, body = "")

        var parameterStart = 0
        var parameterCount = 0
        var body: String? = null
        while (parameterStart <= rawQuery.length) {
            val parameterEnd = rawQuery.indexOf('&', parameterStart)
                .takeIf { it >= 0 }
                ?: rawQuery.length
            if (parameterEnd == parameterStart) return null
            parameterCount += 1
            if (parameterCount > MAX_QUERY_PARAMETERS) return null

            val parameter = rawQuery.substring(parameterStart, parameterEnd)
            val equals = parameter.indexOf('=')
            val rawName = if (equals >= 0) parameter.substring(0, equals) else parameter
            val rawValue = if (equals >= 0) parameter.substring(equals + 1) else ""
            val name = decodePercentEncoded(rawName, MAX_QUERY_PARAMETER_NAME_CHARACTERS)
                ?: return null
            if (name.isEmpty() || name.any(Char::isISOControl)) return null
            val value = decodePercentEncoded(rawValue, Draft.MAX_BODY_CHARACTERS)
                ?: return null
            if (name == BODY_QUERY_PARAMETER) {
                if (body != null) return null
                body = value
            }

            if (parameterEnd == rawQuery.length) break
            parameterStart = parameterEnd + 1
        }
        return ParsedQuery(hasBody = body != null, body = body.orEmpty())
    }

    private fun boundedBody(value: CharSequence): String? {
        if (value.length > Draft.MAX_BODY_CHARACTERS) return null
        val materialized = value.toString()
        return materialized.takeIf { it.length <= Draft.MAX_BODY_CHARACTERS }
    }

    /**
     * Percent-decodes UTF-8 without form-url-encoded '+' substitution.
     * Malformed escapes and malformed UTF-8 fail closed.
     */
    private fun decodePercentEncoded(raw: String, maxDecodedCharacters: Int): String? {
        val decoded = StringBuilder(minOf(raw.length, maxDecodedCharacters))
        var index = 0
        while (index < raw.length) {
            if (raw[index] != '%') {
                val character = raw[index]
                if (character.isHighSurrogate()) {
                    if (index + 1 >= raw.length || !raw[index + 1].isLowSurrogate()) return null
                    decoded.append(character).append(raw[index + 1])
                    index += 2
                } else {
                    if (character.isLowSurrogate()) return null
                    decoded.append(character)
                    index += 1
                }
            } else {
                var escapeEnd = index
                while (escapeEnd < raw.length && raw[escapeEnd] == '%') {
                    if (escapeEnd + 2 >= raw.length) return null
                    if (hexValue(raw[escapeEnd + 1]) < 0 || hexValue(raw[escapeEnd + 2]) < 0) return null
                    escapeEnd += 3
                }
                val byteCount = (escapeEnd - index) / 3
                val bytes = ByteArray(byteCount)
                repeat(byteCount) { byteIndex ->
                    val escapeStart = index + (byteIndex * 3)
                    bytes[byteIndex] = (
                        (hexValue(raw[escapeStart + 1]) shl 4) or hexValue(raw[escapeStart + 2])
                        ).toByte()
                }
                val decodedEscapes = try {
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (_: CharacterCodingException) {
                    return null
                }
                decoded.append(decodedEscapes)
                index = escapeEnd
            }
            if (decoded.length > maxDecodedCharacters) return null
        }
        return decoded.toString()
    }

    private fun hexValue(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> -1
    }

    private data class ParsedQuery(
        val hasBody: Boolean,
        val body: String,
    )

    private const val ACTION_SENDTO = "android.intent.action.SENDTO"
    private const val BODY_QUERY_PARAMETER = "body"
    private const val MAX_QUERY_PARAMETERS = 64
    private const val MAX_QUERY_PARAMETER_NAME_CHARACTERS = 128
    private const val MAX_DECODED_RECIPIENT_LIST_CHARACTERS =
        (RecipientSet.MAX_RECIPIENTS * ParticipantAddress.MAX_ADDRESS_CHARACTERS) +
            (RecipientSet.MAX_RECIPIENTS - 1)

    // A UTF-16 code unit can require three UTF-8 bytes and nine percent-encoded
    // characters. The fixed allowance covers schemes, query names, and separators.
    private const val MAX_EXTERNAL_URI_CHARACTERS =
        ((Draft.MAX_BODY_CHARACTERS + MAX_DECODED_RECIPIENT_LIST_CHARACTERS) * 9) + 1_024
}

/**
 * Applies the shared New Chat/external-compose recipient-list policy.
 *
 * [raw] is already-decoded user input. URI-specific percent decoding belongs at
 * the external boundary and must happen before this helper is called.
 */
internal fun parseComposeRecipientList(
    raw: String,
    existing: Iterable<ParticipantAddress> = emptyList(),
): RecipientSet.CreationResult {
    if (raw.length > MAX_COMPOSE_RECIPIENT_LIST_CHARACTERS) {
        return RecipientSet.CreationResult.Rejected(RecipientSet.RejectionReason.TOO_LONG)
    }

    val candidates = ArrayList<String>()
    existing.forEach { address ->
        candidates += address.value
        if (candidates.size > RecipientSet.MAX_RECIPIENTS) {
            return RecipientSet.CreationResult.Rejected(RecipientSet.RejectionReason.TOO_MANY)
        }
    }
    if (raw.isNotEmpty()) {
        var recipientStart = 0
        raw.forEachIndexed { index, character ->
            if (character == ',' || character == ';') {
                candidates += raw.substring(recipientStart, index)
                if (candidates.size > RecipientSet.MAX_RECIPIENTS) {
                    return RecipientSet.CreationResult.Rejected(RecipientSet.RejectionReason.TOO_MANY)
                }
                recipientStart = index + 1
            }
        }
        candidates += raw.substring(recipientStart)
    }
    return RecipientSet.parse(candidates)
}

private const val MAX_COMPOSE_RECIPIENT_LIST_CHARACTERS =
    (RecipientSet.MAX_RECIPIENTS * ParticipantAddress.MAX_ADDRESS_CHARACTERS) +
        (RecipientSet.MAX_RECIPIENTS - 1)
