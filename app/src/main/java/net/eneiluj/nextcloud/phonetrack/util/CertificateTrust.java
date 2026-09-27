package net.eneiluj.nextcloud.phonetrack.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.preference.PreferenceManager;

import net.eneiluj.nextcloud.phonetrack.R;

import java.security.cert.X509Certificate;

import at.bitfire.cert4android.CustomCertManager;
import at.bitfire.cert4android.CustomCertStore;
import at.bitfire.cert4android.SettingsProvider;

/**
 * Certificates the user trusts one at a time (self-hosted servers often have self-signed ones),
 * handled by cert4android. Its store keeps the decisions in an app-private key store file.
 *
 * Checks block until the user decides (or cert4android times out), so never call them on the
 * main thread. While the app is visible cert4android opens its dialog directly, otherwise it
 * shows a notification.
 */
public final class CertificateTrust {

    private CertificateTrust() {
    }

    /** Trust manager for HTTPS connections to the user's server. */
    @WorkerThread
    public static CustomCertManager newCertManager(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        return new CustomCertManager(CustomCertStore.Companion.getInstance(appContext), new Settings(appContext));
    }

    /**
     * Asks the user about a certificate the system doesn't trust (for the login WebView), unless
     * they already decided about it.
     *
     * @return true if the user trusts it
     */
    @WorkerThread
    public static boolean isTrustedByUser(@NonNull Context context, @NonNull X509Certificate cert) {
        // like cert4android's hostname check: the system already rejected it, so only the
        // user's decision counts
        return CustomCertStore.Companion.getInstance(context.getApplicationContext())
                .isTrusted(new X509Certificate[]{cert}, "RSA", false, true);
    }

    /** Forgets every certificate the user accepted or rejected (rewrites a small file). */
    public static void resetUserDecisions(@NonNull Context context) {
        CustomCertStore.Companion.getInstance(context.getApplicationContext()).clearUserDecisions();
    }

    private static final class Settings implements SettingsProvider {

        private final Context context;

        Settings(Context context) {
            this.context = context;
        }

        @Nullable
        @Override
        public Boolean getAppInForeground() {
            return ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
        }

        @Override
        public boolean getTrustSystemCerts() {
            // read on every check, so changing the setting applies without a restart
            return PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean(context.getString(R.string.pref_key_trust_system_certs), true);
        }
    }
}
