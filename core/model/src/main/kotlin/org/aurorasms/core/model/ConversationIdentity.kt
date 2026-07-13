// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.model

/**
 * Phase 3 intentionally routes Telephony conversations without inventing a
 * second numeric identity. Keeping both types makes the authority boundary
 * explicit while these checked conversions centralize the compatibility.
 */
fun ProviderThreadId.asConversationId(): ConversationId = ConversationId(value)

fun ConversationId.asProviderThreadId(): ProviderThreadId = ProviderThreadId(value)
