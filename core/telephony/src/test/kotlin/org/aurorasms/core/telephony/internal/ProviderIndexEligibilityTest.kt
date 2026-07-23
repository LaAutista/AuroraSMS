// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderIndexEligibilityTest {
    @Test
    fun `SMS eligibility admits only rows that always project`() {
        assertEquals("_id > 0 AND thread_id > 0 AND date >= 0", SMS_INDEX_ELIGIBILITY_SELECTION)
    }

    @Test
    fun `MMS eligibility admits only rows with lossless millisecond timestamps`() {
        assertEquals(
            "_id > 0 AND thread_id > 0 AND date BETWEEN 0 AND $MAX_INDEXABLE_MMS_DATE_SECONDS",
            MMS_INDEX_ELIGIBILITY_SELECTION,
        )
        assertTrue(MAX_INDEXABLE_MMS_DATE_SECONDS * 1_000L <= Long.MAX_VALUE)
    }
}
