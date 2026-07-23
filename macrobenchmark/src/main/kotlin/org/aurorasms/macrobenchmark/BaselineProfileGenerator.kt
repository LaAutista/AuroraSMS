// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Before
    fun verifySyntheticIsolation() {
        FixtureController.requireSyntheticIsolation()
    }

    @Test
    fun startupInboxAndScroll() {
        FixtureController.seed(FixtureShape.INBOX_20K)
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            maxIterations = 5,
            stableIterations = 2,
            includeInStartupProfile = true,
        ) {
            startInbox()
            repeat(4) { scrollInboxOnce() }
            openFirstConversation()
            returnToInbox()
        }
    }

    @Test
    fun threadPrependFlingAttachmentPathAndBack() {
        FixtureController.seed(FixtureShape.THREAD_250K)
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            maxIterations = 5,
            stableIterations = 2,
        ) {
            startInbox()
            openFirstConversation()
            prependOlderMessages()
            flingForFiveSeconds(towardOlder = false)
            returnToInbox()
        }
    }

    @Test
    fun searchExactOldJumpAndBack() {
        FixtureController.seed(FixtureShape.SEARCH_500K)
        val query = FixtureController.oldestSearchToken()
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            maxIterations = 5,
            stableIterations = 2,
        ) {
            startInbox()
            openSearch()
            openSearchHit(enterSearchQuery(query))
            returnToInbox()
        }
    }
}
