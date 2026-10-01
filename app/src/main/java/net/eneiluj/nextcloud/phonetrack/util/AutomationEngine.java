package net.eneiluj.nextcloud.phonetrack.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Calendar;

/**
 * Decides whether a logjob is currently paused by its automation conditions
 * (time window, Wi-Fi SSIDs, location fence). Pure decision logic lives in
 * {@link LogjobAutomation}; this class supplies the device state at
 * evaluation time and is kept behind an interface so the decision logic can
 * be unit-tested without it.
 */
public class AutomationEngine {

    /** Supplies the state the conditions are evaluated against. */
    public interface DeviceState {
        int nowMinutes();

        @Nullable
        String currentWifiSsid();

        @Nullable
        double[] lastKnownLatLon();
    }

    public static final class RealDeviceState implements DeviceState {
        private final Context context;

        public RealDeviceState(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public int nowMinutes() {
            Calendar now = Calendar.getInstance();
            return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        }

        @Override
        @Nullable
        @SuppressWarnings({"deprecation"})
        public String currentWifiSsid() {
            // reading the SSID requires location permission (and location services
            // on some versions); without it Android returns "<unknown ssid>"
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return null;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return null;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null;
            }
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return null;
            }
            WifiInfo info = wm.getConnectionInfo();
            String ssid = info != null ? info.getSSID() : null;
            return ssid == null ? null : ssid;
        }

        @Override
        @Nullable
        public double[] lastKnownLatLon() {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return null;
            }
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location last = lm.getLastKnownLocation(provider);
                if (last != null && (best == null || last.getTime() > best.getTime())) {
                    best = last;
                }
            }
            return best == null ? null : new double[]{best.getLatitude(), best.getLongitude()};
        }
    }

    private final DeviceState state;

    public AutomationEngine(DeviceState state) {
        this.state = state;
    }

    /** True if any of the logjob's conditions is currently active. */
    public boolean isPaused(@Nullable LogjobAutomation automation) {
        if (automation == null || !automation.hasAnyCondition()) {
            return false;
        }
        if (automation.isTimeWindowActive(state.nowMinutes())) {
            return true;
        }
        if (automation.isWifiActive(state.currentWifiSsid())) {
            return true;
        }
        return automation.isFenceActive(state.lastKnownLatLon());
    }
}
