package net.eneiluj.nextcloud.phonetrack.android.activity;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.NavUtils;
import androidx.preference.CheckBoxPreference;
import androidx.preference.PreferenceManager;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;

import net.eneiluj.nextcloud.phonetrack.R;
import net.eneiluj.nextcloud.phonetrack.android.fragment.PreferencesFragment;
import net.eneiluj.nextcloud.phonetrack.util.EdgeToEdgeUtil;
import net.eneiluj.nextcloud.phonetrack.util.ThemeUtils;

import java.security.Permission;

/**
 * Allows to change application settings.
 */
public class PreferencesActivity extends AppCompatActivity {

    private static final String TAG = PreferencesActivity.class.getSimpleName();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        View view = LayoutInflater.from(this).inflate(R.layout.activity_preferences, null);
        setContentView(view);
        EdgeToEdgeUtil.enable(this);
        Toolbar toolbar = view.findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container_view, new PreferencesFragment(), "preftag")
                .commit();

        // onBackPressed() is no longer called with predictive back (default from targetSdk 36)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                NavUtils.navigateUpFromSameTask(PreferencesActivity.this);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case PreferencesFragment.PERMISSION_SMS_SEND_AND_RECEIVE:
                if (grantResults.length > 0) {
                    Log.d(TAG, "[permission SEND'N'RECEIVE SMS result] "+grantResults[0]);
                    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    }
                    else {
                        disableSms();
                    }
                }
                break;
        }
    }

    private void disableSms() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this.getApplicationContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putBoolean(getString(R.string.pref_key_sms), false);
        editor.apply();

        PreferencesFragment frag = ((PreferencesFragment) getSupportFragmentManager().findFragmentByTag("preftag"));
        if (frag != null) {
            frag.disableSms();
        }
    }
}
