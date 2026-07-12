// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.notifications

import android.content.Intent
import org.aurorasms.core.model.ConversationId

/** Supplied by :app so this module never names or navigates directly to an app Activity. */
fun interface NotificationIntentFactory {
    fun conversationIntent(conversationId: ConversationId): Intent
}
