package net.eneiluj.nextcloud.phonetrack.service;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.WorkerThread;
import androidx.preference.PreferenceManager;

import com.google.gson.GsonBuilder;
import com.nextcloud.android.sso.api.NextcloudAPI;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException;
import com.nextcloud.android.sso.exceptions.NoCurrentAccountSelectedException;
import com.nextcloud.android.sso.helper.SingleAccountHelper;
import com.nextcloud.android.sso.model.SingleSignOnAccount;

import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.eneiluj.nextcloud.phonetrack.R;
import net.eneiluj.nextcloud.phonetrack.android.activity.LogjobsListViewActivity;
import net.eneiluj.nextcloud.phonetrack.android.activity.SettingsActivity;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjob;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjobLocation;
import net.eneiluj.nextcloud.phonetrack.persistence.PhoneTrackSQLiteOpenHelper;
import net.eneiluj.nextcloud.phonetrack.persistence.SessionServerSyncHelper;
import net.eneiluj.nextcloud.phonetrack.persistence.WebTrackHelper;
import net.eneiluj.nextcloud.phonetrack.util.CredentialStore;
import net.eneiluj.nextcloud.phonetrack.util.PhoneTrackClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import at.bitfire.cert4android.CustomCertManager;

/**
 * Uploads the positions stored in the local database to their destination
 * (PhoneTrack, Nextcloud Maps or a custom URL). Run by {@link WebTrackWorker}.
 */
public class WebTrackSync {

    private static final String TAG = WebTrackSync.class.getSimpleName();
    public static final String BROADCAST_SYNC_FAILED = "net.eneiluj.nextcloud.phonetrack.broadcast.sync_failed";
    public static final String BROADCAST_SYNC_STARTED = "net.eneiluj.nextcloud.phonetrack.broadcast.sync_started";
    public static final String BROADCAST_SYNC_DONE = "net.eneiluj.nextcloud.phonetrack.broadcast.sync_done";

    private final Context context;
    private final PhoneTrackSQLiteOpenHelper db;
    private final WebTrackHelper web;
    private int sent;
    private final List<Long> failedLogjobIds = new ArrayList<>();

    public WebTrackSync(Context context) {
        this.context = context.getApplicationContext();
        db = PhoneTrackSQLiteOpenHelper.getInstance(this.context);
        CustomCertManager certManager = db.getPhonetrackServerSyncHelper().getCustomCertManager();
        web = new WebTrackHelper(this.context, certManager);
    }

