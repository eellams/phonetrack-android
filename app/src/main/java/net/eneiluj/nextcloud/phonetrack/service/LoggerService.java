/*
 * Copyright (c) 2017 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of μlogger-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package net.eneiluj.nextcloud.phonetrack.service;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.ActivityCompat;
import androidx.core.app.AlarmManagerCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.app.TaskStackBuilder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import net.eneiluj.nextcloud.phonetrack.R;
import net.eneiluj.nextcloud.phonetrack.android.activity.LogjobsListViewActivity;
import net.eneiluj.nextcloud.phonetrack.android.fragment.PreferencesFragment;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjob;
import net.eneiluj.nextcloud.phonetrack.persistence.PhoneTrackSQLiteOpenHelper;
import net.eneiluj.nextcloud.phonetrack.util.AutomationEngine;
import net.eneiluj.nextcloud.phonetrack.util.SupportUtil;
import net.eneiluj.nextcloud.phonetrack.util.CorrectingLocation;
import net.eneiluj.nextcloud.phonetrack.util.SystemLogger;

import static android.location.LocationProvider.AVAILABLE;
import static android.location.LocationProvider.OUT_OF_SERVICE;
import static android.location.LocationProvider.TEMPORARILY_UNAVAILABLE;

/**
 * Background service logging positions to database
 * and synchronizing with remote server.
 *
 */

public class LoggerService extends Service {

    public static double battery = -1.0;

    private static final String TAG = LoggerService.class.getSimpleName();
    public static final String BROADCAST_LOCATION_STARTED = "net.eneiluj.nextcloud.phonetrack.broadcast.location_started";
    public static final String BROADCAST_LOCATION_STOPPED = "net.eneiluj.nextcloud.phonetrack.broadcast.location_stopped";
    public static final String BROADCAST_LOCATION_UPDATED = "net.eneiluj.nextcloud.phonetrack.broadcast.location_updated";
    public static final String BROADCAST_LOCATION_PERMISSION_DENIED = "net.eneiluj.nextcloud.phonetrack.broadcast.location_permission_denied";
    public static final String BROADCAST_LOCATION_NETWORK_DISABLED = "net.eneiluj.nextcloud.phonetrack.broadcast.network_disabled";
    public static final String BROADCAST_LOCATION_GPS_DISABLED = "net.eneiluj.nextcloud.phonetrack.broadcast.gps_disabled";
    public static final String BROADCAST_LOCATION_NETWORK_ENABLED = "net.eneiluj.nextcloud.phonetrack.broadcast.network_enabled";
    public static final String BROADCAST_LOCATION_GPS_ENABLED = "net.eneiluj.nextcloud.phonetrack.broadcast.gps_enabled";
    public static final String BROADCAST_LOCATION_DISABLED = "net.eneiluj.nextcloud.phonetrack.broadcast.location_disabled";
    public static final String BROADCAST_EXTRA_PARAM = "net.eneiluj.nextcloud.phonetrack.broadcast.extra_param";
    public static final String BROADCAST_ERROR_MESSAGE = "net.eneiluj.nextcloud.phonetrack.broadcast.error_message";
    public static final String UPDATE_NOTIFICATION = "net.eneiluj.nextcloud.phonetrack.UPDATE_NOTIFICATION";

    public static final String GET_NEXT_POINT = "getnextpoint";
    public static final String FIRST_REQ_AFTER_ACCEPTED = "firstafteraccepted";
    public static final String START_TIMEOUT = "starttimeout";
    public static final String JOB_ID = "jobid";
    public static final String SCHEDULE_INTERVAL = "scheduleinterval";


    private static volatile boolean isRunning = false;
    private static volatile boolean firstRun = false;
    private LoggerThread thread;
    private Looper looper;
    private LocationManager locManager;

    private Map<Long, mLocationListener> gpsLocListeners;
    private Map<Long, mLocationListener> networkLocListeners;
    private Map<Long, DBLogjob> logjobs;
    private PhoneTrackSQLiteOpenHelper db;

    private Map<Long, CorrectingLocation> lastLocations;
    private static volatile Map<Long, Long> lastUpdateRealtime;

    private final int NOTIFICATION_ID = 1526756640;
    private NotificationManager mNotificationManager;
    private NotificationCompat.Builder mNotificationBuilder;
    private boolean useGps = true;
    private boolean useNet = true;
    public static boolean DEBUG = true;

    private Map<Long, LogjobWorker> mLogjobWorkers;
    private final Set<Long> automationPausedJobIds = new HashSet<>();
    private AutomationEngine automationEngine;

    private ConnectionStateMonitor connectionMonitor;
    private BroadcastReceiver powerSaverChangeReceiver;
    private BroadcastReceiver airplaneModeChangeReceiver;

    AlarmManager alarmManager;

    /**
     * Basic initializations.
     */
    @Override
    public void onCreate() {
        if (DEBUG) {
            SystemLogger.d(TAG, "onCreate");
        }
        firstRun = true;

        connectionMonitor = null;
        powerSaverChangeReceiver = null;
        airplaneModeChangeReceiver = null;

        db = PhoneTrackSQLiteOpenHelper.getInstance(getApplicationContext());

        // start websync service if needed
        if (db.getLocationNotSyncedCount() > 0) {
            WebTrackWorker.enqueue(getApplicationContext(), WebTrackWorker.ALL_LOGJOBS);
        }

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (mNotificationManager != null) {
            mNotificationManager.cancelAll();
        }

        locManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        lastLocations = new HashMap<>();
        lastUpdateRealtime = new HashMap<>();
        gpsLocListeners = new HashMap<>();
        networkLocListeners = new HashMap<>();
        logjobs = new HashMap<>();
        mLogjobWorkers = new HashMap<>();


        List<DBLogjob> ljs = db.getLogjobs();
        for (DBLogjob ljob : ljs) {
            if (ljob.isEnabled()) {
                gpsLocListeners.put(ljob.getId(), new mLocationListener(ljob, "GPS"));
                networkLocListeners.put(ljob.getId(), new mLocationListener(ljob, "NETWORK"));
                logjobs.put(ljob.getId(), ljob);
                lastLocations.put(ljob.getId(), null);
                lastUpdateRealtime.put(ljob.getId(), 0L);

                LogjobWorker jw;
                if (ljob.useSignificantMotion()) {
                    jw = new LogjobSignificantMotionWorker(ljob);
                } else {
                    if (ljob.keepGpsOnBetweenFixes()) {
                        jw = new LogjobClassicGpsOnWorker(ljob);
                    } else {
                        jw = new LogjobClassicWorker(ljob);
                    }
                }
                mLogjobWorkers.put(ljob.getId(), jw);
            }
        }

        // read user preferences
        updatePreferences(null);

        int nbEnabled = 0;
        for (DBLogjob lj : ljs) {
            if (lj.isEnabled()) {
                requestLocationUpdates(lj.getId(), true, true);
                nbEnabled++;
            }
        }

        if (nbEnabled > 0) {
            final Notification notification = showNotification(NOTIFICATION_ID);
            if (!startLocationForeground(notification)) {
                stopSelf();
                return;
            }
            updateNotificationContent();

            isRunning = true;

            sendBroadcast(BROADCAST_LOCATION_STARTED);

            thread = new LoggerThread();
            thread.start();
            looper = thread.getLooper();
            alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

            battery = getBatteryLevelOnce();
            // register for battery level
            this.registerReceiver(this.mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

            // track network connectivity changes
            connectionMonitor = new ConnectionStateMonitor();
            connectionMonitor.enable(getApplicationContext());

            // listen to power saving mode change
            powerSaverChangeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    SystemLogger.d(TAG, "[POWER LISTENER] power saving state changed");
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                    boolean respectPowerSaveMode = prefs.getBoolean(getString(R.string.pref_key_power_saving_awareness), false);
                    if (respectPowerSaveMode) {
                        updateAllActiveLogjobs();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.os.action.POWER_SAVE_MODE_CHANGED");
            registerReceiver(powerSaverChangeReceiver, filter);

            // listen to offline (airplane) mode change
            airplaneModeChangeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    SystemLogger.d(TAG, "[AIRPLANE MODE LISTENER] airplane mode state changed");
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                    boolean respectAirplaneMode = prefs.getBoolean(getString(R.string.pref_key_offline_mode_awareness), false);
                    if (respectAirplaneMode) {
                        updateAllActiveLogjobs();
                    }
                }
            };
            IntentFilter filterAirplane = new IntentFilter();
            //filterAirplane.addAction("android.intent.action.AIRPLANE_MODE_CHANGED");
            filterAirplane.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            registerReceiver(airplaneModeChangeReceiver, filterAirplane);
        } else {
            final Notification notification = showNotification(NOTIFICATION_ID);
            startLocationForeground(notification);
            if (DEBUG) {
                SystemLogger.d(TAG, "onCreate: stop because no logjob enabled");
            }
            stopSelf();
        }
    }

