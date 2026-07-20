// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.voice

import android.Manifest
import org.aurorasms.app.messagingOnboardingPermissions
import org.aurorasms.core.model.AuroraSubscriptionId
import org.aurorasms.core.model.COMPOSER_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.INCOMING_MMS_OPERATION_ID_BOUNDARY
import org.aurorasms.core.model.ProviderThreadId
import org.aurorasms.core.telephony.RecipientSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMemoControllerPolicyTest {
    @Test
    fun microphonePermissionIsExcludedFromMessagingOnboarding() {
        val onboarding = messagingOnboardingPermissions()

        assertFalse(Manifest.permission.RECORD_AUDIO in onboarding)
        assertEquals(onboarding.size, onboarding.distinct().size)
    }

    @Test
    fun operationIdsStayInTheOrdinaryTransportNamespaceAndNeverUseZero() {
        val candidates = ArrayDeque(listOf(0L, COMPOSER_OPERATION_ID_BOUNDARY + 17L))

        val result = nextVoiceMemoOperationId { candidates.removeFirst() }

        assertEquals(17L, result)
        assertTrue(result in 1L until INCOMING_MMS_OPERATION_ID_BOUNDARY)
    }

    @Test
    fun targetRedactsRecipientAndSignatureText() {
        val recipients = RecipientSet.parse(listOf("+15551234567")) as
            RecipientSet.CreationResult.Valid
        val target = VoiceMemoTarget(
            providerThreadId = ProviderThreadId(11L),
            recipients = recipients.recipients,
            subscriptionId = AuroraSubscriptionId(1),
            caption = "private synthetic signature",
        )

        assertFalse(target.toString().contains("+15551234567"))
        assertFalse(target.toString().contains("private synthetic signature"))
        assertTrue(target.toString().contains("REDACTED"))
    }
}
