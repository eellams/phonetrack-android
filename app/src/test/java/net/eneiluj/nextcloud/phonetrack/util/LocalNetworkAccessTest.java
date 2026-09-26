package net.eneiluj.nextcloud.phonetrack.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import net.eneiluj.nextcloud.phonetrack.android.activity.SettingsActivity;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjob;
import net.eneiluj.nextcloud.phonetrack.persistence.DbTestSupport;
import net.eneiluj.nextcloud.phonetrack.persistence.PhoneTrackSQLiteOpenHelper;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class LocalNetworkAccessTest {

    @Test
    public void recognisesLocalNetworkServers() {
        // literal addresses: no DNS involved
        assertTrue(LocalNetworkAccess.isLocalNetworkUrl("https://192.168.1.10/nextcloud"));
        assertTrue(LocalNetworkAccess.isLocalNetworkUrl("http://10.0.0.5:8080"));
        assertTrue(LocalNetworkAccess.isLocalNetworkUrl("172.16.3.4"));
        assertTrue(LocalNetworkAccess.isLocalNetworkUrl("https://169.254.10.1"));
        assertTrue(LocalNetworkAccess.isLocalNetworkUrl("https://[fd12:3456::1]/"));
        assertTrue(LocalNetworkAccess.isLocalNetworkUrl("https://[fe80::1]/"));
        assertTrue(LocalNetworkAccess.isLocalNetworkUrl("https://nextcloud.local/"));
        assertTrue(LocalNetworkAccess.isLocalNetworkUrl("https://NAS.LOCAL"));
    }

    @Test
    public void publicAndLoopbackServersNeedNoPermission() {
        assertFalse(LocalNetworkAccess.isLocalNetworkUrl("https://8.8.8.8/"));
        assertFalse(LocalNetworkAccess.isLocalNetworkUrl("https://172.32.0.1/"));
        assertFalse(LocalNetworkAccess.isLocalNetworkUrl("https://[2001:db8::1]/"));
        assertFalse(LocalNetworkAccess.isLocalNetworkUrl("http://127.0.0.1:8080"));
        assertFalse(LocalNetworkAccess.isLocalNetworkUrl(""));
        assertFalse(LocalNetworkAccess.isLocalNetworkUrl(null));
        assertFalse(LocalNetworkAccess.isLocalNetworkUrl("not a url at all ::"));
    }

    @Test
    public void onlyEnforcedOnAndroid17ForAppsTargetingIt() {
        assertFalse(LocalNetworkAccess.isEnforced(36, 36));
        assertFalse(LocalNetworkAccess.isEnforced(37, 36));
        assertFalse(LocalNetworkAccess.isEnforced(36, 37));
        assertTrue(LocalNetworkAccess.isEnforced(37, 37));
    }

    @Test
    public void listsTheAccountServerUnlessSsoIsUsedAndEveryLogjobServer() {
        DbTestSupport.resetDatabase();
        Context context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(SettingsActivity.SETTINGS_USE_SSO, false)
                .putString(SettingsActivity.SETTINGS_URL, "https://nas.local/nextcloud")
                .commit();
        PhoneTrackSQLiteOpenHelper.getInstance(context).addLogjob(new DBLogjob(0, "custom",
                "http://192.168.1.20/log", "", "", 60, 0, 50, false, false, false, 60,
                false, false, 0, null, null, false));

        List<String> urls = LocalNetworkAccess.configuredServerUrls(context);
        assertTrue(urls.toString(), urls.contains("https://nas.local/nextcloud"));
        assertTrue(urls.toString(), urls.contains("http://192.168.1.20/log"));

        // with SSO the Nextcloud Files app makes the account requests, not this app
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(SettingsActivity.SETTINGS_USE_SSO, true).commit();
        urls = LocalNetworkAccess.configuredServerUrls(context);
        assertFalse(urls.toString(), urls.contains("https://nas.local/nextcloud"));
        assertTrue(urls.toString(), urls.contains("http://192.168.1.20/log"));
        DbTestSupport.resetDatabase();
    }

    @Test
    public void neededForALocalServerUntilGranted() {
        // the app targets Android 17 and the tests emulate it: the permission is enforced
        Context context = ApplicationProvider.getApplicationContext();
        assertTrue(LocalNetworkAccess.isEnforced(context));
        assertTrue(LocalNetworkAccess.isNeededFor(context, Arrays.asList("https://8.8.8.8", "https://192.168.1.10")));
        assertFalse(LocalNetworkAccess.isNeededFor(context, Arrays.asList("https://8.8.8.8")));

        shadowOf((Application) context).grantPermissions(LocalNetworkAccess.PERMISSION);
        assertFalse(LocalNetworkAccess.isNeededFor(context, Arrays.asList("https://192.168.1.10")));
    }
}
