// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

/** A conservative presentation projection; the provider/index body remains authoritative. */
data class ReactionFallback(
    val kind: ReactionFallbackKind,
    val targetText: String,
) {
    init {
        require(targetText.isNotBlank()) { "Reaction target text cannot be blank" }
        require(targetText.length <= MAXIMUM_REACTION_TARGET_CHARACTERS) {
            "Reaction target text is too large"
        }
    }

    override fun toString(): String = "ReactionFallback(kind=$kind, targetLength=${targetText.length}, REDACTED)"
}

enum class ReactionFallbackKind {
    LIKE_ADDED,
    LOVE_ADDED,
    LAUGH_ADDED,
    EMPHASIS_ADDED,
    QUESTION_ADDED,
    DISLIKE_ADDED,
    LIKE_REMOVED,
    LOVE_REMOVED,
    LAUGH_REMOVED,
    EMPHASIS_REMOVED,
    QUESTION_REMOVED,
    DISLIKE_REMOVED,
}

/**
 * Parses only exact whole-message English fallback forms. Anything uncertain is
 * deliberately rendered as the original SMS instead of being misclassified.
 */
fun parseReactionFallback(body: String): ReactionFallback? {
    if (body.length !in MINIMUM_REACTION_BODY_CHARACTERS..MAXIMUM_REACTION_BODY_CHARACTERS) {
        return null
    }
    val rule = REACTION_RULES.firstOrNull { body.startsWith(it.prefixWithSpace) } ?: return null
    val quoted = body.substring(rule.prefixWithSpace.length)
    val target = when {
        quoted.length >= 3 && quoted.first() == '\u201c' && quoted.last() == '\u201d' ->
            quoted.substring(1, quoted.lastIndex)
        quoted.length >= 3 && quoted.first() == '"' && quoted.last() == '"' ->
            quoted.substring(1, quoted.lastIndex)
        else -> return null
    }
    if (
        target.isBlank() ||
        target.length > MAXIMUM_REACTION_TARGET_CHARACTERS ||
        target.any { it == '\n' || it == '\r' || it.isISOControl() }
    ) {
        return null
    }
    return ReactionFallback(rule.kind, target)
}

private data class ReactionRule(
    val kind: ReactionFallbackKind,
    val prefix: String,
) {
    val prefixWithSpace: String = "$prefix "
}

private val REACTION_RULES = listOf(
    ReactionRule(ReactionFallbackKind.LIKE_ADDED, "Liked"),
    ReactionRule(ReactionFallbackKind.LOVE_ADDED, "Loved"),
    ReactionRule(ReactionFallbackKind.LAUGH_ADDED, "Laughed at"),
    ReactionRule(ReactionFallbackKind.EMPHASIS_ADDED, "Emphasized"),
    ReactionRule(ReactionFallbackKind.QUESTION_ADDED, "Questioned"),
    ReactionRule(ReactionFallbackKind.DISLIKE_ADDED, "Disliked"),
    ReactionRule(ReactionFallbackKind.LIKE_REMOVED, "Removed a like from"),
    ReactionRule(ReactionFallbackKind.LOVE_REMOVED, "Removed a heart from"),
    ReactionRule(ReactionFallbackKind.LAUGH_REMOVED, "Removed a laugh from"),
    ReactionRule(ReactionFallbackKind.EMPHASIS_REMOVED, "Removed an exclamation from"),
    ReactionRule(ReactionFallbackKind.QUESTION_REMOVED, "Removed a question mark from"),
    ReactionRule(ReactionFallbackKind.DISLIKE_REMOVED, "Removed a dislike from"),
)

const val MAXIMUM_REACTION_TARGET_CHARACTERS: Int = 4_096
private const val MINIMUM_REACTION_BODY_CHARACTERS: Int = 9
private const val MAXIMUM_REACTION_BODY_CHARACTERS: Int = 4_160
