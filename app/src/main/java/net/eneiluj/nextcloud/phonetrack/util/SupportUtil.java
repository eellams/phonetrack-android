package net.eneiluj.nextcloud.phonetrack.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.text.Html;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.widget.TextView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import at.bitfire.cert4android.CustomCertManager;
import net.eneiluj.nextcloud.phonetrack.R;

/**
 * Some helper functionality in alike the Android support library.
 * Currently, it offers methods for working with HTML string resources.
 */
public class SupportUtil {

    /** The SmsManager system service (SmsManager.getDefault() is deprecated). */
    public static SmsManager getSmsManager(Context context) {
        return context.getSystemService(SmsManager.class);
    }

    /**
     * @return true if precise or approximate location has been granted.
     * This is what Android 14+ requires before a location-type foreground service may start.
     */
    public static boolean hasForegroundLocationPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return true if location may be accessed while no app UI is visible
     * (e.g. when tracking is started at boot).
     */
    public static boolean hasBackgroundLocationPermission(Context context) {
        return hasForegroundLocationPermission(context)
                && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Creates a {@link Spanned} from a HTML string.
     *
     * @param source Source string with HTML markup
     * @return Spannable for using in a {@link TextView}
     */
    public static Spanned fromHtml(String source) {
        return Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY);
    }

    /**
     * Fills a {@link TextView} with HTML content and activates links in that {@link TextView}.
     *
     * @param view       The {@link TextView} which should be filled.
     * @param stringId   The string resource containing HTML tags (escaped by <code>&lt;</code>)
     * @param formatArgs Arguments for the string resource.
     */
    public static void setHtml(TextView view, int stringId, Object... formatArgs) {
        view.setText(SupportUtil.fromHtml(view.getResources().getString(stringId, formatArgs)));
        view.setMovementMethod(LinkMovementMethod.getInstance());
    }

    /**
     * Create a new {@link HttpURLConnection} for strUrl.
     * If protocol equals https, then install CustomCertManager in {@link SSLContext}.
     *
     * @param ccm
     * @param strUrl
     * @return HttpURLConnection with custom trust manager
     * @throws MalformedURLException
     * @throws IOException
     */
    public static HttpURLConnection getHttpURLConnection(CustomCertManager ccm, String strUrl) throws MalformedURLException, IOException {
        URL url = new URL(strUrl);
        HttpURLConnection httpCon = (HttpURLConnection) url.openConnection();
        if (ccm != null && url.getProtocol().equals("https")) {
            HttpsURLConnection httpsCon = (HttpsURLConnection) httpCon;
            httpsCon.setHostnameVerifier(ccm.new HostnameVerifier(httpsCon.getHostnameVerifier()));
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{ccm}, null);
                httpsCon.setSSLSocketFactory(sslContext.getSocketFactory());
            } catch (NoSuchAlgorithmException e) {
                Log.e(SupportUtil.class.getSimpleName(), "Exception", e);
                // ignore, use default TrustManager
            } catch (KeyManagementException e) {
                Log.e(SupportUtil.class.getSimpleName(), "Exception", e);
                // ignore, use default TrustManager
            }
        }
        return httpCon;
    }

    /**
     * Calculate distance between two points in latitude and longitude taking
     * into account height difference. If you are not interested in height
     * difference pass 0.0. Uses Haversine method as its base.
     *
     * lat1, lon1 Start point lat2, lon2 End point el1 Start altitude in meters
     * el2 End altitude in meters
     * @returns Distance in Meters
     */
    public static double distance(double lat1, double lat2, double lon1,
                                  double lon2, @Nullable Double el1p, @Nullable Double el2p) {

        final int R = 6371; // Radius of the earth
        double el1 = (el1p != null) ? el1p : 0;
        double el2 = (el2p != null) ? el2p : 0;

        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distance = R * c * 1000; // convert to meters

        double height = el1 - el2;

        distance = Math.pow(distance, 2) + Math.pow(height, 2);

        return Math.sqrt(distance);
    }

    public static String formatDuration(long seconds, Context context) {
        long absSeconds = Math.abs(seconds);
        String positive;
        if (absSeconds >= (3600 * 24)) {
            positive = String.format(Locale.ENGLISH,
                    "%d %s, %02d:%02d:%02d",
                    absSeconds / (3600 * 24),
                    context.getString(R.string.duration_days),
                    (absSeconds % (3600 * 24)) / 3600,
                    (absSeconds % 3600) / 60,
                    absSeconds % 60);
        }
        else {
            positive = String.format(Locale.ENGLISH,
                    "%02d:%02d:%02d",
                    absSeconds / 3600,
                    (absSeconds % 3600) / 60,
                    absSeconds % 60);
        }
        return seconds < 0 ? "-" + positive : positive;
    }

    public static String formatDistance(double meters, Context context) {
        String result;
        DecimalFormat df2 = new DecimalFormat( "#,###,###,##0.00" );

        if (meters < 1000) {
            result = df2.format(meters) + " m";
        }
        else {
            result = df2.format(meters/1000) + " km";
        }
        return result;
    }

    public static boolean isAirplaneModeOn(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;

    }

    public static boolean isDozing(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager.isDeviceIdleMode() &&
                !powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static String getAppVersionName(Context context) {
        String versionName = "???";
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            versionName = pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(SupportUtil.class.getSimpleName(), "Failed to get app version name", e);
            e.printStackTrace();
        }
        Log.d(SupportUtil.class.getSimpleName(), "app version name is " + versionName);
        return versionName;
    }
}
