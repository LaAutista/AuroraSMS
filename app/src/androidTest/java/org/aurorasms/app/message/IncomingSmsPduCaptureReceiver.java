// SPDX-License-Identifier: GPL-3.0-or-later

package org.aurorasms.app.message;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import java.io.Serializable;

/**
 * Test-APK-only independent observer for the owned emulator SMS journey.
 *
 * <p>AuroraSMS remains the sole SMS_DELIVER receiver. This receiver observes the platform's later
 * SMS_RECEIVED broadcast and atomically records only the exact fixture PDU so instrumentation can
 * independently verify the production delivery fingerprint. It is absent from every app APK.
 * Plain Java is deliberate: a standalone test-APK receiver cannot borrow Kotlin's runtime from the
 * instrumented target process.
 */
public final class IncomingSmsPduCaptureReceiver extends BroadcastReceiver {
    private static final String EXTRA_FORMAT = "format";
    private static final String EXTRA_PDUS = "pdus";
    private static final String EXPECTED_FORMAT = "3gpp";
    private static final String EXPECTED_SENDER = "+15551230017";
    private static final String EXPECTED_BODY = "AuroraSMS modem delivery marker-alpha";
    private static final int MAX_PDU_BYTES = 256 * 1024;
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }
        if (!EXPECTED_FORMAT.equals(intent.getStringExtra(EXTRA_FORMAT))) {
            return;
        }

        SharedPreferences preferences = context.getSharedPreferences(
                IncomingSmsPduCaptureContract.PREFERENCES,
                Context.MODE_PRIVATE);
        if (!preferences.getBoolean(IncomingSmsPduCaptureContract.ARMED, false)
                || preferences.getInt(IncomingSmsPduCaptureContract.COUNT, 0) != 0) {
            return;
        }

        Object[] rawPdus = rawPdusOrNull(intent);
        if (rawPdus == null || rawPdus.length != 1 || !(rawPdus[0] instanceof byte[])) {
            return;
        }
        byte[] pdu = (byte[]) rawPdus[0];
        if (pdu.length == 0 || pdu.length > MAX_PDU_BYTES) {
            return;
        }

        SmsMessage[] messages;
        try {
            messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        } catch (RuntimeException failure) {
            return;
        }
        if (messages == null || messages.length != 1 || messages[0] == null) {
            return;
        }
        String sender = messages[0].getDisplayOriginatingAddress();
        String body = messages[0].getDisplayMessageBody();
        if (sender == null || !EXPECTED_SENDER.equals(sender.trim()) || !EXPECTED_BODY.equals(body)) {
            return;
        }

        preferences.edit()
                .putBoolean(IncomingSmsPduCaptureContract.ARMED, false)
                .putInt(IncomingSmsPduCaptureContract.COUNT, 1)
                .putString(IncomingSmsPduCaptureContract.FORMAT, EXPECTED_FORMAT)
                .putString(IncomingSmsPduCaptureContract.PDU_HEX, toLowerHex(pdu))
                .putLong(
                        IncomingSmsPduCaptureContract.RECEIVED_AT_MILLIS,
                        System.currentTimeMillis())
                .commit();
    }

    private static Object[] rawPdusOrNull(Intent intent) {
        Serializable serialized;
        if (Build.VERSION.SDK_INT >= 33) {
            serialized = intent.getSerializableExtra(EXTRA_PDUS, Serializable.class);
        } else {
            serialized = legacySerializableExtra(intent);
        }
        return serialized instanceof Object[] ? (Object[]) serialized : null;
    }

    @SuppressWarnings("deprecation")
    private static Serializable legacySerializableExtra(Intent intent) {
        return intent.getSerializableExtra(EXTRA_PDUS);
    }

    private static String toLowerHex(byte[] bytes) {
        char[] encoded = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            encoded[index * 2] = HEX_DIGITS[value >>> 4];
            encoded[index * 2 + 1] = HEX_DIGITS[value & 0x0f];
        }
        return new String(encoded);
    }
}

final class IncomingSmsPduCaptureContract {
    static final String PREFERENCES = "aurora_incoming_sms_pdu_capture_v1";
    static final String ARMED = "armed";
    static final String COUNT = "capture_count";
    static final String ARMED_AT_MILLIS = "armed_at_millis";
    static final String FORMAT = "format";
    static final String PDU_HEX = "pdu_hex";
    static final String RECEIVED_AT_MILLIS = "received_at_millis";

    private IncomingSmsPduCaptureContract() {}
}
