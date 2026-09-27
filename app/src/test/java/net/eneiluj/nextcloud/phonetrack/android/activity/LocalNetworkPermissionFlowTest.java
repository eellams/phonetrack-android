package net.eneiluj.nextcloud.phonetrack.android.activity;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.TextView;

import android.app.AlertDialog;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import net.eneiluj.nextcloud.phonetrack.R;
import net.eneiluj.nextcloud.phonetrack.persistence.DbTestSupport;
import net.eneiluj.nextcloud.phonetrack.util.LocalNetworkAccess;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

import java.time.Duration;

/** Android 17 (targetSdk 37): the startup permission steps ask for local network access. */
@RunWith(AndroidJUnit4.class)
public class LocalNetworkPermissionFlowTest {

    private Application app;
    private ActivityController<LogjobsListViewActivity> controller;

    @Before
    public void setUp() {
        DbTestSupport.resetDatabase();
        app = ApplicationProvider.getApplicationContext();
        // the earlier steps are already done, so the local network step is the next one
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS);
        shadowOf(app.getSystemService(PowerManager.class)).setIgnoringBatteryOptimizations(app.getPackageName(), true);
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.destroy();
        }
        DbTestSupport.resetDatabase();
    }

    private void startWithServer(String url) throws Exception {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putString("settingsUrl", url).commit();
        controller = Robolectric.buildActivity(LogjobsListViewActivity.class).setup();
        // the server check runs in the background, its result comes back on the main thread
        for (int i = 0; i < 50 && ShadowDialog.getLatestDialog() == null; i++) {
            Thread.sleep(20);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
        }
    }

    @Test
    public void explainsThenRequestsTheLocalNetworkPermissionForALanServer() throws Exception {
        startWithServer("https://192.168.1.10/nextcloud");

        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("no explanation shown", dialog);
        TextView message = dialog.findViewById(android.R.id.message);
        assertEquals(app.getString(R.string.local_network_permission_message), message.getText().toString());

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertArrayEquals(new String[]{LocalNetworkAccess.PERMISSION},
                shadowOf(controller.get()).getLastRequestedPermission().requestedPermissions);
    }

    @Test
    public void doesNotAskForAPublicServer() throws Exception {
        startWithServer("https://8.8.8.8/nextcloud");

        assertFalse(ShadowDialog.getLatestDialog() != null && ShadowDialog.getLatestDialog().isShowing());
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(LocalNetworkAccess.PERMISSION));
    }
}
