// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony

import kotlinx.coroutines.test.runTest
import org.aurorasms.core.model.AuroraSubscriptionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SubscriptionRepositoryTest {
    @Test
    fun `validation returns the exact active sms capable subscription`() = runTest {
        val selected = subscription(id = 8, smsCapable = true)
        val repository = FakeSubscriptionRepository(
            SubscriptionSnapshot.Available(
                listOf(subscription(id = 4, smsCapable = true), selected),
            ),
        )

        val result = repository.validateActiveSmsSubscription(selected.id)

        assertEquals(ActiveSmsSubscriptionValidation.Valid(selected), result)
    }

    @Test
    fun `validation distinguishes inactive and non sms capable selections`() = runTest {
        val repository = FakeSubscriptionRepository(
            SubscriptionSnapshot.Available(listOf(subscription(id = 4, smsCapable = false))),
        )

        assertSame(
            ActiveSmsSubscriptionValidation.NotSmsCapable,
            repository.validateActiveSmsSubscription(AuroraSubscriptionId(4)),
        )
        assertSame(
            ActiveSmsSubscriptionValidation.Inactive,
            repository.validateActiveSmsSubscription(AuroraSubscriptionId(5)),
        )
    }

    @Test
    fun `validation rejects duplicate rows for the exact selected id as ambiguous`() = runTest {
        val repository = FakeSubscriptionRepository(
            SubscriptionSnapshot.Available(
                listOf(
                    subscription(id = 4, smsCapable = true),
                    subscription(id = 4, smsCapable = true),
                ),
            ),
        )

        assertSame(
            ActiveSmsSubscriptionValidation.Ambiguous,
            repository.validateActiveSmsSubscription(AuroraSubscriptionId(4)),
        )
    }

    @Test
    fun `validation preserves every enumeration failure`() = runTest {
        val selected = AuroraSubscriptionId(4)

        assertSame(
            ActiveSmsSubscriptionValidation.PermissionDenied,
            FakeSubscriptionRepository(SubscriptionSnapshot.PermissionDenied)
                .validateActiveSmsSubscription(selected),
        )
        assertSame(
            ActiveSmsSubscriptionValidation.FeatureUnavailable,
            FakeSubscriptionRepository(SubscriptionSnapshot.FeatureUnavailable)
                .validateActiveSmsSubscription(selected),
        )
        assertSame(
            ActiveSmsSubscriptionValidation.PlatformUnavailable,
            FakeSubscriptionRepository(SubscriptionSnapshot.PlatformUnavailable)
                .validateActiveSmsSubscription(selected),
        )
    }

    private fun subscription(id: Int, smsCapable: Boolean): ActiveSubscription = ActiveSubscription(
        id = AuroraSubscriptionId(id),
        slotIndex = id,
        displayLabel = "synthetic-$id",
        smsCapable = smsCapable,
    )

    private class FakeSubscriptionRepository(
        private val snapshot: SubscriptionSnapshot,
    ) : SubscriptionRepository {
        override suspend fun activeSubscriptions(): SubscriptionSnapshot = snapshot
    }
}
