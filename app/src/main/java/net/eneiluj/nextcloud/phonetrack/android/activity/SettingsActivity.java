package net.eneiluj.nextcloud.phonetrack.android.activity;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;
import com.nextcloud.android.sso.helper.SingleAccountHelper;
import com.nextcloud.android.sso.model.SingleSignOnAccount;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import at.bitfire.cert4android.CustomCertManager;
import net.eneiluj.nextcloud.phonetrack.R;
import net.eneiluj.nextcloud.phonetrack.android.fragment.LoginDialogFragment;
import net.eneiluj.nextcloud.phonetrack.persistence.PhoneTrackSQLiteOpenHelper;
import net.eneiluj.nextcloud.phonetrack.persistence.SessionServerSyncHelper;
import net.eneiluj.nextcloud.phonetrack.service.LoggerService;
import net.eneiluj.nextcloud.phonetrack.util.LocalNetworkAccess;
import net.eneiluj.nextcloud.phonetrack.util.BackgroundTask;
import net.eneiluj.nextcloud.phonetrack.util.CertificateTrust;
import net.eneiluj.nextcloud.phonetrack.util.CredentialStore;
import net.eneiluj.nextcloud.phonetrack.util.EdgeToEdgeUtil;
import net.eneiluj.nextcloud.phonetrack.util.PhoneTrackClientUtil;
import net.eneiluj.nextcloud.phonetrack.util.PhoneTrackClientUtil.LoginStatus;
import net.eneiluj.nextcloud.phonetrack.util.ThemeUtils;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Allows to set Settings like URL, Username and Password for Server-Synchronization
 * Created by stefan on 22.09.15.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = LogjobsListViewActivity.class.getSimpleName();
    private final static int PERMISSION_GET_ACCOUNTS = 42;

    public static final String SETTINGS_USE_SSO = "settingsUseSSO";
    public static final String SETTINGS_SSO_URL = "settingsSSOUrl";
    public static final String SETTINGS_SSO_USERNAME = "settingsSSOUsername";
    public static final String SETTINGS_URL = "settingsUrl";
    public static final String SETTINGS_USERNAME = "settingsUsername";
    /** Legacy location of the account password, see {@link CredentialStore#migrate} */
    public static final String SETTINGS_PASSWORD = "settingsPassword";
    public static final String SETTINGS_KEY_ETAG = "sessions_last_etag";
    public static final String SETTINGS_KEY_LAST_MODIFIED = "sessions_last_modified";
    public static final String SETTINGS_LAST_SELECTED_SESSION_ID = "settingsLastSelectedSessionId";
    public static final String DEFAULT_SETTINGS = "";
    public static final int CREDENTIALS_CHANGED = 3;

    public static final String LOGIN_URL_DATA_KEY_VALUE_SEPARATOR = ":";
    public static final String WEBDAV_PATH_4_0_AND_LATER = "/remote.php/webdav";

    private SharedPreferences preferences = null;

    SwitchMaterial use_sso_switch;
    EditText field_url;
    TextInputLayout url_wrapper;
    TextInputLayout username_wrapper;
    EditText field_username;
    EditText field_password;
    TextInputLayout password_wrapper;
    Button btn_submit;
    View urlWarnHttp;
    private String old_password = "";

    private WebView webView;

    private boolean first_run = false;
    private boolean useWebLogin = true;

    private LoginDialogFragment loginDialogFragment;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View view = LayoutInflater.from(this).inflate(R.layout.activity_settings, null);
        setContentView(view);
        EdgeToEdgeUtil.enable(this);
        Toolbar toolbar = view.findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        use_sso_switch = findViewById(R.id.use_sso_switch);
        field_url = findViewById(R.id.settings_url);
        url_wrapper = findViewById(R.id.settings_url_wrapper);
        username_wrapper = findViewById(R.id.settings_username_wrapper);
        field_username = findViewById(R.id.settings_username);
        field_password = findViewById(R.id.settings_password);
        password_wrapper = findViewById(R.id.settings_password_wrapper);
        btn_submit = findViewById(R.id.settings_submit);
        urlWarnHttp = findViewById(R.id.settings_url_warn_http);

        /*if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS)
                != PackageManager.PERMISSION_GRANTED) {

            if (LoggerService.DEBUG) { Log.d(TAG, "[request get accounts permission]"); }
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.GET_ACCOUNTS},
                    PERMISSION_GET_ACCOUNTS
            );
        }*/

        preferences = PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());

        if (!SessionServerSyncHelper.isConfigured(this)) {
            first_run = true;
            if (getSupportActionBar() != null) {
//                getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            }
        }

        setupListener();

        // Load current Preferences
        use_sso_switch.setChecked(preferences.getBoolean(SETTINGS_USE_SSO, false));
        if (use_sso_switch.isChecked()) {
            url_wrapper.setVisibility(View.INVISIBLE);
            urlWarnHttp.setVisibility(View.GONE);
            btn_submit.setVisibility(View.INVISIBLE);
        }
        // manage switch color
        if (use_sso_switch.isChecked()) {
            use_sso_switch.getTrackDrawable().setColorFilter(ThemeUtils.primaryDarkColor(this), PorterDuff.Mode.SRC_IN);
            use_sso_switch.getThumbDrawable().setColorFilter(ThemeUtils.primaryColor(this), PorterDuff.Mode.MULTIPLY);
        } else {
            use_sso_switch.getTrackDrawable().setColorFilter(ContextCompat.getColor(this, R.color.fg_default_low), PorterDuff.Mode.SRC_IN);
            use_sso_switch.getThumbDrawable().setColorFilter(ContextCompat.getColor(this, R.color.fg_default_high), PorterDuff.Mode.MULTIPLY);
        }
        field_url.setText(preferences.getString(SETTINGS_URL, DEFAULT_SETTINGS));
        field_username.setText(preferences.getString(SETTINGS_USERNAME, DEFAULT_SETTINGS));
        old_password = CredentialStore.getAccountPassword(this);

        field_password.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                login();
                return true;
            }
        });
        field_password.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                setPasswordHint(hasFocus);
            }
        });
        setPasswordHint(false);

        handleSubmitButtonEnabled();
    }

    private void setupListener() {

        use_sso_switch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean isChecked = use_sso_switch.isChecked();

                if (isChecked) {
                    loginDialogFragment = new LoginDialogFragment();
                    loginDialogFragment.show(SettingsActivity.this.getSupportFragmentManager(), "NoticeDialogFragment");

                    use_sso_switch.setChecked(false);
                } else {
                    use_sso_switch.getTrackDrawable().setColorFilter(ContextCompat.getColor(SettingsActivity.this, R.color.fg_default_low), PorterDuff.Mode.SRC_IN);
                    use_sso_switch.getThumbDrawable().setColorFilter(ContextCompat.getColor(SettingsActivity.this, R.color.fg_default_high), PorterDuff.Mode.MULTIPLY);

                    url_wrapper.setVisibility(View.VISIBLE);
                    //urlWarnHttp.setVisibility(View.VISIBLE);
                    // stimulate url field to update http warning
                    String url = field_url.getText().toString();
                    field_url.setText("");
                    field_url.setText(url);
                    btn_submit.setVisibility(View.VISIBLE);

                    // update preferences
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putBoolean(SETTINGS_USE_SSO, false);
                    editor.putString(SETTINGS_URL, DEFAULT_SETTINGS);
                    editor.apply();

                    // empty session list
                    PhoneTrackSQLiteOpenHelper db = PhoneTrackSQLiteOpenHelper.getInstance(view.getContext());
                    db.clearSessions();
                }
            }

        });

        field_url.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                new URLValidatorTask().execute(PhoneTrackClientUtil.formatURL(field_url.getText().toString()));
            }
        });

        field_url.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String url = PhoneTrackClientUtil.formatURL(field_url.getText().toString());

                if (PhoneTrackClientUtil.isHttp(url) && !preferences.getBoolean(SETTINGS_USE_SSO, false)) {
                    urlWarnHttp.setVisibility(View.VISIBLE);
                } else {
                    urlWarnHttp.setVisibility(View.GONE);
                }
                new URLValidatorTask().execute(PhoneTrackClientUtil.formatURL(field_url.getText().toString()));
                //handleSubmitButtonEnabled();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        field_username.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                handleSubmitButtonEnabled();
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        btn_submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                login();
            }
        });
    }

    private void setPasswordHint(boolean hasFocus) {
        boolean unchangedHint = !hasFocus && field_password.getText().toString().isEmpty() && !old_password.isEmpty();
        password_wrapper.setHint(getString(unchangedHint ? R.string.settings_password_unchanged : R.string.settings_password));
    }


    @Override
    protected void onResume() {
        super.onResume();

        /*if ((first_run) && (SessionServerSyncHelper.isConfigured(this))) {
            finish();
        }*/
    }

    protected boolean isValidUrl(String url) {
        return Patterns.WEB_URL.matcher(url).matches();
    }

    private void legacyLogin() {
        String url = field_url.getText().toString().trim();
        String username = field_username.getText().toString();
        String password = field_password.getText().toString();

        if (password.isEmpty()) {
            password = old_password;
        }

        url = PhoneTrackClientUtil.formatURL(url);

        new LoginValidatorTask().executeInParallel(url, username, password);
    }

    private final ActivityResultLauncher<String> localNetworkPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    // go on either way: a refusal shows up as a connection error
                    granted -> startLogin());

    /**
     * Android 17: a server on the local network can only be reached with the "Nearby devices"
     * permission, so ask for it first when the server URL is local.
     */
    private void login() {
        if (!LocalNetworkAccess.isEnforced(this) || LocalNetworkAccess.isGranted(this)) {
            startLogin();
            return;
        }
        final String url = PhoneTrackClientUtil.formatURL(field_url.getText().toString().trim());
        new BackgroundTask<Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                return LocalNetworkAccess.isLocalNetworkUrl(url);
            }

            @Override
            protected void onPostExecute(Boolean local) {
                if (isDestroyed()) {
                    return;
                }
                if (local) {
                    localNetworkPermissionLauncher.launch(LocalNetworkAccess.PERMISSION);
                } else {
                    startLogin();
                }
            }
        }.executeInParallel();
    }

    private void startLogin() {
        if (useWebLogin) {
            webLogin();
        } else {
            legacyLogin();
        }
    }

    /**
     * Obtain the X509Certificate from SslError
     *
     * @param error SslError
     * @return X509Certificate from error
     */
    public static X509Certificate getX509CertificateFromError(SslError error) {
        Bundle bundle = SslCertificate.saveState(error.getCertificate());
        X509Certificate x509Certificate;
        byte[] bytes = bundle.getByteArray("x509-certificate");
        if (bytes == null) {
            x509Certificate = null;
        } else {
            try {
                CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
                Certificate cert = certFactory.generateCertificate(new ByteArrayInputStream(bytes));
                x509Certificate = (X509Certificate) cert;
            } catch (CertificateException e) {
                x509Certificate = null;
            }
        }
        return x509Certificate;
    }

    // JavaScript: Nextcloud's web login flow (login/v2) needs it. The WebView only loads the
    // user's own server.
    @SuppressLint("SetJavaScriptEnabled")
    private void webLogin() {
        setContentView(R.layout.activity_settings_webview);
        webView = findViewById(R.id.login_webview);
        webView.setVisibility(View.GONE);

        final ProgressBar progressBar = findViewById(R.id.login_webview_progress_bar);

        WebSettings settings = webView.getSettings();
        settings.setAllowFileAccess(false);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(getWebLoginUserAgent());
        settings.setSaveFormData(false);
        settings.setSavePassword(false);

        Map<String, String> headers = new HashMap<>();
        headers.put("OCS-APIREQUEST", "true");


        webView.loadUrl(normalizeUrlSuffix(field_url.getText().toString()) + "index.php/login/flow", headers);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("nc://login/")) {
                    parseAndLoginFromWebView(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                progressBar.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }

            // proceed() only runs after the user explicitly trusted this certificate in
            // cert4android's dialog (self-hosted servers often use self-signed certificates);
            // otherwise the load is cancelled
            @SuppressLint("WebViewClientOnReceivedSslError")
            @Override
            public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
                final X509Certificate cert = getX509CertificateFromError(error);
                if (cert == null) {
                    handler.cancel();
                    return;
                }
                final Context appContext = getApplicationContext();
                // blocks until the user decides in cert4android's dialog
                new Thread(() -> {
                    final boolean trusted = CertificateTrust.isTrustedByUser(appContext, cert);
                    Log.d("PhoneTrack", trusted ? "cert accepted" : "cert rejected");
                    runOnUiThread(() -> {
                        if (trusted) {
                            handler.proceed();
                        } else {
                            handler.cancel();
                        }
                    });
                }).start();
            }

        });

        // don't show old login method because SSO+weblogin is enough
        // TODO cleanup
        /*// show snackbar after 60s to switch back to old login method
        new Handler().postDelayed(() -> {
            Snackbar.make(webView, R.string.fallback_weblogin_text, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.fallback_weblogin_back, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            initLegacyLogin(field_url.getText().toString());
                        }
                    }).show();
        }, 60 * 1000);*/
    }

    private String getWebLoginUserAgent() {
        return Build.MANUFACTURER.substring(0, 1).toUpperCase(Locale.getDefault()) +
                Build.MANUFACTURER.substring(1).toLowerCase(Locale.getDefault()) + " " + Build.MODEL;
    }

    private void parseAndLoginFromWebView(String dataString) {
        String prefix = "nc://login/";
        LoginUrlInfo loginUrlInfo = parseLoginDataUrl(prefix, dataString);

        if (loginUrlInfo != null) {
            String url = normalizeUrlSuffix(loginUrlInfo.serverAddress);

            new LoginValidatorTask().executeInParallel(url, loginUrlInfo.username,
                    loginUrlInfo.password);
        }
    }

    /**
     * parses a URI string and returns a login data object with the information from the URI string.
     *
     * @param prefix     URI beginning, e.g. cloud://login/
     * @param dataString the complete URI
     * @return login data
     * @throws IllegalArgumentException when
     */
    private LoginUrlInfo parseLoginDataUrl(String prefix, String dataString) throws IllegalArgumentException {
        if (dataString.length() < prefix.length()) {
            throw new IllegalArgumentException("Invalid login URL detected");
        }
        LoginUrlInfo loginUrlInfo = new LoginUrlInfo();

        // format is basically xxx://login/server:xxx&user:xxx&password while all variables are optional
        String data = dataString.substring(prefix.length());

        // parse data
        String[] values = data.split("&");

        if (values.length < 1 || values.length > 3) {
            // error illegal number of URL elements detected
            throw new IllegalArgumentException("Illegal number of login URL elements detected: " + values.length);
        }

        for (String value : values) {
            if (value.startsWith("user" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR)) {
                loginUrlInfo.username = URLDecoder.decode(
                        value.substring(("user" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR).length()));
            } else if (value.startsWith("password" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR)) {
                loginUrlInfo.password = URLDecoder.decode(
                        value.substring(("password" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR).length()));
            } else if (value.startsWith("server" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR)) {
                loginUrlInfo.serverAddress = URLDecoder.decode(
                        value.substring(("server" + LOGIN_URL_DATA_KEY_VALUE_SEPARATOR).length()));
            } else {
                // error illegal URL element detected
                throw new IllegalArgumentException("Illegal magic login URL element detected: " + value);
            }
        }

        return loginUrlInfo;
    }

    private String normalizeUrlSuffix(String url) {
        if (url.toLowerCase(Locale.ROOT).endsWith(WEBDAV_PATH_4_0_AND_LATER)) {
            return url.substring(0, url.length() - WEBDAV_PATH_4_0_AND_LATER.length());
        }

        if (!url.endsWith("/")) {
            return url + "/";
        }

        return url;
    }

    private void initLegacyLogin(String oldUrl) {
        useWebLogin = false;

        webView.setVisibility(View.INVISIBLE);
        setContentView(R.layout.activity_settings);

        setupListener();

        field_url.setText(oldUrl);
        username_wrapper.setVisibility(View.VISIBLE);
        password_wrapper.setVisibility(View.VISIBLE);
    }

    private void handleSubmitButtonEnabled() {
        // drawable[2] is not null if url is valid, see URLValidatorTask::onPostExecute
        if (field_url.getCompoundDrawables()[2] != null && (username_wrapper.getVisibility() == View.GONE ||
                (username_wrapper.getVisibility() == View.VISIBLE && field_username.getText().length() > 0))) {
            btn_submit.setEnabled(true);
        } else {
            btn_submit.setEnabled(false);
        }
    }

    /************************************ Async Tasks ************************************/

    /**
     * Checks if the given URL returns a valid status code and sets the Check next to the URL-Input Field to visible.
     */
    private class URLValidatorTask extends BackgroundTask<String, Boolean> {

        @Override
        protected void onPreExecute() {
            btn_submit.setEnabled(false);
            field_url.setCompoundDrawables(null, null, null, null);
        }

        @Override
        protected Boolean doInBackground(String... params) {
            //CustomCertManager ccm = SessionServerSyncHelper.getInstance(PhoneTrackSQLiteOpenHelper.getInstance(getApplicationContext())).getCustomCertManager();
            //return PhoneTrackClientUtil.isValidURL(ccm, params[0]);
            Log.v("PLOP", "URL is "+params[0]);
            return (params[0] != null && !params[0].equals("") && isValidUrl(params[0]));
        }

        @Override
        protected void onPostExecute(Boolean o) {
            if (o) {
                Drawable actionDoneDark = ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_check_grey600_24dp);
                actionDoneDark.setBounds(0, 0, actionDoneDark.getIntrinsicWidth(), actionDoneDark.getIntrinsicHeight());
                field_url.setCompoundDrawables(null, null, actionDoneDark, null);
            } else {
                field_url.setCompoundDrawables(null, null, null, null);
            }
            handleSubmitButtonEnabled();
        }
    }

    /**
     * If Log-In-Credentials are correct, save Credentials to Shared Preferences and finish First Run Wizard.
     */
    private class LoginValidatorTask extends BackgroundTask<String, LoginStatus> {
        String url, username, password;

        @Override
        protected void onPreExecute() {
            setInputsEnabled(false);
            btn_submit.setText(R.string.settings_submitting);
        }

        /**
         * @param params url, username and password
         * @return isValidLogin Boolean
         */
        @Override
        protected LoginStatus doInBackground(String... params) {
            url = params[0];
            username = params[1];
            password = params[2];
            CustomCertManager ccm = SessionServerSyncHelper.getInstance(PhoneTrackSQLiteOpenHelper.getInstance(getApplicationContext())).getCustomCertManager();
            return PhoneTrackClientUtil.isValidLogin(ccm, url, username, password);
        }

        @Override
        protected void onPostExecute(LoginStatus status) {
            if (LoginStatus.OK.equals(status)) {
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString(SETTINGS_URL, url);
                editor.putString(SETTINGS_USERNAME, username);
                editor.remove(SETTINGS_KEY_ETAG);
                editor.remove(SETTINGS_KEY_LAST_MODIFIED);
                editor.apply();
                CredentialStore.setAccountPassword(getApplicationContext(), password);

                final Intent data = new Intent();
                data.putExtra(LogjobsListViewActivity.CREDENTIALS_CHANGED, CREDENTIALS_CHANGED);
                setResult(RESULT_OK, data);
                finish();
            } else {
                Log.e("PhoneTrack", "invalid login");
                btn_submit.setText(R.string.settings_submit);
                setInputsEnabled(true);
                Toast.makeText(getApplicationContext(), getString(R.string.error_invalid_login, getString(status.str)), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Sets all Input-Fields and Buttons to enabled or disabled depending on the given boolean.
         *
         * @param enabled - boolean
         */
        private void setInputsEnabled(boolean enabled) {
            btn_submit.setEnabled(enabled);
            field_url.setEnabled(enabled);
            field_username.setEnabled(enabled);
            field_password.setEnabled(enabled);
        }
    }
    /**
     * Data object holding the login url fields.
     */
    public class LoginUrlInfo {
        String serverAddress;
        String username;
        String password;
    }

    public void onAccountChoose(SingleSignOnAccount account) {
        getSupportFragmentManager().beginTransaction().remove(loginDialogFragment).commit();
        //Snackbar.make(, "Account URL: "+account.url, Snackbar.LENGTH_LONG).show();

        url_wrapper.setVisibility(View.INVISIBLE);
        urlWarnHttp.setVisibility(View.GONE);
        btn_submit.setVisibility(View.INVISIBLE);

        SingleAccountHelper.applyCurrentAccount(this, account.name);

        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(SETTINGS_USE_SSO, true);
        editor.putString(SETTINGS_SSO_URL, account.url+"/");
        editor.putString(SETTINGS_SSO_USERNAME, account.name);
        editor.apply();

        final Intent data = new Intent();
        data.putExtra(LogjobsListViewActivity.CREDENTIALS_CHANGED, CREDENTIALS_CHANGED);
        setResult(RESULT_OK, data);
        finish();

        //SingleSignOnAccount ssoAccount = SingleAccountHelper.getCurrentSingleSignOnAccount(context);
        //NextcloudAPI nextcloudAPI = new NextcloudAPI(context, ssoAccount, new GsonBuilder().create(), callback);

    }
}
