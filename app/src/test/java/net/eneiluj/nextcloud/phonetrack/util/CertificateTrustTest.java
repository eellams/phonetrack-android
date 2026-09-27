package net.eneiluj.nextcloud.phonetrack.util;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import at.bitfire.cert4android.CustomCertManager;
import at.bitfire.cert4android.CustomCertStore;

/**
 * Runs in the background (no activity started, no notification permission), where cert4android
 * can't ask the user: unknown certificates must be rejected right away, not after a timeout.
 */
@RunWith(AndroidJUnit4.class)
public class CertificateTrustTest {

    private final Context context = ApplicationProvider.getApplicationContext();
    private X509Certificate selfSigned;

    @Before
    public void setUp() throws Exception {
        // CustomCertStore is a process-wide singleton: start every test from the files on disk
        Field instance = CustomCertStore.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("self-signed.crt")) {
            selfSigned = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    @Test
    public void rejectsAnUnknownSelfSignedCertificate() {
        CustomCertManager manager = CertificateTrust.newCertManager(context);
        assertThrows(CertificateException.class,
                () -> manager.checkServerTrusted(new X509Certificate[]{selfSigned}, "RSA"));
    }

    @Test
    public void trustsACertificateTheUserAccepted() throws Exception {
        CustomCertStore.Companion.getInstance(context).setTrustedByUser(selfSigned);

        CertificateTrust.newCertManager(context).checkServerTrusted(new X509Certificate[]{selfSigned}, "RSA");
        assertTrue(CertificateTrust.isTrustedByUser(context, selfSigned));
    }

    @Test
    public void resetForgetsAcceptedCertificates() {
        CustomCertStore.Companion.getInstance(context).setTrustedByUser(selfSigned);

        CertificateTrust.resetUserDecisions(context);

        CustomCertManager manager = CertificateTrust.newCertManager(context);
        assertThrows(CertificateException.class,
                () -> manager.checkServerTrusted(new X509Certificate[]{selfSigned}, "RSA"));
    }

    @Test
    public void keepsCertificatesAcceptedWithTheOldCert4android() throws Exception {
        // what cert4android 7814052's CustomCertService wrote when the user accepted a certificate
        KeyStore old = KeyStore.getInstance(KeyStore.getDefaultType());
        old.load(null, null);
        old.setCertificateEntry("accepted", selfSigned);
        File file = new File(context.getDir("KeyStore", Context.MODE_PRIVATE), "KeyStore.bks");
        try (FileOutputStream out = new FileOutputStream(file)) {
            old.store(out, null);
        }

        CertificateTrust.newCertManager(context).checkServerTrusted(new X509Certificate[]{selfSigned}, "RSA");
    }
}
