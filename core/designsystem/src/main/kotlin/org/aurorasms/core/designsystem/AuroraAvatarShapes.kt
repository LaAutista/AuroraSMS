// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape

fun AuroraAvatarMask.toShape(): Shape = when (this) {
    AuroraAvatarMask.CIRCLE -> CircleShape
    AuroraAvatarMask.ROUNDED_SQUARE -> RoundedCornerShape(percent = 22)
    AuroraAvatarMask.SQUIRCLE -> RoundedCornerShape(percent = 38)
    AuroraAvatarMask.HEXAGON -> HexagonShape
}

private val HexagonShape = GenericShape { size, _ ->
    moveTo(size.width * 0.25f, 0f)
    lineTo(size.width * 0.75f, 0f)
    lineTo(size.width, size.height * 0.5f)
    lineTo(size.width * 0.75f, size.height)
    lineTo(size.width * 0.25f, size.height)
    lineTo(0f, size.height * 0.5f)
    close()
}
