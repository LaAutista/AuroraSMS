// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.testing

import org.aurorasms.core.telephony.SubscriptionRepository
import org.aurorasms.core.telephony.SubscriptionSnapshot

class FakeSubscriptionRepository(
    var snapshot: SubscriptionSnapshot = SubscriptionSnapshot.Available(emptyList()),
) : SubscriptionRepository {
    var requestCount: Int = 0
        private set

    override suspend fun activeSubscriptions(): SubscriptionSnapshot {
        requestCount += 1
        return snapshot
    }
}
