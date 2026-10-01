package net.eneiluj.nextcloud.phonetrack.android.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;

import net.eneiluj.nextcloud.phonetrack.R;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjob;
import net.eneiluj.nextcloud.phonetrack.util.ICallback;
import net.eneiluj.nextcloud.phonetrack.util.LogjobAutomation;

import static android.webkit.URLUtil.isValidUrl;

public class EditCustomLogjobFragment extends EditLogjobFragment {
    private static final String TAG = EditCustomLogjobFragment.class.getSimpleName();

    private CheckBox editPost;
    private CheckBox editJson;
    protected EditText editLogin;
    protected EditText editPassword;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "CUSTOM on create : "+logjob);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_custom_edit, container, false);
        super.onCreateView(view);

        editPost = view.findViewById(R.id.post);
        editPost.setChecked(logjob.getPost());

        editJson = view.findViewById(R.id.json);
        editJson.setChecked(logjob.getJson());

        editLogin = view.findViewById(R.id.editLogin);
        editLogin.setText((logjob.getLogin() == null) ? "" : logjob.getLogin());

        editPassword = view.findViewById(R.id.editPassword);
        editPassword.setText((logjob.getPassword() == null) ? "" : logjob.getPassword());

        editPost.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        Log.d(TAG, "use POST change");
                        showHideFields();
                    }
                }
        );

        editJson.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        Log.d(TAG, "send JSON change");
                    }
                }
        );

        showHideFields();
        showHideValidationButtons();

        return view;
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        MenuItem itemSelectSession = menu.findItem(R.id.menu_selectSession);
        itemSelectSession.setVisible(false);
        MenuItem itemFromLogUrl = menu.findItem(R.id.menu_fromLogUrl);
        itemFromLogUrl.setVisible(false);
    }

    private void showHideFields() {
        if (getPost()) {
            editJsonLayout.setVisibility(View.VISIBLE);
        }
        else {
            editJsonLayout.setVisibility(View.GONE);
        }
    }


    /**
     * Save the current state in the database and schedule synchronization if needed.
     *
     * @param callback Observer which is called after save/synchronization
     */
    @Override
    protected void saveLogjob(@Nullable ICallback callback) {
        Log.d(getClass().getSimpleName(), "CUSTOM saveData()");
        String newTitle = getTitle();
        String newURL = getURL();
        Log.v(getClass().getSimpleName(), "NEW URL "+newURL);
        boolean newPost = getPost();
        boolean newJson = getJson();
        boolean newUseSignificantMotion = getUseSignificantMotion();
        boolean newUseSignificantMotionMixed = getUseSignificantMotionMixed();
        int newMinTime = 0;
        // Store the interval as zero if we're not using it (ie. when sampling with significant motion and
        // not using an interval)
        if (!newUseSignificantMotion || getUseSignificantMotionInterval()) {
            newMinTime = getMintime();
        }
        int newMinDistance = getMindistance();
        int newMinAccuracy = getMinaccuracy();
        boolean newKeepGpsOn = newUseSignificantMotion ? false : getKeepGpsOn();
        int newTimeout = getLocationRequestTimeout();

        String newLogin = getLogin();
        String newPassword = getPassword();
        String oldLogin = logjob.getLogin();
        String oldPassword = logjob.getPassword();

        boolean loginChanged = (
                (oldLogin == null && newLogin != null) ||
                (oldLogin != null && newLogin == null) ||
                (oldLogin != null && newLogin != null && !oldLogin.equals(newLogin))
        );
        boolean passwordChanged = (
                (oldPassword == null && newPassword != null) ||
                        (oldPassword != null && newPassword == null) ||
                        (oldPassword != null && newPassword != null && !oldPassword.equals(newPassword))
        );

        LogjobAutomation newAutomation = buildAutomationFromForm();
        boolean automationChanged = !java.util.Objects.equals(logjob.getAutomation(), newAutomation);
        // if this is an existing logjob
        if (logjob.getId() != 0) {
            if (logjob.getTitle().equals(newTitle) &&
                    logjob.getUrl().equals(newURL) &&
                    logjob.getPost() == newPost &&
                    logjob.getJson() == newJson &&
                    logjob.getMinTime() == newMinTime &&
                    logjob.keepGpsOnBetweenFixes() == newKeepGpsOn &&
                    logjob.getMinDistance() == newMinDistance &&
                    logjob.getMinAccuracy() == newMinAccuracy &&
                    logjob.useSignificantMotion() == newUseSignificantMotion &&
                    logjob.useSignificantMotionMixed() == newUseSignificantMotionMixed &&
                    logjob.getLocationRequestTimeout() == newTimeout &&
                    !loginChanged &&
                    !passwordChanged &&
                    !automationChanged
                    ) {
                Log.v(getClass().getSimpleName(), "... not saving logjob, since nothing has changed");
            } else {
                Log.i(TAG, "====== update logjob");
                logjob = db.updateLogjobAndSync(
                        logjob, newTitle, "", newURL, "",
                        newPost, newMinTime, newMinDistance, newMinAccuracy, newKeepGpsOn,
                        newUseSignificantMotion, newUseSignificantMotionMixed, newTimeout,
                        newLogin, newPassword, newJson, callback
                );
                logjob.setAutomation(newAutomation);
                db.updateLogjobAutomation(logjob.getId(), newAutomation);
                notifyLoggerService(logjob.getId());
                //Log.i(TAG, "AFFFFFFTTTTTTEEERRRRR : "+logjob);
                //listener.onLogjobUpdated(logjob);
            }
        }
        // this is a new logjob
        else {
            DBLogjob newLogjob = new DBLogjob(
                    0, newTitle, newURL, "", "",
                    newMinTime, newMinDistance, newMinAccuracy, newKeepGpsOn,
                    newUseSignificantMotion, newUseSignificantMotionMixed, newTimeout,
                    newPost, false, 0, newLogin, newPassword, newJson
            );
            newLogjob.setAutomation(newAutomation);
            long newId = db.addLogjob(newLogjob);
            notifyLoggerService(newId);
        }
        saveLastValues(
                newMinTime, newMinDistance, newMinAccuracy,
                newKeepGpsOn, newUseSignificantMotion, newUseSignificantMotionMixed,
                newTimeout
        );
    }

    public static EditCustomLogjobFragment newInstance(long logjobId) {
        EditCustomLogjobFragment f = new EditCustomLogjobFragment();
        Bundle b = new Bundle();
        b.putLong(PARAM_LOGJOB_ID, logjobId);
        f.setArguments(b);
        return f;
    }

    public static EditCustomLogjobFragment newInstanceWithNewLogjob(DBLogjob newLogjob) {
        EditCustomLogjobFragment f = new EditCustomLogjobFragment();
        Bundle b = new Bundle();
        b.putSerializable(PARAM_NEWLOGJOB, newLogjob);
        f.setArguments(b);
        return f;
    }

    protected boolean isFormValid() {
        boolean valid = true;
        if (getTitle() == null || getTitle().equals("")) {
            editTitleHint.setBackgroundColor(0x55FF0000);
            valid = false;
        }
        else {
            editTitleHint.setBackgroundColor(getResources().getColor(R.color.bg_normal));
        }
        if (getURL() == null || getURL().equals("") || !isValidUrl(getURL())) {
            editUrlHint.setBackgroundColor(0x55FF0000);
            valid = false;
        }
        else {
            editUrlHint.setBackgroundColor(getResources().getColor(R.color.bg_normal));
        }
        if (getMindistance() < 0) {
            editMindistanceHint.setBackgroundColor(0x55FF0000);
            valid = false;
        }
        else {
            editMindistanceHint.setBackgroundColor(getResources().getColor(R.color.bg_normal));
        }
        if (getMinaccuracy() < 1) {
            editMinaccuracyHint.setBackgroundColor(0x55FF0000);
            valid = false;
        }
        else {
            editMinaccuracyHint.setBackgroundColor(getResources().getColor(R.color.bg_normal));
        }
        if (getUseSignificantMotion()) {
            if (getUseSignificantMotionInterval() && getMintime() < 30) {
                minTimeTextInputLayout.setBackgroundColor(0x55FF0000);
                valid = false;
            } else {
                minTimeTextInputLayout.setBackgroundColor(getResources().getColor(R.color.bg_normal));
            }
        }
        else {
            if (getMintime() < 1) {
                minTimeTextInputLayout.setBackgroundColor(0x55FF0000);
                valid = false;
            } else {
                minTimeTextInputLayout.setBackgroundColor(getResources().getColor(R.color.bg_normal));
            }
        }
        if (getLocationRequestTimeout() < 0 || getLocationRequestTimeout() >= getMintime()) {
            editLocationTimeoutHint.setBackgroundColor(0x55FF0000);
            valid = false;
        }
        else {
            editLocationTimeoutHint.setBackgroundColor(getResources().getColor(R.color.bg_normal));
        }

        return valid;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Log.i(TAG,"CUSTOM ACT CREATEDDDDDDD");
    }

    private boolean getPost() {
        return editPost.isChecked();
    }

    private boolean getJson() {
        return editJson.isChecked();
    }

    protected String getLogin() {
        if (editLogin.getText() == null || editLogin.getText().toString().equals("")) {
            return null;
        }
        else {
            return editLogin.getText().toString();
        }
    }

    protected String getPassword() {
        if (editPassword.getText() == null || editPassword.getText().toString().equals("")) {
            return null;
        }
        else {
            return editPassword.getText().toString();
        }
    }

}
