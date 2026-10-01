package net.eneiluj.nextcloud.phonetrack.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * Decides when buffered positions of a log job may be uploaded:
 * - the log job's upload policy (allowed SSIDs, unmetered-only, time window)
 * - a per-log job "gate" written after a failed upload, so the app does not
 *   keep hitting a server that is down. The gate duration follows the log
 *   job's backoff schema: a fixed interval, or exponential growth capped at
 *   an optional maximum. The failure counter resets on the first success.
 */
public final class UploadScheduler {

    private static final String PREFS_NAME = "upload_scheduler";
    private static final String KEY_GATE_PREFIX = "retryGate_";
    private static final String KEY_ATTEMPT_PREFIX = "retryAttempts_";

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

    /**
     * Called after a failed upload: counts the failure and writes the gate
     * for the delay the log job's backoff schema asks for. Does nothing when
     * the log job has no backoff configured (the default WorkManager retry
     * behaviour then applies).
     */
    public void gateAfterFailure(long logjobId, @Nullable LogjobAutomation.Backoff backoff) {
        if (backoff == null || !backoff.isSet()) {
            return;
        }
        int attempt = getAttemptCount(logjobId);
        long minutes = backoff.minutesForAttempt(attempt);
        long until = System.currentTimeMillis() + minutes * 60_000L;
        prefs().edit()
                .putLong(KEY_GATE_PREFIX + logjobId, until)
                .putInt(KEY_ATTEMPT_PREFIX + logjobId, attempt + 1)
                .apply();
    }

    /** Clears the gate and the failure counter, e.g. after a successful upload. */
    public void clearGate(long logjobId) {
        prefs().edit()
                .remove(KEY_GATE_PREFIX + logjobId)
                .remove(KEY_ATTEMPT_PREFIX + logjobId)
                .apply();
    }

    public boolean isGated(long logjobId) {
        long until = prefs().getLong(KEY_GATE_PREFIX + logjobId, 0L);
        return until > System.currentTimeMillis();
    }

    private int getAttemptCount(long logjobId) {
        return prefs().getInt(KEY_ATTEMPT_PREFIX + logjobId, 0);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
