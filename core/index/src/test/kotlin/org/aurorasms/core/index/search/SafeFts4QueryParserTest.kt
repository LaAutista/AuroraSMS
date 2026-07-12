// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.index.search

import org.aurorasms.core.index.SearchValidationFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeFts4QueryParserTest {
    @Test
    fun emptyAndPunctuationOnlyInputDoesNotIssueAQuery() {
        assertEquals(ParsedFts4Query.NoQuery, SafeFts4QueryParser.parse("   "))
        assertEquals(ParsedFts4Query.NoQuery, SafeFts4QueryParser.parse("()+-:* 😀"))
    }

    @Test
    fun termsAreNfcNormalizedLowercasedAndBoundToParserOwnedSyntax() {
        assertReady("\"café\" AND \"later\"*", "  CAFE\u0301, Later  ")
    }

    @Test
    fun quotedTextBecomesOnePhraseWithoutAPrefix() {
        assertReady("\"meet me tonight\"", "\"Meet me tonight\"")
    }

    @Test
    fun onlyAFinalUnquotedTermReceivesAGuardedPrefix() {
        assertReady("\"one\" AND \"two words\"", "one \"two words\"")
        assertReady("\"two words\" AND \"later\"*", "\"two words\" later")
        assertReady("\"a\"", "a")
    }

    @Test
    fun callerOperatorsAreLiteralSearchTerms() {
        assertReady(
            "\"body\" AND \"or\" AND \"near\" AND \"sender\" AND \"drop\"*",
            "body: OR NEAR(sender) -drop*",
        )
    }

    @Test
    fun unicodeRtlAndCombiningMarksRemainSearchable() {
        assertReady("\"שלום\" AND \"مرحبا\"*", "שלום / مرحبا")
        assertReady("\"ḋ\"", "d\u0307")
    }

    @Test
    fun emojiIsADelimiterAndCannotBecomeSyntax() {
        assertReady("\"hello\" AND \"world\"*", "hello😀world")
    }

    @Test
    fun controlsAreRejected() {
        assertInvalid(SearchValidationFailure.CONTROL_CHARACTER, "hello\nworld")
    }

    @Test
    fun unmatchedAndEmptyQuotesAreRejected() {
        assertInvalid(SearchValidationFailure.UNMATCHED_QUOTE, "\"hello")
        assertInvalid(SearchValidationFailure.EMPTY_PHRASE, "before \"\" after")
        assertInvalid(SearchValidationFailure.EMPTY_PHRASE, "\" 😀 \"")
    }

    @Test
    fun originalQueryLengthIsBoundedBeforeNormalization() {
        assertInvalid(
            SearchValidationFailure.QUERY_TOO_LONG,
            "a".repeat(SafeFts4QueryParser.MAX_QUERY_CODE_UNITS + 1),
        )
    }

    @Test
    fun individualTermLengthIsBounded() {
        assertInvalid(
            SearchValidationFailure.TERM_TOO_LONG,
            "a".repeat(SafeFts4QueryParser.MAX_TERM_CODE_UNITS + 1),
        )
    }

    @Test
    fun totalTermCountIsBoundedAcrossPhrasesAndTerms() {
        assertInvalid(
            SearchValidationFailure.TOO_MANY_TERMS,
            (1..(SafeFts4QueryParser.MAX_TERMS + 1)).joinToString(" ") { "t$it" },
        )
    }

    @Test
    fun phraseCountAndPhraseTermCountAreBounded() {
        assertInvalid(
            SearchValidationFailure.TOO_MANY_PHRASES,
            (1..(SafeFts4QueryParser.MAX_PHRASES + 1)).joinToString(" ") { "\"p$it\"" },
        )
        assertInvalid(
            SearchValidationFailure.PHRASE_TOO_LONG,
            "\"${(1..(SafeFts4QueryParser.MAX_TERMS_PER_PHRASE + 1)).joinToString(" ") { "p$it" }}\"",
        )
    }

    @Test
    fun readyResultContainsAStableSyntaxFreeCanonicalForm() {
        val result = SafeFts4QueryParser.parse("Alpha \"Beta Gamma\"")
        assertTrue(result is ParsedFts4Query.Ready)
        result as ParsedFts4Query.Ready
        assertEquals("t:alpha\u001fp:beta\u001egamma", result.canonicalQuery)
    }

    private fun assertReady(expectedExpression: String, input: String) {
        val result = SafeFts4QueryParser.parse(input)
        assertTrue("Expected Ready but was $result", result is ParsedFts4Query.Ready)
        assertEquals(expectedExpression, (result as ParsedFts4Query.Ready).matchExpression)
    }

    private fun assertInvalid(expected: SearchValidationFailure, input: String) {
        assertEquals(
            ParsedFts4Query.Invalid(expected),
            SafeFts4QueryParser.parse(input),
        )
    }
}
