package net.eneiluj.nextcloud.phonetrack.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Calendar;

/**
 * Decides when buffered positions of a log job may be uploaded:
 * - the log job's upload policy (allowed SSIDs, unmetered-only, time window)
 * - a per-log job "gate" written after a failed upload, so the app does not
 *   keep hitting a server that is down: while the gate is in the future,
 *   uploads are skipped and the positions keep buffering.
 */
public final class UploadScheduler {

    private static final String PREFS_NAME = "upload_scheduler";
    private static final String KEY_GATE_PREFIX = "retryGate_";

    private final Context context;
    private final AutomationEngine.DeviceState state;
    private final boolean unmetered;

    public UploadScheduler(Context context, AutomationEngine.DeviceState state, boolean unmetered) {
        this.context = context.getApplicationContext();
        this.state = state;
        this.unmetered = unmetered;
    }

    /**
     * True if the log job's policy allows uploading right now and no failure
     * gate is active. A log job without a policy and without a gate uploads.
     */
    public boolean mayUploadNow(long logjobId, @Nullable LogjobAutomation automation) {
        if (isGated(logjobId)) {
            return false;
        }
        if (automation == null || automation.uploadPolicy == null
                || !automation.uploadPolicy.hasAnyRestriction()) {
            return true;
        }
        LogjobAutomation.UploadPolicy policy = automation.uploadPolicy;
        if (!policy.isSsidAllowed(state.currentWifiSsid())) {
            return false;
        }
        if (policy.unmeteredOnly && !unmetered) {
            return false;
        }
        return policy.isTimeAllowed(state.nowMinutes());
    }

    /** Blocks uploads for this log job until now + retryMinutes. */
    public void gateAfterFailure(long logjobId, int retryMinutes) {
        if (retryMinutes <= 0) {
            return;
        }
        long until = System.currentTimeMillis() + retryMinutes * 60_000L;
        prefs().edit().putLong(KEY_GATE_PREFIX + logjobId, until).apply();
    }

    /** Clears the gate, e.g. after a successful upload. */
    public void clearGate(long logjobId) {
        prefs().edit().remove(KEY_GATE_PREFIX + logjobId).apply();
    }

    public boolean isGated(long logjobId) {
        long until = prefs().getLong(KEY_GATE_PREFIX + logjobId, 0L);
        return until > System.currentTimeMillis();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Minutes since midnight of the current time; exposed for tests. */
    public static int nowMinutes() {
        Calendar now = Calendar.getInstance();
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    }
}
