// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

/** Arms and clears the standalone test-APK PDU observer. */
public final class IncomingSmsPduCaptureArmActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean armed = getSharedPreferences(
                        IncomingSmsPduCaptureContract.PREFERENCES,
                        Context.MODE_PRIVATE)
                .edit()
                .clear()
                .putBoolean(IncomingSmsPduCaptureContract.ARMED, true)
                .putInt(IncomingSmsPduCaptureContract.COUNT, 0)
                .putLong(
                        IncomingSmsPduCaptureContract.ARMED_AT_MILLIS,
                        System.currentTimeMillis())
                .commit();
        setResult(armed ? RESULT_OK : RESULT_CANCELED);
        finish();
    }
}
