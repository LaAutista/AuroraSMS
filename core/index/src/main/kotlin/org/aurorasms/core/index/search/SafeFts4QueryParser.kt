// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.search

import java.text.Normalizer
import java.util.Locale
import org.aurorasms.core.index.SearchValidationFailure

/**
 * Converts user text into the deliberately small subset of FTS4 MATCH syntax AuroraSMS owns.
 * Raw caller text is never returned as an expression.
 */
object SafeFts4QueryParser {
    const val MAX_QUERY_CODE_UNITS: Int = 256
    const val MAX_TERMS: Int = 16
    const val MAX_PHRASES: Int = 4
    const val MAX_TERM_CODE_UNITS: Int = 64
    const val MAX_TERMS_PER_PHRASE: Int = 8

    fun parse(rawQuery: String): ParsedFts4Query {
        if (rawQuery.length > MAX_QUERY_CODE_UNITS) {
            return ParsedFts4Query.Invalid(SearchValidationFailure.QUERY_TOO_LONG)
        }
        if (rawQuery.codePoints().anyMatch(Character::isISOControl)) {
            return ParsedFts4Query.Invalid(SearchValidationFailure.CONTROL_CHARACTER)
        }

        val normalized = Normalizer.normalize(rawQuery.trim(), Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return ParsedFts4Query.NoQuery

        val atoms = mutableListOf<QueryAtom>()
        val segment = StringBuilder()
        var quoted = false
        var phraseCount = 0
        var totalTermCount = 0

        fun consumeSegment(): SearchValidationFailure? {
            val terms = tokenize(segment.toString())
            segment.setLength(0)
            terms.firstOrNull { it.length > MAX_TERM_CODE_UNITS }?.let {
                return SearchValidationFailure.TERM_TOO_LONG
            }
            if (quoted) {
                if (terms.isEmpty()) return SearchValidationFailure.EMPTY_PHRASE
                if (terms.size > MAX_TERMS_PER_PHRASE) {
                    return SearchValidationFailure.PHRASE_TOO_LONG
                }
                phraseCount += 1
                if (phraseCount > MAX_PHRASES) {
                    return SearchValidationFailure.TOO_MANY_PHRASES
                }
                atoms += QueryAtom.Phrase(terms)
            } else {
                atoms += terms.map(QueryAtom::Term)
            }
            totalTermCount += terms.size
            return if (totalTermCount > MAX_TERMS) {
                SearchValidationFailure.TOO_MANY_TERMS
            } else {
                null
            }
        }

        normalized.forEach { character ->
            if (character == '"') {
                consumeSegment()?.let { return ParsedFts4Query.Invalid(it) }
                quoted = !quoted
            } else {
                segment.append(character)
            }
        }
        if (quoted) return ParsedFts4Query.Invalid(SearchValidationFailure.UNMATCHED_QUOTE)
        consumeSegment()?.let { return ParsedFts4Query.Invalid(it) }
        if (atoms.isEmpty()) return ParsedFts4Query.NoQuery

        val prefixAtomIndex = atoms.indexOfLast { it is QueryAtom.Term }
            .takeIf { it == atoms.lastIndex }
        val expression = atoms.mapIndexed { index, atom ->
            when (atom) {
                is QueryAtom.Phrase -> quote(atom.terms.joinToString(" "))
                is QueryAtom.Term -> {
                    val guardedPrefix = index == prefixAtomIndex &&
                        atom.value.codePoints().filter(Character::isLetterOrDigit).count() >= 2L
                    quote(atom.value) + if (guardedPrefix) "*" else ""
                }
            }
        }.joinToString(" AND ")

        val canonicalQuery = atoms.joinToString(separator = "\u001f") { atom ->
            when (atom) {
                is QueryAtom.Phrase -> "p:${atom.terms.joinToString("\u001e")}"
                is QueryAtom.Term -> "t:${atom.value}"
            }
        }
        return ParsedFts4Query.Ready(
            matchExpression = expression,
            canonicalQuery = canonicalQuery,
        )
    }

    private fun tokenize(input: String): List<String> {
        val terms = mutableListOf<String>()
        val token = StringBuilder()

        fun flush() {
            if (token.isNotEmpty()) {
                terms += token.toString()
                token.setLength(0)
            }
        }

        input.codePoints().forEach { codePoint ->
            when {
                Character.isLetterOrDigit(codePoint) -> token.appendCodePoint(codePoint)
                token.isNotEmpty() && isCombiningMark(codePoint) -> token.appendCodePoint(codePoint)
                else -> flush()
            }
        }
        flush()
        return terms
    }

    private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }

    private fun quote(value: String): String = "\"$value\""

    private sealed interface QueryAtom {
        data class Term(val value: String) : QueryAtom
        data class Phrase(val terms: List<String>) : QueryAtom
    }
}

sealed interface ParsedFts4Query {
    data class Ready(
        val matchExpression: String,
        val canonicalQuery: String,
    ) : ParsedFts4Query

    data object NoQuery : ParsedFts4Query

    data class Invalid(val reason: SearchValidationFailure) : ParsedFts4Query
}
