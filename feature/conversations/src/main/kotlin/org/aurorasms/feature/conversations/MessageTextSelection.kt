// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

/**
 * Returns exactly the selected range and nothing else. Reversed selections are
 * normalized; collapsed or invalid ranges fail closed without copying text.
 */
fun selectedMessageTextOrNull(
    displayedText: String,
    selectionStart: Int,
    selectionEnd: Int,
): String? {
    if (selectionStart !in 0..displayedText.length || selectionEnd !in 0..displayedText.length) {
        return null
    }
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    if (start == end) return null
    return displayedText.substring(start, end)
}