    /**
     * Start main thread, request location updates, start synchronization.
     *
     * @param intent Intent
     * @param flags Flags
     * @param startId Unique id
     * @return Always returns START_STICKY
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isRunning) {
            final boolean logjobsUpdated = (intent != null) && intent.getBooleanExtra(LogjobsListViewActivity.UPDATED_LOGJOBS, false);
            final boolean providersUpdated = (intent != null) && intent.getBooleanExtra(PreferencesFragment.UPDATED_PROVIDERS, false);
            final boolean updateNotif = (intent != null) && intent.getBooleanExtra(UPDATE_NOTIFICATION, false);
            final boolean getNextPoint = (intent != null) && intent.getBooleanExtra(GET_NEXT_POINT, false);
            if (logjobsUpdated) {
                // this to avoid doing two loc upd when service is down and then a logjob is enabled
                // in this scenario, we run onCreate which already does it all, no need to handle logjo updated
                if (firstRun) {
                    if (DEBUG) {
                        SystemLogger.d(TAG, "onStartCommand: updated logjob but firstrun so nothing");
                    }
                } else {
                    long ljId = intent.getLongExtra(LogjobsListViewActivity.UPDATED_LOGJOB_ID, 0);
                    if (DEBUG) {
                        SystemLogger.d(TAG, "onStartCommand: updated logjob");
                    }
                    handleLogjobUpdated(ljId);
                }
            } else if (providersUpdated) {
                if (DEBUG) {
                    SystemLogger.d(TAG, "onStartCommand : updated providers");
                }
                String providersValue = intent.getStringExtra(PreferencesFragment.UPDATED_PROVIDERS_VALUE);
                updatePreferences(providersValue);
                for (long ljId : logjobs.keySet()) {
                    restartUpdates(ljId);
                }
            } else if (updateNotif && isRunning) {
                updateNotificationContent();
            } else if (getNextPoint) {
                long jobId = intent.getLongExtra(JOB_ID, 0);
                if (logjobs.containsKey(jobId)) {
                    LogjobWorker logjobWorker = mLogjobWorkers.get(jobId);
                    if (logjobWorker != null) {
                        boolean shouldGetPosition = logjobWorker.shouldGetPositionAfterInterval();
                        if (!shouldGetPosition) {
                            SystemLogger.d(TAG, "[command] only schedule for " + jobId);
                            // we just schedule next time to get a point. this happens in sigmotion when no motion has been seen
                            long intervalTimeMillis = intent.getLongExtra(SCHEDULE_INTERVAL, 0);
                            logjobWorker.scheduleSampleAfterInterval(intervalTimeMillis);
                        } else {
                            SystemLogger.d(TAG, "[command] request location update for " + jobId);
                            boolean startTimeout = intent.getBooleanExtra(START_TIMEOUT, false);
                            boolean firstReqAfterAccepted = intent.getBooleanExtra(FIRST_REQ_AFTER_ACCEPTED, false);
                            requestLocationUpdates(jobId, startTimeout, firstReqAfterAccepted);
                        }
                    }
                }
            } else {
                // start without parameter
                if (DEBUG) {
                    SystemLogger.d(TAG, "onStartCommand: start without parameter");
                }
            }
            // anyway, first run is over
            firstRun = false;
        }

        return START_STICKY;
    }

    /**
     * When user updated a logjob, restart location updates, stop service on failure
     */
    private void handleLogjobUpdated(long ljId) {
        boolean wasAlreadyThere = logjobs.containsKey(ljId);
        updateLogjob(ljId);
        // if it was not deleted or disabled
        if (logjobs.containsKey(ljId)) {
            if (isRunning) {
                // it was modified
                if (wasAlreadyThere) {
                    restartUpdates(ljId);
                }
                // it was created
                else {
                    requestLocationUpdates(ljId, true, true);
                }
            }
        }
        // it was deleted or disabled
        else {
            if (logjobs.isEmpty()) {
                stopSelf();
            }
        }
    }

    private void updateAllActiveLogjobs() {
        List<DBLogjob> logjobs = db.getLogjobs();
        // we tell logger service to restart updates for enabled logjobs
        for (DBLogjob lj: logjobs) {
            if (lj.isEnabled()) {
                handleLogjobUpdated(lj.getId());
            }
        }
    }

    /**
     * Check if user granted permission to access location.
     *
     * @return True if permission granted, false otherwise
     */
    private boolean canAccessLocation() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // first we check is device is in power saving mode
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isPowerSaveMode = false;
        if (pm != null) {
            isPowerSaveMode = pm.isPowerSaveMode();
        }
        if (DEBUG) {
            SystemLogger.d(TAG, "POWERSAVE mode: " + isPowerSaveMode);
            SystemLogger.d(TAG, "AIRPLANE mode: " + SupportUtil.isAirplaneModeOn(this));
        }

        boolean respectPowerSaveMode = prefs.getBoolean(getString(R.string.pref_key_power_saving_awareness), false);

        // then we check airplane mode related stuff
        boolean respectAirplaneMode = prefs.getBoolean(getString(R.string.pref_key_offline_mode_awareness), false);

        // then we check if we have location permissions
        boolean hasLocPermissions = (
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        );

