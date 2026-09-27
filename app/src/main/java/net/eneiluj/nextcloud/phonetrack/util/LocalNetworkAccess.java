package net.eneiluj.nextcloud.phonetrack.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import net.eneiluj.nextcloud.phonetrack.android.activity.SettingsActivity;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjob;
import net.eneiluj.nextcloud.phonetrack.persistence.PhoneTrackSQLiteOpenHelper;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Android 17 blocks connections to the local network (private and link-local addresses,
 * mDNS ".local" names) for apps targeting API 37 unless they hold the runtime permission
 * ACCESS_LOCAL_NETWORK (in the "Nearby devices" group). Self-hosted Nextcloud / PhoneTrack
 * servers are often on the LAN, so ask for it when a configured server is local.
 *
 * Enforced on Android 17+ now that the app targets it (API 37); older Android versions don't
 * have the restriction, so nobody there sees the prompt.
 */
public final class LocalNetworkAccess {

    /** Manifest.permission.ACCESS_LOCAL_NETWORK (as a literal: the constant is newer than minSdk) */
    public static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    /** Android 17 */
    @VisibleForTesting
    static final int ENFORCED_FROM_SDK = 37;

    private LocalNetworkAccess() {
    }

    /** True when this device and this build are subject to local network protection. */
    public static boolean isEnforced(Context context) {
        return isEnforced(Build.VERSION.SDK_INT, context.getApplicationInfo().targetSdkVersion);
    }

    @VisibleForTesting
    static boolean isEnforced(int deviceSdk, int targetSdk) {
        return deviceSdk >= ENFORCED_FROM_SDK && targetSdk >= ENFORCED_FROM_SDK;
    }

    public static boolean isGranted(Context context) {
        return ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return true if the permission is enforced, not granted, and one of the URLs is on the
     * local network. Resolves host names, so call it off the main thread.
     */
    @WorkerThread
    public static boolean isNeededFor(Context context, Iterable<String> urls) {
        if (!isEnforced(context) || isGranted(context)) {
            return false;
        }
        for (String url : urls) {
            if (isLocalNetworkUrl(url)) {
                return true;
            }
        }
        return false;
    }

    /** The servers this app talks to: the Nextcloud account (unless SSO) and the logjob URLs. */
    @WorkerThread
    public static List<String> configuredServerUrls(Context context) {
        List<String> urls = new ArrayList<>();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(SettingsActivity.SETTINGS_USE_SSO, false)) {
            // with SSO the Nextcloud Files app makes the requests
            urls.add(prefs.getString(SettingsActivity.SETTINGS_URL, ""));
        }
        for (DBLogjob logjob : PhoneTrackSQLiteOpenHelper.getInstance(context).getLogjobs()) {
            urls.add(logjob.getUrl());
        }
        return urls;
    }

    /** True if the URL's host is a ".local" name or resolves to a local network address. */
    @WorkerThread
    public static boolean isLocalNetworkUrl(@Nullable String url) {
        String host = host(url);
        if (host == null) {
            return false;
        }
        if (host.endsWith(".local")) {
            return true;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isLocalNetworkAddress(address)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // unknown host (or no network right now): nothing to ask for
        }
        return false;
    }

    /** RFC 1918 and link-local IPv4, link-local and unique local (fc00::/7) IPv6. Not loopback. */
    @VisibleForTesting
    static boolean isLocalNetworkAddress(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return false;
        }
        if (address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
    }

    @Nullable
    private static String host(@Nullable String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String candidate = url.trim();
        if (!candidate.contains("://")) {
            candidate = "https://" + candidate;
        }
        try {
            String host = new URI(candidate).getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            // URI keeps the brackets of IPv6 literals
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
