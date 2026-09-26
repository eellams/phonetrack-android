package net.eneiluj.nextcloud.phonetrack.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import net.eneiluj.nextcloud.phonetrack.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phone numbers allowed to send SMS commands (position request, start/stop/create logjob, alarm).
 * The keyword alone is not a secret, so every command is checked against this list;
 * an empty list means SMS commands are ignored.
 */
public final class SmsSenderAllowlist {

    private SmsSenderAllowlist() {
    }

    /** Numbers are stored as one preference string, separated by commas, semicolons or new lines. */
    public static List<String> parse(@Nullable String stored) {
        List<String> numbers = new ArrayList<>();
        if (stored == null) {
            return numbers;
        }
        for (String entry : stored.split("[,;\\n]")) {
            String number = entry.trim();
            if (!number.isEmpty()) {
                numbers.add(number);
            }
        }
        return numbers;
    }

    public static String join(List<String> numbers) {
        return TextUtils.join(", ", numbers);
    }

    /** @return true if the entry looks like a phone number (at least 3 digits, only dialable characters) */
    public static boolean isValidEntry(String entry) {
        return entry.matches("[+]?[0-9 ()./-]*") && entry.replaceAll("[^0-9]", "").length() >= 3;
    }

    public static List<String> getAllowedSenders(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return parse(prefs.getString(context.getString(R.string.pref_key_sms_allowed_senders), ""));
    }

    public static boolean isAllowed(Context context, @Nullable String sender) {
        return isAllowed(sender, getAllowedSenders(context), countryIso(context));
    }

    @VisibleForTesting
    static boolean isAllowed(@Nullable String sender, List<String> allowed, @Nullable String countryIso) {
        if (sender == null || sender.trim().isEmpty()) {
            return false;
        }
        for (String number : allowed) {
            if (samePhoneNumber(sender, number, countryIso)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares numbers the way the telephony stack does, so "+353 87 123 4567" matches
     * "087 123 4567" on an Irish SIM.
     */
    private static boolean samePhoneNumber(String a, String b, @Nullable String countryIso) {
        if (countryIso != null && !countryIso.isEmpty()) {
            return PhoneNumberUtils.areSamePhoneNumber(a, b, countryIso);
        }
        return PhoneNumberUtils.compare(a, b);
    }

    @Nullable
    private static String countryIso(Context context) {
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        if (tm == null) {
            return null;
        }
        String iso = tm.getSimCountryIso();
        if (iso == null || iso.isEmpty()) {
            iso = tm.getNetworkCountryIso();
        }
        return iso == null || iso.isEmpty() ? null : iso.toLowerCase(Locale.ROOT);
    }
}
