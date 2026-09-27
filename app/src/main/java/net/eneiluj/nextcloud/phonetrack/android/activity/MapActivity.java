package net.eneiluj.nextcloud.phonetrack.android.activity;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.Toolbar;

import android.os.Parcelable;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import net.eneiluj.nextcloud.phonetrack.R;
import net.eneiluj.nextcloud.phonetrack.model.BasicLocation;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjob;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjobLocation;
import net.eneiluj.nextcloud.phonetrack.model.DBSession;
import net.eneiluj.nextcloud.phonetrack.model.NavigationAdapter;
import net.eneiluj.nextcloud.phonetrack.persistence.PhoneTrackSQLiteOpenHelper;
import net.eneiluj.nextcloud.phonetrack.service.LoggerService;
import net.eneiluj.nextcloud.phonetrack.util.KeyboardUtil;
import net.eneiluj.nextcloud.phonetrack.util.EdgeToEdgeUtil;
import net.eneiluj.nextcloud.phonetrack.util.IGetLastPosCallback;
import net.eneiluj.nextcloud.phonetrack.util.MapUtils;
import net.eneiluj.nextcloud.phonetrack.util.ThemeUtils;

import org.mapsforge.map.android.rendertheme.AssetsRenderTheme;
import org.mapsforge.map.rendertheme.XmlRenderTheme;
import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.mapsforge.MapsForgeTileProvider;
import org.osmdroid.mapsforge.MapsForgeTileSource;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver;
import org.osmdroid.tileprovider.util.StorageUtils;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.ScaleBarOverlay;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.File;
import java.io.FileFilter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import static android.text.format.DateUtils.isToday;

public class MapActivity extends AppCompatActivity {
    MapView map = null;

    private static final String TAG = MapActivity.class.getSimpleName();

    public static final String PARAM_SESSIONID = "net.eneiluj.nextcloud.phonetrack.mapSessionId";
    public static final String ID_ITEM_ALL_DEVICES = "net.eneiluj.nextcloud.phonetrack.id_item_all_devices";

    private MyLocationNewOverlay mLocationOverlay;
    private RotationGestureOverlay mRotationGestureOverlay;
    private ScaleBarOverlay mScaleBarOverlay;
    private Context ctx;

    private ImageButton btLayers;
    private ImageButton btDisplayMyLoc;
    private ImageButton btFollowMe;
    private ImageButton btZoom;
    private ImageButton btZoomAuto;
    private ImageButton btAllowRotation;

    // device data
    private Long lastTimestamp = null;
    private Map<String, Long> lastTimestamps;
    private Map<String, List<BasicLocation>> locations;
    private Map<String, List<BasicLocation>> displayedLocations;
    private Map<String, Integer> colors;
    // graphical stuff
    private Map<String, Polyline> lines;
    private Map<String, Marker> markers;
    private Map<String, CustomLocationMarkerDrawable> markerDrawables;

    private DBSession session;
    private PhoneTrackSQLiteOpenHelper db;

    private String selectedDeviceItemId;
    private Map<String, Boolean> linesEnabled;

    Toolbar toolbar;
    DrawerLayout drawerLayoutMap;
    TextView account;
    RelativeLayout relativeLayoutMap;

    RecyclerView listNavigationDevices;
    RecyclerView listNavigationMenu;

    private NavigationAdapter adapterDevices;
    ArrayList<NavigationAdapter.NavigationItem> itemsNavigationDevice = null;

    private ActionBarDrawerToggle drawerToggle;
    private SharedPreferences prefs;

    private final SimpleDateFormat sdfComplete = new SimpleDateFormat("yyyy-MM-dd\nHH:mm:ss z", Locale.ROOT);
    private final SimpleDateFormat sdfCompleteSimple = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private final SimpleDateFormat sdfHour = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private Drawable toggleCircle;

