// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app

import org.aurorasms.core.model.ProviderThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppRouteStackTest {
    @Test
    fun restoredRoutesMustRemainInboxRooted() {
        assertNull(normalizeRestoredRoutes(listOf(AppRoute.Appearance)))
        assertNull(normalizeRestoredRoutes(listOf(AppRoute.Inbox, AppRoute.Inbox)))
        assertEquals(
            listOf(AppRoute.Inbox, AppRoute.Appearance),
            normalizeRestoredRoutes(listOf(AppRoute.Inbox, AppRoute.Appearance)),
        )
        assertEquals(
            listOf(AppRoute.Inbox, AppRoute.SpamBlocked),
            normalizeRestoredRoutes(listOf(AppRoute.Inbox, AppRoute.SpamBlocked)),
        )
        assertEquals(
            listOf(AppRoute.Inbox, AppRoute.Thread(ProviderThreadId(4L))),
            normalizeRestoredRoutes(
                listOf(AppRoute.Inbox, AppRoute.Thread(ProviderThreadId(4L))),
            ),
        )
    }

    @Test
    fun notificationDismissalGenerationSurvivesRotationButInvalidatesRetainedEditors() {
        assertEquals(false, shouldDismissScopedEditor(observedGeneration = 4L, currentGeneration = 4L))
        assertEquals(true, shouldDismissScopedEditor(observedGeneration = 4L, currentGeneration = 5L))
    }

    @Test
    fun restoredThreadStateEntriesArePositiveAndUniqueWithoutChangingValidIds() {
        val repaired = repairThreadStateEntryIds(
            listOf(
                AppRoute.Inbox,
                AppRoute.Thread(ProviderThreadId(4L), stateEntryId = 0L),
                AppRoute.Thread(ProviderThreadId(5L), stateEntryId = 7L),
                AppRoute.Thread(ProviderThreadId(6L), stateEntryId = 7L),
            ),
        ).filterIsInstance<AppRoute.Thread>()

        assertEquals(3, repaired.map { it.stateEntryId }.toSet().size)
        assertEquals(true, repaired.all { it.stateEntryId > 0L })
        assertEquals(7L, repaired[1].stateEntryId)
    }
}
