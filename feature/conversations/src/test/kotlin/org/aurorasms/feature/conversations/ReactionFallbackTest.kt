// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionFallbackTest {
    @Test
    fun exactAddAndRemoveFormsParseWithoutChangingTheSourceText() {
        val cases = mapOf(
            "Liked “hello”" to ReactionFallbackKind.LIKE_ADDED,
            "Loved “hello”" to ReactionFallbackKind.LOVE_ADDED,
            "Laughed at “hello”" to ReactionFallbackKind.LAUGH_ADDED,
            "Emphasized “hello”" to ReactionFallbackKind.EMPHASIS_ADDED,
            "Questioned “hello”" to ReactionFallbackKind.QUESTION_ADDED,
            "Disliked “hello”" to ReactionFallbackKind.DISLIKE_ADDED,
            "Removed a like from “hello”" to ReactionFallbackKind.LIKE_REMOVED,
            "Removed a heart from “hello”" to ReactionFallbackKind.LOVE_REMOVED,
            "Removed a laugh from “hello”" to ReactionFallbackKind.LAUGH_REMOVED,
            "Removed an exclamation from “hello”" to ReactionFallbackKind.EMPHASIS_REMOVED,
            "Removed a question mark from “hello”" to ReactionFallbackKind.QUESTION_REMOVED,
            "Removed a dislike from “hello”" to ReactionFallbackKind.DISLIKE_REMOVED,
        )

        cases.forEach { (source, expectedKind) ->
            val original = source.toCharArray().concatToString()
            val parsed = checkNotNull(parseReactionFallback(source))
            assertEquals(expectedKind, parsed.kind)
            assertEquals("hello", parsed.targetText)
            assertEquals(original, source)
        }
    }

    @Test
    fun straightQuotesAreAcceptedButLookalikesRemainRaw() {
        assertEquals(
            ReactionFallbackKind.LIKE_ADDED,
            parseReactionFallback("Liked \"x\"")?.kind,
        )
        listOf(
            "liked “hello”",
            "Liked hello",
            "Liked “hello” trailing",
            "Liked “”",
            "Liked “line one\nline two”",
            "Reacted to “hello”",
            "Removed a maybe from “hello”",
        ).forEach { assertNull(parseReactionFallback(it)) }
    }

    @Test
    fun boundsAndToStringDoNotRetainOrExposeMessageContent() {
        assertNull(parseReactionFallback("Liked “${"x".repeat(MAXIMUM_REACTION_TARGET_CHARACTERS + 1)}”"))
        val parsed = checkNotNull(parseReactionFallback("Liked “private words”"))

        assertTrue(parsed.toString().contains("targetLength=13"))
        assertTrue(!parsed.toString().contains("private words"))
    }
}