        return (!respectPowerSaveMode || !isPowerSaveMode)
                && (!respectAirplaneMode || !SupportUtil.isAirplaneModeOn(this))
                && hasLocPermissions;
    }

    /**
     * Check if given provider exists on device
     * @param provider Provider
     * @return True if exists, false otherwise
     */
    private boolean providerExists(String provider) {
        return locManager.getAllProviders().contains(provider);
    }

    /**
     * Reread preferences
     */
    private void updatePreferences(String value) {
        String providersPref;
        if (value == null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            providersPref = prefs.getString(getString(R.string.pref_key_providers), "1");
        } else {
            providersPref = value;
        }
        useGps = ((   "1".equals(providersPref)
                   || "3".equals(providersPref)
                   || "5".equals(providersPref)
                   || "7".equals(providersPref)
                 ) && providerExists(LocationManager.GPS_PROVIDER));
        useNet = ((   "2".equals(providersPref)
                   || "3".equals(providersPref)
                   || "6".equals(providersPref)
                   || "7".equals(providersPref)
                 ) && providerExists(LocationManager.NETWORK_PROVIDER));
        if (DEBUG) {
            SystemLogger.d(TAG, "update prefs " + providersPref + ", gps : " + useGps
                + ", net : " + useNet + "]");
        }
    }

    /**
     * update internal values
     *
     * logjob might have been added, modified or deleted
     *
     */
    private void updateLogjob(long ljId) {
        DBLogjob lj = db.getLogjob(ljId);
        if (lj != null && lj.isEnabled()) {
            // new or modified : update logjob
            logjobs.put(ljId, lj);

            // this is a new logjob
            if (!gpsLocListeners.containsKey(ljId)) {
                gpsLocListeners.put(ljId, new mLocationListener(lj, "GPS"));
                networkLocListeners.put(ljId, new mLocationListener(lj, "NETWORK"));
                lastLocations.put(ljId, null);
                lastUpdateRealtime.put(ljId, 0L);
            } else {
                // Update listener for changed parameters
                mLocationListener gpsLocListener = gpsLocListeners.get(ljId);
                if (gpsLocListener != null) {
                    gpsLocListener.populateFromLogjob(lj);
                }
                mLocationListener networkLocListener = networkLocListeners.get(ljId);
                if (networkLocListener != null) {
                    networkLocListener.populateFromLogjob(lj);
                }

                LogjobWorker logjobWorker = mLogjobWorkers.get(ljId);
                if (logjobWorker != null) {
                    logjobWorker.stop();
                    //mLogjobWorkers.get(ljId).populate(lj);
                }
            }

            // anyway (new/existing logjob) we instanciate a new one
            LogjobWorker jw;
            if (lj.useSignificantMotion()) {
                // Assume motion exists when logging begins
                jw = new LogjobSignificantMotionWorker(lj);
            } else {
                if (lj.keepGpsOnBetweenFixes()) {
                    jw = new LogjobClassicGpsOnWorker(lj);
                }
                else {
                    jw = new LogjobClassicWorker(lj);
                }
            }
            mLogjobWorkers.put(ljId, jw);
        }
        // it has been deleted or disabled
        else {
            if (gpsLocListeners.containsKey(ljId)) {
                // Stop requested updates, sleeping motion-based job
                stopJob(ljId);

                gpsLocListeners.remove(ljId);
                networkLocListeners.remove(ljId);
                lastLocations.remove(ljId);
                lastUpdateRealtime.remove(ljId);
                logjobs.remove(ljId);
                mLogjobWorkers.remove(ljId);
            }
        }
    }

    /**
     * Restart request for location updates
     *
     * @return True if succeeded, false otherwise (eg. disabled all providers)
     */
    private boolean restartUpdates(long jobId) {
        if (DEBUG) { SystemLogger.d(TAG, "location updates restart for job: " + jobId); }

        stopJob(jobId);

        return requestLocationUpdates(jobId, true, true);
    }

    private void stopJob(long jobId) {
        SystemLogger.d(TAG, "stop job " + jobId + " => locManager.removeUpdates()");
        mLocationListener gpsLocListener = gpsLocListeners.get(jobId);
        if (gpsLocListener != null) {
            locManager.removeUpdates(gpsLocListener);
        }
        mLocationListener networkLocListener = networkLocListeners.get(jobId);
        if (networkLocListener != null) {
            locManager.removeUpdates(networkLocListener);
        }

        // stop any runnables waiting for an interval
        DBLogjob lj = db.getLogjob(jobId);
        SystemLogger.d(TAG, "will stop runnable? job " + jobId);
        if (lj != null) {
            SystemLogger.e(TAG, "YES, stop for job " + jobId);
            LogjobWorker logjobWorker = mLogjobWorkers.get(jobId);
            if (logjobWorker != null) {
                logjobWorker.stop();
            }
        }
    }

    /**
     * Whether the logjob's automation conditions (time window, Wi-Fi SSID, fence)
     * currently pause it: no location is requested while paused, so the logjob
     * costs nothing until the condition clears. Evaluated again at every
     * acquisition attempt, so no background watcher is needed for time and Wi-Fi.
     */
    private boolean isAutomationPaused(DBLogjob lj) {
        if (lj.getAutomation() == null || !lj.getAutomation().hasAnyCondition()) {
            automationPausedJobIds.remove(lj.getId());
            return false;
        }
        if (automationEngine == null) {
            automationEngine = new AutomationEngine(new AutomationEngine.RealDeviceState(this));
        }
        boolean paused = automationEngine.isPaused(lj.getAutomation());
        if (paused) {
            automationPausedJobIds.add(lj.getId());
        } else {
            automationPausedJobIds.remove(lj.getId());
        }
        if (isRunning) {
            updateNotificationContent();
        }
        return paused;
    }

    /**
     * Request location updates
     * @return True if succeeded from at least one provider
     */
    @SuppressWarnings({"MissingPermission"})
    private boolean requestLocationUpdates(long ljId, boolean startTimeout, boolean firstRequestAfterAccepted) {
        // here we start a location request for each activated logjob
        DBLogjob lj = logjobs.get(ljId);
        SystemLogger.d(TAG, "requestLocationUpdates job " + ljId);
        mLocationListener gpsLocListener = gpsLocListeners.get(ljId);
        mLocationListener networkLocListener = networkLocListeners.get(ljId);
        LogjobWorker logjobWorker = mLogjobWorkers.get(ljId);
        if (lj == null || gpsLocListener == null || networkLocListener == null || logjobWorker == null) {
            SystemLogger.d(TAG, "requestLocationUpdates ERROR for job " + ljId + ". Unexpected null value.");
            return false;
        }
        if (isAutomationPaused(lj)) {
            SystemLogger.d(TAG, "requestLocationUpdates job " + ljId + " skipped: paused by automation condition");
            return false;
        }
        boolean hasLocationUpdates = false;
        if (canAccessLocation()) {
            // update last acquisition start time only if we know
            // we are not in an "accuracy improvement" loop
            // in other words: if we start a timeout
            if (firstRequestAfterAccepted) {
                logjobWorker.updateLastAcquisitionStart();
            }
            if (useNet) {
                // normal or significant motion based sampling
                locManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, networkLocListener, looper);

                if (locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    hasLocationUpdates = true;
                    if (DEBUG) { SystemLogger.d(TAG, "requestLocationUpdates using network provider, min time " + lj.getMinTime()); }
                }
            }
            if (useGps) {
                locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsLocListener, looper);

                if (locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    hasLocationUpdates = true;
                    if (DEBUG) { SystemLogger.d(TAG, "requestLocationUpdates using gps provider, min time " + lj.getMinTime()); }
                }
            }
            if (hasLocationUpdates) {
                // start timeout only if we're not in an "accuracy improvement" loop
                if (startTimeout) {
                    if (DEBUG) { SystemLogger.d(TAG, "requestLocationUpdates startResultTimeout()"); }
                    logjobWorker.startResultTimeout();
                }
            } else {
                // no location provider available
                sendBroadcast(BROADCAST_LOCATION_DISABLED);
                if (DEBUG) { SystemLogger.d(TAG, "No available location updates"); }
            }
        } else {
            // can't access location
            sendBroadcast(BROADCAST_LOCATION_PERMISSION_DENIED);
            if (DEBUG) { SystemLogger.d(TAG, "Location permission denied"); }
        }

        return hasLocationUpdates;
    }

    /**
     * Service cleanup
     */
    @Override
    public void onDestroy() {
        if (DEBUG) { SystemLogger.d(TAG, "onDestroy"); }

        if (canAccessLocation()) {
            //noinspection MissingPermission
            for (long ljId : gpsLocListeners.keySet()) {
                stopJob(ljId);
            }
        }

        isRunning = false;

        mNotificationManager.cancel(NOTIFICATION_ID);


        if (thread != null) {
            thread.interrupt();
            unregisterReceiver(mBatInfoReceiver);
            sendBroadcast(BROADCAST_LOCATION_STOPPED);
        }
        thread = null;

        if (connectionMonitor != null) {
            connectionMonitor.disable(getApplicationContext());
        }

        if (powerSaverChangeReceiver != null) {
            unregisterReceiver(powerSaverChangeReceiver);
        }
        if (airplaneModeChangeReceiver != null) {
            unregisterReceiver(airplaneModeChangeReceiver);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Check if logger service is running.
     *
     * @return True if running, false otherwise
     */
    public static boolean isRunning() {
        return isRunning;
    }

    /**
     * Main service thread class handling location updates.
     */
    private class LoggerThread extends HandlerThread {
        LoggerThread() {
            super("LoggerThread");
        }
        private final String TAG = LoggerThread.class.getSimpleName();

        @Override
        public void interrupt() {
            if (DEBUG) { SystemLogger.d(TAG, "LoggerThread interrupt"); }
        }

        @Override
        public void finalize() throws Throwable {
            if (DEBUG) { SystemLogger.d(TAG, "LoggerThread finalize"); }
            super.finalize();
        }

        @Override
        public void run() {
            if (DEBUG) { SystemLogger.d(TAG, "LoggerThread run"); }
            super.run();
        }
    }

    /**
     * Schedule an ELAPSED_REALTIME_WAKEUP alarm that fires during Doze.
     * Exact alarms need SCHEDULE_EXACT_ALARM, which Android 14+ denies by default
     * and the user can revoke at any time; setExactAndAllowWhileIdle() throws a
     * SecurityException without it, so fall back to an inexact idle alarm.
     */
    @VisibleForTesting
    static void setAlarmAllowWhileIdle(AlarmManager alarmManager, long triggerAtElapsed, PendingIntent operation) {
        if (AlarmManagerCompat.canScheduleExactAlarms(alarmManager)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, operation);
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, operation);
        }
    }

    /**
     * Promote this service to a location-type foreground service.
     * Android 14+ throws if the location permission is missing, and Android 12+
     * throws if the start is not allowed from the background (e.g. at boot without
     * background location access). Callers gate on the permissions, this is the last line.
     *
     * @return true if the service is now in the foreground
     */
    private boolean startLocationForeground(Notification notification) {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            return true;
        } catch (RuntimeException e) {
            // SecurityException or ForegroundServiceStartNotAllowedException
            SystemLogger.e(TAG, "Unable to start location foreground service: " + e);
            return false;
        }
    }

    /**
     * Show notification
     *
     * @param mId Notification Id
     */
    private Notification showNotification(int mId) {
        if (DEBUG) { SystemLogger.v(TAG, "showNotification " + mId); }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean lowImportance = prefs.getBoolean(getString(R.string.pref_key_notification_importance), false);
        int priority = NotificationCompat.PRIORITY_DEFAULT;
        if (lowImportance) {
            priority = NotificationCompat.PRIORITY_MIN;
        }

        final String channelId = String.valueOf(mId);
        createNotificationChannel(channelId, lowImportance);
        String nbLocations = String.valueOf(db.getLocationNotSyncedCount());
        String nbSent = String.valueOf(db.getNbTotalSync());
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(R.drawable.ic_notify_24dp)
                        .setContentTitle(getString(R.string.app_name))
                        .setPriority(priority)
                        .setOnlyAlertOnce(true)
                        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                        .setContentText(String.format(getString(R.string.is_running), nbLocations, nbSent));
                        //.setSmallIcon(R.drawable.ic_stat_notify_24dp)
                        //.setContentText(String.format(getString(R.string.is_running), getString(R.string.app_name)));
        mNotificationBuilder = mBuilder;

        Intent resultIntent = new Intent(this, LogjobsListViewActivity.class);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(LogjobsListViewActivity.class);
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        mBuilder.setContentIntent(resultPendingIntent);
        Notification mNotification = mBuilder.build();
        mNotificationManager.notify(mId, mNotification);
        return mNotification;
    }

    private void updateNotificationContent() {
        String nbLocations = String.valueOf(db.getLocationNotSyncedCount());
        String nbSent = String.valueOf(db.getNbTotalSync());
        String text;
        if (!automationPausedJobIds.isEmpty()) {
            text = String.format(getString(R.string.is_running), nbLocations, nbSent)
                    + " · " + getString(R.string.automation_paused_notification, automationPausedJobIds.size());
        } else {
            text = String.format(getString(R.string.is_running), nbLocations, nbSent);
        }
        mNotificationBuilder.setContentText(text);
        mNotificationManager.notify(this.NOTIFICATION_ID, mNotificationBuilder.build());
    }

    private void createNotificationChannel(String channelId, boolean lowImportance) {
        int importance = NotificationManager.IMPORTANCE_LOW;
        if (lowImportance) {
            importance = NotificationManager.IMPORTANCE_MIN;
        }
        NotificationChannel chan = new NotificationChannel(channelId, getString(R.string.app_name), importance);
        mNotificationManager.createNotificationChannel(chan);
    }

    /**
     * Send broadcast message
     * @param broadcast Broadcast message
     */
    private void sendBroadcast(String broadcast) {
        Intent intent = new Intent(broadcast);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /**
     * Send broadcast message
     * @param broadcast Broadcast message
     */
    private void sendBroadcast(String broadcast, long ljId) {
        Intent intent = new Intent(broadcast);
        intent.putExtra(LoggerService.BROADCAST_EXTRA_PARAM, ljId);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private final BroadcastReceiver mBatInfoReceiver = new BroadcastReceiver(){
        @Override
        public void onReceive(Context ctxt, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level == -1 || scale == -1) {
                battery = 0.0;
            }
            double batLevel = ((double)level / (double)scale) * 100.0;
            double newBatteryLevel = Math.round(batLevel * 100.0) / 100.0;
            if (newBatteryLevel != battery) {
                battery = newBatteryLevel;
                if (LoggerService.DEBUG) {
                    SystemLogger.i(TAG, "battery level changed " + battery);
                }
            }
        }
    };

    private double getBatteryLevelOnce() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null) {
            return 0.0;
        }
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level == -1 || scale == -1) {
            return 0.0;
        }

        double batLevel = ((double)level / (double)scale) * 100.0;
        batLevel = Math.round(batLevel * 100.0) / 100.0;
        return batLevel;
    }

    /**
     * Location listener class
     */
    private class mLocationListener implements LocationListener {

        private DBLogjob logjob;
        private final String type;
        private long logjobId;
        private long maxTimeMillis;
        private long minTimeMillis;
        private boolean keepGpsOn;

        public mLocationListener(DBLogjob logjob, String type) {
            this.type = type;
            populateFromLogjob(logjob);
        }

        /**
         * Populate logging job and cache values
         * @param logjob The logging job
         */
        public void populateFromLogjob(DBLogjob logjob) {
            this.logjob = logjob;
            this.logjobId = logjob.getId();
            this.keepGpsOn = logjob.keepGpsOnBetweenFixes();
            // max time tolerance is half min time, but not more that 5 min
            this.minTimeMillis = logjob.getMinTime() * 1000L;
            long minTimeTolerance = Math.min(minTimeMillis / 2, 5 * 60 * 1000);
            maxTimeMillis = minTimeMillis + minTimeTolerance;
        }

        @Override
        public void onLocationChanged(@NonNull Location location) {
            CorrectingLocation loc = new CorrectingLocation(location);
            if (DEBUG) {
                SystemLogger.d(TAG, "location changed [" + type + "]: " + logjobId + "/" + logjob.getTitle() + ", bat : " + battery);
            }

            // always pass to the worker (sig motion or not)
            LogjobWorker logjobWorker = mLogjobWorkers.get(logjobId);
            if (logjobWorker != null) {
                logjobWorker.handleLocationChange(loc);
            }
        }

        /**
         * Should the location be logged or skipped
         * @param loc Location
         * @return True if skipped
         */
        private boolean skipLocation(DBLogjob logjob, CorrectingLocation loc) {
            // if we keep gps on, we take care of the timing between points
            if (keepGpsOn) {
                long elapsedMillisSinceLastUpdate;
                Long ljLastUpdateRealtime = lastUpdateRealtime.get(logjobId);
                if (ljLastUpdateRealtime == null) {
                    ljLastUpdateRealtime = 0L;
                }
                elapsedMillisSinceLastUpdate = (loc.getElapsedRealtimeNanos() / 1000000) - ljLastUpdateRealtime;

                if (elapsedMillisSinceLastUpdate < minTimeMillis) {
                    if (DEBUG) { SystemLogger.d(TAG,"skip because " + elapsedMillisSinceLastUpdate + " < "+ minTimeMillis); }
                    return true;
                }
            }
            int maxAccuracy = logjob.getMinAccuracy();
            // accuracy radius too high
            if (loc.hasAccuracy() && loc.getAccuracy() > maxAccuracy) {
                if (DEBUG) { SystemLogger.d(TAG, "[location accuracy above limit: " + loc.getAccuracy() + " > " + maxAccuracy + "]"); }
                // reset gps provider to get better accuracy even if time and distance criteria don't change
                if (loc.getProvider().equals(LocationManager.GPS_PROVIDER)) {
                    restartUpdates(logjobId);
                }
                return true;
            }
            // use network provider only if recent gps data is missing
            if (loc.getProvider().equals(LocationManager.NETWORK_PROVIDER)) {
                CorrectingLocation lastLocation = lastLocations.get(logjobId);
                if (lastLocation != null) {
                    // we received update from gps provider not later than after maxTime period
                    Long ljLastUpdateRealtime = lastUpdateRealtime.get(logjobId);
                    if (ljLastUpdateRealtime == null) {
                        ljLastUpdateRealtime = 0L;
                    }
                    long elapsedMillis = SystemClock.elapsedRealtime() - ljLastUpdateRealtime;
                    if (lastLocation.getProvider().equals(LocationManager.GPS_PROVIDER) && elapsedMillis < maxTimeMillis) {
                        // skip network provider
                        if (DEBUG) {
                            SystemLogger.d(TAG, "[location network provider skipped]");
                        }
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Callback on provider disabled
         * @param provider Provider
         */
        @Override
        public void onProviderDisabled(@NonNull String provider) {
            if (DEBUG) { SystemLogger.d(TAG, "location provider " + provider + " disabled"); }
            if (provider.equals(LocationManager.GPS_PROVIDER)) {
                sendBroadcast(BROADCAST_LOCATION_GPS_DISABLED);
            } else if (provider.equals(LocationManager.NETWORK_PROVIDER)) {
                sendBroadcast(BROADCAST_LOCATION_NETWORK_DISABLED);
            }
        }

        /**
         * Callback on provider enabled
         * @param provider Provider
         */
        @Override
        public void onProviderEnabled(@NonNull String provider) {
            if (DEBUG) { SystemLogger.d(TAG, "location provider " + provider + " enabled"); }
            if (provider.equals(LocationManager.GPS_PROVIDER)) {
                sendBroadcast(BROADCAST_LOCATION_GPS_ENABLED);
            } else if (provider.equals(LocationManager.NETWORK_PROVIDER)) {
                sendBroadcast(BROADCAST_LOCATION_NETWORK_ENABLED);
            }
        }

        /**
         * Callback on provider status change
         * @param provider Provider
         * @param status Status
         * @param extras Extras
         */
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            if (DEBUG) {
                final String statusString;
                switch (status) {
                    case OUT_OF_SERVICE:
                        statusString = "out of service";
                        break;
                    case TEMPORARILY_UNAVAILABLE:
                        statusString = "temporarily unavailable";
                        break;
                    case AVAILABLE:
                        statusString = "available";
                        break;
                    default:
                        statusString = "unknown";
                        break;
                }
                if (DEBUG) { SystemLogger.d(TAG, "location status for " + provider + " changed: " + statusString); }
            }
        }
    }

    private class ConnectionStateMonitor extends ConnectivityManager.NetworkCallback {

        final NetworkRequest networkRequest;

        public ConnectionStateMonitor() {
            networkRequest = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        }

        public void enable(Context context) {
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            connectivityManager.registerNetworkCallback(networkRequest , this);
        }

        // Likewise, you can have a disable method that simply calls ConnectivityManager#unregisterCallback(networkRequest) too.

        public void disable(Context context) {
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            connectivityManager.unregisterNetworkCallback(this);
        }

        @Override
        public void onAvailable(@NonNull Network network) {
            if (DEBUG) { SystemLogger.d(TAG, "Network is available again: launch sync from loggerservice"); }
            try {
                // just to be sure the connection is effective
                // sometimes i experienced problems when connecting to slow wifi networks
                // i think internet access was not yet established when syncService was launched
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                if (DEBUG) { SystemLogger.e(TAG, "interrupted"); }
            }
            WebTrackWorker.enqueue(getApplicationContext(), WebTrackWorker.ALL_LOGJOBS);
        }
    }

    private void acceptAndSyncLocation(long logjobId, CorrectingLocation loc) {
        lastLocations.put(logjobId, loc);
        lastUpdateRealtime.put(logjobId, loc.getElapsedRealtimeNanos() / 1000000);

        db.addLocation(logjobId, loc, battery);

        sendBroadcast(BROADCAST_LOCATION_UPDATED, logjobId);
        updateNotificationContent();

        WebTrackWorker.enqueue(getApplicationContext(), logjobId);
    }

    // worker superclass
    private abstract class LogjobWorker extends TriggerEventListener {
        protected mLocationListener gpsLocationListener;
        protected mLocationListener networkLocationListener;
        protected DBLogjob mLogJob;
        protected long mJobId;
        protected CorrectingLocation lastLocation;

        protected Long mLastUpdateRealtime;

        protected Handler mTimeoutHandler;
        protected Runnable mTimeoutRunnable;
        protected PendingIntent nextPointIntent;
        protected CorrectingLocation mCachedNetworkResult;

        protected boolean mUseSignificantMotion;
        protected boolean mUseMixedMode;

        protected long mIntervalTimeMillis;
        protected boolean mUseInterval;
        protected int mLocationTimeout;
        protected long lastAcquisitionStartTimestamp;

        LogjobWorker(DBLogjob logjob) {
            populate(logjob);
            gpsLocationListener = gpsLocListeners.get(mJobId);
            networkLocationListener = networkLocListeners.get(mJobId);
        }

        protected void populate(DBLogjob logjob) {
            mLogJob = logjob;
            mJobId = logjob.getId();
            lastLocation = null;

            mLastUpdateRealtime = 0L;
            lastAcquisitionStartTimestamp = System.currentTimeMillis() / 1000;

            mTimeoutHandler = null;
            mTimeoutRunnable = null;
            nextPointIntent = null;
            mCachedNetworkResult = null;

            mIntervalTimeMillis = mLogJob.getMinTime() * 1000L;
            mUseInterval = mIntervalTimeMillis > 0;
            mUseSignificantMotion = logjob.useSignificantMotion();
            mUseMixedMode = logjob.useSignificantMotionMixed();
            mLocationTimeout = mLogJob.getLocationRequestTimeout();
        }

        protected boolean isMinDistanceOk(CorrectingLocation loc) {
            int minDistance = mLogJob.getMinDistance();
            if (minDistance == 0 || lastLocation == null) {
                return true;
            }
            else {
                double distance = SupportUtil.distance(
                        lastLocation.getLatitude(), loc.getLatitude(),
                        lastLocation.getLongitude(), loc.getLongitude(),
                        lastLocation.getAltitude(), loc.getAltitude()
                );
                SystemLogger.d(TAG, "Distance with last point: " + distance);
                SystemLogger.d(TAG, "Logjob minimum distance: " + minDistance);
                boolean isOk = (distance >= minDistance);
                SystemLogger.d(TAG, "isMinDistanceOk? " + isOk);
                return isOk;
            }
        }

        protected boolean isMinAccuracyOk(CorrectingLocation loc) {
            int minAccuracy = mLogJob.getMinAccuracy();
            SystemLogger.d(TAG, "Accuracy of current point: "+loc.getAccuracy());
            boolean isOk = (loc.getAccuracy() <= minAccuracy);
            SystemLogger.d(TAG, "isMinAccuracyOk? " + isOk);
            return isOk;
        }

        public void updateLastAcquisitionStart() {
            // store time when position acquisition was launched
            lastAcquisitionStartTimestamp = System.currentTimeMillis() / 1000;
        }

        protected void stop() {
            if (mTimeoutHandler != null) {
                if (mTimeoutRunnable != null) {
                    mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
                    mTimeoutRunnable = null;
                }
                mTimeoutHandler = null;
            }
            if (nextPointIntent != null) {
                alarmManager.cancel(nextPointIntent);
            }
        }

        public void startResultTimeout() {
            mCachedNetworkResult = null;

            if (mLocationTimeout > 0) {
                if (mTimeoutHandler == null)
                    mTimeoutHandler = new Handler();

                // Create and post
                mTimeoutRunnable = createSampleTimeoutDelayRunnable();
                SystemLogger.d(TAG, "Waiting " + mLocationTimeout + " seconds for timeout");
                mTimeoutHandler.postDelayed(mTimeoutRunnable, mLocationTimeout * 1000L);
            }
        }

        public boolean shouldGetPositionAfterInterval() {
            // by default, a logjob gets a position after the interval
            return true;
        }

        protected abstract Runnable createSampleTimeoutDelayRunnable();

        public abstract void handleLocationChange(Location location);
        public abstract void scheduleSampleAfterInterval(long millisDelay);
    }

    private class LogjobClassicWorker extends LogjobWorker {

        LogjobClassicWorker(DBLogjob logjob) {
            super(logjob);
        }

        protected Runnable createSampleTimeoutDelayRunnable() {

            return new Runnable() {
                public void run() {
                    SystemLogger.d(TAG, "[LogjobClassicWorker] Location request timeout hit");

                    if (mCachedNetworkResult != null) {
                        SystemLogger.d(TAG, "Reached timeout for location request, using network cached location");
                        lastLocation = mCachedNetworkResult;
                        acceptAndSyncLocation(mJobId, mCachedNetworkResult);
                    } else {
                        SystemLogger.d(TAG, "Reached timeout for location request, NO network location");
                    }

                    // stop requesting locations anyway
                    locManager.removeUpdates(gpsLocationListener);
                    locManager.removeUpdates(networkLocationListener);
                    // always get rid of cached network location
                    mCachedNetworkResult = null;

                    // Schedule sample for X seconds from last time a sample was asked
                    if (mUseInterval) {
                        long timeToWait = mIntervalTimeMillis - (mLocationTimeout * 1000L);
                        if (timeToWait < 0) {
                            timeToWait = 0;
                        }
                        SystemLogger.d(TAG, "Schedule next sample in " + (timeToWait / 1000) + " seconds [timeout reached]");
                        scheduleSampleAfterInterval(timeToWait);
                    }
                }
            };
        }

        public void scheduleSampleAfterInterval(long millisDelay) {
            SystemLogger.d(TAG, "Schedule location request in " + (millisDelay / 1000.0) + " seconds");
            if (nextPointIntent != null) {
                alarmManager.cancel(nextPointIntent);
            }

            Intent i = new Intent(LoggerService.this, LoggerService.class);
            i.putExtra(GET_NEXT_POINT, true);
            i.putExtra(JOB_ID, mJobId);
            i.putExtra(START_TIMEOUT, true);
            i.putExtra(FIRST_REQ_AFTER_ACCEPTED, true);
            int intJobId = (int) mJobId;
            nextPointIntent = PendingIntent.getService(LoggerService.this, intJobId, i, PendingIntent.FLAG_IMMUTABLE);
            alarmManager.cancel(nextPointIntent);

            if (SupportUtil.isDozing(LoggerService.this)){
                //Only invoked once per 15 minutes in doze mode
                SystemLogger.d(TAG, "Device is dozing, using infrequent alarm");
                setAlarmAllowWhileIdle(alarmManager, SystemClock.elapsedRealtime() + millisDelay, nextPointIntent);
            } else {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + millisDelay,
                        nextPointIntent
                );
            }
        }

        public void handleLocationChange(Location location) {
            CorrectingLocation loc = new CorrectingLocation(location);
            if (loc.getProvider().equals(LocationManager.GPS_PROVIDER)
                    || (!useGps)
            ) {
                SystemLogger.d(TAG, "[LogjobClassicWorker] Got GPS result (or network one result without using GPS provider), immediately accepting");

                // respect minimum distance/accuracy settings
                boolean minDistanceOk = isMinDistanceOk(loc);
                boolean minAccuracyOk = isMinAccuracyOk(loc);
                if (minDistanceOk && minAccuracyOk) {
                    // Accept, store and sync location
                    mCachedNetworkResult = null;
                    lastLocation = loc;
                    acceptAndSyncLocation(mJobId, loc);

                    mLastUpdateRealtime = loc.getElapsedRealtimeNanos() / 1000000;

                    // Cancel timeout runnable if we got a correct position
                    if (mTimeoutHandler != null) {
                        mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
                        mTimeoutRunnable = null;
                    }

                    // stop location request
                    if (useGps || useNet) {
                        SystemLogger.d(TAG, "[LogjobClassicWorker::handleLocationChange] => minDistanceOk && minAccuracyOk && (useGps || useNet) so locManager.removeUpdates()");
                        locManager.removeUpdates(gpsLocationListener);
                        locManager.removeUpdates(networkLocationListener);
                        SystemLogger.d(TAG, "[LogjobClassicWorker::handleLocationChange] remove updates because got position");
                    }
                } else {
                    SystemLogger.d(TAG, "[LogjobClassicWorker::handleLocationChange] Not enough DISTANCE (min " + mLogJob.getMinDistance() +
                            ") or ACCURACY (min " + mLogJob.getMinAccuracy() + "), we skip this location");
                }

                // If using an interval AND position was accepted : schedule sample for X seconds from last sample
                if (mUseInterval && minDistanceOk && minAccuracyOk) {
                    // how much time did it take to get current position?
                    long cTs = System.currentTimeMillis() / 1000;
                    long timeSpentSearching = cTs - lastAcquisitionStartTimestamp;
                    long timeToWaitSecond = mLogJob.getMinTime() - timeSpentSearching;
                    if (timeToWaitSecond < 0) {
                        timeToWaitSecond = 0;
                    }
                    SystemLogger.d(TAG, "As we spent " + timeSpentSearching + "s to search position, " +
                            "with interval=" + mLogJob.getMinTime() + ", " +
                            "we now wait " + timeToWaitSecond + " seconds before getting a new one");
                    SystemLogger.d(TAG, "Schedule next location request because we accepted a position");
                    scheduleSampleAfterInterval(timeToWaitSecond * 1000);
                } else {
                    SystemLogger.d(TAG, "DO NOT schedule next location request because "
                            + "mUseInterval " + mUseInterval
                            + " && minDistanceOk " + minDistanceOk
                            + " && minAccuracyOk " + minAccuracyOk);
                }
            } else {
                SystemLogger.d(TAG, "Network location returned first, caching");
                // Cache lower quality network result
                mCachedNetworkResult = loc;
            }
        }

        // this is triggered only when significant motion mode is enabled
        @Override
        public void onTrigger(TriggerEvent event) {
        }
    }

    private class LogjobClassicGpsOnWorker extends LogjobWorker {

        LogjobClassicGpsOnWorker(DBLogjob logjob) {
            super(logjob);
        }

        protected Runnable createSampleTimeoutDelayRunnable() {
            return null;
        }

        public void scheduleSampleAfterInterval(long millisDelay) {

        }

        public void handleLocationChange(Location location) {
            CorrectingLocation loc = new CorrectingLocation(location);
            if (loc.getProvider().equals(LocationManager.GPS_PROVIDER)
                    || (!useGps)
            ) {
                SystemLogger.d(TAG, "[LogjobClassicGpsOnWorker::handleLocationChange] Got GPS result (or network one without using GPS provider), immediately accepting");

                // respect minimum distance/accuracy/time settings
                boolean minDistanceOk = isMinDistanceOk(loc);
                boolean minAccuracyOk = isMinAccuracyOk(loc);
                boolean minTimeOk = isMinTimeOk(loc);
                long timeSinceLastAccepted = (loc.getElapsedRealtimeNanos() / 1000000000) - (mLastUpdateRealtime / 1000);
                if (minDistanceOk && minAccuracyOk && minTimeOk) {
                    // Accept, store and sync location
                    mCachedNetworkResult = null;
                    lastLocation = loc;
                    acceptAndSyncLocation(mJobId, loc);

                    mLastUpdateRealtime = loc.getElapsedRealtimeNanos() / 1000000;
                } else {
                    SystemLogger.d(TAG, "Not enough DISTANCE (" + minDistanceOk + " min " + mLogJob.getMinDistance()
                            + ") or ACCURACY (" + minAccuracyOk + " min " + mLogJob.getMinAccuracy()
                            + ") or TIME (" + minTimeOk + " " + timeSinceLastAccepted + "/" + mLogJob.getMinTime() + "), we skip this location");
                }
                // no need to schedule anything now as requestLocationUpdates is still running
            } else {
                SystemLogger.d(TAG, "Network location returned first, caching");
                // Cache lower quality network result
                mCachedNetworkResult = loc;
            }
        }

        protected boolean isMinTimeOk(CorrectingLocation loc) {
            long timeSinceLastAccepted = (loc.getElapsedRealtimeNanos() / 1000000000) - (mLastUpdateRealtime / 1000);
            int minTime = mLogJob.getMinTime();
            SystemLogger.d(TAG, "is " + timeSinceLastAccepted + " >= " + minTime + "?");
            boolean isOk = (timeSinceLastAccepted >= minTime);
            SystemLogger.d(TAG, "isMinTimeOk? " + isOk);
            return isOk;
        }

        // this is triggered only when significant motion mode is enabled
        @Override
        public void onTrigger(TriggerEvent event) {
        }
    }

    // this worker can be used for significant motion ones (with or without hybrid mode)
    private class LogjobSignificantMotionWorker extends LogjobWorker {
        private final SensorManager mSensorManager;
        private final Sensor mSensor;
        private boolean mMotionDetected = false;

        LogjobSignificantMotionWorker(DBLogjob logjob) {
            super(logjob);

            mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
        }

        public void stop() {
            super.stop();
            SystemLogger.e(TAG, "stop sigmotion sensor");
            mSensorManager.cancelTriggerSensor(LogjobSignificantMotionWorker.this, mSensor);
        }

        public boolean shouldGetPositionAfterInterval() {
            // in case the interval is over, get a position if a motion was detected
            // or if using the mixed mode
            boolean shouldWe = mMotionDetected || mUseMixedMode;
            SystemLogger.d(TAG, "[SigMotion] Interval is finished, should we get a location after having waited? => " + shouldWe
                    + " (motion detected: " + mMotionDetected + " ; mixed mode: " + mUseMixedMode + ")");
            return shouldWe;
        }

        protected Runnable createSampleTimeoutDelayRunnable() {

            return new Runnable() {
                public void run() {
                    SystemLogger.d(TAG, "[LogjobSignificantMotionWorker] Location request timeout hit");

                    if (mCachedNetworkResult != null) {
                        SystemLogger.d(TAG, "Reached timeout before GPS sample, using network sample");
                        lastLocation = mCachedNetworkResult;
                        acceptAndSyncLocation(mJobId, mCachedNetworkResult);
                    } else {
                        SystemLogger.d(TAG, "Reached timeout before GPS sample, NO network sample");
                    }

                    locManager.removeUpdates(gpsLocationListener);
                    locManager.removeUpdates(networkLocationListener);
                    mCachedNetworkResult = null;

                    // Clear significant motion flag for next interval
                    mMotionDetected = false;
                    // Request significant motion notification
                    mSensorManager.requestTriggerSensor(LogjobSignificantMotionWorker.this, mSensor);

                    // Schedule sample for X seconds from last time a sample was asked
                    if (mUseInterval) {
                        long timeToWait = mIntervalTimeMillis - (mLocationTimeout * 1000L);
                        if (timeToWait < 0) {
                            timeToWait = 0;
                        }
                        SystemLogger.d(TAG, "Schedule next location request in " + (timeToWait / 1000) + "s");
                        scheduleSampleAfterInterval(timeToWait);
                    }
                }
            };
        }

        public void scheduleSampleAfterInterval(long millisDelay) {
            SystemLogger.d(TAG, "Scheduling next location request in " + (millisDelay / 1000.0) + "s");
            if (nextPointIntent != null) {
                alarmManager.cancel(nextPointIntent);
            }

            Intent i = new Intent(LoggerService.this, LoggerService.class);
            i.putExtra(GET_NEXT_POINT, true);
            i.putExtra(JOB_ID, mJobId);
            i.putExtra(START_TIMEOUT, true);
            i.putExtra(FIRST_REQ_AFTER_ACCEPTED, true);
            i.putExtra(SCHEDULE_INTERVAL, mIntervalTimeMillis);
            int intJobId = (int) mJobId;
            nextPointIntent = PendingIntent.getService(LoggerService.this, intJobId, i, PendingIntent.FLAG_IMMUTABLE);
            alarmManager.cancel(nextPointIntent);

            if (SupportUtil.isDozing(LoggerService.this)){
                // Only invoked every 15 minutes in doze mode
                SystemLogger.e(TAG, "Device is dozing, using infrequent alarm");
                setAlarmAllowWhileIdle(alarmManager, SystemClock.elapsedRealtime() + millisDelay, nextPointIntent);
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + millisDelay, nextPointIntent);
            }

            // Ensure significant motion notifications are enabled
            mSensorManager.requestTriggerSensor(LogjobSignificantMotionWorker.this, mSensor);
        }

        public void handleLocationChange(Location location) {
            CorrectingLocation loc = new CorrectingLocation(location);
            if (loc.getProvider().equals(LocationManager.GPS_PROVIDER)
                    || (!useGps)
            ) {
                SystemLogger.d(TAG, "[LogjobSignificantMotionWorker] Got location result, immediately accepting");

                // respect minimum distance/accuracy settings
                boolean minDistanceOk = isMinDistanceOk(loc);
                boolean minAccuracyOk = isMinAccuracyOk(loc);
                if (minDistanceOk && minAccuracyOk) {
                    // Accept, store and sync location
                    mCachedNetworkResult = null;
                    lastLocation = loc;
                    acceptAndSyncLocation(mJobId, loc);

                    mLastUpdateRealtime = loc.getElapsedRealtimeNanos() / 1000000;

                    // Cancel timeout runnable
                    if (mTimeoutHandler != null) {
                        mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
                        mTimeoutRunnable = null;
                    }

                    // stop location request because we accept the location
                    if (useGps || useNet) {
                        SystemLogger.d(TAG, "LogjobSignificantMotionWorker::timeoutRunnable => minDistanceOk && minAccuracyOk && (useGps || useNet) so locManager.removeUpdates()");
                        locManager.removeUpdates(gpsLocationListener);
                        locManager.removeUpdates(networkLocationListener);
                    }
                } else {
                    SystemLogger.d(TAG, "Not enough DISTANCE (min " + mLogJob.getMinDistance() +
                            ") or ACCURACY (min " + mLogJob.getMinAccuracy() + "), we skip this location");
                }

                // Clear significant motion flag for next interval
                mMotionDetected = false;

                // Request significant motion notification
                mSensorManager.requestTriggerSensor(LogjobSignificantMotionWorker.this, mSensor);

                // If using an interval and point accepted, schedule sample for X seconds from last sample
                if (mUseInterval && minDistanceOk && minAccuracyOk) {
                    // how much time did it take to get current position?
                    long cTs = System.currentTimeMillis() / 1000;
                    long timeSpentSearching = cTs - lastAcquisitionStartTimestamp;
                    long timeToWaitSecond = mLogJob.getMinTime() - timeSpentSearching;
                    if (timeToWaitSecond < 0) {
                        timeToWaitSecond = 0;
                    }
                    SystemLogger.d(TAG, "[MOTION] As we spent " + timeSpentSearching + " seconds to search position, " +
                            "with interval=" + mLogJob.getMinTime() + ", " +
                            "we now wait " + timeToWaitSecond + " seconds before requesting a new one");
                    scheduleSampleAfterInterval(timeToWaitSecond * 1000);
                }
                // anyway if the position was rejected, the location request is still running
                // and we call this method when we get next location
            } else {
                SystemLogger.d(TAG, "Network location returned first, caching");
                // Cache lower quality network result
                mCachedNetworkResult = loc;
            }
        }

        // motion detected by the sensor
        @Override
        public void onTrigger(TriggerEvent event) {
            SystemLogger.d(TAG, "Significant motion seen, logjob " + mJobId);

            // We detected a motion during the interval
            mMotionDetected = true;

            // If the job doesn't have a minimum interval or hasn't requested a sample for longer than its interval:
            // sample immediately
            long millisSinceLast = SystemClock.elapsedRealtime() - mLastUpdateRealtime;
            // without mixed mode
            if (!mUseInterval || !mUseMixedMode) {
                if (!mUseInterval || millisSinceLast > mIntervalTimeMillis) {
                    // If not using an interval, definitely request updates
                    boolean requestUpdates = !mUseInterval;

                    // If we're interval-based and there is a runnable waiting for the next interval we know we haven't
                    // already requested a location. This check helps us to avoid having two sampling sequences running
                    // for the same job.
                    if (mUseInterval && nextPointIntent != null) {
                        alarmManager.cancel(nextPointIntent);
                        nextPointIntent = null;

                        SystemLogger.d(TAG, "Triggering immediate location request after significant motion due to " +
                                (millisSinceLast / 1000.0) + " seconds since last point");

                        requestUpdates = true;
                    }

                    if (requestUpdates) {
                        requestLocationUpdates(mJobId, true, true);
                    }
                }
            }
            // with mixed mode
            // we take the position anyway, then runnable has to be killed, it will be launched again
            // when handling the position result
            else {
                SystemLogger.d(TAG, "Triggering immediate sample after significant motion because we're in MIXED mode");

                // If we're interval-based and there is a runnable waiting for the next interval we know we haven't
                // already requested a location. This checks helps us prevent having two sampling sequences running
                // for the same job.
                if (nextPointIntent != null) {
                    SystemLogger.d(TAG, "stop interval schedule runnable because MIXED mode");
                    // Stop waiting
                    alarmManager.cancel(nextPointIntent);
                    nextPointIntent = null;
                }

                requestLocationUpdates(mJobId, true, true);
            }

            // Request notification for next significant motion
            mSensorManager.requestTriggerSensor(LogjobSignificantMotionWorker.this, mSensor);
        }
    }
}
