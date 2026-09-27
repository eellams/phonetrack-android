package net.eneiluj.nextcloud.phonetrack.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.TelephonyManager;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import net.eneiluj.nextcloud.phonetrack.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SmsListenerTest {

    // GSM SMS-DELIVER from +31641600986, text "phonetrack" (GSM 7-bit packed)
    private static final String PDU_HEX =
            "07911326040000F0040B911346610089F60000208062917314080A70F4DB5DA6CBC3E335";

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        shadowOf(app.getSystemService(TelephonyManager.class)).setSimCountryIso("nl");
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putBoolean(app.getString(R.string.pref_key_sms), true)
                // the keyword alone is a position request
                .putString(app.getString(R.string.pref_key_sms_keyword), "phonetrack")
                .apply();
    }

    private void setAllowedSenders(String numbers) {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString(app.getString(R.string.pref_key_sms_allowed_senders), numbers)
                .apply();
    }

    private void receiveSms() {
        Intent intent = new Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
        intent.putExtra("pdus", new Object[]{hexToBytes(PDU_HEX)});
        intent.putExtra("format", "3gpp");
        new SmsListener().onReceive(app, intent);
    }

    @Test
    public void ignoresCommandsWhenNoSenderIsAllowed() {
        setAllowedSenders("");
        receiveSms();
        assertNull(startedLocationReply());
    }

    @Test
    public void ignoresCommandsFromOtherNumbers() {
        setAllowedSenders("+31641600987");
        receiveSms();
        assertNull(startedLocationReply());
    }

    @Test
    public void answersAnAllowedSenderWrittenInNationalFormat() {
        setAllowedSenders("06 41600986");
        receiveSms();
        Intent started = startedLocationReply();
        assertNotNull(started);
        assertEquals("+31641600986", started.getStringExtra("from"));
    }

    /** Other services may have been started too, look for the SMS position reply only. */
    private Intent startedLocationReply() {
        Intent intent;
        while ((intent = shadowOf(app).getNextStartedService()) != null) {
            if (SmsLocationSendService.class.getName().equals(intent.getComponent().getClassName())) {
                return intent;
            }
        }
        return null;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
