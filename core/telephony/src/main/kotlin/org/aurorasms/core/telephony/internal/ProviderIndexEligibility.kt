// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.core.telephony.internal

import android.provider.BaseColumns
import android.provider.Telephony

/** The exact provider-row universe that can produce an index projection. */
internal val SMS_INDEX_ELIGIBILITY_SELECTION: String =
    "${BaseColumns._ID} > 0 AND " +
        "${Telephony.Sms.THREAD_ID} > 0 AND " +
        "${Telephony.Sms.DATE} >= 0"

/** MMS stores seconds; this upper bound keeps conversion to milliseconds lossless. */
internal const val MAX_INDEXABLE_MMS_DATE_SECONDS: Long = Long.MAX_VALUE / 1_000L

/** The exact provider-row universe that can produce an index projection. */
internal val MMS_INDEX_ELIGIBILITY_SELECTION: String =
    "${BaseColumns._ID} > 0 AND " +
        "${Telephony.Mms.THREAD_ID} > 0 AND " +
        "${Telephony.Mms.DATE} BETWEEN 0 AND $MAX_INDEXABLE_MMS_DATE_SECONDS"
