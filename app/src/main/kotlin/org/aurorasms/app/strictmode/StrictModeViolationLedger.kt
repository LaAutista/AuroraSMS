// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.strictmode

import java.util.EnumMap

internal enum class SanitizedViolationType {
    DISK_READ,
    DISK_WRITE,
    NETWORK,
    SLOW_CALL,
    LEAKED_CLOSABLE,
    LEAKED_REGISTRATION,
    ACTIVITY_LEAK,
    FILE_URI,
    OTHER,
}

/** Retains only fixed categories and counts: never messages, paths, content, or stack traces. */
internal object StrictModeViolationLedger {
    private val counts = EnumMap<SanitizedViolationType, Long>(SanitizedViolationType::class.java)

    @Synchronized
    fun record(platformClassSimpleName: String) {
        val type = when (platformClassSimpleName) {
            "DiskReadViolation" -> SanitizedViolationType.DISK_READ
            "DiskWriteViolation" -> SanitizedViolationType.DISK_WRITE
            "NetworkViolation" -> SanitizedViolationType.NETWORK
            "CustomViolation" -> SanitizedViolationType.SLOW_CALL
            "LeakedClosableViolation" -> SanitizedViolationType.LEAKED_CLOSABLE
            "LeakedRegistrationViolation" -> SanitizedViolationType.LEAKED_REGISTRATION
            "InstanceCountViolation" -> SanitizedViolationType.ACTIVITY_LEAK
            "FileUriExposedViolation" -> SanitizedViolationType.FILE_URI
            else -> SanitizedViolationType.OTHER
        }
        counts[type] = counts.getOrDefault(type, 0L) + 1L
    }

    @Synchronized
    fun snapshot(): Map<SanitizedViolationType, Long> = counts.toMap()

    @Synchronized
    fun reset() {
        counts.clear()
    }
}
