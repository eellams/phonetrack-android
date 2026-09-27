package net.eneiluj.nextcloud.phonetrack.android.fragment;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.RecyclerView;

import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.Toast;


import net.eneiluj.nextcloud.phonetrack.R;

import net.eneiluj.nextcloud.phonetrack.android.activity.EditMapsLogjobActivity;
import net.eneiluj.nextcloud.phonetrack.android.preference.ColorPreference;
import net.eneiluj.nextcloud.phonetrack.android.activity.LogjobsListViewActivity;
import net.eneiluj.nextcloud.phonetrack.android.activity.MapActivity;
import net.eneiluj.nextcloud.phonetrack.android.activity.SyslogManagerActivity;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjob;
import net.eneiluj.nextcloud.phonetrack.persistence.PhoneTrackSQLiteOpenHelper;
import net.eneiluj.nextcloud.phonetrack.service.LoggerService;
import net.eneiluj.nextcloud.phonetrack.util.CertificateTrust;
import net.eneiluj.nextcloud.phonetrack.util.MapUtils;
import net.eneiluj.nextcloud.phonetrack.util.PhoneTrack;
import net.eneiluj.nextcloud.phonetrack.util.SmsSenderAllowlist;
import net.eneiluj.nextcloud.phonetrack.util.ThemeUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PreferencesFragment extends PreferenceFragmentCompat implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback{

    public final static String UPDATED_PROVIDERS = "net.eneiluj.nextcloud.phonetrack.UPDATED_PROVIDERS";
    public final static String UPDATED_PROVIDERS_VALUE = "net.eneiluj.nextcloud.phonetrack.UPDATED_PROVIDERS_VALUE";

    public final static int PERMISSION_SMS_SEND_AND_RECEIVE = 4;
    private final static int import_file_cmd = 123;

    private static final String TAG = PreferencesFragment.class.getSimpleName();

    private List<String> providersList;
    private ActionBar toolbar;

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen pref) {
        caller.setPreferenceScreen(pref);
        return true;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootkey) {

    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RecyclerView recyclerView = getListView();
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(recyclerView.getContext(),
                DividerItemDecoration.VERTICAL);
        recyclerView.addItemDecoration(dividerItemDecoration);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        // hide the keyboard when this window gets the focus
        getActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        Preference openSyslog = findPreference(getString(R.string.pref_key_open_syslog));
        openSyslog.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent createIntent = new Intent(getContext(), SyslogManagerActivity.class);
                startActivity(createIntent);
                return true;
            }
        });


        Preference resetTrust = findPreference(getString(R.string.pref_key_reset_trust));
        resetTrust.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                CertificateTrust.resetUserDecisions(requireContext());
                Toast.makeText(getActivity(), getString(R.string.settings_cert_reset_toast), Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext());

        Preference loadOsmdroidPref = findPreference(getString(R.string.pref_key_osmdroid_load));
        loadOsmdroidPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent intent = new Intent()
                        .setType("*/*")
                        .setAction(Intent.ACTION_GET_CONTENT);

                startActivityForResult(Intent.createChooser(intent, "Select a file"), import_file_cmd);
                return true;
            }
        });

        Preference deleteOsmdroidPref = findPreference(getString(R.string.pref_key_osmdroid_delete));
        deleteOsmdroidPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                MapUtils.showDeleteMapFileDialog(getContext());
                return true;
            }
        });

        final CheckBoxPreference useServerColorPref = (CheckBoxPreference) findPreference(getString(R.string.pref_key_use_server_color));

        Boolean useServerColor = sp.getBoolean(getString(R.string.pref_key_use_server_color), false);
        if (useServerColor) {
            findPreference(getString(R.string.pref_key_color)).setVisible(false);
        }

        useServerColorPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                Boolean useServerColor = (Boolean) newValue;
                if (useServerColor) {
                    findPreference(getString(R.string.pref_key_color)).setVisible(false);
                }
                else {
                    findPreference(getString(R.string.pref_key_color)).setVisible(true);
                }
                return true;
            }
        });

        final SwitchPreferenceCompat themePref = (SwitchPreferenceCompat) findPreference(getString(R.string.pref_key_theme));

        Boolean darkTheme = sp.getBoolean(getString(R.string.pref_key_theme), false);

        setThemePreferenceSummary(themePref, darkTheme);
        setThemePreferenceIcon(themePref, darkTheme);
        themePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                Boolean darkTheme = (Boolean) newValue;
                PhoneTrack.setAppTheme(darkTheme);
                setThemePreferenceSummary(themePref, darkTheme);
                setThemePreferenceIcon(themePref, darkTheme);

                if (getActivity() != null) {
                    getActivity().recreate();
                }
                return true;
            }
        });

        final Preference providersPref = findPreference(getString(R.string.pref_key_providers));
        providersPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                setProvidersSummary(providersPref, (String) newValue);
                Intent intent = new Intent(getActivity(), LoggerService.class);
                intent.putExtra(PreferencesFragment.UPDATED_PROVIDERS, true);
                intent.putExtra(PreferencesFragment.UPDATED_PROVIDERS_VALUE, (String) newValue);
                getActivity().startService(intent);
                return true;
            }
        });

        final EditTextPreference smsKeywordPref = (EditTextPreference) findPreference(getString(R.string.pref_key_sms_keyword));
        String keyword = sp.getString(getString(R.string.pref_key_sms_keyword), "phonetrack");
        smsKeywordPref.setSummary(keyword);
        smsKeywordPref.setDialogMessage(
                getString(R.string.settings_sms_keyword_long)+"\n"
                        + getString(R.string.settings_sms_keyword_long2)+"\n"
                        + getString(R.string.settings_sms_keyword_long3, "alarm", "startlogjobs", "stoplogjobs", "createlogjob")
        );
        smsKeywordPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {

            @Override
            public boolean onPreferenceChange(Preference preference,
                                              Object newValue) {
                String newValueString = (String) newValue;
                if (newValueString == null || newValueString.equals("")) {
                    showToast(getString(R.string.error_invalid_sms_keyword), Toast.LENGTH_LONG);
                    return false;
                }
                else {
                    preference.setSummary((CharSequence) newValue);
                    return true;
                }
            }

        });
        final CheckBoxPreference smsPref = (CheckBoxPreference) findPreference(getString(R.string.pref_key_sms));
        smsPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                Boolean listenToSms = (Boolean) newValue;
                if (listenToSms) {
                    if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.SEND_SMS)
                            != PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.RECEIVE_SMS)
                            != PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.READ_CONTACTS)
                            != PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.GET_ACCOUNTS)
                            != PackageManager.PERMISSION_GRANTED) {

                        if (LoggerService.DEBUG) {
                            Log.d(TAG, "[request send sms permission]");
                        }
                        ActivityCompat.requestPermissions(
                                getActivity(),
                                new String[]{
                                        Manifest.permission.SEND_SMS,
                                        Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.READ_CONTACTS,
                                        Manifest.permission.GET_ACCOUNTS
                                },
                                PERMISSION_SMS_SEND_AND_RECEIVE
                        );
                    }

                    setSmsSettingsVisible(true);
                }
                else {
                    setSmsSettingsVisible(false);
                }
                return true;
            }
        });

        setupSmsAllowedSenders();
        setSmsSettingsVisible(smsPref.isChecked());

        final EditTextPreference groupSyncPref = (EditTextPreference) findPreference(getString(R.string.pref_key_group_sync));
        String groupSyncValStr = sp.getString(getString(R.string.pref_key_group_sync), "0");
        long groupSyncVal = Long.valueOf(groupSyncValStr);
        groupSyncPref.setSummary(String.valueOf(groupSyncVal));
        groupSyncPref.setDialogMessage(getString(R.string.settings_group_sync_long));
        groupSyncPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {

            @Override
            public boolean onPreferenceChange(Preference preference,
                                              Object newValue) {
                String newValueString = (String) newValue;
                if (newValueString == null || newValueString.equals("")) {
                    showToast(getString(R.string.error_invalid_group_sync), Toast.LENGTH_LONG);
                    return false;
                }
                else {
                    long valLong;
                    if (newValueString.length() > 6) {
                        showToast(getString(R.string.error_invalid_group_sync), Toast.LENGTH_LONG);
                        return false;
                    }
                    else {
                        valLong = Long.valueOf(newValueString);
                    }
                    //groupSyncPref.setText(String.valueOf(valInt));
                    // changing the value here does not have any effect
                    /*SharedPreferences.Editor editor = sp.edit();
                    editor.putString(getString(R.string.pref_key_group_sync), String.valueOf(valLong));
                    editor.apply();*/
                    preference.setSummary(String.valueOf(valLong));
                    return true;
                }
            }

        });

        ListPreference providersListPref = (ListPreference) providersPref;
        providersList = new ArrayList<>();
        providersList.add(getString(R.string.providers_gps));
        providersList.add(getString(R.string.providers_network));
        providersList.add(getString(R.string.providers_gps_network));
        CharSequence[] providerEntries = providersList.toArray(new CharSequence[providersList.size()]);
        providersListPref.setEntries(providerEntries);

        String providersValue = sp.getString(getString(R.string.pref_key_providers), "1");

        setProvidersSummary(providersPref, providersValue);

        findPreference(getString(R.string.pref_key_color)).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                showColorDialog(preference);
                return true;
            }
        });

        // update enabled logjobs if we start/stop respecting power saving mode
        final CheckBoxPreference powerModePref = (CheckBoxPreference) findPreference(getString(R.string.pref_key_power_saving_awareness));
        powerModePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                Boolean respectPowerMode = (Boolean) newValue;

                PhoneTrackSQLiteOpenHelper db = PhoneTrackSQLiteOpenHelper.getInstance(getActivity());
                List<DBLogjob> logjobs = db.getLogjobs();

                for (DBLogjob lj: logjobs) {
                    if (lj.isEnabled()) {
                        Intent intent = new Intent(getActivity(), LoggerService.class);
                        intent.putExtra(LogjobsListViewActivity.UPDATED_LOGJOBS, true);
                        intent.putExtra(LogjobsListViewActivity.UPDATED_LOGJOB_ID, lj.getId());
                        getActivity().startService(intent);
                    }
                }

                return true;
            }
        });

        // update enabled logjobs if we start/stop respecting airplane mode
        final CheckBoxPreference airplaneModePref = (CheckBoxPreference) findPreference(getString(R.string.pref_key_offline_mode_awareness));
        airplaneModePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                Boolean respectAirplaneMode = (Boolean) newValue;

                PhoneTrackSQLiteOpenHelper db = PhoneTrackSQLiteOpenHelper.getInstance(getActivity());
                List<DBLogjob> logjobs = db.getLogjobs();

                for (DBLogjob lj: logjobs) {
                    if (lj.isEnabled()) {
                        Intent intent = new Intent(getActivity(), LoggerService.class);
                        intent.putExtra(LogjobsListViewActivity.UPDATED_LOGJOBS, true);
                        intent.putExtra(LogjobsListViewActivity.UPDATED_LOGJOB_ID, lj.getId());
                        getActivity().startService(intent);
                    }
                }

                return true;
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "[ACT RESULT]");
        // Check which request we're responding to
        if (requestCode == import_file_cmd && resultCode == Activity.RESULT_OK) {
            Uri selectedfile = data.getData();
            boolean ok = MapUtils.importMapFile(getContext(), selectedfile);
            if (ok && getActivity() != null) {
                getActivity().recreate();
            }
        }
    }

    private void setThemePreferenceSummary(SwitchPreferenceCompat themePref, Boolean darkTheme) {
        if (darkTheme) {
            themePref.setSummary(getString(R.string.pref_value_theme_dark));
        } else {
            themePref.setSummary(getString(R.string.pref_value_theme_light));
        }
    }

    private void setProvidersSummary(Preference providersPref, String value) {
        int intVal = Integer.parseInt(value);
        providersPref.setSummary(providersList.get(intVal-1));
    }

    // Material 500 colors, plus the Nextcloud blue the app uses by default
    private static final int[] PRESET_COLORS = {
            0xFF0082C9, 0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7,
            0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4, 0xFF009688,
            0xFF4CAF50, 0xFF8BC34A, 0xFFCDDC39, 0xFFFFC107, 0xFFFF9800,
            0xFFFF5722, 0xFF795548, 0xFF9E9E9E, 0xFF607D8B, 0xFF000000,
    };

    private void showColorDialog(final Preference preference) {
        final ColorPreference colorPref = (ColorPreference) preference;
        View colorView = getLayoutInflater().inflate(R.layout.dialog_color, null);
        GridLayout grid = colorView.findViewById(R.id.colorGrid);
        final View preview = colorView.findViewById(R.id.colorPreview);
        final EditText hexField = colorView.findViewById(R.id.colorHex);
        final int[] chosen = {colorPref.getValue()};

        preview.setBackground(ColorPreference.circle(chosen[0]));
        hexField.setText(ColorPreference.toHex(chosen[0]));

        int size = (int) (40 * getResources().getDisplayMetrics().density);
        int margin = (int) (6 * getResources().getDisplayMetrics().density);
        for (final int color : PRESET_COLORS) {
            View swatch = new View(requireContext());
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(margin, margin, margin, margin);
            swatch.setLayoutParams(lp);
            swatch.setBackground(ColorPreference.circle(color));
            swatch.setContentDescription(ColorPreference.toHex(color));
            swatch.setOnClickListener(v -> {
                chosen[0] = color;
                preview.setBackground(ColorPreference.circle(color));
                // updates the field; the watcher below accepts the same value
                hexField.setText(ColorPreference.toHex(color));
            });
            grid.addView(swatch);
        }

        hexField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Integer parsed = ColorPreference.parseHex(s.toString());
                if (parsed != null) {
                    chosen[0] = parsed;
                    preview.setBackground(ColorPreference.circle(parsed));
                }
            }
        });

        new AlertDialog.Builder(requireActivity())
                .setView(colorView)
                .setTitle(getString(R.string.settings_colorpicker_title))
                .setPositiveButton(getString(R.string.simple_ok), (dialogInterface, i) -> {
                    colorPref.setValue(chosen[0]);
                    if (getActivity() != null) {
                        getActivity().recreate();
                    }
                })
                .setNegativeButton(getString(R.string.simple_cancel), null)
                .show();
    }

    private final ActivityResultLauncher<Intent> pickSmsSenderLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null && data.getData() != null) {
                    addSmsAllowedSender(data.getData());
                }
            });

    private void setSmsSettingsVisible(boolean visible) {
        findPreference(getString(R.string.pref_key_sms_keyword)).setVisible(visible);
        findPreference(getString(R.string.pref_key_sms_allowed_senders)).setVisible(visible);
        findPreference(getString(R.string.pref_key_sms_add_contact)).setVisible(visible);
    }

    private void setupSmsAllowedSenders() {
        final EditTextPreference allowedPref = findPreference(getString(R.string.pref_key_sms_allowed_senders));
        updateSmsAllowedSendersSummary(allowedPref, allowedPref.getText());
        allowedPref.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_PHONE));
        allowedPref.setOnPreferenceChangeListener((preference, newValue) -> {
            List<String> numbers = SmsSenderAllowlist.parse((String) newValue);
            for (String number : numbers) {
                if (!SmsSenderAllowlist.isValidEntry(number)) {
                    showToast(getString(R.string.error_invalid_sms_allowed_sender, number), Toast.LENGTH_LONG);
                    return false;
                }
            }
            // store normalised, then refresh the summary
            String normalised = SmsSenderAllowlist.join(numbers);
            ((EditTextPreference) preference).setText(normalised);
            updateSmsAllowedSendersSummary(preference, normalised);
            return false;
        });

        Preference addContactPref = findPreference(getString(R.string.pref_key_sms_add_contact));
        addContactPref.setOnPreferenceClickListener(preference -> {
            // the system picker grants access to the chosen entry only: no READ_CONTACTS needed
            Intent pick = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
            try {
                pickSmsSenderLauncher.launch(pick);
            } catch (ActivityNotFoundException e) {
                showToast(getString(R.string.error_no_contact_picker), Toast.LENGTH_LONG);
            }
            return true;
        });
    }

    private void addSmsAllowedSender(Uri phoneUri) {
        String number = null;
        try (Cursor c = requireContext().getContentResolver().query(phoneUri,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                number = c.getString(0);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to read picked contact: " + e);
        }
        if (number == null || number.trim().isEmpty()) {
            return;
        }
        EditTextPreference allowedPref = findPreference(getString(R.string.pref_key_sms_allowed_senders));
        List<String> numbers = SmsSenderAllowlist.parse(allowedPref.getText());
        if (!numbers.contains(number.trim())) {
            numbers.add(number.trim());
        }
        String joined = SmsSenderAllowlist.join(numbers);
        allowedPref.setText(joined);
        updateSmsAllowedSendersSummary(allowedPref, joined);
    }

    private void updateSmsAllowedSendersSummary(Preference preference, String value) {
        List<String> numbers = SmsSenderAllowlist.parse(value);
        preference.setSummary(numbers.isEmpty()
                ? getString(R.string.settings_sms_allowed_senders_none)
                : SmsSenderAllowlist.join(numbers));
    }

    public void disableSms() {
        final CheckBoxPreference smsPref = (CheckBoxPreference) findPreference(getString(R.string.pref_key_sms));
        smsPref.setChecked(false);
    }

    private void setThemePreferenceIcon(Preference preference, boolean darkThemeActive) {
        if (darkThemeActive) {
            preference.setIcon(R.drawable.ic_brightness_2_grey_24dp);
        } else {
            preference.setIcon(R.drawable.ic_sunny_grey_24dp);
        }
    }

    protected void showToast(CharSequence text, int duration) {
        Context context = getActivity();
        Toast toast = Toast.makeText(context, text, duration);
        toast.show();
    }
}