    /**
     * Send the positions not synced yet, of one logjob or of all of them (id 0).
     *
     * @return false if any upload failed (the positions stay unsynced and can be retried)
     */
    @WorkerThread
    public boolean sync(long ljIdToSync) {
        sent = 0;
        failedLogjobIds.clear();
        boolean anyError = false;

        // get the logjobs
        List<DBLogjob> logjobs;
        if (ljIdToSync == 0) {
            // iterate over positions in db
            logjobs = db.getLogjobs();
        }
        // if only one logjob is asked, just get this one
        else {
            logjobs = new ArrayList<>();
            logjobs.add(db.getLogjob(ljIdToSync));
        }

        if (logjobs.size() > 0) {
            // start loading animation in logjob list
            Intent intent = new Intent(BROADCAST_SYNC_STARTED);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        long groupSync = 0;
        String groupsyncString = prefs.getString(context.getString(R.string.pref_key_group_sync), "0");
        if (groupsyncString != null) {
            groupSync = Long.parseLong(groupsyncString);
        }

        for (DBLogjob logjob : logjobs) {
            long ljId = logjob.getId();
            try {
                // Maps logjob
                if (logjob.getDeviceName().isEmpty() && logjob.getToken().isEmpty() && logjob.getUrl().isEmpty()) {
                    List<DBLogjobLocation> locations = db.getLocationsToSyncOfLogjob(ljId);
                    if (locations.size() > 0 && locations.size() >= groupSync) {
                        if (!SessionServerSyncHelper.isConfigured(context)) {
                            throw new Exception(context.getString(R.string.error_no_account_maps));
                        }
                        PhoneTrackClient client = createPhoneTrackClient();
                        for (DBLogjobLocation loc : locations) {
                            long locId = loc.getId();
                            Map<String, String> params = dbLocationToMap(loc);
                            web.postPositionToMaps(client, params);
                            db.setLocationSynced(locId);
                            sent++;
                            db.incNbSync(logjob);
                            db.setLastSyncTimestamp(ljId, System.currentTimeMillis() / 1000);
                            Intent intent = new Intent(BROADCAST_SYNC_DONE);
                            intent.putExtra(LoggerService.BROADCAST_EXTRA_PARAM, ljId);
                            intent.setPackage(context.getPackageName());
                            context.sendBroadcast(intent);
                        }
                        if (locations.size() > 0) {
                            db.resetLastSyncError(ljId);
                        }
                    }
                }
                // PhoneTrack logjob
                else if (!logjob.getDeviceName().isEmpty() && !logjob.getToken().isEmpty()) {
                    URL url = web.getUrlFromPhoneTrackLogjob(logjob);
                    List<DBLogjobLocation> locations = db.getLocationsToSyncOfLogjob(ljId);
                    if (locations.size() < groupSync) {
                        continue;
                    }
                    // send one by one
                    if (locations.size() <= 5) {
                        for (DBLogjobLocation loc : locations) {
                            long locId = loc.getId();
                            Map<String, String> params = dbLocationToMap(loc);
                            web.postPositionToPhoneTrack(url, params);
                            db.setLocationSynced(locId);
                            sent++;
                            db.incNbSync(logjob);
                            db.setLastSyncTimestamp(ljId, System.currentTimeMillis() / 1000);
                            Intent intent = new Intent(BROADCAST_SYNC_DONE);
                            intent.putExtra(LoggerService.BROADCAST_EXTRA_PARAM, ljId);
                            intent.setPackage(context.getPackageName());
                            context.sendBroadcast(intent);
                        }
                        if (locations.size() > 0) {
                            db.resetLastSyncError(ljId);
                        }
                    }
                    // send multiple locations per request
                    else {
                        url = web.getUrlMultipleFromPhoneTrackLogjob(logjob);
                        List<DBLogjobLocation> tmpLocs = new ArrayList<>();
                        int n = 0;
                        for (DBLogjobLocation loc : locations) {
                            tmpLocs.add(loc);
                            n++;
                            if (n%200 == 0) {
                                JSONObject params = dbLocationsToJSON(tmpLocs);
                                web.postMultiplePositionsToPhoneTrack(url, params);
                                for (DBLogjobLocation locToDel : tmpLocs) {
                                    long locId = locToDel.getId();
                                    db.setLocationSynced(locId);
                                    sent++;
                                    db.incNbSync(logjob);
                                }
                                tmpLocs = new ArrayList<>();
                                // update nbsync in logjob list
                                Intent intent = new Intent(BROADCAST_SYNC_DONE);
                                intent.putExtra(LoggerService.BROADCAST_EXTRA_PARAM, ljId);
                                intent.setPackage(context.getPackageName());
                                context.sendBroadcast(intent);
                            }
                        }
                        // last bunch
                        if (tmpLocs.size() > 0) {
                            JSONObject params = dbLocationsToJSON(tmpLocs);
                            web.postMultiplePositionsToPhoneTrack(url, params);
                            for (DBLogjobLocation locToDel : tmpLocs) {
                                long locId = locToDel.getId();
                                db.setLocationSynced(locId);
                                sent++;
                                db.incNbSync(logjob);
                            }
                        }
                        db.setLastSyncTimestamp(ljId, System.currentTimeMillis() / 1000);
                        db.resetLastSyncError(ljId);
                        Intent intent = new Intent(BROADCAST_SYNC_DONE);
                        intent.putExtra(LoggerService.BROADCAST_EXTRA_PARAM, ljId);
                        intent.setPackage(context.getPackageName());
                        context.sendBroadcast(intent);
                    }
                }
                // custom logjob
                else {
                    String destUrl = logjob.getUrl();
                    // potential login/password for HTTP auth, those might be null
                    String login = logjob.getLogin();
                    String password = logjob.getPassword();
                    boolean sendJsonPayload = logjob.getJson();
                    List<DBLogjobLocation> locations = db.getLocationsToSyncOfLogjob(ljId);
                    if (locations.size() < groupSync) {
                        continue;
                    }
                    for (DBLogjobLocation loc : locations) {
                        long locId = loc.getId();
                        Map<String, String> params = dbLocationToMap(loc);
                        if (logjob.getPost()) {
                            web.sendPOSTPositionToCustom(destUrl, params, login, password, sendJsonPayload);
                        } else {
                            web.sendGETPositionToCustom(destUrl, params, login, password);
                        }

                        db.setLocationSynced(locId);

                        sent++;
                        db.incNbSync(logjob);
                        db.setLastSyncTimestamp(ljId, System.currentTimeMillis() / 1000);
                        Intent intent = new Intent(BROADCAST_SYNC_DONE);
                        intent.putExtra(LoggerService.BROADCAST_EXTRA_PARAM, ljId);
                        intent.setPackage(context.getPackageName());
                        context.sendBroadcast(intent);
                    }
                    if (locations.size() > 0) {
                        db.resetLastSyncError(ljId);
                    }
                }
            } catch (IOException e) {
                // handle web errors
                if (LoggerService.DEBUG) {
                    Log.d(TAG, "[websync io exception: " + e + "]");
                }
                anyError = true;
                failedLogjobIds.add(ljId);
                handleError(e, ljId);
            } catch (JSONException e2) {
                if (LoggerService.DEBUG) {
                    Log.d(TAG, "[websync JSON exception: " + e2 + "]");
                }
                anyError = true;
                failedLogjobIds.add(ljId);
                handleError(e2, ljId);
            } catch (Exception e3) {
                anyError = true;
                failedLogjobIds.add(ljId);
                handleError(e3, ljId);
            }
        }
        // stop loading animation in logjob list
        Intent intent = new Intent(BROADCAST_SYNC_DONE);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
        return !anyError;
    }

    /**
     * Actions performed in case of synchronization error.
     * Send broadcast to main activity, schedule retry if tracking is on.
     *
     * @param e Exception
     */
    private void handleError(Exception e, long ljId) {
        String message;
        if (e instanceof UnknownHostException) {
            message = context.getString(R.string.e_unknown_host, e.getMessage());
        } else if (e instanceof MalformedURLException || e instanceof URISyntaxException) {
            message = context.getString(R.string.e_bad_url, e.getMessage());
        } else if (e instanceof ConnectException || e instanceof NoRouteToHostException) {
            message = context.getString(R.string.e_connect, e.getMessage());
        } else {
            message = e.getMessage();
        }
        if (LoggerService.DEBUG) { Log.d(TAG, "[websync retry: " + message + "]"); }

        db.setLastSyncError(ljId, System.currentTimeMillis()/1000, message);
        //db.setError(message);
        Intent intent = new Intent(BROADCAST_SYNC_FAILED);
        intent.putExtra(LoggerService.BROADCAST_EXTRA_PARAM, ljId);
        intent.putExtra(LoggerService.BROADCAST_ERROR_MESSAGE, message);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    /**
     * Convert cursor to map of request parameters
     *
     * @return Map of parameters
     */
    private Map<String, String> dbLocationToMap(DBLogjobLocation loc) {
        if (LoggerService.DEBUG) { Log.d(TAG, "[DBLOC to map "+loc+"]"); }

        Map<String, String> params = new HashMap<>();
        params.put(WebTrackHelper.PARAM_TIME, String.valueOf(loc.getTimestamp()));
        params.put(WebTrackHelper.PARAM_LAT, String.valueOf(loc.getLat()));
        params.put(WebTrackHelper.PARAM_LON, String.valueOf(loc.getLon()));
        params.put(WebTrackHelper.PARAM_ALT, (loc.getAltitude() != null) ? String.valueOf(loc.getAltitude()) : "");
        params.put(WebTrackHelper.PARAM_ACCURACY, (loc.getAccuracy() != null) ? String.valueOf(loc.getAccuracy()): "");
        params.put(WebTrackHelper.PARAM_SPEED, (loc.getSpeed() != null) ? String.valueOf(loc.getSpeed()) : "");
        params.put(WebTrackHelper.PARAM_BEARING, (loc.getBearing() != null) ? String.valueOf(loc.getBearing()) : "");
        params.put(WebTrackHelper.PARAM_SATELLITES, (loc.getSatellites() != null) ? String.valueOf(loc.getSatellites()) : "");
        params.put(WebTrackHelper.PARAM_BATTERY, String.valueOf(loc.getBattery()));
        params.put(WebTrackHelper.PARAM_USERAGENT, (loc.getUserAgent() != null) ? loc.getUserAgent() : "");
        return params;
    }

    private JSONObject dbLocationsToJSON(List<DBLogjobLocation> locations) throws JSONException {
        if (LoggerService.DEBUG) { Log.d(TAG, "[DBLOC to JSONObject]"); }

        JSONObject result = new JSONObject();
        JSONArray points = new JSONArray();

        for (DBLogjobLocation loc : locations) {
            JSONArray point = new JSONArray();
            point.put(loc.getLat());
            point.put(loc.getLon());
            point.put(loc.getTimestamp());
            point.put(loc.getAltitude());
            point.put(loc.getAccuracy());
            point.put(loc.getBattery());
            point.put(loc.getSatellites());
            point.put(loc.getUserAgent());
            point.put(loc.getSpeed());
            point.put(loc.getBearing());

            points.put(point);
        }

        result.put("points", points);
        return result;
    }

    /** Number of positions uploaded by the last {@link #sync} call. */
    /** IDs of the logjobs whose upload failed in the last sync() call. */
    public List<Long> getFailedLogjobIds() {
        return failedLogjobIds;
    }

    public int getSentCount() {
        return sent;
    }

    private PhoneTrackClient createPhoneTrackClient() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String url = "";
        String username = "";
        String password = "";
        boolean useSSO = preferences.getBoolean(SettingsActivity.SETTINGS_USE_SSO, false);
        if (useSSO) {
            try {
                SingleSignOnAccount ssoAccount = SingleAccountHelper.getCurrentSingleSignOnAccount(context);
                NextcloudAPI nextcloudAPI = new NextcloudAPI(context, ssoAccount, new GsonBuilder().create(), apiCallback);
                return new PhoneTrackClient(url, username, password, nextcloudAPI, context);
            } catch (NextcloudFilesAppAccountNotFoundException e) {
                if (LoggerService.DEBUG) {
                    Log.d(TAG, "[NextcloudFilesAppAccountNotFoundException: " + e + "]");
                }
                return null;
            } catch (NoCurrentAccountSelectedException e) {
                if (LoggerService.DEBUG) {
                    Log.d(TAG, "[NoCurrentAccountSelectedException: " + e + "]");
                }
                return null;
            }
        }
        else {
            url = preferences.getString(SettingsActivity.SETTINGS_URL, SettingsActivity.DEFAULT_SETTINGS);
            username = preferences.getString(SettingsActivity.SETTINGS_USERNAME, SettingsActivity.DEFAULT_SETTINGS);
            password = CredentialStore.getAccountPassword(context);
            return new PhoneTrackClient(url, username, password, null, context);
        }
    }

    private final NextcloudAPI.ApiConnectedListener apiCallback = new NextcloudAPI.ApiConnectedListener() {
        @Override
        public void onConnected() {
            // ignore this one..
            Log.d(getClass().getSimpleName(), "API connected!!!!");
        }

        @Override
        public void onError(Exception ex) {
            // TODO handle error in your app
        }
    };

}
