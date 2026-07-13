// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.feature.conversations

data class WindowAnchor<K>(
    val stableKey: K,
    val scrollOffsetPixels: Int,
)

data class WindowMutation<W, K>(
    val window: W,
    val restoreAnchor: WindowAnchor<K>?,
)