    private Map<String, OnlineTileSourceBase> layersMap;
    private String selectedLayer;
    private MapTileProviderBasic defaultTileProvider;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_map_view, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_import_map) {
            Intent intent = new Intent()
                    .setType("*/*")
                    .setAction(Intent.ACTION_GET_CONTENT);

            //startActivityForResult(Intent.createChooser(intent, "Select a file"), import_file_cmd);
            importMapFileLauncher.launch(Intent.createChooser(intent, "Select a file"));
            return true;
        }
        else if (itemId == R.id.menu_delete_map) {
            MapUtils.showDeleteMapFileDialog(this);
            return true;
        }
        else {
            return super.onOptionsItemSelected(item);
        }
    }

    private final ActivityResultLauncher<Intent> importMapFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    new ActivityResultCallback<ActivityResult>() {
                        @Override
                        public void onActivityResult(ActivityResult result) {
                            Intent data = result.getData();
                            if (result.getResultCode() == RESULT_OK && data != null) {
                                Uri selectedFile = data.getData();
                                boolean ok = MapUtils.importMapFile(MapActivity.this, selectedFile);
                                if (ok) {
                                    recreate();
                                }
                            }
                        }
                    });

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "[ACT RESULT]");
        // Check which request we're responding to
        //if (requestCode == import_file_cmd && resultCode == Activity.RESULT_OK) {
    }

    // small local SQLite read on the main thread: the session to show (one row) is needed to build the screen
    @SuppressLint("ThreadConstraint")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ctx = getApplicationContext();
        db = PhoneTrackSQLiteOpenHelper.getInstance(ctx);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        long sessionid = getIntent().getLongExtra(PARAM_SESSIONID, 0);
        if (sessionid == 0) {
            Context context = getApplicationContext();
            Toast toast = Toast.makeText(context, getString(R.string.session_not_found), Toast.LENGTH_LONG);
            toast.show();
            finish();
            return;
        }
        session = db.getSession(sessionid);

        toggleCircle = ContextCompat.getDrawable(ctx, R.drawable.ic_plain_circle_grey_24dp)
                .getConstantState().newDrawable();
        toggleCircle.setColorFilter(
                new PorterDuffColorFilter(
                        ThemeUtils.primaryColor(ctx),
                        PorterDuff.Mode.SRC_IN
                )
        );

        setContentView(R.layout.drawer_layout_map);
        EdgeToEdgeUtil.enable(this);

        toolbar = findViewById(R.id.mapActivityActionBar);
        drawerLayoutMap = findViewById(R.id.drawerLayoutMap);
        account = findViewById(R.id.account);
        relativeLayoutMap = findViewById(R.id.relativelayoutMap);
        listNavigationDevices = findViewById(R.id.navigationList);
        listNavigationMenu = findViewById(R.id.navigationMenu);

        setupActionBar();
        drawerToggle.syncState();

        lastTimestamps = new HashMap<>();
        locations = new HashMap<>();
        displayedLocations = new HashMap<>();
        colors = new HashMap<>();
        lines = new HashMap<>();
        markers = new HashMap<>();
        markerDrawables = new HashMap<>();
        selectedDeviceItemId = ID_ITEM_ALL_DEVICES;
        linesEnabled = new HashMap<>();

        // load/initialize the osmdroid configuration, this can be done

        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        // setting this before the layout is inflated is a good idea
        // it 'should' ensure that the map has a writable location for the map cache, even without permissions
        // if no tiles are displayed, you can try overriding the cache path using Configuration.getInstance().setCachePath
        // see also StorageUtils
        // note, the load method also sets the HTTP User Agent to your application's package name, abusing osm's tile servers will get you banned based on this string
        Log.i(TAG, "CREATE map : session : "+session);

        //inflate and create the map (already done upper ;-) )
        //setContentView(R.layout.activity_map);

        map = (MapView) findViewById(R.id.map);
        map.setMaxZoomLevel(20.0);

        setupMapTileProviders();

        selectedLayer = prefs.getString("map_selected_layer", "OpenStreetMap Mapnik");
        if (!layersMap.containsKey(selectedLayer)) {
            // selected layer was removed
            selectedLayer = "OpenStreetMap Mapnik";
            prefs.edit().putString("map_selected_layer", "OpenStreetMap Mapnik").apply();
        }
        setTileSource(selectedLayer);

        IMapController mapController = map.getController();
        mapController.setZoom(2.0);

        map.setMultiTouchControls(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.ALWAYS);

        this.mLocationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(ctx), map);
        Bitmap iconPos = BitmapFactory.decodeResource(ctx.getResources(), R.mipmap.ic_my_position)
                .copy(Bitmap.Config.ARGB_8888, true);
        iconPos = Bitmap.createScaledBitmap(iconPos, 70, 70, true);
        Bitmap iconDir = BitmapFactory.decodeResource(ctx.getResources(), R.mipmap.ic_my_direction)
                .copy(Bitmap.Config.ARGB_8888, true);
        iconDir = Bitmap.createScaledBitmap(iconDir, 70, 70, true);

        mLocationOverlay.setDirectionArrow(iconPos, iconDir);

        map.getOverlays().add(this.mLocationOverlay);

        mRotationGestureOverlay = new RotationGestureOverlay(map);
        map.getOverlays().add(this.mRotationGestureOverlay);

        CopyrightOverlay copyrightOverlay = new CopyrightOverlay(map.getContext());
        copyrightOverlay.setTextColor(Color.BLACK);
        map.getOverlays().add(copyrightOverlay);

        setupMapButtons();

        final DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        mScaleBarOverlay = new ScaleBarOverlay(map);
        mScaleBarOverlay.setCentred(true);
        //play around with these values to get the location on screen in the right place for your application
        mScaleBarOverlay.setScaleBarOffset(dm.widthPixels / 2, 10);
        map.getOverlays().add(this.mScaleBarOverlay);

    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        drawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.syncState();
    }

    private void setupMapTileProviders() {
        layersMap = new HashMap<>();
        layersMap.put("OpenStreetMap Mapnik", TileSourceFactory.MAPNIK);
        layersMap.put("Hike bike map", TileSourceFactory.HIKEBIKEMAP);
        layersMap.put("OpenTopoMap", TileSourceFactory.OpenTopo);
        layersMap.put(
                "OpenCycleMap",
                new XYTileSource(
                        "OpenCycleMap", 1, 22, 256,
                        ".png",
                        new String[]{
                                "https://a.tile.thunderforest.com/cycle/",
                                "https://b.tile.thunderforest.com/cycle/",
                                "https://c.tile.thunderforest.com/cycle/"
                        },
                        "OpenCycleMap (https://www.opencyclemap.org)"
                )
        );
        layersMap.put(
                "ESRI Aerial",
                new OnlineTileSourceBase(
                        "ARCGisOnline", 1, 19, 256,
                        "",
                        new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"},
                        "Esri ArcgisOnline") {
                    @Override
                    public String getTileURLString(long tileIndex) {
                        String mImageFilenameEnding = "";

                        return getBaseUrl() + MapTileIndex.getZoom(tileIndex) + "/"
                                + MapTileIndex.getY(tileIndex) + "/" + MapTileIndex.getX(tileIndex)
                                + mImageFilenameEnding;
                    }
                }
        );
        layersMap.put(
                "ESRI Topo with relief",
                new OnlineTileSourceBase(
                        "ARCGisOnlineTopo", 1, 19, 256,
                        "",
                        new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/"},
                        "Esri ArcgisOnline") {
                    @Override
                    public String getTileURLString(long tileIndex) {
                        String mImageFilenameEnding = "";

                        return getBaseUrl() + MapTileIndex.getZoom(tileIndex) + "/"
                                + MapTileIndex.getY(tileIndex) + "/" + MapTileIndex.getX(tileIndex)
                                + mImageFilenameEnding;
                    }
                }
        );

        // MAPSFORGE
        // Imported .map files live in the app-specific external files dir, which needs
        // no permission. This used to be gated on WRITE_EXTERNAL_STORAGE, which the
        // manifest no longer declares, so offline maps were never loaded.
        declareMapsForgeProvider();

    }

    private void declareMapsForgeProvider() {
        Log.i(TAG, "[DECLARE MAPSFORGE]");
        MapsForgeTileSource.createInstance(this.getApplication());
        Set<File> mapfiles = findMapFiles();
        // do a simple scan of local storage for .map files.
        File[] maps = new File[mapfiles.size()];
        maps = mapfiles.toArray(maps);
        if (maps == null || maps.length == 0) {
        } else {
            layersMap.put("MapsForge", null);
        }
    }

    private MapsForgeTileProvider getMapsForgeTileProvider() {
        MapsForgeTileProvider mapsForgeTileProvider;
        Set<File> mapfiles = findMapFiles();
        // do a simple scan of local storage for .map files.
        File[] maps = new File[mapfiles.size()];
        maps = mapfiles.toArray(maps);
        if (maps == null || maps.length == 0) {
            mapsForgeTileProvider = null;
        } else {
            XmlRenderTheme theme = null;
            try {
                theme = new AssetsRenderTheme(map.getContext().getAssets(), "renderthemes/", "rendertheme-v4.xml");
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            MapsForgeTileSource fromFiles = null;

            fromFiles = MapsForgeTileSource.createFromFiles(maps, theme, "rendertheme-v4");
            mapsForgeTileProvider = new MapsForgeTileProvider(
                    new SimpleRegisterReceiver(map.getContext()),
                    fromFiles, null);
        }
        return mapsForgeTileProvider;
    }

    private void setupActionBar() {
        Log.i(TAG, "[setupactionbar]");
        setSupportActionBar(toolbar);
        drawerToggle = new ActionBarDrawerToggle(this, drawerLayoutMap, toolbar, R.string.action_drawer_open, R.string.action_drawer_close);
        drawerToggle.setDrawerIndicatorEnabled(true);
        drawerLayoutMap.addDrawerListener(drawerToggle);
        setTitle(getString(R.string.simple_map_title, session.getName()));

        //drawerLayoutMap.findViewById(R.id.drawer_top_layout_map).setBackgroundColor(ThemeUtils.primaryColor(this));
        int[] colors = { ThemeUtils.primaryColor(this), ThemeUtils.primaryLightColor(this) };
        GradientDrawable gradientDrawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, colors);
        drawerLayoutMap.findViewById(R.id.drawer_top_layout_map).setBackground(gradientDrawable);

        ImageView logoView = drawerLayoutMap.findViewById(R.id.drawer_logo_map);
        logoView.setColorFilter(ThemeUtils.primaryColor(this), PorterDuff.Mode.OVERLAY);
    }

    public void onResume(){
        Log.i(TAG, "[onResume begin]");
        super.onResume();
        //this will refresh the osmdroid configuration on resuming.
        //if you make changes to the configuration, use
        //SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));

        map.onResume(); //needed for compass, my location overlays, v6.0.0 and up

        // i don't know why but map.onResume() always enables myLocation...
        if (prefs.getBoolean("map_myposition", true)) {
            mLocationOverlay.enableMyLocation();
        } else {
            mLocationOverlay.disableMyLocation();
        }
        //this.mLocationOverlay.enableMyLocation();
        //this.mLocationOverlay.enableFollowLocation();
        /*Location currentLocation = mLocationOverlay.getLastFix();
        if (currentLocation != null) {
            GeoPoint myPosition = new GeoPoint(currentLocation.getLatitude(), currentLocation.getLongitude());
            map.getController().animateTo(myPosition);
        }
        */
        setupNavigationMenu();
        startRefresh();
        registerBroadcastReceiver();
        Log.i(TAG, "[onResume end]");
    }

    public void onPause(){
        Log.i(TAG, "[onPause begin]");
        super.onPause();
        //this will refresh the osmdroid configuration on resuming.
        //if you make changes to the configuration, use
        //SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //Configuration.getInstance().save(this, prefs);
        map.onPause();  //needed for compass, my location overlays, v6.0.0 and up

        stopRefresh();
        try {
            unregisterReceiver(mBroadcastReceiver);
        } catch (RuntimeException e) {
            if (LoggerService.DEBUG) { Log.d(TAG, "RECEIVER PROBLEM, let's ignore it..."); }
        }
        Log.i(TAG, "[onPause end]");
    }

    private void setupNavigationDeviceList() {
        // potentially save scroll position
        Parcelable state = null;
        boolean restoreScroll = false;
        if (itemsNavigationDevice != null && itemsNavigationDevice.size() > 0) {
            restoreScroll = true;
        }
        if (restoreScroll) {
            state = listNavigationDevices.getLayoutManager().onSaveInstanceState();
        }

        itemsNavigationDevice = new ArrayList<>();

        NavigationAdapter.NavigationItem itemAll = new NavigationAdapter.NavigationItem(ID_ITEM_ALL_DEVICES, getString(R.string.item_all_devices_label), markers.keySet().size(), R.drawable.ic_check_box_grey_24dp);
        itemsNavigationDevice.add(itemAll);
        List<String> devNames = new ArrayList<>(markers.keySet());
        Collections.sort(devNames, new Comparator<String>() {
            @Override
            public int compare(String s1, String s2) {
                return s1.compareToIgnoreCase(s2);
            }
        });
        Log.v(TAG, "NAVIGATION LIST we have "+devNames.size()+" devices");
        for (String devName : devNames) {
            String label = devName;
            List<BasicLocation> locs = locations.get(devName);
            BasicLocation lastLoc = locs.get(locs.size()-1);
            if (isToday(lastLoc.getTimestamp()*1000)) {
                label += " (" + sdfHour.format(lastLoc.getTimestamp() * 1000) + ")";
            } else {
                label += "\n(" + sdfCompleteSimple.format(lastLoc.getTimestamp() * 1000) + ")";
            }
            int icon;
            Boolean isLinesEnabled = linesEnabled.get(devName);
            if (isLinesEnabled != null && isLinesEnabled) {
                icon = R.drawable.ic_device_check_24;
            } else {
                icon = R.drawable.ic_phone_android_grey_24dp;
            }
            int nbPoints = 0;
            if (displayedLocations.containsKey(devName)) {
                nbPoints = displayedLocations.get(devName).size();
            }
            NavigationAdapter.NavigationItem item = new NavigationAdapter.NavigationItem(devName, label, nbPoints, icon);
            itemsNavigationDevice.add(item);
        }
        bringMarkersToFrontByTimestamp();

        adapterDevices = new NavigationAdapter(new NavigationAdapter.ClickListener() {
            @Override
            public void onItemClick(NavigationAdapter.NavigationItem item) {
                selectItem(item, true);
            }

            private void selectItem(NavigationAdapter.NavigationItem item, boolean closeNavigation) {
                adapterDevices.setSelectedItem(item.id);
                Log.i(TAG, "[select item] "+item.id);
                selectedDeviceItemId = item.id;
                if (!selectedDeviceItemId.equals(ID_ITEM_ALL_DEVICES)) {
                    bringDeviceToFront(selectedDeviceItemId);
                }
                // update views
                if (closeNavigation) {
                    drawerLayoutMap.closeDrawers();
                }
                // zoom anyway, whatever the autozoom value is
                zoomOnAllMarkers();
            }

            @Override
            public void onIconClick(NavigationAdapter.NavigationItem item) {
                if (!item.id.equals(ID_ITEM_ALL_DEVICES)) {
                    Boolean isLinesEnabled = linesEnabled.get(item.id);
                    if (isLinesEnabled != null && isLinesEnabled) {
                        item.icon = R.drawable.ic_phone_android_grey_24dp;
                        linesEnabled.put(item.id, false);
                        map.getOverlays().remove(lines.get(item.id));
                    } else {
                        item.icon = R.drawable.ic_device_check_24;
                        linesEnabled.put(item.id, true);
                        map.getOverlays().add(lines.get(item.id));
                    }
                    adapterDevices.notifyDataSetChanged();
                    map.invalidate();
                } else {
                    toggleAllDeviceLines();
                }
                //onItemClick(item);
            }
        });

        adapterDevices.setItems(itemsNavigationDevice);
        if (markers.containsKey(selectedDeviceItemId)) {
            adapterDevices.setSelectedItem(selectedDeviceItemId);
        } else {
            adapterDevices.setSelectedItem(ID_ITEM_ALL_DEVICES);
            selectedDeviceItemId = ID_ITEM_ALL_DEVICES;
        }
        listNavigationDevices.setAdapter(adapterDevices);
        if (restoreScroll) {
            listNavigationDevices.getLayoutManager().onRestoreInstanceState(state);
        }
    }

    private void toggleAllDeviceLines() {
        NavigationAdapter.NavigationItem item;
        boolean oneEnabled = false;
        for (int i = 1; i < itemsNavigationDevice.size(); i++) {
            item = itemsNavigationDevice.get(i);
            Boolean isLinesEnabled = linesEnabled.get(item.id);
            if (isLinesEnabled != null && isLinesEnabled) {
                oneEnabled = true;
                break;
            }
        }
        if (oneEnabled) {
            for (int i = 1; i < itemsNavigationDevice.size(); i++) {
                item = itemsNavigationDevice.get(i);
                item.icon = R.drawable.ic_phone_android_grey_24dp;
                linesEnabled.put(item.id, false);
                map.getOverlays().remove(lines.get(item.id));
            }
        } else {
            for (int i = 1; i < itemsNavigationDevice.size(); i++) {
                item = itemsNavigationDevice.get(i);
                item.icon = R.drawable.ic_device_check_24;
                linesEnabled.put(item.id, true);
                map.getOverlays().add(lines.get(item.id));
            }
        }
        //adapterDevices.setItems(itemsNavigationDevice);
        adapterDevices.notifyDataSetChanged();
        map.invalidate();
    }

    private void setupNavigationMenu() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int freq = prefs.getInt("map_freq", 15);
        int limit = prefs.getInt("map_limit", 300);
        int lastMin = prefs.getInt("map_last_min", 120);
        //final NavigationAdapter.NavigationItem itemTrashbin = new NavigationAdapter.NavigationItem("trashbin", getString(R.string.action_trashbin), null, R.drawable.ic_delete_grey600_24dp);
        final NavigationAdapter.NavigationItem itemFreq = new NavigationAdapter.NavigationItem("freq", getString(R.string.action_frequency), freq, R.drawable.ic_timer_grey_24dp);
        final NavigationAdapter.NavigationItem itemlimit = new NavigationAdapter.NavigationItem("limit", getString(R.string.action_map_limit), limit, R.drawable.ic_baseline_more_horiz_24);
        final NavigationAdapter.NavigationItem itemLastMin = new NavigationAdapter.NavigationItem("lastmin", getString(R.string.action_map_last_min), lastMin, R.drawable.ic_baseline_av_timer_24);
        //final NavigationAdapter.NavigationItem itemSettings = new NavigationAdapter.NavigationItem("settings", getString(R.string.action_settings), null, R.drawable.ic_settings_grey600_24dp);
        //final NavigationAdapter.NavigationItem itemAbout = new NavigationAdapter.NavigationItem("about", getString(R.string.simple_about), null, R.drawable.ic_info_outline_grey600_24dp);
        final NavigationAdapter.NavigationItem itemPin = new NavigationAdapter.NavigationItem("pin", getString(R.string.action_pin_to_homescreen), null, R.drawable.ic_add_menu_grey_24dp);

        ArrayList<NavigationAdapter.NavigationItem> itemsMenu = new ArrayList<>();
        itemsMenu.add(itemFreq);
        itemsMenu.add(itemlimit);
        itemsMenu.add(itemLastMin);

        // If the platform supports pinned shortcuts, show menu item
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            itemsMenu.add(itemPin);
        }

        NavigationAdapter adapterMenu = new NavigationAdapter(new NavigationAdapter.ClickListener() {
            @Override
            public void onItemClick(NavigationAdapter.NavigationItem item) {
                if (item == itemLastMin) {
                    int currentLastMin = prefs.getInt("map_last_min", 120);

                    final EditText numberEdit = new EditText(map.getContext());
                    numberEdit.setText(String.valueOf(currentLastMin));
                    numberEdit.setRawInputType(InputType.TYPE_CLASS_NUMBER);
                    numberEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
                    AlertDialog.Builder fromUrlBuilder = new AlertDialog.Builder(new ContextThemeWrapper(map.getContext(), R.style.AppThemeDialog));
                    fromUrlBuilder.setMessage(getString(R.string.map_choose_last_min_dialog_message));
                    fromUrlBuilder.setTitle(getString(R.string.map_choose_last_min_dialog_title));

                    fromUrlBuilder.setView(numberEdit);

                    fromUrlBuilder.setPositiveButton(getString(R.string.simple_ok), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            setLastMin(numberEdit.getText().toString());
                            Log.i(TAG, "[CHANGE last min] "+numberEdit.getText().toString());
                        }
                    });

                    fromUrlBuilder.setNegativeButton(getString(R.string.simple_cancel), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                        }
                    });

                    // create the alert dialog
                    Dialog fromUrlDialog = fromUrlBuilder.create();
                    fromUrlDialog.show();
                    KeyboardUtil.showForDialog(fromUrlDialog, numberEdit);
                } else if (item == itemlimit) {
                    int currentLimit = prefs.getInt("map_limit", 300);

                    final EditText limitEdit = new EditText(map.getContext());
                    limitEdit.setText(String.valueOf(currentLimit));
                    limitEdit.setRawInputType(InputType.TYPE_CLASS_NUMBER);
                    limitEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
                    AlertDialog.Builder fromUrlBuilder = new AlertDialog.Builder(new ContextThemeWrapper(map.getContext(), R.style.AppThemeDialog));
                    fromUrlBuilder.setMessage(getString(R.string.map_choose_limit_dialog_message));
                    fromUrlBuilder.setTitle(getString(R.string.map_choose_limit_dialog_title));

                    fromUrlBuilder.setView(limitEdit);

                    fromUrlBuilder.setPositiveButton(getString(R.string.simple_ok), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            setLimit(limitEdit.getText().toString());
                            Log.i(TAG, "[CHANGE LIMIT] "+limitEdit.getText().toString());
                        }
                    });

                    fromUrlBuilder.setNegativeButton(getString(R.string.simple_cancel), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                        }
                    });

                    // create the alert dialog
                    Dialog fromUrlDialog = fromUrlBuilder.create();
                    fromUrlDialog.show();
                    KeyboardUtil.showForDialog(fromUrlDialog, limitEdit);
                } else if (item == itemFreq) {
                    int currentFreq = prefs.getInt("map_freq", 15);

                    final EditText frequencyEdit = new EditText(map.getContext());
                    frequencyEdit.setText(String.valueOf(currentFreq));
                    frequencyEdit.setRawInputType(InputType.TYPE_CLASS_NUMBER);
                    frequencyEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
                    AlertDialog.Builder fromUrlBuilder = new AlertDialog.Builder(new ContextThemeWrapper(map.getContext(), R.style.AppThemeDialog));
                    fromUrlBuilder.setMessage(getString(R.string.map_choose_frequency_dialog_message));
                    fromUrlBuilder.setTitle(getString(R.string.map_choose_frequency_dialog_title));

                    fromUrlBuilder.setView(frequencyEdit);

                    fromUrlBuilder.setPositiveButton(getString(R.string.simple_ok), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            setFrequency(frequencyEdit.getText().toString());
                            Log.i(TAG, "[CHANGE FREQ] "+frequencyEdit.getText().toString());
                        }
                    });

                    fromUrlBuilder.setNegativeButton(getString(R.string.simple_cancel), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                        }
                    });

                    // create the alert dialog
                    Dialog fromUrlDialog = fromUrlBuilder.create();
                    fromUrlDialog.show();
                    KeyboardUtil.showForDialog(fromUrlDialog, frequencyEdit);
                } else if (item == itemPin) {
                    if (ShortcutManagerCompat.isRequestPinShortcutSupported(getApplicationContext())) {
                        long sessionId = getIntent().getLongExtra(PARAM_SESSIONID, 0);

                        // Main app intent
                        Intent mainIntent = new Intent(getApplicationContext(), LogjobsListViewActivity.class);
                        mainIntent.setAction(Intent.ACTION_VIEW);

                        // Map intent
                        Intent mapIntent = new Intent(getApplicationContext(), MapActivity.class);
                        mapIntent.setAction(Intent.ACTION_VIEW);
                        // Add session id
                        mapIntent.putExtra(PARAM_SESSIONID, sessionId);

                        // Build shortcut
                        ShortcutInfoCompat pinShortcutInfo = new ShortcutInfoCompat.Builder(MapActivity.this, "map" + sessionId)
                                .setShortLabel(session.getName())
                                .setLongLabel(getString(R.string.homescreen_map_shortcut_long_title, session.getName()))
                                .setIcon(IconCompat.createWithResource(MapActivity.this, R.drawable.ic_map_grey_24dp))
                                .setIntents(new Intent[]{mainIntent, mapIntent})
                                .build();

                        // Request to launcher to pin shortcut
                        ShortcutManagerCompat.requestPinShortcut(getApplicationContext(), pinShortcutInfo, null);
                    }
                }
            }

            @Override
            public void onIconClick(NavigationAdapter.NavigationItem item) {
                onItemClick(item);
            }
        });

        adapterMenu.setItems(itemsMenu);
        listNavigationMenu.setAdapter(adapterMenu);
    }

    private void setFrequency(String f) {
        try {
            int freq = Integer.parseInt(f);
            if (freq > 0) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit().putInt("map_freq", freq).apply();
                stopRefresh();
                startRefresh();
                // to update freq displayed value
                setupNavigationMenu();
            }
        } catch (Exception e) {

        }
    }

    private void setLastMin(String f) {
        try {
            int nbMin = Integer.parseInt(f);
            if (nbMin < 0) {
                nbMin = 0;
            }
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putInt("map_last_min", nbMin).apply();
            // to update last min displayed value
            setupNavigationMenu();
            applyPointFilters();
        } catch (Exception e) {

        }
    }

    private void setLimit(String f) {
        try {
            int limit = Integer.parseInt(f);
            if (limit > 0) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit().putInt("map_limit", limit).apply();
                // to update limit displayed value
                setupNavigationMenu();
                applyPointFilters();
            }
        } catch (Exception e) {

        }
    }

    private void applyPointFilters() {
        int limit = prefs.getInt("map_limit", 300);
        int lastMin = prefs.getInt("map_last_min", 120);

        List<BasicLocation> locationsToDisplay;
        List<GeoPoint> geoPoints;
        for (String devName: lines.keySet()) {
            // first apply point limit
            List<BasicLocation> locationMap = locations.get(devName);
            if (locationMap != null) {
                if (locationMap.size() > limit) {
                    locationsToDisplay = getLimitedLocations(locationMap, limit);
                } else {
                    locationsToDisplay = locationMap;
                }
            } else {
                locationsToDisplay = new ArrayList<>();
            }
            // then apply time filter
            if (lastMin > 0) {
                Log.v("FIFI", "time filtering for "+devName);
                locationsToDisplay = getTimeFilteredLocations(locationsToDisplay, lastMin);
            }

            geoPoints = new ArrayList<>();
            for (BasicLocation loc : locationsToDisplay) {
                geoPoints.add(new GeoPoint(loc.getLat(), loc.getLon()));
            }
            lines.get(devName).setPoints(geoPoints);
            displayedLocations.put(devName, locationsToDisplay);
        }
        updateMap();
    }

    private void bringDeviceToFront(String devName) {
        if (linesEnabled.get(devName)) {
            map.getOverlays().remove(lines.get(devName));
            map.getOverlays().add(lines.get(devName));
        }
        map.getOverlays().remove(markers.get(devName));
        map.getOverlays().add(markers.get(devName));
    }

    private void bringMarkersToFrontByTimestamp() {
        List<String> devNames = new ArrayList<>(markers.keySet());
        Collections.sort(devNames, new Comparator<String>() {
            @Override
            public int compare(String s1, String s2) {
                Long lastTimestamp1 = lastTimestamps.get(s1);
                Long lastTimestamp2 = lastTimestamps.get(s2);
                if (lastTimestamp1 != null && lastTimestamp2 != null) {
                    if (lastTimestamp1.equals(lastTimestamp2)) {
                        return 0;
                    }
                    boolean yep = (lastTimestamp1 - lastTimestamp2) > 0;
                    return yep ? 1 : -1;
                } else {
                    return 0;
                }
            }
        });
        for (String devName : devNames) {
            bringDeviceToFront(devName);
        }
    }

    private void zoomOnAllMarkers() {
        if (markers.keySet().size() == 0) {
            return;
        }
        boolean selectMode = false;
        List<GeoPoint> points = new ArrayList<>();
        for (String devName : markers.keySet()) {
            Marker m = markers.get(devName);
            if (devName.equals(selectedDeviceItemId)) {
                if (selectMode) {
                } else {
                    selectMode = true;
                    points.clear();
                }
                // anyway we want this point
                points.add(new GeoPoint(m.getPosition().getLatitude(), m.getPosition().getLongitude()));
            }
            else {
                if (selectMode) {
                } else {
                    points.add(new GeoPoint(m.getPosition().getLatitude(), m.getPosition().getLongitude()));
                }
            }
        }
        if (points.size() == 1) {
            GeoPoint p = new GeoPoint(points.get(0).getLatitude(), points.get(0).getLongitude());
            //map.getController().setZoom(18.0);
            //map.getController().setCenter(p);
            //map.invalidate();
            if (map.getZoomLevelDouble() > 17.0) {
                map.getController().animateTo(p);
            } else {
                map.getController().animateTo(p, 17.0, (long) 1000);
            }

            Log.i(TAG, "[set center] "+p+" map center "+map.getMapCenter());
        } else {
            BoundingBox bb = new BoundingBox(
                    points.get(0).getLatitude(), points.get(0).getLongitude(),
                    points.get(0).getLatitude(), points.get(0).getLongitude()
            );
            for (GeoPoint point : points) {
                if (point.getLatitude() < bb.getLatSouth()) {
                    bb.set(bb.getLatNorth(), bb.getLonEast(), point.getLatitude(), bb.getLonWest());
                } else if (point.getLatitude() > bb.getLatNorth()) {
                    bb.set(point.getLatitude(), bb.getLonEast(), bb.getLatSouth(), bb.getLonWest());
                }
                if (point.getLongitude() > bb.getLonEast()) {
                    bb.set(bb.getLatNorth(), point.getLongitude(), bb.getLatSouth(), bb.getLonWest());
                } else if (point.getLongitude() < bb.getLonWest()) {
                    bb.set(bb.getLatNorth(), bb.getLonEast(), bb.getLatSouth(), point.getLongitude());
                }
            }
            //map.postInvalidate();
            map.zoomToBoundingBox(bb, true, 120);
            //map.getController().setCenter(new GeoPoint(bb.getCenterLatitude(), bb.getCenterLongitude()));
            //map.postInvalidate();
            Log.i(TAG, "[zoomToBounds] "+bb+" map center "+map.getMapCenter());

        }
    }

    /**
     * get data from the database
     */
    private void updatePositionsWithLocalData() {
        List<DBLogjobLocation> locs;
        List<BasicLocation> basicLocs;
        // get list of session's devices with a logjob
        List<DBLogjob> logjobs = db.getLogjobs();
        for (DBLogjob lj: logjobs) {
            // if lj URL matches account URL and session token matches
            if (lj.isPhonetrack()
                    && db.getPhonetrackServerSyncHelper().isAccountUrl(lj.getUrl())
                    && lj.getToken().equals(session.getToken())) {
                // get local positions of devices
                locs = db.getLocationsOfLogjob(lj.getId());
                basicLocs = new ArrayList<>(locs);
                updateDevicePositions(lj.getDeviceName(), basicLocs, null);
            }
        }

        // after data update : view update
        updateMap();
    }

    private void updateDevicePositions(String devName, List<BasicLocation> locs, @Nullable String colorStr) {
        /////// LOCATIONS
        if (locs.size() == 0) {
            return;
        }
        Long lastDevTs = lastTimestamps.get(devName);
        List<BasicLocation> locationsToAdd = new ArrayList<>();
        // if no locations : add all
        List<BasicLocation> devLocations = locations.get(devName);
        if (devLocations == null) {
            locations.put(devName, locs);
            // just to know if marker needs an update
            locationsToAdd = locs;
            Log.v(TAG, "first add for dev "+devName+" ADD "+locs.size()+" locations");
        } else {
            // else add what's new
            if (lastDevTs == null) {
                devLocations.addAll(locs);
            } else {
                for (BasicLocation loc : locs) {
                    Log.v(TAG, "AAAAA "+loc.getTimestamp()+" > "+ lastDevTs);
                    if (loc.getTimestamp() > lastDevTs) {
                        locationsToAdd.add(loc);
                    }
                }
                devLocations.addAll(locationsToAdd);
                Log.v(TAG, "existing dev "+devName+" ADD "+locationsToAdd.size()+" locations");
            }
        }
        List<BasicLocation> deviceLocations = locations.get(devName);
        Log.v(TAG, "deviceLocations size " + deviceLocations.size() + " access " + (deviceLocations.size()-1));
        BasicLocation lastLoc = deviceLocations.get(deviceLocations.size()-1);
        lastTimestamps.put(devName, lastLoc.getTimestamp());

        /////// COLORS
        if (!colors.containsKey(devName)) {
            if (colorStr != null) {
                colors.put(devName, Color.parseColor(colorStr));
            } else {
                colors.put(devName, ThemeUtils.primaryColor(ctx));
            }
        } else {
            if (colorStr != null) {
                colors.put(devName, Color.parseColor(colorStr));
            }
        }
        int color = colors.get(devName);

        /////// LINES

        if (!lines.containsKey(devName)) {
            Polyline line = new Polyline();
            line.getOutlinePaint().setColor(color);
            lines.put(devName, line);
            // enabled lines by default for new devices
            linesEnabled.put(devName, true);
            map.getOverlays().add(line);
        } else {
            Polyline line = lines.get(devName);
            line.getOutlinePaint().setColor(color);
        }
        applyPointFilters();

        /////// MARKER
        CustomLocationMarkerDrawable markerDrawable;
        // marker already exists, check if color needs to be updated
        if (markers.containsKey(devName)) {
            markerDrawable = markerDrawables.get(devName);
            int currentColor = markerDrawable.getColor();
            Double currentAccuracy = markerDrawable.getAccuracy();
            if (color != currentColor || currentAccuracy.equals(lastLoc.getAccuracy())) {
                int textColor;
                if (ThemeUtils.isBrightColor(color)) {
                    textColor = android.R.color.black;
                } else {
                    textColor = android.R.color.white;
                }
                markerDrawable.update(color, textColor, lastLoc.getAccuracy());
            }
        }
        // create the marker
        else {
            Marker m = new Marker(map);
            int textColor;
            if (ThemeUtils.isBrightColor(color)) {
                textColor = android.R.color.black;
            } else {
                textColor = android.R.color.white;
            }
            markerDrawable = new CustomLocationMarkerDrawable(R.mipmap.ic_marker, devName.substring(0, 1), color, textColor, lastLoc.getAccuracy());
            m.setIcon(markerDrawable);

            map.getOverlays().add(m);
            markers.put(devName, m);
            markerDrawables.put(devName, markerDrawable);
        }

        // always update location data
        //locations.put(devName, loc);
        Marker m = markers.get(devName);
        String text = devName;
        text += "\n" + sdfComplete.format(new Date(lastLoc.getTimestamp() * 1000));
        if (lastLoc.getAltitude() != null) {
            text += "\n" + getString(R.string.popup_altitude_value, lastLoc.getAltitude());
        }
        if (lastLoc.getAccuracy() != null) {
            text += "\n" + getString(R.string.popup_accuracy_value, lastLoc.getAccuracy());
        }
        if (lastLoc.getSpeed() != null) {
            text += "\n" + getString(R.string.popup_speed_value, lastLoc.getSpeed() * 3.6);
        }
        if (lastLoc.getBearing() != null) {
            text += "\n" + getString(R.string.popup_bearing_value, lastLoc.getBearing());
        }
        if (lastLoc.getSatellites() != null) {
            text += "\n" + getString(R.string.popup_satellites, lastLoc.getSatellites());
        }
        if (lastLoc.getBattery() != null) {
            text += "\n" + getString(R.string.popup_battery_value, lastLoc.getBattery());
        }
        if (lastLoc.getUserAgent() != null) {
            text += "\n" + getString(R.string.popup_user_agent) + " : " + lastLoc.getUserAgent();
        }
        m.setTitle(text);
        if (locationsToAdd.size() > 0) {
            m.setPosition(new GeoPoint(lastLoc.getLat(), lastLoc.getLon()));
        }
    }

    private List<BasicLocation> getLimitedLocations(List<BasicLocation> locations, int limit) {
        List<BasicLocation> result;
        if (locations.size() <= limit) {
            result = locations;
        } else {
            int lastIndex = locations.size();
            int firstIndex = lastIndex - limit;
            result = locations.subList(firstIndex, lastIndex);
        }
        return result;
    }

    private List<BasicLocation> getTimeFilteredLocations(List<BasicLocation> locations, int nbMin) {
        List<BasicLocation> result;
        if (nbMin <= 0) {
            result = locations;
        } else {
            long nowTs = System.currentTimeMillis() / 1000;
            long pastTs = nowTs - (60 * nbMin);
            int lastIndex = locations.size();
            int firstIndex = lastIndex;
            for (int i=0; i < locations.size(); i++) {
                if (locations.get(i).getTimestamp() >= pastTs) {
                    firstIndex = i;
                    break;
                }
            }
            Log.v("FIFI", "time filtering "+firstIndex+" "+lastIndex);
            result = locations.subList(firstIndex, lastIndex);
        }
        return result;
    }

    private Timer timer;
    private TimerTask timerTask;

    public void startRefresh() {
        if(timer != null) {
            return;
        }
        timerTask = new TimerTask() {

            @Override
            public void run() {
                // launch task of server sync with callback
                Log.i(TAG, "[Task run]");
                updatePositionsWithLocalData();
                int currentLimit = prefs.getInt("map_limit", 300);
                db.getPhonetrackServerSyncHelper().getSessionPositions(session, lastTimestamp, Long.valueOf(currentLimit), syncCallBack);
            }
        };
        int currentFreq = prefs.getInt("map_freq", 15);
        if (currentFreq == 0) {
            currentFreq = 15;
            prefs.edit().putInt("map_freq", 15).apply();
        }
        timer = new Timer();
        timer.scheduleAtFixedRate(timerTask, 0, currentFreq*1000);
    }

    public void stopRefresh() {
        if (timer != null) {
            timer.cancel();
            timer = null;
            timerTask = null;
        }
    }

    private final IGetLastPosCallback syncCallBack = new IGetLastPosCallback() {
        @Override
        public void onFinish(Map<String, List<BasicLocation>> newLocations, Map<String, String> newColors, String message) {
            for (String devName : newLocations.keySet()) {
                List<BasicLocation> locs = newLocations.get(devName);
                if (locs != null) {
                    Log.i(TAG, "position results for dev : " + devName + " | " + locs.size());
                    String colorStr = newColors.get(devName);
                    // update map with new device positions
                    updateDevicePositions(devName, locs, colorStr);
                }
            }

            // update lastTimestamp for next server request
            updateLastTimestamp();

            updateMap();
        }
    };

    public void updateMap() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                map.invalidate();
                // update device list
                setupNavigationDeviceList();
                if (prefs.getBoolean("map_autozoom", true)) {
                    zoomOnAllMarkers();
                }
                if (!selectedDeviceItemId.equals(ID_ITEM_ALL_DEVICES)) {
                    bringDeviceToFront(selectedDeviceItemId);
                }
            }
        });
    }

    private void updateLastTimestamp() {
        for (String devName : locations.keySet()) {
            List<BasicLocation> locs = locations.get(devName);
            if (locs != null && locs.size() > 0) {
                BasicLocation lastLoc = locs.get(locs.size()-1);
                if (lastTimestamp == null || lastLoc.getTimestamp() > lastTimestamp) {
                    lastTimestamp = lastLoc.getTimestamp() + 1;
                }
            }
        }
    }

    private void setupMapButtons() {
        btDisplayMyLoc = (ImageButton) findViewById(R.id.ic_center_map);
        btFollowMe = (ImageButton) findViewById(R.id.ic_follow_me);
        btZoom = (ImageButton) findViewById(R.id.ic_zoom_all);
        btZoomAuto = (ImageButton) findViewById(R.id.ic_zoom_auto);
        btAllowRotation = (ImageButton) findViewById(R.id.ic_allow_rotation);
        btLayers = (ImageButton) findViewById(R.id.ic_map_layers);

        btDisplayMyLoc.setColorFilter(Color.WHITE);
        btFollowMe.setColorFilter(Color.WHITE);
        btZoom.setColorFilter(Color.WHITE);
        btZoomAuto.setColorFilter(Color.WHITE);
        btAllowRotation.setColorFilter(Color.WHITE);
        btLayers.setColorFilter(Color.WHITE);

        if (prefs.getBoolean("map_myposition", true)) {
            btDisplayMyLoc.setBackground(toggleCircle);
            mLocationOverlay.enableMyLocation();
        } else {
            mLocationOverlay.disableMyLocation();
            prefs.edit().putBoolean("map_followme", false).apply();
        }

        btDisplayMyLoc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "centerMap clicked ");
                if (mLocationOverlay.isMyLocationEnabled()) {
                    mLocationOverlay.disableMyLocation();
                    mLocationOverlay.disableFollowLocation();
                    btDisplayMyLoc.setBackgroundResource(0);
                    btFollowMe.setBackgroundResource(0);
                    prefs.edit().putBoolean("map_myposition", false).apply();
                    prefs.edit().putBoolean("map_followme", false).apply();
                } else {
                    mLocationOverlay.enableMyLocation();
                    btDisplayMyLoc.setBackground(toggleCircle);
                    prefs.edit().putBoolean("map_myposition", true).apply();
                }
            }
        });

        if (prefs.getBoolean("map_followme", false)) {
            // disable auto zoom (which shouldn't be enabled but who knows these days)
            btZoomAuto.setBackgroundResource(0);
            prefs.edit().putBoolean("map_autozoom", false).apply();
            // enable follow me
            mLocationOverlay.enableMyLocation();
            mLocationOverlay.enableFollowLocation();
            btFollowMe.setBackground(toggleCircle);
            btDisplayMyLoc.setBackground(toggleCircle);
        } else {
            mLocationOverlay.disableFollowLocation();
        }

        ///////////// rotation
        btAllowRotation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "rotation clicked ");
                if (mRotationGestureOverlay.isEnabled()) {
                    mRotationGestureOverlay.setEnabled(false);
                    btAllowRotation.setBackgroundResource(0);
                    map.setMapOrientation(0);
                    prefs.edit().putBoolean("map_allow_rotation", false).apply();
                } else {
                    mRotationGestureOverlay.setEnabled(true);
                    btAllowRotation.setBackground(toggleCircle);
                    prefs.edit().putBoolean("map_allow_rotation", true).apply();
                }
            }
        });

        if (prefs.getBoolean("map_allow_rotation", false)) {
            mRotationGestureOverlay.setEnabled(true);
            btAllowRotation.setBackground(toggleCircle);
        } else {
            mRotationGestureOverlay.setEnabled(false);
        }

        btFollowMe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "btFollowMe clicked ");
                if (mLocationOverlay.isFollowLocationEnabled()) {
                    mLocationOverlay.disableFollowLocation();
                    btFollowMe.setBackgroundResource(0);
                    prefs.edit().putBoolean("map_followme", false).apply();
                } else {
                    // disable autozoom
                    btZoomAuto.setBackgroundResource(0);
                    prefs.edit().putBoolean("map_autozoom", false).apply();
                    // enable follow me
                    mLocationOverlay.enableMyLocation();
                    mLocationOverlay.enableFollowLocation();
                    btFollowMe.setBackground(toggleCircle);
                    btDisplayMyLoc.setBackground(toggleCircle);
                    prefs.edit().putBoolean("map_myposition", true).apply();
                    prefs.edit().putBoolean("map_followme", true).apply();
                }
            }
        });



        btZoom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "btZoom clicked ");
                zoomOnAllMarkers();
            }
        });

        if (prefs.getBoolean("map_autozoom", true)) {
            btZoomAuto.setBackground(toggleCircle);
            // disable follow me
            mLocationOverlay.disableFollowLocation();
            btFollowMe.setBackgroundResource(0);
            prefs.edit().putBoolean("map_followme", false).apply();
        }

        btZoomAuto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "btAUTOZoom clicked ");
                if (!prefs.getBoolean("map_autozoom", true)) {
                    // disable follow me
                    mLocationOverlay.disableFollowLocation();
                    btFollowMe.setBackgroundResource(0);
                    prefs.edit().putBoolean("map_followme", false).apply();
                    // enable auto zoom
                    btZoomAuto.setBackground(toggleCircle);
                    prefs.edit().putBoolean("map_autozoom", true).apply();
                    zoomOnAllMarkers();
                } else {
                    btZoomAuto.setBackgroundResource(0);
                    prefs.edit().putBoolean("map_autozoom", false).apply();
                }
            }
        });

        btLayers.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder selectBuilder = new AlertDialog.Builder(new ContextThemeWrapper(map.getContext(), R.style.AppThemeDialog));
                selectBuilder.setTitle(getString(R.string.map_choose_layer));

                final CharSequence[] layers = layersMap.keySet().toArray(new CharSequence[layersMap.keySet().size()]);
                List<String> layerNamesList = new ArrayList<>();
                for (CharSequence layer : layers) {
                    layerNamesList.add(layer.toString());
                }
                int checked = layerNamesList.indexOf(selectedLayer);
                selectBuilder.setSingleChoiceItems(layers, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        selectedLayer = layers[which].toString();
                        setTileSource(selectedLayer);
                        prefs.edit().putString("map_selected_layer", selectedLayer).apply();
                        dialog.dismiss();
                    }
                });
                selectBuilder.setNegativeButton(getString(R.string.simple_cancel), null);
                AlertDialog selectDialog = selectBuilder.create();
                selectDialog.show();
            }
        });
    }

    private void setTileSource(String layerKey) {
        // unfortunately i didn't find a way to keep existing tile providers
        // it seems they are destoryed/detached when an other one is selected
        // so here, we create a new one each time
        if (layerKey.equals("MapsForge")) {
            map.setTileProvider(getMapsForgeTileProvider());
        } else {
            defaultTileProvider = new MapTileProviderBasic(getApplicationContext());
            defaultTileProvider.setTileSource(layersMap.get(layerKey));
            map.setTileProvider(defaultTileProvider);
        }
    }

    protected Set<File> findMapFiles() {
        Set<File> maps = new HashSet<>();
        List<StorageUtils.StorageInfo> storageList = StorageUtils.getStorageList();
        for (int i = 0; i < storageList.size(); i++) {
            File f = new File(storageList.get(i).path + File.separator + "osmdroid" + File.separator);
            Log.v("MAPSFORGE", "looking for "+storageList.get(i).path + File.separator + "osmdroid" + File.separator);
            if (f.exists()) {
                maps.addAll(scan(f));
            }
        }
        // for Android >= 10
        File[] externalStorageVolumes =
                ContextCompat.getExternalFilesDirs(ctx, null);
        File primaryExternalStorage = externalStorageVolumes[0];
        Log.e(TAG, "ACC2 "+primaryExternalStorage.getAbsolutePath()+" "+primaryExternalStorage.exists());
        if (primaryExternalStorage.exists()) {
            Log.e(TAG,"prima exists");
            File f = new File(primaryExternalStorage.getAbsolutePath()+ File.separator);
            if (f.exists()) {
                Log.e(TAG,"prima file exists");
                maps.addAll(scan(f));
            }
        }

        return maps;
    }

    static public Collection<? extends File> scan(File f) {
        Log.e(TAG,"SCANNING inside "+f.getAbsolutePath());
        List<File> ret = new ArrayList<>();
        File[] files = f.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                Log.e(TAG,"SCANNING "+pathname);
                if (pathname.getName().toLowerCase().endsWith(".map")) {
                    Log.e(TAG,"EXEXE "+pathname);
                    return true;
                }
                return false;
            }
        });
        if (files != null) {
            Collections.addAll(ret, files);
        }
        return ret;
    }

    /**
     * Marker drawable icon with accuracy
     */
    private class CustomLocationMarkerDrawable extends Drawable  {
        // Cached values
        final String mLetter;
        final int mDrawableId;
        private Double mAccuracy;
        private int mColor;

        // Main part of the icon, which is only regenerated on colour change
        private Bitmap mBitmap;

        private final Paint mPaint;
        private final Paint mAccuracyPaint;
        private final Paint mAccuracyBorderPaint;

        /**
         * Constructor
         * @param drawableId Drawable identifier for the icon resource
         * @param text Letter to place on the marker
         * @param markerColor Primary color
         * @param textColorId Text color
         * @param accuracy Location accuracy
         */
        public CustomLocationMarkerDrawable(int drawableId, String text, int markerColor, int textColorId, Double accuracy) {
            // Cache values
            mLetter = text;
            mDrawableId = drawableId;

            // Create paints
            mPaint = new Paint();
            mAccuracyPaint = new Paint();
            mAccuracyBorderPaint = new Paint();

            // Initialize icon and accuracy paints
            update(markerColor, textColorId, accuracy);
        }

        /**
         * Update for changed color or accuracy
         * @param markerColor Primary color
         * @param textColorId Text color
         * @param accuracy Location accuracy
         */
        public void update(int markerColor, int textColorId, Double accuracy) {
            mBitmap = BitmapFactory.decodeResource(ctx.getResources(), mDrawableId).copy(Bitmap.Config.ARGB_8888, true);
            mBitmap = Bitmap.createScaledBitmap(mBitmap, 70, 70, true);

            // Cache for use updating accuracy circle
            mColor = markerColor;

            Canvas canvas = new Canvas(mBitmap);
            Paint paintCol = new Paint();

            ColorFilter filter = new PorterDuffColorFilter(
                    markerColor,
                    PorterDuff.Mode.SRC_IN
            );
            paintCol.setColorFilter(filter);

            canvas.drawBitmap(mBitmap, 0, 0, paintCol);

            Paint paint = new Paint();

            paint.setStyle(Paint.Style.FILL);
            //paint.setColor(Color.BLACK);
            paint.setColor(ContextCompat.getColor(ctx, textColorId));
            paint.setTextSize(35);
            paint.setAntiAlias(true);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            float textWidth = paint.measureText(mLetter);

            canvas.drawText(mLetter, mBitmap.getWidth() / 2f - textWidth / 2f, mBitmap.getHeight() / 2f, paint);

            // Store accuracy
            mAccuracy = accuracy;

            // Create accuracy paints for updated color
            mAccuracyPaint.setAntiAlias(false);
            mAccuracyPaint.setStyle(Paint.Style.FILL);
            mAccuracyPaint.setColor(mColor);
            mAccuracyPaint.setAlpha(45);

            mAccuracyBorderPaint.setAntiAlias(true);
            mAccuracyBorderPaint.setStyle(Paint.Style.STROKE);
            mAccuracyBorderPaint.setColor(mColor);
            mAccuracyBorderPaint.setAlpha(180);
        }

        public Double getAccuracy() {
            return mAccuracy;
        }

        public int getColor() {
            return mColor;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect bounds = getBounds();

            // Draw accuracy, if we have one
            if (mAccuracy != null) {
                final float accuracyRadius = map.getProjection().metersToPixels(mAccuracy.floatValue());

                // Avoid drawing if it's going to be very small
                if (accuracyRadius > 15) {
                    canvas.drawCircle(bounds.centerX(), bounds.centerY() + getIntrinsicHeight() / 2f, accuracyRadius, mAccuracyPaint);
                    canvas.drawCircle(bounds.centerX(), bounds.centerY() + getIntrinsicHeight() / 2f, accuracyRadius, mAccuracyBorderPaint);
                }
            }

            // Draw main icon
            canvas.drawBitmap(mBitmap, bounds.centerX() - mBitmap.getWidth() / 2f, bounds.centerY() + mBitmap.getHeight() / 2f - mBitmap.getHeight(), mPaint);

            // Debug marker
            // canvas.drawCircle(bounds.centerX(), bounds.centerY() + getIntrinsicHeight()/2, 5, mPaint);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }

        @Override
        public void setAlpha(int arg0) {
        }

        @Override
        public void setColorFilter(ColorFilter arg0) {
        }

        @Override
        public int getIntrinsicWidth() {
            return mBitmap.getWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return mBitmap.getHeight();
        }
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(LoggerService.BROADCAST_LOCATION_UPDATED);
        ContextCompat.registerReceiver(this, mBroadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /**
     * Broadcast receiver
     */
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LoggerService.DEBUG) { Log.d(TAG, "[broadcast received " + intent + "]"); }
            if (intent == null || intent.getAction() == null) {
                return;
            }
            switch (intent.getAction()) {
                case LoggerService.BROADCAST_LOCATION_UPDATED:
                    long ljId = intent.getLongExtra(LoggerService.BROADCAST_EXTRA_PARAM, 0);
                    if (LoggerService.DEBUG) { Log.d(TAG, "[inMAP broadcast loc updated " + ljId + "]"); }
                    updatePositionsWithLocalData();
                    break;
            }
        }
    };
}
