package net.eneiluj.nextcloud.phonetrack.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class UploadSchedulerTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    private static class FixedState implements AutomationEngine.DeviceState {
        public int nowMinutes() { return 12 * 60; }
        public String currentWifiSsid() { return "HomeWiFi"; }
        public double[] lastKnownLatLon() { return null; }
    }

    @Test
    public void unrestrictedLogjobMayUpload() {
        UploadScheduler scheduler = new UploadScheduler(context, new FixedState(), true);
        assertTrue(scheduler.mayUploadNow(1, null));
        assertTrue(scheduler.mayUploadNow(1, new LogjobAutomation(null, null, null, null, null)));
    }

    @Test
    public void policyIsRespected() {
        UploadScheduler scheduler = new UploadScheduler(context, new FixedState(), false);
        LogjobAutomation wifiOnly = new LogjobAutomation(null, null, null, null,
                new LogjobAutomation.UploadPolicy(java.util.Arrays.asList("OtherWiFi"), false, null, 0));
        assertFalse(scheduler.mayUploadNow(1, wifiOnly));

        LogjobAutomation unmeteredOnly = new LogjobAutomation(null, null, null, null,
                new LogjobAutomation.UploadPolicy(null, true, null, 0));
        assertFalse(scheduler.mayUploadNow(1, unmeteredOnly));

        UploadScheduler unmeteredScheduler = new UploadScheduler(context, new FixedState(), true);
        LogjobAutomation matching = new LogjobAutomation(null, null, null, null,
                new LogjobAutomation.UploadPolicy(java.util.Arrays.asList("HomeWiFi"), true, null, 0));
        assertTrue(unmeteredScheduler.mayUploadNow(1, matching));
    }

    @Test
    public void failureGateBlocksAndClears() {
        UploadScheduler scheduler = new UploadScheduler(context, new FixedState(), true);
        scheduler.gateAfterFailure(1, 60);
        assertFalse(scheduler.mayUploadNow(1, null));

        scheduler.clearGate(1);
        assertTrue(scheduler.mayUploadNow(1, null));
    }

    @Test
    public void gateIsPerLogjob() {
        UploadScheduler scheduler = new UploadScheduler(context, new FixedState(), true);
        scheduler.gateAfterFailure(1, 60);
        assertFalse(scheduler.mayUploadNow(1, null));
        assertTrue(scheduler.mayUploadNow(2, null));
        scheduler.clearGate(2);
    }
}
