package net.eneiluj.nextcloud.phonetrack.util;

import androidx.annotation.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.Gson;

/**
 * Conditions under which a logjob pauses itself, evaluated before every location
 * acquisition: no GPS is requested at all while paused, so a paused logjob
 * costs (almost) nothing. A logjob with no active condition of a type simply
 * ignores that type; if several conditions are set, any one of them being
 * "in its pause zone" pauses the logjob.
 *
 * Stored as JSON (Gson, already a dependency) in a single AUTOMATION column,
 * so new condition types can be added without a database migration.
 */
public class LogjobAutomation implements Serializable {
    private static final Gson GSON = new Gson();

    /** Pause every day between these times, minutes since midnight; null when unused. */
    public static class TimeWindow implements Serializable {
        public final int startMinutes;
        public final int endMinutes;

        public TimeWindow(int startMinutes, int endMinutes) {
            this.startMinutes = startMinutes;
            this.endMinutes = endMinutes;
        }
    }

    /** Pause while inside this circle; null when unused. */
    public static class Fence implements Serializable {
        public final double latitude;
        public final double longitude;
        /** meters; at least ~100 m to be reliable */
        public final int radius;

        public Fence(double latitude, double longitude, int radius) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.radius = radius;
        }
    }

    /**
     * Alternative sampling parameters used while the device is in battery saver
     * mode: a longer interval and/or a longer minimum distance, so the log job
     * keeps logging (less often) instead of stopping or draining the battery.
     */
    public static class LowPower implements Serializable {
        /** sampling interval in seconds while in battery saver; -1 keeps the normal one */
        public final int minTime;
        /** minimum distance in meters while in battery saver; -1 keeps the normal one */
        public final int minDistance;

        public LowPower(int minTime, int minDistance) {
            this.minTime = minTime;
            this.minDistance = minDistance;
        }

        public boolean isDefault() {
            return minTime < 0 && minDistance < 0;
        }
    }

    @Nullable
    public final TimeWindow timeWindow;
    /** Pause while the device is connected to any of these (case-insensitive) Wi-Fi SSIDs. */
    public final List<String> wifiSsids;
    @Nullable
    public final Fence fence;
    /** Optional low power (battery saver) sampling overrides; null when unused. */
    @Nullable
    public final LowPower lowPower;
    /** Optional upload restrictions; null when uploads are unrestricted. */
    @Nullable
    public final UploadPolicy uploadPolicy;

    public LogjobAutomation(@Nullable TimeWindow timeWindow, @Nullable List<String> wifiSsids,
                            @Nullable Fence fence) {
        this(timeWindow, wifiSsids, fence, null);
    }

    public LogjobAutomation(@Nullable TimeWindow timeWindow, @Nullable List<String> wifiSsids,
                            @Nullable Fence fence, @Nullable LowPower lowPower) {
        this(timeWindow, wifiSsids, fence, lowPower, null);
    }

    public LogjobAutomation(@Nullable TimeWindow timeWindow, @Nullable List<String> wifiSsids,
                            @Nullable Fence fence, @Nullable LowPower lowPower,
                            @Nullable UploadPolicy uploadPolicy) {
        this.timeWindow = timeWindow;
        this.wifiSsids = wifiSsids == null ? new ArrayList<>() : wifiSsids;
        this.fence = fence;
        this.lowPower = lowPower;
        this.uploadPolicy = uploadPolicy;
    }

    public boolean hasAnyCondition() {
        return timeWindow != null || !wifiSsids.isEmpty() || fence != null;
    }

    public boolean hasAnySetting() {
        boolean lowPowerSet = lowPower != null && !lowPower.isDefault();
        boolean uploadPolicySet = uploadPolicy != null
                && (uploadPolicy.hasAnyRestriction()
                    || (uploadPolicy.backoff != null && uploadPolicy.backoff.isSet()));
        return hasAnyCondition() || lowPowerSet || uploadPolicySet;
    }

    /** True if "now" falls in the window. A window may wrap past midnight (e.g. 21:00-06:00). */
    public boolean isTimeWindowActive(int nowMinutes) {
        if (timeWindow == null) {
            return false;
        }
        int start = timeWindow.startMinutes;
        int end = timeWindow.endMinutes;
        if (start == end) {
            return true;
        }
        if (start < end) {
            return nowMinutes >= start && nowMinutes < end;
        }
        return nowMinutes >= start || nowMinutes < end;
    }

    /** True if connected to one of the pause SSIDs (quotes trimmed, case-insensitive). */
    public boolean isWifiActive(@Nullable String currentSsid) {
        if (currentSsid == null || wifiSsids.isEmpty()) {
            return false;
        }
        String trimmed = currentSsid.replace("\"", "").trim();
        if (trimmed.isEmpty() || "<unknown ssid>".equals(trimmed)) {
            return false;
        }
        for (String ssid : wifiSsids) {
            if (ssid != null && ssid.trim().equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /** True if the given location lies inside the fence. */
    public boolean isFenceActive(@Nullable double[] latLon) {
        if (fence == null || latLon == null || latLon.length < 2) {
            return false;
        }
        return SupportUtil.distance(fence.latitude, latLon[0], fence.longitude, latLon[1], 0.0, 0.0)
                <= fence.radius;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    @Nullable
    public static LogjobAutomation fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            LogjobAutomation automation = GSON.fromJson(json, LogjobAutomation.class);
            if (automation == null) {
                return null;
            }
            if (automation.uploadPolicy != null && automation.uploadPolicy.backoff == null
                    && automation.uploadPolicy.retryMinutes != null
                    && automation.uploadPolicy.retryMinutes > 0) {
                // early development builds stored a plain interval: a fixed backoff
                return new LogjobAutomation(automation.timeWindow, automation.wifiSsids,
                        automation.fence, automation.lowPower,
                        new UploadPolicy(automation.uploadPolicy.ssids,
                                automation.uploadPolicy.unmeteredOnly,
                                automation.uploadPolicy.timeWindow,
                                new Backoff(Backoff.FIXED, automation.uploadPolicy.retryMinutes, 0)));
            }
            return automation.hasAnySetting() ? automation : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "21:07" -> 1267; -1 when not parseable. */
    public static int parseHhMm(@Nullable String hhMm) {
        if (hhMm == null) {
            return -1;
        }
        String[] parts = hhMm.trim().split(":");
        if (parts.length != 2) {
            return -1;
        }
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return -1;
            }
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String formatHhMm(int minutes) {
        minutes = Math.max(0, Math.min(24 * 60 - 1, minutes));
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /**
     * When buffered positions may be uploaded: only on given Wi-Fi SSIDs, only
     * on unmetered networks, and/or only between given times (minutes since
     * midnight, wrapping past midnight like the pause window). Positions keep
     * buffering while an upload is not allowed; nothing is lost, just delayed.
     */
    /**
     * How long to wait after a failed upload before trying again. FIXED jumps
     * straight to a fixed interval (e.g. "every 60 minutes while the server is
     * down"); EXPONENTIAL doubles the base interval with every consecutive
     * failure, up to an optional maximum. The counter resets on the first
     * successful upload.
     */
    public static class Backoff implements Serializable {
        public static final int FIXED = 0;
        public static final int EXPONENTIAL = 1;

        public final int strategy;
        /** minutes between attempts (FIXED) or the starting interval (EXPONENTIAL) */
        public final int baseMinutes;
        /** maximum minutes between attempts; 0 = no cap */
        public final int maxMinutes;

        public Backoff(int strategy, int baseMinutes, int maxMinutes) {
            this.strategy = strategy;
            this.baseMinutes = baseMinutes;
            this.maxMinutes = maxMinutes;
        }

        public boolean isSet() {
            return baseMinutes > 0;
        }

        /** Minutes to wait after the given consecutive failure (0-based). */
        public long minutesForAttempt(int attempt) {
            if (baseMinutes <= 0) {
                return 0;
            }
            if (strategy == EXPONENTIAL) {
                long minutes;
                try {
                    minutes = Math.multiplyExact((long) baseMinutes, 1L << Math.min(attempt, 62));
                } catch (ArithmeticException e) {
                    minutes = Long.MAX_VALUE;
                }
                if (maxMinutes > 0) {
                    minutes = Math.min(minutes, maxMinutes);
                }
                return minutes;
            }
            return baseMinutes;
        }
    }

    public static class UploadPolicy implements Serializable {
        /** Only for reading settings written by early development builds of this feature. */
        @Nullable
        private Integer retryMinutes;

        public final List<String> ssids;
        public final boolean unmeteredOnly;
        @Nullable
        public final TimeWindow timeWindow;
        /** how long to wait after a failed upload; null uses the default (WorkManager) backoff */
        @Nullable
        public final Backoff backoff;

        public UploadPolicy(@Nullable List<String> ssids, boolean unmeteredOnly,
                            @Nullable TimeWindow timeWindow, int retryMinutes) {
            this(ssids, unmeteredOnly, timeWindow, retryMinutes <= 0 ? null
                    : new Backoff(Backoff.FIXED, retryMinutes, 0));
        }

        public UploadPolicy(@Nullable List<String> ssids, boolean unmeteredOnly,
                            @Nullable TimeWindow timeWindow, @Nullable Backoff backoff) {
            this.ssids = ssids == null ? new ArrayList<>() : ssids;
            this.unmeteredOnly = unmeteredOnly;
            this.timeWindow = timeWindow;
            this.backoff = backoff;
        }

        public boolean hasAnyRestriction() {
            return !ssids.isEmpty() || unmeteredOnly || timeWindow != null;
        }

        public boolean isSsidAllowed(@Nullable String currentSsid) {
            if (ssids.isEmpty()) {
                return true;
            }
            return currentSsid != null && !currentSsid.replace("\"", "").trim().isEmpty()
                    && containsIgnoreCase(ssids, currentSsid.replace("\"", "").trim());
        }

        public boolean isTimeAllowed(int nowMinutes) {
            if (timeWindow == null) {
                return true;
            }
            int start = timeWindow.startMinutes;
            int end = timeWindow.endMinutes;
            if (start == end) {
                return true;
            }
            if (start < end) {
                return nowMinutes >= start && nowMinutes < end;
            }
            return nowMinutes >= start || nowMinutes < end;
        }

        private static boolean containsIgnoreCase(List<String> list, String value) {
            for (String entry : list) {
                if (entry != null && entry.trim().equalsIgnoreCase(value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
