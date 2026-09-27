package net.eneiluj.nextcloud.phonetrack.android.activity;

import android.Manifest;
import android.animation.AnimatorInflater;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;

import androidx.core.os.BundleCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.preference.PreferenceManager;

/*
import com.codebutchery.androidgpx.data.GPXDocument;
import com.codebutchery.androidgpx.data.GPXSegment;
import com.codebutchery.androidgpx.data.GPXTrack;
import com.codebutchery.androidgpx.data.GPXTrackPoint;
*/
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException;
import com.nextcloud.android.sso.exceptions.NoCurrentAccountSelectedException;
import com.nextcloud.android.sso.helper.SingleAccountHelper;
import com.nextcloud.android.sso.model.SingleSignOnAccount;

import androidx.core.app.ActivityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback;

import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.eneiluj.nextcloud.phonetrack.R;
import net.eneiluj.nextcloud.phonetrack.model.Category;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjob;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjobLocation;
import net.eneiluj.nextcloud.phonetrack.model.DBSession;
import net.eneiluj.nextcloud.phonetrack.model.Item;
import net.eneiluj.nextcloud.phonetrack.model.ItemAdapter;
import net.eneiluj.nextcloud.phonetrack.model.NavigationAdapter;
import net.eneiluj.nextcloud.phonetrack.model.SyncError;
import net.eneiluj.nextcloud.phonetrack.persistence.LoadLogjobsListTask;
import net.eneiluj.nextcloud.phonetrack.persistence.PhoneTrackSQLiteOpenHelper;
import net.eneiluj.nextcloud.phonetrack.persistence.SessionServerSyncHelper;
import net.eneiluj.nextcloud.phonetrack.service.LoggerService;
import net.eneiluj.nextcloud.phonetrack.service.SmsListener;
import net.eneiluj.nextcloud.phonetrack.service.WebTrackSync;
import net.eneiluj.nextcloud.phonetrack.service.WebTrackWorker;
import net.eneiluj.nextcloud.phonetrack.util.KeyboardUtil;
import net.eneiluj.nextcloud.phonetrack.util.LocalNetworkAccess;
import net.eneiluj.nextcloud.phonetrack.util.BackgroundTask;
import net.eneiluj.nextcloud.phonetrack.util.EdgeToEdgeUtil;
import net.eneiluj.nextcloud.phonetrack.util.ICallback;
import net.eneiluj.nextcloud.phonetrack.util.PhoneTrack;
import net.eneiluj.nextcloud.phonetrack.util.PhoneTrackClientUtil;
import net.eneiluj.nextcloud.phonetrack.util.SupportUtil;
import net.eneiluj.nextcloud.phonetrack.util.SystemLogger;
import net.eneiluj.nextcloud.phonetrack.util.ThemeUtils;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static net.eneiluj.nextcloud.phonetrack.util.SupportUtil.formatDistance;

public class LogjobsListViewActivity extends AppCompatActivity implements ItemAdapter.LogjobClickListener {

    // Runtime permissions are requested one step at a time (see requestNextPermission()):
    // Android only shows one request at a time and ignores a background location
    // request made before foreground location is granted.
    private boolean locationPermissionAsked = false;
    private boolean notificationPermissionAsked = false;
    private boolean backgroundLocationPermissionAsked = false;
    private boolean localNetworkAsked = false;
    private boolean batteryOptimizationAsked = false;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> requestNextPermission());

    private static final String TAG = LogjobsListViewActivity.class.getSimpleName();

    public final static String PARAM_SMSINFO_FROM = "net.eneiluj.nextcloud.phonetrack.smsinfoFrom";
    public final static String PARAM_SMSINFO_CONTENT = "net.eneiluj.nextcloud.phonetrack.smsinfoContent";

    public final static String CREDENTIALS_CHANGED = "net.eneiluj.nextcloud.phonetrack.CREDENTIALS_CHANGED";
    public static final String ADAPTER_KEY_ALL = "all";
    public static final String ADAPTER_KEY_ENABLED = "enabled";
    public static final String ADAPTER_KEY_PHONETRACK = "pt";
    public static final String ADAPTER_KEY_CUSTOM = "custom";
    public static final String CATEGORY_PHONETRACK = "pt";
    public static final String CATEGORY_CUSTOM = "cu";

    public final static String UPDATED_LOGJOBS = "net.eneiluj.nextcloud.phonetrack.UPDATED_LOGJOBS";
    public final static String UPDATED_LOGJOB_ID = "net.eneiluj.nextcloud.phonetrack.UPDATED_LOGJOB_ID";

    private static final String SAVED_STATE_NAVIGATION_SELECTION = "navigationSelection";
    private static final String SAVED_STATE_NAVIGATION_ADAPTER_SLECTION = "navigationAdapterSelection";
    private static final String SAVED_STATE_NAVIGATION_OPEN = "navigationOpen";

    private static String contentToExport = "";


    Toolbar toolbar;
    DrawerLayout drawerLayout;
    TextView account;
    SwipeRefreshLayout swipeRefreshLayout;
    FloatingActionButton fabCreatePhoneTrack;
    FloatingActionButton fabCreateCustom;
    FloatingActionButton fabCreateSession;
    FloatingActionButton fabCreateMaps;
    FloatingActionButton fabMain;
    /** the whole speed-dial menu (hidden while searching) */
    View fabMenu;
    View fabActions;
    View fabScrim;
    private boolean fabMenuOpen = false;
    /** closes the speed-dial menu; enabled only while it is open */
    private final OnBackPressedCallback closeFabMenuOnBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            setFabMenuOpen(false, true);
        }
    };
    RecyclerView listNavigationCategories;
    RecyclerView listNavigationMenu;
    RecyclerView listView;
    Snackbar ssoSnackbar;
    ImageView avatarView;
    AppCompatImageButton menuButton;
    AppCompatImageView accountButton;
    MaterialCardView homeToolbar;
    AppBarLayout appBar;


    private View currentInfoDialogView = null;
    private long currentInfoDialogLogjobId = -1;

    private ItemAdapter adapter = null;
    private NavigationAdapter adapterCategories;
    private NavigationAdapter.NavigationItem itemAll, itemEnabled, itemPhonetrack, itemCustom, itemUncategorized;
    private Category navigationSelection = new Category(null, null);
    private String navigationOpen = "";
    private ActionMode mActionMode;
    private PhoneTrackSQLiteOpenHelper db = null;
    private SearchView searchView = null;
    private final ICallback syncCallBack = new ICallback() {
        @Override
        public void onFinish() {
            adapter.clearSelection();
            if (mActionMode != null) {
                mActionMode.finish();
            }
            refreshLists();
            //swipeRefreshLayout.setRefreshing(false);
        }

        @Override
        public void onFinish(String result, String message) {
        }

        @Override
        public void onScheduled() {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fixProviders();
        ssoSnackbar = null;

        String categoryAdapterSelectedItem = ADAPTER_KEY_ALL;
        if (savedInstanceState != null) {
            navigationSelection = BundleCompat.getSerializable(savedInstanceState, SAVED_STATE_NAVIGATION_SELECTION, Category.class);
            navigationOpen = savedInstanceState.getString(SAVED_STATE_NAVIGATION_OPEN);
            categoryAdapterSelectedItem = savedInstanceState.getString(SAVED_STATE_NAVIGATION_ADAPTER_SLECTION);
        }

        setContentView(R.layout.drawer_layout);
        EdgeToEdgeUtil.enable(this);
        toolbar = findViewById(R.id.logjobsListActivityActionBar);
        drawerLayout = findViewById(R.id.drawerLayout);
        account = findViewById(R.id.account);
        swipeRefreshLayout = findViewById(R.id.swiperefreshlayout);
        fabCreatePhoneTrack = findViewById(R.id.fab_create_phonetrack);
        fabCreateCustom = findViewById(R.id.fab_create_custom);
        fabCreateSession = findViewById(R.id.fab_create_session);
        fabCreateMaps = findViewById(R.id.fab_create_maps);
        fabMenu = findViewById(R.id.floatingMenu);
        fabMain = findViewById(R.id.fabMain);
        fabActions = findViewById(R.id.fabActions);
        fabScrim = findViewById(R.id.fabScrim);
        listNavigationCategories = findViewById(R.id.navigationList);
        listNavigationMenu = findViewById(R.id.navigationMenu);
        listView = findViewById(R.id.recycler_view);
        avatarView = findViewById(R.id.drawer_nc_logo);
        menuButton = findViewById(R.id.menu_button);
        accountButton = findViewById(R.id.launchAccountSwitcher);
        searchView = findViewById(R.id.search_view);
        homeToolbar = findViewById(R.id.home_toolbar);
        appBar = findViewById(R.id.appBar);

        db = PhoneTrackSQLiteOpenHelper.getInstance(this);

        getOnBackPressedDispatcher().addCallback(this, closeSearchOnBack);
        setupToolBar();
        setupLogjobsList();
        setupNavigationList(categoryAdapterSelectedItem);
        setupNavigationMenu();

        if (savedInstanceState == null) {
            // not again on rotation / recreation
            requestNextPermission();
        }

        Map<String, Integer> enabled = db.getEnabledCount();
        Integer enabledCount = enabled.get("1");
        int nbEnabledLogjobs = enabledCount != null ? enabledCount : 0;
        if (nbEnabledLogjobs > 0 && SupportUtil.hasForegroundLocationPermission(this)) {
            SystemLogger.d(TAG, "Found enabled jobs => start loggerservice");
            // start loggerservice !
            Intent intent = new Intent(LogjobsListViewActivity.this, LoggerService.class);
            startForegroundService(intent);
        }

        String smsInfoContent = getIntent().getStringExtra(PARAM_SMSINFO_CONTENT);
        String smsInfoFrom = getIntent().getStringExtra(PARAM_SMSINFO_FROM);
        if (smsInfoContent != null) {
            View dView = LayoutInflater.from(this).inflate(R.layout.items_sms_infodialog, null);
            TextView tv = dView.findViewById(R.id.smsInfoDialogTextMessage);
            tv.setText(smsInfoContent);
            TextView tv2 = dView.findViewById(R.id.smsInfoDialogText1);
            tv2.setText(getString(R.string.sms_notif_info_dialog_message1, smsInfoFrom));

            AlertDialog.Builder builder;
            builder = new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.AppThemeDialog));
            builder.setTitle(this.getString(R.string.sms_notif_info_dialog_title))
                    .setView(dView)
                    //.setMessage(this.getString(R.string.sms_notif_info_dialog_message, smsInfoContent))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {

                        }
                    })
                    .setIcon(R.drawable.ic_sms_grey_24dp)
                    .show();
        }
    }

    /**
     * Walk through the permissions the app needs, one request at a time:
     * foreground location, notifications (API 33+), background location (with an
     * explanation first, API 29+), then the battery optimization exemption.
     * Each request's result callback calls this again to continue.
     */
    @SuppressLint("BatteryLife")
    private void requestNextPermission() {
        if (!locationPermissionAsked) {
            locationPermissionAsked = true;
            if (!SupportUtil.hasForegroundLocationPermission(this)) {
                if (LoggerService.DEBUG) { SystemLogger.d(TAG, "request fine and coarse location permissions"); }
                permissionLauncher.launch(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                });
                return;
            }
        }

        if (!notificationPermissionAsked) {
            notificationPermissionAsked = true;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (LoggerService.DEBUG) { SystemLogger.d(TAG, "requesting POST_NOTIFICATIONS permission"); }
                permissionLauncher.launch(new String[]{Manifest.permission.POST_NOTIFICATIONS});
                return;
            }
        }

        if (!backgroundLocationPermissionAsked) {
            backgroundLocationPermissionAsked = true;
            // background location can only be granted on top of foreground location
            if (SupportUtil.hasForegroundLocationPermission(this)
                    && !SupportUtil.hasBackgroundLocationPermission(this)) {
                CharSequence optionLabel = getPackageManager().getBackgroundPermissionOptionLabel();
                new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.AppThemeDialog))
                        .setTitle(R.string.background_location_permission_title)
                        .setMessage(getString(R.string.background_location_permission_message) + "\n\n" + optionLabel)
                        .setPositiveButton(R.string.simple_yes, (dialog, which) -> {
                            if (LoggerService.DEBUG) { SystemLogger.d(TAG, "request background location permission"); }
                            // on Android 11+ this takes the user to the app's location settings page
                            permissionLauncher.launch(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION});
                        })
                        // "no" or back: carry on with the next step
                        .setNegativeButton(R.string.simple_no, (dialog, which) -> requestNextPermission())
                        .setOnCancelListener(dialog -> requestNextPermission())
                        .show();
                return;
            }
        }

        if (!localNetworkAsked) {
            localNetworkAsked = true;
            // Android 17: reaching a server on the LAN needs the "Nearby devices" permission
            if (LocalNetworkAccess.isEnforced(this) && !LocalNetworkAccess.isGranted(this)) {
                new BackgroundTask<Void, Boolean>() {
                    @Override
                    protected Boolean doInBackground(Void... voids) {
                        Context context = getApplicationContext();
                        return LocalNetworkAccess.isNeededFor(context, LocalNetworkAccess.configuredServerUrls(context));
                    }

                    @Override
                    protected void onPostExecute(Boolean needed) {
                        if (isDestroyed()) {
                            return;
                        }
                        if (needed) {
                            showLocalNetworkPermissionRationale();
                        } else {
                            requestNextPermission();
                        }
                    }
                }.executeInParallel();
                return;
            }
        }

        if (!batteryOptimizationAsked) {
            batteryOptimizationAsked = true;
            try {
                String packageName = getPackageName();
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                SystemLogger.d(TAG, "check if we need to request for ignoring battery optimizations for " + packageName);
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    Intent i = new Intent();
                    i.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + packageName));
                    SystemLogger.d(TAG, "request for ignoring battery optimizations for " + packageName);
                    startActivity(i);
                }
            } catch (Exception e) {
                SystemLogger.d(TAG, "Unable to request ignoring battery optimizations: " + e);
            }
        }
    }

    private void showLocalNetworkPermissionRationale() {
        new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.AppThemeDialog))
                .setTitle(R.string.local_network_permission_title)
                .setMessage(R.string.local_network_permission_message)
                .setPositiveButton(R.string.simple_yes, (dialog, which) ->
                        permissionLauncher.launch(new String[]{LocalNetworkAccess.PERMISSION}))
                .setNegativeButton(R.string.simple_no, (dialog, which) -> requestNextPermission())
                .setOnCancelListener(dialog -> requestNextPermission())
                .show();
    }

    /**
     * Open or close the "New ..." speed-dial menu. Session and Maps logjobs need a Nextcloud
     * account, so their entries only show when one is configured.
     */
    private void setFabMenuOpen(boolean open, boolean animate) {
        fabMenuOpen = open;
        closeFabMenuOnBack.setEnabled(open);
        if (open) {
            int accountEntries = SessionServerSyncHelper.isConfigured(getApplicationContext()) ? View.VISIBLE : View.GONE;
            findViewById(R.id.fab_row_session).setVisibility(accountEntries);
            findViewById(R.id.fab_row_maps).setVisibility(accountEntries);
        }
        fabActions.setVisibility(open ? View.VISIBLE : View.GONE);
        fabScrim.setVisibility(open ? View.VISIBLE : View.GONE);
        fabMain.setContentDescription(getString(open ? R.string.simple_cancel : R.string.action_create));
        // the "+" turns into an "x"
        float rotation = open ? 45f : 0f;
        if (animate) {
            fabMain.animate().rotation(rotation).setDuration(150).start();
            if (open) {
                fabActions.setAlpha(0f);
                fabActions.setTranslationY(getResources().getDisplayMetrics().density * 16);
                fabActions.animate().alpha(1f).translationY(0f).setDuration(150).start();
            }
        } else {
            fabMain.setRotation(rotation);
        }
    }

    private void fixProviders() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        boolean fixed = preferences.getBoolean("fixProvidersV18Done", false);
        if (!fixed) {
            String currentValue = preferences.getString(getString(R.string.pref_key_providers), "1");
            String fixedValue = currentValue;
            if ("4".equals(currentValue) || "5".equals(currentValue)) {
                fixedValue = "1";
            } else if ("6".equals(currentValue)) {
                fixedValue = "2";
            } else if ("7".equals(currentValue)) {
                fixedValue = "3";
            }
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString(getString(R.string.pref_key_providers), fixedValue);
            editor.putBoolean("fixProvidersV18Done", true);
            editor.apply();
        }
    }

    @Override
    protected void onResume() {
        if (LoggerService.DEBUG) { SystemLogger.d(TAG, "onResume"); }
        super.onResume();
        // refresh and sync every time the activity gets visible
        refreshLists();
        swipeRefreshLayout.setRefreshing(false);
        //db.getPhonetrackServerSyncHelper().addCallbackPull(syncCallBack);
        if (db.getPhonetrackServerSyncHelper().isSyncPossible()) {
            swipeRefreshLayout.setEnabled(true);
            synchronize();
        }
        else {
            swipeRefreshLayout.setEnabled(false);
        }

        registerBroadcastReceiver();

        updateCurrentInfoDialog();

        updateUsernameInDrawer();

        if (LoggerService.DEBUG) { SystemLogger.d(TAG, "onResume END"); }
    }

    private void updateCurrentInfoDialog() {
        if (currentInfoDialogLogjobId != -1) {
            updateInfoDialogContent(currentInfoDialogView, currentInfoDialogLogjobId, getApplicationContext());
        }
    }

    /**
     * On pause
     */
    @Override
    protected void onPause() {
        if (LoggerService.DEBUG) { SystemLogger.d(TAG, "onPause"); }
        super.onPause();

        try {
            unregisterReceiver(mBroadcastReceiver);
        } catch (RuntimeException e) {
            // i don't understand why this is happening on 6.0 only
            // onPause is called twice when trying to launch preferences activity
            // anyway this solves it, at least the app does not crash anymore
            if (LoggerService.DEBUG) { SystemLogger.d(TAG, "RECEIVER PROBLEM, let's ignore it..."); }
        }
        if (LoggerService.DEBUG) { SystemLogger.d(TAG, "onPause END"); }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(SAVED_STATE_NAVIGATION_SELECTION, navigationSelection);
        outState.putString(SAVED_STATE_NAVIGATION_ADAPTER_SLECTION, adapterCategories.getSelectedItem());
        outState.putString(SAVED_STATE_NAVIGATION_OPEN, navigationOpen);
    }

    private void setupToolBar() {
        setSupportActionBar(toolbar);
        int[] colors = { ThemeUtils.primaryColor(this), ThemeUtils.primaryLightColor(this) };
        GradientDrawable gradientDrawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, colors);
        drawerLayout.findViewById(R.id.drawer_top_layout).setBackground(gradientDrawable);

        ImageView logoView = drawerLayout.findViewById(R.id.drawer_logo);
        logoView.setColorFilter(ThemeUtils.primaryColor(this), PorterDuff.Mode.OVERLAY);

        menuButton.setOnClickListener((v) -> drawerLayout.openDrawer(GravityCompat.START));
        final LogjobsListViewActivity that = this;
        accountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent settingsIntent = new Intent(that, SettingsActivity.class);
                accountSettingsLauncher.launch(settingsIntent);
            }
        });

        ///////// SEARCH
        homeToolbar.setOnClickListener((v) -> {
            if (toolbar.getVisibility() == GONE) {
                updateToolbars(false);
            }
        });

        final LinearLayout searchEditFrame = searchView.findViewById(androidx.appcompat.R.id.search_edit_frame);

        searchEditFrame.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            int oldVisibility = -1;

            @Override
            public void onGlobalLayout() {
                int currentVisibility = searchEditFrame.getVisibility();

                if (currentVisibility != oldVisibility) {
                    if (currentVisibility == VISIBLE) {
                        fabMenu.setVisibility(View.INVISIBLE);
                    } else {
                        new Handler().postDelayed(() -> fabMenu.setVisibility(View.VISIBLE), 150);
                    }

                    oldVisibility = currentVisibility;
                }
            }

        });
        searchView.setOnCloseListener(() -> {
            if (toolbar.getVisibility() == VISIBLE && TextUtils.isEmpty(searchView.getQuery())) {
                updateToolbars(true);
                return true;
            }
            return false;
        });
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                refreshLists();
                return true;
            }
        });
    }

    @SuppressLint("PrivateResource")
    private void updateToolbars(boolean disableSearch) {
        closeSearchOnBack.setEnabled(!disableSearch);
        homeToolbar.setVisibility(disableSearch ? VISIBLE : GONE);
        toolbar.setVisibility(disableSearch ? GONE : VISIBLE);
        appBar.setStateListAnimator(
                AnimatorInflater.loadStateListAnimator(appBar.getContext(),
                disableSearch ? R.animator.appbar_elevation_off : R.animator.appbar_elevation_on)
        );
        if (disableSearch) {
            searchView.setQuery(null, true);
        }
        searchView.setIconified(disableSearch);
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (toolbar.getVisibility() == VISIBLE) {
            updateToolbars(true);
            return true;
        } else {
            return super.onSupportNavigateUp();
        }
    }

    private void setupLogjobsList() {
        initList();
        // Pull to Refresh
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                if (db.getPhonetrackServerSyncHelper().isSyncPossible()) {
                    synchronize();
                } else {
                    //swipeRefreshLayout.setRefreshing(false);
                    // don't bother user if no conf
                    if (SessionServerSyncHelper.isConfigured(getApplicationContext())) {
                        Toast.makeText(getApplicationContext(), getString(R.string.error_sync, getString(PhoneTrackClientUtil.LoginStatus.NO_NETWORK.str)), Toast.LENGTH_LONG).show();
                    }
                }
                if (db.getLocationNotSyncedCount() > 0) {
                    WebTrackWorker.enqueue(getApplicationContext(), WebTrackWorker.ALL_LOGJOBS);
                    showToast(getString(R.string.uploading_started));
                }
                else {
                    swipeRefreshLayout.setRefreshing(false);
                }
            }
        });

        if (!db.getPhonetrackServerSyncHelper().isSyncPossible()) {
            swipeRefreshLayout.setEnabled(false);
        }

        getOnBackPressedDispatcher().addCallback(this, closeFabMenuOnBack);
        fabMain.setOnClickListener(v -> setFabMenuOpen(!fabMenuOpen, true));
        fabScrim.setOnClickListener(v -> setFabMenuOpen(false, true));
        // tapping a label does the same as its button
        findViewById(R.id.fab_create_session_label).setOnClickListener(v -> fabCreateSession.performClick());
        findViewById(R.id.fab_create_phonetrack_label).setOnClickListener(v -> fabCreatePhoneTrack.performClick());
        findViewById(R.id.fab_create_maps_label).setOnClickListener(v -> fabCreateMaps.performClick());
        findViewById(R.id.fab_create_custom_label).setOnClickListener(v -> fabCreateCustom.performClick());

        fabCreateSession.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                setFabMenuOpen(false, true);
                EditText sessionNameEdit = new EditText(view.getContext());
                AlertDialog.Builder sessionBuilder = new AlertDialog.Builder(new ContextThemeWrapper(view.getContext(), R.style.AppThemeDialog));
                sessionBuilder.setMessage(getString(R.string.dialog_msg_create_session));
                sessionBuilder.setTitle(getString(R.string.dialog_title_create_session));

                sessionBuilder.setView(sessionNameEdit);

                sessionBuilder.setPositiveButton(getString(R.string.simple_ok), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        String sessionName = sessionNameEdit.getText().toString();
                        if (!sessionName.isEmpty()) {
                            if (!db.getPhonetrackServerSyncHelper().createSession(sessionName, createSessionCallBack)) {
                                showToast(getString(R.string.error_create_session_network), Toast.LENGTH_LONG);
                            }
                        }
                    }
                });
                sessionBuilder.setNegativeButton(getString(R.string.simple_cancel), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                    }
                });
                AlertDialog sessionDialog = sessionBuilder.create();
                sessionDialog.show();
                KeyboardUtil.showForDialog(sessionDialog, sessionNameEdit);
            }
        });

        fabCreateMaps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent createIntent = new Intent(getApplicationContext(), EditMapsLogjobActivity.class);
                //startActivityForResult(createIntent, create_logjob_cmd);
                createLogjobLauncher.launch(createIntent);
                setFabMenuOpen(false, false);
            }
        });
        fabCreateCustom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent createIntent = new Intent(getApplicationContext(), EditCustomLogjobActivity.class);
                //startActivityForResult(createIntent, create_logjob_cmd);
                createLogjobLauncher.launch(createIntent);
                setFabMenuOpen(false, false);
            }
        });
        fabCreatePhoneTrack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent createIntent = new Intent(getApplicationContext(), EditPhoneTrackLogjobActivity.class);
                //startActivityForResult(createIntent, create_logjob_cmd);
                createLogjobLauncher.launch(createIntent);
                setFabMenuOpen(false, false);
            }
        });

        boolean darkTheme = PhoneTrack.getAppTheme(this);
        // if dark theme and main color is black, make fab button lighter/gray
        int fabColor = darkTheme && ThemeUtils.primaryColor(this) == Color.BLACK
                ? Color.DKGRAY
                : ThemeUtils.primaryColor(this);
        ColorStateList fabTint = ColorStateList.valueOf(fabColor);
        for (FloatingActionButton fab : new FloatingActionButton[]{fabMain, fabCreateSession, fabCreatePhoneTrack, fabCreateMaps, fabCreateCustom}) {
            fab.setBackgroundTintList(fabTint);
        }
    }

    private void setupNavigationList(final String selectedItem) {
        itemAll = new NavigationAdapter.NavigationItem(ADAPTER_KEY_ALL, getString(R.string.label_all_logjobs), null, R.drawable.ic_allgrey_24dp);
        itemEnabled = new NavigationAdapter.NavigationItem(ADAPTER_KEY_ENABLED, getString(R.string.label_enabled), null, R.drawable.ic_check_box_grey_24dp);
        itemPhonetrack = new NavigationAdapter.NavigationItem(ADAPTER_KEY_PHONETRACK, getString(R.string.label_phonetrack_lj), null, R.drawable.ic_phonetrack_grey_24dp);
        itemCustom = new NavigationAdapter.NavigationItem(ADAPTER_KEY_CUSTOM, getString(R.string.label_custom_lj), null, R.drawable.ic_link_menu_grey_24dp);
        adapterCategories = new NavigationAdapter(new NavigationAdapter.ClickListener() {
            @Override
            public void onItemClick(NavigationAdapter.NavigationItem item) {
                selectItem(item, true);
            }

            private void selectItem(NavigationAdapter.NavigationItem item, boolean closeNavigation) {
                adapterCategories.setSelectedItem(item.id);

                // update current selection
                if (itemAll == item) {
                    navigationSelection = new Category(null, null);
                } else if (itemEnabled == item) {
                    navigationSelection = new Category(null, true);
                } else if (itemUncategorized == item) {
                    navigationSelection = new Category("", null);
                } else if (itemPhonetrack == item) {
                    navigationSelection = new Category(CATEGORY_PHONETRACK, null);
                } else if (itemCustom == item) {
                    navigationSelection = new Category(CATEGORY_CUSTOM, null);
                } else {
                    navigationSelection = new Category(item.label, null);
                }

                // auto-close sub-folder in Navigation if selection is outside of that folder
                if (navigationOpen != null) {
                    int slashIndex = navigationSelection.category == null ? -1 : navigationSelection.category.indexOf('/');
                    String rootCategory = slashIndex < 0 ? navigationSelection.category : navigationSelection.category.substring(0, slashIndex);
                    if (!navigationOpen.equals(rootCategory)) {
                        navigationOpen = null;
                    }
                }

                // update views
                if (closeNavigation) {
                    drawerLayout.closeDrawers();
                }
                refreshLists(true);
            }

            @Override
            public void onIconClick(NavigationAdapter.NavigationItem item) {
                if (item.icon == NavigationAdapter.ICON_MULTIPLE && !item.label.equals(navigationOpen)) {
                    navigationOpen = item.label;
                    selectItem(item, false);
                } else if (item.icon == NavigationAdapter.ICON_MULTIPLE || item.icon == NavigationAdapter.ICON_MULTIPLE_OPEN && item.label.equals(navigationOpen)) {
                    navigationOpen = null;
                    refreshLists();
                } else {
                    onItemClick(item);
                }
            }
        });
        adapterCategories.setSelectedItem(selectedItem);
        listNavigationCategories.setAdapter(adapterCategories);
    }


    private class LoadCategoryListTask extends BackgroundTask<Void, List<NavigationAdapter.NavigationItem>> {
        @Override
        protected List<NavigationAdapter.NavigationItem> doInBackground(Void... voids) {
            /*List<NavigationAdapter.NavigationItem> categories = db.getCategories();
            if (!categories.isEmpty() && categories.get(0).label.isEmpty()) {
                itemUncategorized = categories.get(0);
                itemUncategorized.label = getString(R.string.action_uncategorized);
                itemUncategorized.icon = NavigationAdapter.ICON_NOFOLDER;
            } else {
                itemUncategorized = null;
            }*/
            itemUncategorized = null;

            int nbPT = 0;
            int nbCU = 0;
            List<DBLogjob> ljs = db.getLogjobs();
            for (DBLogjob lj : ljs) {
                if (lj.getToken().isEmpty() && lj.getDeviceName().isEmpty()) {
                    nbCU++;
                } else {
                    nbPT++;
                }
            }

            Map<String, Integer> enabled = db.getEnabledCount();
            Integer enabledCount = enabled.get("1");
            Integer disabledCount = enabled.get("0");
            int numEnabled = enabledCount != null ? enabledCount : 0;
            int numDisabled = disabledCount != null ? disabledCount : 0;
            itemEnabled.count = numEnabled;
            itemAll.count = numEnabled + numDisabled;
            itemPhonetrack.count = nbPT;
            itemCustom.count = nbCU;

            ArrayList<NavigationAdapter.NavigationItem> items = new ArrayList<>();
            items.add(itemAll);
            items.add(itemEnabled);
            items.add(itemPhonetrack);
            items.add(itemCustom);
            return items;
        }

        @Override
        protected void onPostExecute(List<NavigationAdapter.NavigationItem> items) {
            adapterCategories.setItems(items);
        }
    }


    private void setupNavigationMenu() {
        //final NavigationAdapter.NavigationItem itemTrashbin = new NavigationAdapter.NavigationItem("trashbin", getString(R.string.action_trashbin), null, R.drawable.ic_delete_grey600_24dp);
        final NavigationAdapter.NavigationItem itemMap = new NavigationAdapter.NavigationItem("map", getString(R.string.simple_map), null, R.drawable.ic_map_grey_24dp);
        final NavigationAdapter.NavigationItem itemSettings = new NavigationAdapter.NavigationItem("settings", getString(R.string.action_settings), null, R.drawable.ic_settings_grey600_24dp);
        final NavigationAdapter.NavigationItem itemAbout = new NavigationAdapter.NavigationItem("about", getString(R.string.simple_about), null, R.drawable.ic_info_outline_grey600_24dp);

        ArrayList<NavigationAdapter.NavigationItem> itemsMenu = new ArrayList<>();
        itemsMenu.add(itemMap);
        itemsMenu.add(itemSettings);
        itemsMenu.add(itemAbout);

        NavigationAdapter adapterMenu = new NavigationAdapter(new NavigationAdapter.ClickListener() {
            @Override
            public void onItemClick(NavigationAdapter.NavigationItem item) {
                if (item == itemSettings) {
                    Intent settingsIntent = new Intent(getApplicationContext(), PreferencesActivity.class);
                    startActivity(settingsIntent);
                } else if (item == itemAbout) {
                    Intent aboutIntent = new Intent(getApplicationContext(), AboutActivity.class);
                    startActivity(aboutIntent);
                } else if (item == itemMap) {
                    List<DBSession> sessions = db.getSessions();
                    List<String> sessionNameList = new ArrayList<>();
                    final List<Long> sessionIdList = new ArrayList<>();
                    for (DBSession session : sessions) {
                        sessionNameList.add(session.getName());
                        sessionIdList.add(session.getId());
                    }
                    // manage session list DIALOG
                    AlertDialog.Builder selectBuilder = new AlertDialog.Builder(new ContextThemeWrapper(listView.getContext(), R.style.AppThemeDialog));
                    selectBuilder.setTitle(getString(R.string.map_choose_session_dialog_title));

                    if (sessionNameList.size() > 0) {
                        if (sessionNameList.size() == 1) {
                            long sid = sessionIdList.get(0);
                            Intent mapIntent = new Intent(getApplicationContext(), MapActivity.class);
                            mapIntent.putExtra(MapActivity.PARAM_SESSIONID, sid);
                            startActivity(mapIntent);
                        } else {
                            CharSequence[] entcs = sessionNameList.toArray(new CharSequence[0]);
                            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                            long lastSelectedSessionId = preferences.getLong(SettingsActivity.SETTINGS_LAST_SELECTED_SESSION_ID, -1);

                            int selectedIndex = sessionIdList.indexOf(lastSelectedSessionId);
                            selectBuilder.setSingleChoiceItems(entcs, selectedIndex, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {

                                    long sid = sessionIdList.get(which);
                                    Intent mapIntent = new Intent(getApplicationContext(), MapActivity.class);
                                    mapIntent.putExtra(MapActivity.PARAM_SESSIONID, sid);
                                    startActivity(mapIntent);
                                    dialog.dismiss();

                                    SharedPreferences.Editor editor = preferences.edit();
                                    editor.putLong(SettingsActivity.SETTINGS_LAST_SELECTED_SESSION_ID, sid);
                                    editor.apply();
                                }
                            });
                            selectBuilder.setNegativeButton(getString(R.string.simple_cancel), null);
                            selectBuilder.setPositiveButton(getString(R.string.simple_ok), new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    ListView lw = ((AlertDialog)dialog).getListView();
                                    int w = lw.getCheckedItemPosition();

                                    if (w >= 0) {
                                        long sid = sessionIdList.get(w);
                                        Intent mapIntent = new Intent(getApplicationContext(), MapActivity.class);
                                        mapIntent.putExtra(MapActivity.PARAM_SESSIONID, sid);
                                        startActivity(mapIntent);
                                        dialog.dismiss();
                                    }
                                }
                            });


                            AlertDialog selectDialog = selectBuilder.create();
                            selectDialog.show();
                        }
                    }
                    else {
                        showToast(getString(R.string.map_choose_session_dialog_impossible), Toast.LENGTH_LONG);
                    }
                }
            }

            @Override
            public void onIconClick(NavigationAdapter.NavigationItem item) {
                onItemClick(item);
            }
        });


        this.updateUsernameInDrawer();
        final LogjobsListViewActivity that = this;
        this.account.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent settingsIntent = new Intent(that, SettingsActivity.class);
                accountSettingsLauncher.launch(settingsIntent);
            }
        });

        adapterMenu.setItems(itemsMenu);
        listNavigationMenu.setAdapter(adapterMenu);
    }

    private void cancelableLogjobDeletion(DBLogjob dbLogjob) {
        // get locations
        final List<DBLogjobLocation> locations = db.getLocationsOfLogjob(dbLogjob.getId());
        db.deleteLogjob(dbLogjob.getId());
        adapter.remove(dbLogjob);
        refreshLists();
        notifyLoggerService(dbLogjob.getId());

        SystemLogger.v(TAG, "Item deleted through swipe");
        Snackbar.make(swipeRefreshLayout, R.string.action_logjob_deleted, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        long restoredId = db.addLogjob(dbLogjob);
                        SystemLogger.e(TAG, "logjob " + dbLogjob.getId() + " restored " + restoredId);
                        for (DBLogjobLocation dbloc : locations) {
                            db.addLocation(dbloc);
                        }
                        refreshLists();
                        Snackbar.make(swipeRefreshLayout, R.string.action_logjob_restored, Snackbar.LENGTH_SHORT)
                                .show();
                        notifyLoggerService(restoredId);
                    }
                })
                .show();
    }

    public void initList() {
        adapter = new ItemAdapter(this, db);
        listView.setAdapter(adapter);
        listView.setLayoutManager(new LinearLayoutManager(this));
        ItemTouchHelper touchHelper = new ItemTouchHelper(new SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            /**
             * Disable swipe on sections
             *
             * @param recyclerView RecyclerView
             * @param viewHolder   RecyclerView.ViewHoler
             * @return 0 if section, otherwise super()
             */
            @Override
            public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                if (viewHolder instanceof ItemAdapter.SectionViewHolder) return 0;
                return super.getSwipeDirs(recyclerView, viewHolder);
            }

            /**
             * Delete logjob if logjob is swiped to left or right
             *
             * @param viewHolder RecyclerView.ViewHoler
             * @param direction  int
             */
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                switch(direction) {
                    case ItemTouchHelper.LEFT: {
                        // warning, this could be viewHolder.getAbsoluteAdapterPosition() if we were using ConcatAdapter
                        final DBLogjob dbLogjob = (DBLogjob) adapter.getItem(viewHolder.getBindingAdapterPosition());
                        DBLogjob upToDateLogjob = db.getLogjob(dbLogjob.getId());
                        if (upToDateLogjob.isEnabled()) {
                            showToast(getString(R.string.logjob_delete_active_impossible));
                            adapter.notifyItemChanged(viewHolder.getBindingAdapterPosition());
                        } else {
                            cancelableLogjobDeletion(dbLogjob);
                        }
                        break;
                    }
                    case ItemTouchHelper.RIGHT: {
                        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                        boolean resetOnToggle = preferences.getBoolean(getString(R.string.pref_key_reset_stats), false);
                        final DBLogjob dbLogjob = (DBLogjob) adapter.getItem(viewHolder.getBindingAdapterPosition());
                        db.toggleEnabled(dbLogjob, syncCallBack, resetOnToggle);
                        refreshLists();
                        notifyLoggerService(dbLogjob.getId());
                        break;
                    }
                }
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                ItemAdapter.LogjobViewHolder logjobViewHolder = (ItemAdapter.LogjobViewHolder) viewHolder;
                // show swipe icon on the side
                logjobViewHolder.showSwipe(dX>0);
                // move only swipeable part of item (not leave-behind)
                getDefaultUIUtil().onDraw(c, recyclerView, logjobViewHolder.logjobSwipeable, dX, dY, actionState, isCurrentlyActive);
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                getDefaultUIUtil().clearView(((ItemAdapter.LogjobViewHolder) viewHolder).logjobSwipeable);
            }
        });
        touchHelper.attachToRecyclerView(listView);
    }

    private void refreshLists() {
        refreshLists(false);
    }
    private void refreshLists(final boolean scrollToTop) {
        String subtitle;
        if (navigationSelection.favorite != null && navigationSelection.favorite) {
            subtitle = getString(R.string.app_name) + " - " + getString(R.string.label_enabled);
        } else if (CATEGORY_PHONETRACK.equals(navigationSelection.category)) {
            subtitle = getString(R.string.app_name);
        } else if (CATEGORY_CUSTOM.equals(navigationSelection.category)) {
            subtitle = getString(R.string.app_name) + " - " + getString(R.string.label_custom);
        } else {
            subtitle = getString(R.string.app_name) + " - " + getString(R.string.label_all_logjobs);
        }
        setTitle(subtitle);
        CharSequence query = null;
        if (searchView != null && !searchView.isIconified() && searchView.getQuery().length() != 0) {
            query = searchView.getQuery();
        }

        LoadLogjobsListTask.LogjobsLoadedListener callback = new LoadLogjobsListTask.LogjobsLoadedListener() {
            @Override
            public void onLogjobsLoaded(List<Item> ljItems, boolean showCategory) {
                adapter.setShowCategory(showCategory);
                adapter.setItemList(ljItems);
                if(scrollToTop) {
                    listView.scrollToPosition(0);
                }
            }
        };
        new LoadLogjobsListTask(getApplicationContext(), callback, navigationSelection, query).executeInParallel();
        new LoadCategoryListTask().executeInParallel();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            searchView.setQuery(intent.getStringExtra(SearchManager.QUERY), true);
        }
        super.onNewIntent(intent);
    }

    /**
     * Handles the Results of started Sub Activities (Created Logjob, Edited Logjob)
     *
     * @param requestCode int to distinguish between the different Sub Activities
     * @param resultCode  int Return Code
     * @param data        Intent
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Check which request we're responding to
        //if (requestCode == save_file_cmd) {
    }

    private final ActivityResultLauncher<Intent> saveFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    new ActivityResultCallback<ActivityResult>() {
                        @Override
                        public void onActivityResult(ActivityResult result) {
                            SystemLogger.d(TAG, "saveFileLauncher result, is " + result.getResultCode() + " == " + RESULT_OK + " ?");
                            Intent data = result.getData();
                            if (result.getResultCode() == RESULT_OK && data != null) {
                                Uri savedFile = data.getData();
                                SystemLogger.v(TAG, "Save to " + savedFile);
                                saveToFileUri(contentToExport, savedFile);
                            }
                        }
                    });

    private final ActivityResultLauncher<Intent> createLogjobLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    new ActivityResultCallback<ActivityResult>() {
                        @Override
                        public void onActivityResult(ActivityResult result) {
                            SystemLogger.d(TAG, "createLogjobLauncher result, is " + result.getResultCode() + " == " + RESULT_OK + " ?");
                            listView.scrollToPosition(0);
                        }
                    });

    private final ActivityResultLauncher<Intent> accountSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    SystemLogger.d(TAG, "accountSettingsLauncher result OK");
                    db = PhoneTrackSQLiteOpenHelper.getInstance(LogjobsListViewActivity.this);
                    if (db.getPhonetrackServerSyncHelper().isSyncPossible()) {
                        LogjobsListViewActivity.this.updateUsernameInDrawer();
                        adapter.removeAll();
                        //synchronize();
                    } else {
                        if (SessionServerSyncHelper.isConfigured(getApplicationContext())) {
                            Toast.makeText(getApplicationContext(), getString(R.string.error_sync, getString(PhoneTrackClientUtil.LoginStatus.NO_NETWORK.str)), Toast.LENGTH_LONG).show();
                        }
                    }
                }
            });

    private void updateUsernameInDrawer() {
        if (!SessionServerSyncHelper.isNextcloudAccountConfigured(this)) {
            account.setText(getString(R.string.drawer_connect_hint));
            updateAvatarInDrawer(false);
        } else {
            String accountServerUrl;
            String accountUser;
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            if (preferences.getBoolean(SettingsActivity.SETTINGS_USE_SSO, false)) {
                try {
                    SingleSignOnAccount ssoAccount = SingleAccountHelper.getCurrentSingleSignOnAccount(this);
                    accountServerUrl = ssoAccount.url.replaceAll("/+$", "").replaceAll("^https?://", "");
                    accountUser = ssoAccount.userId;
                } catch (NextcloudFilesAppAccountNotFoundException | NoCurrentAccountSelectedException e) {
                    accountServerUrl = "error";
                    accountUser = "error";
                }
            } else {
                accountServerUrl = preferences.getString(SettingsActivity.SETTINGS_URL, SettingsActivity.DEFAULT_SETTINGS);
                if (accountServerUrl != null) {
                        accountServerUrl = accountServerUrl
                                .replaceAll("/+$", "")
                                .replaceAll("^https?://", "");
                }
                accountUser = preferences.getString(SettingsActivity.SETTINGS_USERNAME, SettingsActivity.DEFAULT_SETTINGS);
            }
            String accountString = accountUser + "@" + accountServerUrl;
            account.setText(accountString);
            updateAvatarInDrawer(true);
        }
    }

    private void updateAvatarInDrawer(boolean isAccountConfigured) {
        if (isAccountConfigured) {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            String avatarB64 = preferences.getString(getString(R.string.pref_key_avatar), "");
            if (!"".equals(avatarB64)) {
                try {
                    SystemLogger.d(TAG, "[Avatar] Try to set avatar from stored data");
                    byte[] decodedString = Base64.decode(avatarB64, Base64.DEFAULT);
                    Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
                    Bitmap rounded = ThemeUtils.getRoundedBitmap(decodedByte, decodedByte.getWidth() / 2);
                    avatarView.setImageBitmap(rounded);
                    accountButton.setImageBitmap(rounded);
                } catch (Exception e) {
                    SystemLogger.d(TAG, "[Avatar] Avatar can't be set: " + e.toString());
                    avatarView.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_nextcloud_logo_white));
                    accountButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_nextcloud_logo_white));
                    accountButton.setColorFilter(Color.GRAY);
                    SystemLogger.d(TAG, "[Avatar] Default icons have been set");
                }
            } else {
                SystemLogger.d(TAG, "[Avatar] Empty avatar");
                avatarView.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_nextcloud_logo_white));
                accountButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_nextcloud_logo_white));
                accountButton.setColorFilter(Color.GRAY);
                SystemLogger.d(TAG, "[Avatar] Default icons have been set");
            }
        } else {
            SystemLogger.d(TAG, "[Avatar] No account configured");
            avatarView.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_nextcloud_logo_white));
            accountButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_account_circle_grey_24dp));
            SystemLogger.d(TAG, "[Avatar] Default icons have been set");
        }
    }

    @Override
    public void onLogjobClick(int position, View v) {
        if (mActionMode != null) {
            if (!adapter.select(position)) {
                v.setSelected(false);
                adapter.deselect(position);
            } else {
                v.setSelected(true);
            }
            int size = adapter.getSelected().size();
            mActionMode.setTitle(getResources().getQuantityString(R.plurals.ab_selected, size, size));
            int checkedItemCount = adapter.getSelected().size();
            boolean hasCheckedItems = checkedItemCount > 0;

            if (hasCheckedItems && mActionMode == null) {
                // TODO differ if one or more items are selected
                // if (checkedItemCount == 1) {
                // mActionMode = startActionMode(new
                // SingleSelectedActionModeCallback());
                // } else {
                // there are some selected items, start the actionMode
                mActionMode = startSupportActionMode(new MultiSelectedActionModeCallback());
                // }
            } else if (!hasCheckedItems && mActionMode != null) {
                // there no selected items, finish the actionMode
                mActionMode.finish();
            }
        } else {
            DBLogjob logjob = (DBLogjob) adapter.getItem(position);
            Intent intent;
            if (logjob.getToken().isEmpty() && logjob.getDeviceName().isEmpty() && logjob.getUrl().isEmpty()) {
                intent = new Intent(getApplicationContext(), EditMapsLogjobActivity.class);
            } else if (logjob.getToken().isEmpty() && logjob.getDeviceName().isEmpty()) {
                intent = new Intent(getApplicationContext(), EditCustomLogjobActivity.class);
            } else {
                intent = new Intent(getApplicationContext(), EditPhoneTrackLogjobActivity.class);
            }
            intent.putExtra(EditLogjobActivity.PARAM_LOGJOB_ID, logjob.getId());
            startActivity(intent);
        }
    }

    @Override
    public void onLogjobEnabledClick(int position, View view) {
        DBLogjob logjob = (DBLogjob) adapter.getItem(position);
        if (logjob != null) {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            boolean resetOnToggle = preferences.getBoolean(getString(R.string.pref_key_reset_stats), false);
            PhoneTrackSQLiteOpenHelper db = PhoneTrackSQLiteOpenHelper.getInstance(view.getContext());
            db.toggleEnabled(logjob, syncCallBack, resetOnToggle);
            adapter.notifyItemChanged(position);
            refreshLists();

            notifyLoggerService(logjob.getId());
        }
    }

    public void onLogjobMapButtonClick(long sessionId) {
        Intent mapIntent = new Intent(getApplicationContext(), MapActivity.class);
        mapIntent.putExtra(MapActivity.PARAM_SESSIONID, sessionId);
        startActivity(mapIntent);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong(SettingsActivity.SETTINGS_LAST_SELECTED_SESSION_ID, sessionId);
        editor.apply();
    }

    @Override
    public void onLogjobMoreButtonClick(int position, View view) {
        DBLogjob logjobItem = (DBLogjob) adapter.getItem(position);
        if (logjobItem != null) {
            DBLogjob logjob = db.getLogjob(logjobItem.getId());

            PopupMenu popup = new PopupMenu(this, view);
            popup.setForceShowIcon(true);

            popup.getMenuInflater()
                    .inflate(R.menu.logjob_popup_menu, popup.getMenu());

            if (!logjob.isPhonetrack()) {
                popup.getMenu().findItem(R.id.menuDisplayMap).setVisible(false);
            }

            popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    DBLogjob logjobMenu = db.getLogjob(logjobItem.getId());
                    if (item.getItemId() == R.id.menuDisplayMap) {
                        long sessionId = 0;
                        String token = logjobMenu.getToken();
                        if (token != null && !token.equals("")){
                            List<DBSession> sessions = db.getSessions();
                            for (DBSession s : sessions) {
                                if (s.getToken().equals(token)) {
                                    sessionId = s.getId();
                                    break;
                                }
                            }
                        }
                        onLogjobMapButtonClick(sessionId);
                    } else if (item.getItemId() == R.id.menuDisplayLogjobInfo) {
                        onLogjobInfoButtonClick(logjobMenu);
                    } else if (item.getItemId() == R.id.menuDeleteLogjob) {
                        if (logjobMenu.isEnabled()) {
                            showToast(getString(R.string.logjob_delete_active_impossible));
                        } else {
                            cancelableLogjobDeletion(logjobItem);
                        }
                    // } else if (item.getItemId() == R.id.menuExportToGpx) {
                        // exportLogjobToGPX(logjobMenu);
                    }
                    return true;
                }
            });
            popup.show();
        }
    }

    /*
    public void exportLogjobToGPX(DBLogjob logjob) {
        List<DBLogjobLocation> locs = db.getLocationsOfLogjob(logjob.getId());

        GPXSegment segment = new GPXSegment();
        GPXTrackPoint point;
        for (DBLogjobLocation loc: locs) {
            point = new GPXTrackPoint((float)loc.getLat(), (float)loc.getLon());
            segment.addPoint(point);
        }
        GPXTrack track = new GPXTrack();
        track.addSegment(segment);
        List<GPXTrack> tracks = new ArrayList<>();
        tracks.add(track);
        GPXDocument gpxDoc = new GPXDocument(null, tracks, null);

        contentToExport = "";
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(baos, true, "utf-8")) {
            gpxDoc.toGPX(ps);
            contentToExport = baos.toString("utf-8");
        } catch (UnsupportedEncodingException e) {

        }
        String userAgent = getString(R.string.app_name) + "/" + SupportUtil.getAppVersionName(this);
        contentToExport = contentToExport.replace("AndroidGPX ( http://codebutchery.wordpress.com )", userAgent);

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/gpx+xml");
        intent.putExtra(Intent.EXTRA_TITLE, logjob.getTitle() + ".gpx");

        // Optionally, specify a URI for the directory that should be opened in
        // the system file picker when your app creates the document.
        //intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri);

        //startActivityForResult(intent, save_file_cmd);
        saveFileLauncher.launch(intent);
    }
    */

    private void saveToFileUri(String content, Uri fileUri) {
        try {
            OutputStream fOut = getContentResolver().openOutputStream(fileUri);
            OutputStreamWriter myOutWriter = new OutputStreamWriter(fOut);
            myOutWriter.append(content);
            myOutWriter.close();
            if (fOut != null) {
                fOut.flush();
                fOut.close();
            }
            String lastPathSegment = fileUri.getLastPathSegment();
            if (lastPathSegment != null) {
                showToast(
                    getString(
                        R.string.file_saved_success, lastPathSegment.replace(
                            Environment.getExternalStorageDirectory().toString(),
                            ""
                        )
                    )
                );
            }
        } catch (IOException e) {
            SystemLogger.e(TAG, "File write failed: " + e.toString());
            showToast(e.toString());
        }
    }

    public void onLogjobInfoButtonClick(DBLogjob logjob) {
        if (logjob != null) {
            long ljId = logjob.getId();
            PhoneTrackSQLiteOpenHelper db = PhoneTrackSQLiteOpenHelper.getInstance(this);
            View iView = LayoutInflater.from(this).inflate(R.layout.items_infodialog, null);

            updateInfoDialogContent(iView, ljId, this);

            AlertDialog.Builder builder;
            builder = new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.AppThemeDialog));
            builder.setTitle(getString(R.string.logjob_info_dialog_title, logjob.getTitle()))
                    .setView(iView)
                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            currentInfoDialogView = null;
                            currentInfoDialogLogjobId = -1;
                        }
                    })
                    .setNeutralButton(R.string.reset_current_run, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            db.resetLogjobCurrentRun(ljId);
                            dialog.dismiss();
                            refreshLists();
                        }
                    })
                    .setIcon(R.drawable.ic_info_outline_grey600_24dp)
                    .show();

            currentInfoDialogView = iView;
            currentInfoDialogLogjobId = ljId;
        }
    }

    private void updateInfoDialogContent(View iView, long ljId, Context c) {
        PhoneTrackSQLiteOpenHelper db = PhoneTrackSQLiteOpenHelper.getInstance(c);
        DBLogjob logjob = db.getLogjob(ljId);
        long tsNow = new Date().getTime() / 1000;
        long tsLastActivationSystem = db.getLastActivationSystemTimestamp(ljId);
        long diffLastActivation = tsNow - tsLastActivationSystem;
        long tsLastLoc = db.getLastLocTimestamp(ljId);
        long diffLastLoc = tsNow - tsLastLoc;
        long tsLastSync = db.getLastSyncTimestamp(ljId);
        long diffLastSync = tsNow - tsLastSync;
        SyncError lastSyncErr = db.getLastSyncError(ljId);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);

        if (LoggerService.DEBUG) { SystemLogger.d(TAG, "updateInfoDialogContent " + tsLastLoc + " " + tsLastSync); }

        List<DBLogjobLocation> cRLocations = db.getCurrentRunLocationsOfLogjob(ljId);
        double totDistance = 0.0;
        long duration = 0;
        if (cRLocations.size() > 1) {
            // distance
            DBLogjobLocation loc;
            DBLogjobLocation prevLoc = cRLocations.get(0);
            int i = 1;
            while (i < cRLocations.size()) {
                loc = cRLocations.get(i);
                totDistance += SupportUtil.distance(
                        prevLoc.getLat(), loc.getLat(),
                        prevLoc.getLon(), loc.getLon(),
                        prevLoc.getAltitude(), loc.getAltitude()
                );
                prevLoc = loc;
                i++;
            }
            // duration
            long tFirst = cRLocations.get(0).getTimestamp();
            long tLast = cRLocations.get(cRLocations.size()-1).getTimestamp();
            duration = tLast - tFirst;
        }

        String nbsyncText = c.getString(R.string.logjob_info_nbsync, logjob.getNbSync());
        String nbnotsyncText = c.getString(R.string.logjob_info_nbnotsync, db.getLogjobLocationNotSyncedCount(logjob.getId()));
        String lastLocText;
        String lastSyncText;
        String lastSyncErrText;


        TextView tv = iView.findViewById(R.id.infoNbsyncText);
        tv.setText(nbsyncText);
        TextView tv2 = iView.findViewById(R.id.infoNbnotsyncText);
        tv2.setText(nbnotsyncText);

        if (cRLocations.size() > 0) {
            String nbPointsText = c.getString(R.string.logjob_info_nbpoints, cRLocations.size());

            TextView tv3 = iView.findViewById(R.id.infoNbPointsText);
            tv3.setText(nbPointsText);
            iView.findViewById(R.id.infoNbPointsLayout).setVisibility(View.VISIBLE);
        }
        else {
            iView.findViewById(R.id.infoNbPointsLayout).setVisibility(View.GONE);
        } if (totDistance != 0.0) {
            String formattedDistance = formatDistance(totDistance, c);
            String totDistanceText = c.getString(R.string.logjob_info_distance, formattedDistance);

            TextView tv3 = iView.findViewById(R.id.infoDistanceText);
            tv3.setText(totDistanceText);
            iView.findViewById(R.id.infoDistanceLayout).setVisibility(View.VISIBLE);
        } else {
            iView.findViewById(R.id.infoDistanceLayout).setVisibility(View.GONE);
        }
        if (duration != 0) {
            String formattedDuration = SupportUtil.formatDuration(duration, c);
            String durationText = c.getString(R.string.logjob_info_duration, formattedDuration);

            TextView tv3 = iView.findViewById(R.id.infoDurationText);
            tv3.setText(durationText);
            iView.findViewById(R.id.infoDurationLayout).setVisibility(View.VISIBLE);
        } else {
            iView.findViewById(R.id.infoDurationLayout).setVisibility(View.GONE);
        }
        if (tsLastLoc != 0 && cRLocations.size() > 0) {
            Date d = new Date(tsLastLoc*1000);
            String diffLastLocString = SupportUtil.formatDuration(diffLastLoc, c);
            lastLocText = c.getString(R.string.logjob_info_lastloc, diffLastLocString, sdf.format(d));

            TextView tv3 = iView.findViewById(R.id.infoLastLocText);
            tv3.setText(lastLocText);
            iView.findViewById(R.id.infoLastLocLayout).setVisibility(View.VISIBLE);
        } else {
            iView.findViewById(R.id.infoLastLocLayout).setVisibility(View.GONE);
        }
        if (tsLastSync != 0 && logjob.getNbSync() > 0) {
            Date d = new Date(tsLastSync*1000);
            String diffLastSyncString = SupportUtil.formatDuration(diffLastSync, c);
            lastSyncText = c.getString(R.string.logjob_info_lastsync, diffLastSyncString, sdf.format(d));

            TextView tv4 = iView.findViewById(R.id.infoLastSyncText);
            tv4.setText(lastSyncText);
            iView.findViewById(R.id.infoLastSyncLayout).setVisibility(View.VISIBLE);
        } else {
            iView.findViewById(R.id.infoLastSyncLayout).setVisibility(View.GONE);
        }

        if (lastSyncErr.getTimestamp() != 0) {
            Date d = new Date(lastSyncErr.getTimestamp()*1000);
            String diffLastLocString = SupportUtil.formatDuration(diffLastLoc, c);
            lastSyncErrText = c.getString(
                    R.string.logjob_info_lastsync_error,
                    diffLastLocString,
                    sdf.format(d),
                    lastSyncErr.getMessage());

            TextView tv5 = iView.findViewById(R.id.infoLastSyncErrText);
            tv5.setText(lastSyncErrText);
            iView.findViewById(R.id.infoLastSyncErrLayout).setVisibility(View.VISIBLE);
        } else {
            iView.findViewById(R.id.infoLastSyncErrLayout).setVisibility(View.GONE);
        }
        if (logjob.isEnabled()) {
            Date d = new Date(tsLastActivationSystem*1000);
            String diffLastActivationString = SupportUtil.formatDuration(diffLastActivation, c);
            String lastActivationText = c.getString(R.string.logjob_info_last_activation, diffLastActivationString, sdf.format(d));

            TextView tv3 = iView.findViewById(R.id.infoLastActivationText);
            tv3.setText(lastActivationText);
            iView.findViewById(R.id.infoLastActivationLayout).setVisibility(View.VISIBLE);
        } else {
            iView.findViewById(R.id.infoLastActivationLayout).setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onLogjobLongClick(int position, View v) {
        boolean selected = adapter.select(position);
        if (selected) {
            v.setSelected(true);
            mActionMode = startSupportActionMode(new MultiSelectedActionModeCallback());
            int checkedItemCount = adapter.getSelected().size();
            mActionMode.setTitle(getResources().getQuantityString(R.plurals.ab_selected, checkedItemCount, checkedItemCount));
        }
        return selected;
    }

    /**
     * Back closes the search toolbar when it is open. Enabled only while it is,
     * so the system (predictive) back gesture works normally otherwise.
     */
    private final OnBackPressedCallback closeSearchOnBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            updateToolbars(true);
        }
    };

    private void synchronize() {
        if (LoggerService.DEBUG) { SystemLogger.d(TAG, "synchronize()"); }
        db.getPhonetrackServerSyncHelper().addCallbackPull(syncCallBack);
        db.getPhonetrackServerSyncHelper().scheduleSync(false);
    }

    private void notifyLoggerService(long jobId) {
        Intent intent = new Intent(LogjobsListViewActivity.this, LoggerService.class);
        intent.putExtra(UPDATED_LOGJOBS, true);
        intent.putExtra(UPDATED_LOGJOB_ID, jobId);
        startService(intent);
    }

    /**
     * Handler for the MultiSelect Actions
     */
    private class MultiSelectedActionModeCallback implements ActionMode.Callback {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // inflate contextual menu
            mode.getMenuInflater().inflate(R.menu.menu_list_context_multiple, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        /**
         * @param mode ActionMode - used to close the Action Bar after all work is done.
         * @param item MenuItem - the item in the List that contains the Node
         * @return boolean
         */
        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == R.id.menu_delete) {
                List<Integer> selection = adapter.getSelected();
                for (Integer i : selection) {
                    DBLogjob logjob = (DBLogjob) adapter.getItem(i);
                    db.deleteLogjob(logjob.getId());
                    adapter.remove(logjob);
                    notifyLoggerService(logjob.getId());
                }
                mode.finish(); // Action picked, so close the CAB
                //after delete selection has to be cleared
                searchView.setIconified(true);
                refreshLists();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            adapter.clearSelection();
            mActionMode = null;
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * Display toast message
     * @param text Message
     */
    private void showToast(CharSequence text) {
        showToast(text, Toast.LENGTH_SHORT);
    }

    /**
     * Display toast message
     * @param text Message
     * @param duration Duration
     */
    private void showToast(CharSequence text, int duration) {
        Context context = getApplicationContext();
        Toast toast = Toast.makeText(context, text, duration);
        toast.show();
    }

    private void updateAllLogjobItems() {
        for (int i = 0; i < adapter.getItemCount(); i++) {
            adapter.notifyItemChanged(i);
            if (LoggerService.DEBUG) {
                SystemLogger.d(TAG, "notifyItemChanged " + i);
            }
        }
    }

    /**
     * Register broadcast receiver for synchronization
     * and tracking status updates
     */
    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(SmsListener.BROADCAST_LOGJOB_LIST_UPDATED);
        filter.addAction(LoggerService.BROADCAST_LOCATION_STARTED);
        filter.addAction(LoggerService.BROADCAST_LOCATION_STOPPED);
        filter.addAction(LoggerService.BROADCAST_LOCATION_UPDATED);
        filter.addAction(LoggerService.BROADCAST_LOCATION_DISABLED);
        filter.addAction(LoggerService.BROADCAST_LOCATION_GPS_DISABLED);
        filter.addAction(LoggerService.BROADCAST_LOCATION_NETWORK_DISABLED);
        filter.addAction(LoggerService.BROADCAST_LOCATION_GPS_ENABLED);
        filter.addAction(LoggerService.BROADCAST_LOCATION_NETWORK_ENABLED);
        filter.addAction(LoggerService.BROADCAST_LOCATION_PERMISSION_DENIED);
        filter.addAction(WebTrackSync.BROADCAST_SYNC_STARTED);
        filter.addAction(WebTrackSync.BROADCAST_SYNC_DONE);
        filter.addAction(WebTrackSync.BROADCAST_SYNC_FAILED);
        filter.addAction(SessionServerSyncHelper.BROADCAST_SESSIONS_SYNC_FAILED);
        filter.addAction(SessionServerSyncHelper.BROADCAST_SESSIONS_SYNCED);
        filter.addAction(SessionServerSyncHelper.BROADCAST_SSO_TOKEN_MISMATCH);
        filter.addAction(SessionServerSyncHelper.BROADCAST_NETWORK_AVAILABLE);
        filter.addAction(SessionServerSyncHelper.BROADCAST_NETWORK_UNAVAILABLE);
        filter.addAction(SessionServerSyncHelper.BROADCAST_AVATAR_UPDATED);
        ContextCompat.registerReceiver(this, mBroadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /**
     * Broadcast receiver
     */
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LoggerService.DEBUG) { SystemLogger.d(TAG, "broadcast received " + intent); }
            if (intent == null || intent.getAction() == null) {
                return;
            }
            switch (intent.getAction()) {
                case SmsListener.BROADCAST_LOGJOB_LIST_UPDATED:
                    refreshLists();
                    break;
                case LoggerService.BROADCAST_LOCATION_UPDATED:
                    long ljId = intent.getLongExtra(LoggerService.BROADCAST_EXTRA_PARAM, 0);
                    if (LoggerService.DEBUG) { SystemLogger.d(TAG, "broadcast location updated " + ljId); }
                    // to update all items
                    //adapter.notifyDataSetChanged();
                    // but we update just the changed one
                    DBLogjob lj;
                    for (int i = 0; i < adapter.getItemCount(); i++) {
                        lj = (DBLogjob) adapter.getItem(i);
                        if (lj.getId() == ljId) {
                            adapter.notifyItemChanged(i);
                            break;
                        }
                    }
                    break;
                case WebTrackSync.BROADCAST_SYNC_STARTED:
                    //swipeRefreshLayout.setRefreshing(true);
                    break;
                // when sync is finished (fail or success)
                case WebTrackSync.BROADCAST_SYNC_DONE:
                    long ljId2 = intent.getLongExtra(LoggerService.BROADCAST_EXTRA_PARAM, 0);
                    if (ljId2 != 0) {
                        if (LoggerService.DEBUG) {
                            SystemLogger.d(TAG, "broadcast loc synced " + ljId2);
                        }
                        // to update all items
                        //adapter.notifyDataSetChanged();
                        // but we update just the changed one
                        DBLogjob lj2;
                        for (int i = 0; i < adapter.getItemCount(); i++) {
                            lj2 = (DBLogjob) adapter.getItem(i);
                            if (lj2.getId() == ljId2) {
                                adapter.notifyItemChanged(i);
                                if (LoggerService.DEBUG) {
                                    SystemLogger.d(TAG, "notifyItemChanged " + i);
                                }
                                break;
                            }
                        }
                    }
                    // without parameter : end of sync service
                    else {
                        swipeRefreshLayout.setRefreshing(false);
                    }
                    updateCurrentInfoDialog();
                    break;
                case (WebTrackSync.BROADCAST_SYNC_FAILED): {
                    //long ljId3 = intent.getLongExtra(LoggerService.BROADCAST_EXTRA_PARAM, 0);
                    String errorMessage = intent.getStringExtra(LoggerService.BROADCAST_ERROR_MESSAGE);
                    showToast(getString(R.string.uploading_failed) + "\n" + errorMessage, Toast.LENGTH_LONG);
                    updateCurrentInfoDialog();
                    break;
                }
                case SessionServerSyncHelper.BROADCAST_SESSIONS_SYNC_FAILED:
                    String errorMessage = intent.getStringExtra(LoggerService.BROADCAST_ERROR_MESSAGE);
                    if (errorMessage != null) {
                        showToast(errorMessage, Toast.LENGTH_LONG);
                    }


                    updateAllLogjobItems();
                    break;
                case SessionServerSyncHelper.BROADCAST_SESSIONS_SYNCED:
                    if (ssoSnackbar != null) {
                        ssoSnackbar.dismiss();
                        ssoSnackbar = null;
                    }
                    // (custom-view toasts are deprecated and not shown from the background)
                    Snackbar.make(swipeRefreshLayout, R.string.sessions_sync_success, Snackbar.LENGTH_SHORT).show();

                    updateAllLogjobItems();
                    break;
                case SessionServerSyncHelper.BROADCAST_SSO_TOKEN_MISMATCH:
                    ssoSnackbar = Snackbar.make(swipeRefreshLayout, R.string.error_token_mismatch, Snackbar.LENGTH_INDEFINITE);
                    ssoSnackbar.show();
                    break;
                case LoggerService.BROADCAST_LOCATION_STARTED:
                    showToast(getString(R.string.tracking_started));
                    break;
                case LoggerService.BROADCAST_LOCATION_STOPPED:
                    showToast(getString(R.string.tracking_stopped));
                    break;
                case LoggerService.BROADCAST_LOCATION_GPS_DISABLED:
                    showToast(getString(R.string.gps_disabled_warning), Toast.LENGTH_LONG);
                    break;
                case LoggerService.BROADCAST_LOCATION_NETWORK_DISABLED:
                    showToast(getString(R.string.net_disabled_warning), Toast.LENGTH_LONG);
                    break;
                case LoggerService.BROADCAST_LOCATION_DISABLED:
                    showToast(getString(R.string.location_disabled), Toast.LENGTH_LONG);
                    break;
                case LoggerService.BROADCAST_LOCATION_NETWORK_ENABLED:
                    showToast(getString(R.string.using_network), Toast.LENGTH_LONG);
                    break;
                case LoggerService.BROADCAST_LOCATION_GPS_ENABLED:
                    showToast(getString(R.string.using_gps), Toast.LENGTH_LONG);
                    break;
                case LoggerService.BROADCAST_LOCATION_PERMISSION_DENIED:
                    showToast(getString(R.string.location_permission_denied), Toast.LENGTH_LONG);
                    locationPermissionAsked = false;
                    backgroundLocationPermissionAsked = false;
                    requestNextPermission();
                    break;
                case SessionServerSyncHelper.BROADCAST_NETWORK_AVAILABLE:
                    swipeRefreshLayout.setEnabled(true);
                    break;
                case SessionServerSyncHelper.BROADCAST_NETWORK_UNAVAILABLE:
                    swipeRefreshLayout.setEnabled(false);
                    break;
                case SessionServerSyncHelper.BROADCAST_AVATAR_UPDATED:
                    // this is the account avatar
                    SystemLogger.v(TAG, "broadcast UPDATE avatar of NC account");
                    updateAvatarInDrawer(true);
                    break;
            }
        }
    };

    private final ICallback createSessionCallBack = new ICallback() {
        @Override
        public void onFinish() {
        }

        public void onFinish(String sessionId, String message) {
            if (sessionId != null) {
                Snackbar.make(swipeRefreshLayout, R.string.action_session_created, Snackbar.LENGTH_LONG).show();
                synchronize();
            }
            else {
                showToast(getString(R.string.error_create_session_helper, message), Toast.LENGTH_LONG);
            }
        }

        @Override
        public void onScheduled() {
        }
    };
}
