package net.eneiluj.nextcloud.phonetrack.persistence;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.preference.PreferenceManager;

import android.util.Log;

import com.google.gson.GsonBuilder;
import com.nextcloud.android.sso.api.NextcloudAPI;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException;
import com.nextcloud.android.sso.exceptions.NoCurrentAccountSelectedException;
import com.nextcloud.android.sso.exceptions.TokenMismatchException;
import com.nextcloud.android.sso.helper.SingleAccountHelper;
import com.nextcloud.android.sso.model.SingleSignOnAccount;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import at.bitfire.cert4android.CustomCertManager;

import net.eneiluj.nextcloud.phonetrack.R;
import net.eneiluj.nextcloud.phonetrack.android.activity.SettingsActivity;
import net.eneiluj.nextcloud.phonetrack.model.BasicLocation;
import net.eneiluj.nextcloud.phonetrack.model.DBSession;
import net.eneiluj.nextcloud.phonetrack.service.LoggerService;
import net.eneiluj.nextcloud.phonetrack.util.BackgroundTask;
import net.eneiluj.nextcloud.phonetrack.util.CertificateTrust;
import net.eneiluj.nextcloud.phonetrack.util.CredentialStore;
import net.eneiluj.nextcloud.phonetrack.util.ICallback;
import net.eneiluj.nextcloud.phonetrack.util.IGetLastPosCallback;
import net.eneiluj.nextcloud.phonetrack.util.PhoneTrackClient;
import net.eneiluj.nextcloud.phonetrack.util.PhoneTrackClientUtil.LoginStatus;
import net.eneiluj.nextcloud.phonetrack.util.ServerResponse;

/**
 * Helps to synchronize the Database to the Server.
 */
public class SessionServerSyncHelper {

    private static final String TAG = SessionServerSyncHelper.class.getSimpleName();

    public static final String BROADCAST_SESSIONS_SYNC_FAILED = "net.eneiluj.nextcloud.phonetrack.broadcast.sessions_sync_failed";
    public static final String BROADCAST_SESSIONS_SYNCED = "net.eneiluj.nextcloud.phonetrack.broadcast.sessions_synced";
    public static final String BROADCAST_SSO_TOKEN_MISMATCH = "net.eneiluj.nextcloud.phonetrack.broadcast.token_mismatch";
    public static final String BROADCAST_NETWORK_AVAILABLE = "net.eneiluj.nextcloud.phonetrack.broadcast.network_available";
    public static final String BROADCAST_NETWORK_UNAVAILABLE = "net.eneiluj.nextcloud.phonetrack.broadcast.network_unavailable";
    public static final String BROADCAST_AVATAR_UPDATED = "net.eneiluj.nextcloud.phonetrack.broadcast.avatar_updated";

    private static SessionServerSyncHelper instance;

    /**
     * Get (or create) instance from SessionServerSyncHelper.
     * This has to be a singleton in order to realize correct registering and unregistering of
     * the BroadcastReceiver, which listens on changes of network connectivity.
     *
     * @param dbHelper PhoneTrackSQLiteOpenHelper
     * @return SessionServerSyncHelper
     */
    public static synchronized SessionServerSyncHelper getInstance(PhoneTrackSQLiteOpenHelper dbHelper) {
        if (instance == null) {
            instance = new SessionServerSyncHelper(dbHelper);
        }
        return instance;
    }

    private final PhoneTrackSQLiteOpenHelper dbHelper;
    private final Context appContext;

    private CustomCertManager customCertManager;

    // Track network connection changes using a BroadcastReceiver
    private boolean networkConnected = false;

    // current state of the synchronization
    private boolean syncActive = false;
    private boolean syncScheduled = false;

    // list of callbacks for both parts of synchronziation
    private List<ICallback> callbacksPush = new ArrayList<>();
    private List<ICallback> callbacksPull = new ArrayList<>();

    private final ConnectionStateMonitor connectionMonitor;

    private SessionServerSyncHelper(PhoneTrackSQLiteOpenHelper db) {
        this.dbHelper = db;
        this.appContext = db.getContext().getApplicationContext();

        // track network connectivity changes
        connectionMonitor = new ConnectionStateMonitor();
        connectionMonitor.enable(appContext);
        updateNetworkStatus();
    }

    @Override
    protected void finalize() throws Throwable {
        connectionMonitor.disable(appContext);
        super.finalize();
    }

    public static boolean isNextcloudAccountConfigured(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String settingsUrl = preferences.getString(SettingsActivity.SETTINGS_URL, SettingsActivity.DEFAULT_SETTINGS);
        boolean settingsUrlIsEmpty = (settingsUrl == null || settingsUrl.isEmpty());
        return !settingsUrlIsEmpty ||
                preferences.getBoolean(SettingsActivity.SETTINGS_USE_SSO, false);
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
            if (LoggerService.DEBUG) { Log.d(TAG, "NETWORK AVAILABLE : SYNC SESSIONS from synchelper"); }
            updateNetworkStatus();
            if (isSyncPossible()) {
                scheduleSync(false);
                Intent intent2 = new Intent(BROADCAST_NETWORK_AVAILABLE);
                intent2.setPackage(appContext.getPackageName());
                appContext.sendBroadcast(intent2);
            }
        }

        @Override
        public void onLost(@NonNull Network network) {
            if (!isSyncPossible()) {
                Intent intent2 = new Intent(BROADCAST_NETWORK_UNAVAILABLE);
                intent2.setPackage(appContext.getPackageName());
                appContext.sendBroadcast(intent2);
            }
        }
    }

    public static boolean isConfigured(Context context) {
        boolean useSSO = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(SettingsActivity.SETTINGS_USE_SSO, false);
        String classicUrl = PreferenceManager.getDefaultSharedPreferences(context).getString(
                SettingsActivity.SETTINGS_URL, SettingsActivity.DEFAULT_SETTINGS
        );
        boolean classicURLConfigured = !SettingsActivity.DEFAULT_SETTINGS.equals(classicUrl);
        return useSSO || classicURLConfigured;
    }

    /**
     * Synchronization is only possible, if there is an active network connection and
     * a server is configured.
     * SessionServerSyncHelper observes changes in the network connection.
     * The current state can be retrieved with this method.
     *
     * @return true if sync is possible, otherwise false.
     */
    public boolean isSyncPossible() {
        updateNetworkStatus();
        return networkConnected && isConfigured(appContext);
    }

    /** Created on first use: loading the user's trusted certificates reads a file. */
    @WorkerThread
    public synchronized CustomCertManager getCustomCertManager() {
        if (customCertManager == null) {
            customCertManager = CertificateTrust.newCertManager(appContext);
        }
        return customCertManager;
    }

    /**
     * Adds a callback method to the SessionServerSyncHelper for the synchronization part push local changes to the server.
     * All callbacks will be executed once the synchronization operations are done.
     * After execution the callback will be deleted, so it has to be added again if it shall be
     * executed the next time all synchronize operations are finished.
     *
     * @param callback Implementation of ICallback, contains one method that shall be executed.
     */
    public void addCallbackPush(ICallback callback) {
        callbacksPush.add(callback);
    }

    /**
     * Adds a callback method to the SessionServerSyncHelper for the synchronization part pull remote changes from the server.
     * All callbacks will be executed once the synchronization operations are done.
     * After execution the callback will be deleted, so it has to be added again if it shall be
     * executed the next time all synchronize operations are finished.
     *
     * @param callback Implementation of ICallback, contains one method that shall be executed.
     */
    public void addCallbackPull(ICallback callback) {
        callbacksPull.add(callback);
    }


    /**
     * Schedules a synchronization and start it directly, if the network is connected and no
     * synchronization is currently running.
     *
     * @param onlyLocalChanges Whether to only push local changes to the server or to also load the whole list of sessions from the server.
     */
    public void scheduleSync(boolean onlyLocalChanges) {
        Log.d(TAG, "Sync requested (" + (onlyLocalChanges ? "onlyLocalChanges" : "full") + "; " + (syncActive ? "sync active" : "sync NOT active") + ") ...");
        Log.d(TAG, "(network:" + networkConnected + "; conf:" + isConfigured(appContext) + ")");
        if (isSyncPossible() && (!syncActive || onlyLocalChanges)) {
            Log.d(TAG, "... starting now");
            SyncTask syncTask = new SyncTask(onlyLocalChanges);
            syncTask.addCallbacks(callbacksPush);
            callbacksPush = new ArrayList<>();
            if (!onlyLocalChanges) {
                syncTask.addCallbacks(callbacksPull);
                callbacksPull = new ArrayList<>();
            }
            syncTask.execute();
            // get NC color
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
            boolean settingServerColor = preferences.getBoolean(appContext.getString(R.string.pref_key_use_server_color), false);
            if (settingServerColor) {
                GetNCColorTask getColorTask = new GetNCColorTask();
                getColorTask.execute();
            }
            GetNCUserAvatarTask getAvatarTask = new GetNCUserAvatarTask();
            getAvatarTask.execute();
        } else if (!onlyLocalChanges) {
            Log.d(TAG, "... scheduled");
            syncScheduled = true;
            for (ICallback callback : callbacksPush) {
                callback.onScheduled();
            }
        } else {
            Log.d(TAG, "... do nothing");
            for (ICallback callback : callbacksPush) {
                callback.onScheduled();
            }
        }
    }

    private void updateNetworkStatus() {
        ConnectivityManager connMgr = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeInfo = connMgr.getActiveNetworkInfo();
        if (activeInfo != null && activeInfo.isConnected()) {
            Log.d(SessionServerSyncHelper.class.getSimpleName(), "Network connection established.");
            networkConnected = true;
        } else {
            networkConnected = false;
            Log.d(SessionServerSyncHelper.class.getSimpleName(), "No network connection.");
        }
    }

    /**
     * SyncTask is a BackgroundTask which performs the synchronization in a background thread.
     * Synchronization consists of two parts: pushLocalChanges and pullRemoteChanges.
     */
    private class SyncTask extends BackgroundTask<Void, LoginStatus> {
        private final boolean onlyLocalChanges;
        private final List<ICallback> callbacks = new ArrayList<>();
        private PhoneTrackClient client;
        private final List<Throwable> exceptions = new ArrayList<>();

        public SyncTask(boolean onlyLocalChanges) {
            this.onlyLocalChanges = onlyLocalChanges;
        }

        public void addCallbacks(List<ICallback> callbacks) {
            this.callbacks.addAll(callbacks);
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (!onlyLocalChanges && syncScheduled) {
                syncScheduled = false;
            }
            syncActive = true;
        }

        @Override
        protected LoginStatus doInBackground(Void... voids) {
            client = createPhoneTrackClient(); // recreate PhoneTrackClients on every sync in case the connection settings was changed
            Log.i(TAG, "STARTING SYNCHRONIZATION");
            //dbHelper.debugPrintFullDB();
            LoginStatus status;
            // TODO avoid doing getsessions everytime
            //pushLocalChanges();
            //if (!onlyLocalChanges) {
            if (client != null) {
                status = pullRemoteChanges();
            } else {
                status = LoginStatus.SSO_TOKEN_MISMATCH;
            }
            //}
            //dbHelper.debugPrintFullDB();
            Log.i(TAG, "SYNCHRONIZATION FINISHED");
            return status;
        }

        /**
         * Pull remote Changes: update or create each remote session and remove remotely deleted sessions.
         */
        private LoginStatus pullRemoteChanges() {
            // TODO add/remove sessions
            Log.d(TAG, "pullRemoteChanges()");
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
            String lastETag = preferences.getString(SettingsActivity.SETTINGS_KEY_ETAG, null);
            long lastModified = preferences.getLong(SettingsActivity.SETTINGS_KEY_LAST_MODIFIED, 0);
            LoginStatus status;
            try {
                Map<String, DBSession> localTokenToSession = dbHelper.getTokenMap();
                ServerResponse.SessionsResponse response = client.getSessions(getCustomCertManager(), lastModified, lastETag);
                List<DBSession> remoteSessions = response.getSessions(dbHelper);
                Set<String> remoteTokens = new HashSet<>();
                // pull remote changes: update or create each remote session
                for (DBSession remoteSession : remoteSessions) {
                    //Log.v(TAG, "   Process Remote Session: " + remoteSession);
                    remoteTokens.add(remoteSession.getToken());
                    if (localTokenToSession.containsKey(remoteSession.getToken())) {

                        DBSession localSession = localTokenToSession.get(remoteSession.getToken());
                        if (localSession != null) {
                            if (!localSession.getName().equals(remoteSession.getName())
                                    || !localSession.getNextURL().equals(remoteSession.getNextURL())
                                    || !localSession.getToken().equals(remoteSession.getToken())
                                    || localSession.getPublicToken() == null
                                    || !localSession.getPublicToken().equals(remoteSession.getPublicToken())
                                    || localSession.isFromShare() != remoteSession.isFromShare()
                                    || localSession.isPublic() != remoteSession.isPublic()
                            ) {
                                Log.v(TAG, "session " + localSession.getName() + " found locally -> needs update");
                                dbHelper.updateSession(localSession.getId(), remoteSession);
                            } else {
                                Log.v(TAG, "session " + localSession.getName() + " found locally -> does not need update");
                            }
                        } else {
                            Log.e(TAG, "session with token " + remoteSession.getToken() + " NOT found locally");
                        }
                    } else {
                        Log.v(TAG, "create session");
                        dbHelper.addSession(remoteSession);
                    }
                }
                Log.d(TAG, "Remove remotely deleted Sessions");
                // remove remotely deleted sessions
                for (String localToken : localTokenToSession.keySet()) {
                    if (!remoteTokens.contains(localToken)) {
                        DBSession s = localTokenToSession.get(localToken);
                        if (s != null) {
                            Log.v(TAG, "   ... remove " + s.getName());
                            dbHelper.deleteSession(s.getId());
                        }
                    }
                }
                status = LoginStatus.OK;

                // update ETag and Last-Modified in order to reduce size of next response
                SharedPreferences.Editor editor = preferences.edit();
                String etag = response.getETag();
                if (etag != null && !etag.isEmpty()) {
                    editor.putString(SettingsActivity.SETTINGS_KEY_ETAG, etag);
                } else {
                    editor.remove(SettingsActivity.SETTINGS_KEY_ETAG);
                }
                long modified = response.getLastModified();
                if (modified != 0) {
                    editor.putLong(SettingsActivity.SETTINGS_KEY_LAST_MODIFIED, modified);
                } else {
                    editor.remove(SettingsActivity.SETTINGS_KEY_LAST_MODIFIED);
                }
                editor.apply();
            } catch (ServerResponse.NotModifiedException e) {
                Log.d(TAG, "No changes, nothing to do.");
                status = LoginStatus.OK;
            } catch (IOException e) {
                Log.e(TAG, "Exception", e);
                exceptions.add(e);
                status = LoginStatus.CONNECTION_FAILED;
            } catch (JSONException e) {
                Log.e(TAG, "Exception", e);
                exceptions.add(e);
                status = LoginStatus.JSON_FAILED;
            } catch (TokenMismatchException e) {
                Log.e(TAG, "Catch MISMATCHTOKEN", e);
                status = LoginStatus.SSO_TOKEN_MISMATCH;
            }

            return status;
        }

        @Override
        protected void onPostExecute(LoginStatus status) {
            super.onPostExecute(status);
            if (status != LoginStatus.OK) {
                String errorString = appContext.getString(
                        R.string.error_sync,
                        appContext.getString(status.str)
                );
                errorString += "\n\n";
                for (Throwable e : exceptions) {
                    errorString += e.getClass().getName() + ": " + e.getMessage();
                }
                // broadcast the error
                // if the log job list is not visible, no toast
                Intent intent = new Intent(BROADCAST_SESSIONS_SYNC_FAILED);
                if (status != LoginStatus.JSON_FAILED) {
                    intent.putExtra(LoggerService.BROADCAST_ERROR_MESSAGE, errorString);
                }
                else {
                    Log.e(TAG, "Error while retrieving sessions: "+errorString);
                }
                intent.setPackage(appContext.getPackageName());
                appContext.sendBroadcast(intent);
                if (status == LoginStatus.SSO_TOKEN_MISMATCH) {
                    Intent intent2 = new Intent(BROADCAST_SSO_TOKEN_MISMATCH);
                    intent2.setPackage(appContext.getPackageName());
                    appContext.sendBroadcast(intent2);
                }
            }
            else {
                Intent intent = new Intent(BROADCAST_SESSIONS_SYNCED);
                intent.setPackage(appContext.getPackageName());
                appContext.sendBroadcast(intent);
            }
            syncActive = false;
            // notify callbacks
            for (ICallback callback : callbacks) {
                callback.onFinish();
            }
            dbHelper.notifySessionsChanged();
            // start next sync if scheduled meanwhile
            if (syncScheduled) {
                scheduleSync(false);
            }
        }
    }

    private class GetNCColorTask extends BackgroundTask<Void, LoginStatus> {

        private final List<ICallback> callbacks = new ArrayList<>();
        private PhoneTrackClient client;
        private final List<Throwable> exceptions = new ArrayList<>();

        public GetNCColorTask() {

        }

        public void addCallbacks(List<ICallback> callbacks) {
            this.callbacks.addAll(callbacks);
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected LoginStatus doInBackground(Void... voids) {
            client = createPhoneTrackClient(); // recreate PhoneTrackClients on every sync in case the connection settings was changed
            Log.i(TAG, "STARTING get color");

            LoginStatus status = LoginStatus.OK;

            if (client != null) {
                status = getNextcloudColor();
            }
            else {
                status = LoginStatus.SSO_TOKEN_MISMATCH;
            }

            Log.i(TAG, "Get color FINISHED");
            return status;
        }

        /**
         * Pull remote Changes: update or create each remote session and remove remotely deleted sessions.
         */
        private LoginStatus getNextcloudColor() {
            Log.d(TAG, "getNextcloudColor()");
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
            String lastETag = preferences.getString(SettingsActivity.SETTINGS_KEY_ETAG, null);
            long lastModified = preferences.getLong(SettingsActivity.SETTINGS_KEY_LAST_MODIFIED, 0);
            LoginStatus status;
            try {

                ServerResponse.CapabilitiesResponse response = client.getColor(getCustomCertManager());
                String color = response.getColor();

                status = LoginStatus.OK;

                // update ETag and Last-Modified in order to reduce size of next response
                SharedPreferences.Editor editor = preferences.edit();

                if (color != null && !color.isEmpty() && color.startsWith("#")) {
                    if (color.length() == 4) {
                        color = "#" + color.charAt(1) + color.charAt(1)
                                    + color.charAt(2) + color.charAt(2)
                                    + color.charAt(3) + color.charAt(3);
                    }
                    int intColor = Color.parseColor(color);
                    Log.d(TAG, "COLOR from server is "+color);
                    editor.putInt(appContext.getString(R.string.pref_key_color), intColor);
                }
                else {
                    //editor.remove(SettingsActivity.SETTINGS_KEY_ETAG);
                }

                editor.apply();
            } catch (ServerResponse.NotModifiedException e) {
                Log.d(TAG, "No changes, nothing to do.");
                status = LoginStatus.OK;
            } catch (IOException e) {
                Log.e(TAG, "Exception", e);
                exceptions.add(e);
                status = LoginStatus.CONNECTION_FAILED;
            } catch (JSONException e) {
                Log.e(TAG, "Exception", e);
                exceptions.add(e);
                status = LoginStatus.JSON_FAILED;
            } catch (TokenMismatchException e) {
                Log.e(TAG, "Catch MISMATCHTOKEN", e);
                status = LoginStatus.SSO_TOKEN_MISMATCH;
            }

            return status;
        }

        @Override
        protected void onPostExecute(LoginStatus status) {
            super.onPostExecute(status);
        }
    }


    private final NextcloudAPI.ApiConnectedListener apiCallback = new NextcloudAPI.ApiConnectedListener() {
        @Override
        public void onConnected() {
            // ignore this one..
            Log.d(TAG, "API connected!!!!");
        }

        @Override
        public void onError(Exception ex) {
            // TODO handle error in your app
        }
    };

    private PhoneTrackClient createPhoneTrackClient() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext.getApplicationContext());
        String url = "";
        String password = "";
        boolean useSSO = preferences.getBoolean(SettingsActivity.SETTINGS_USE_SSO, false);
        if (useSSO) {
            try {
                SingleSignOnAccount ssoAccount = SingleAccountHelper.getCurrentSingleSignOnAccount(appContext.getApplicationContext());
                NextcloudAPI nextcloudAPI = new NextcloudAPI(appContext.getApplicationContext(), ssoAccount, new GsonBuilder().create(), apiCallback);
                return new PhoneTrackClient(url, ssoAccount.userId, password, nextcloudAPI, appContext);
            } catch (NextcloudFilesAppAccountNotFoundException e) {
                Log.e(TAG, "NextcloudFilesAppAccountNotFoundException");
                return null;
            } catch (NoCurrentAccountSelectedException e) {
                Log.e(TAG, "NoCurrentAccountSelectedException");
                return null;
            }
        } else {
            url = preferences.getString(SettingsActivity.SETTINGS_URL, SettingsActivity.DEFAULT_SETTINGS);
            String username = preferences.getString(SettingsActivity.SETTINGS_USERNAME, SettingsActivity.DEFAULT_SETTINGS);
            password = CredentialStore.getAccountPassword(appContext);
            return new PhoneTrackClient(url, username, password, null, appContext);
        }
    }

    public boolean isAccountUrl(String url) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext.getApplicationContext());
        boolean useSSO = preferences.getBoolean(SettingsActivity.SETTINGS_USE_SSO, false);
        if (useSSO) {
            try {
                SingleSignOnAccount ssoAccount = SingleAccountHelper.getCurrentSingleSignOnAccount(appContext.getApplicationContext());
                String accountUrl = ssoAccount.url;
                return accountUrl.replaceAll("/$", "").equals(url.replaceAll("/$", ""));
            } catch (NextcloudFilesAppAccountNotFoundException e) {
                Log.e(TAG, "NextcloudFilesAppAccountNotFoundException");
                return false;
            } catch (NoCurrentAccountSelectedException e) {
                Log.e(TAG, "NoCurrentAccountSelectedException");
                return false;
            }
        } else {
            String accountUrl = preferences.getString(SettingsActivity.SETTINGS_URL, SettingsActivity.DEFAULT_SETTINGS);
            if (accountUrl == null) {
                return false;
            } else {
                return accountUrl.replaceAll("/$", "").equals(url.replaceAll("/$", ""));
            }
        }
    }

    public boolean shareDevice(String token, String deviceName, ICallback callback) {
        if (isSyncPossible()) {
            ShareDeviceTask shareDeviceTask = new ShareDeviceTask(token, deviceName, callback);
            shareDeviceTask.execute();
            return true;
        }
        return false;
    }

    /**
     * task to ask server to create public share with name restriction on device
     * or just get the share token if it already exists
     *
     */
    private class ShareDeviceTask extends BackgroundTask<Void, LoginStatus> {
        private PhoneTrackClient client;
        private final String token;
        private final String deviceName;
        private String publicUrl = null;
        private final ICallback callback;
        private final List<Throwable> exceptions = new ArrayList<>();

        public ShareDeviceTask(String token, String deviceName, ICallback callback) {
            this.token = token;
            this.deviceName = deviceName;
            this.callback = callback;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected LoginStatus doInBackground(Void... voids) {
            client = createPhoneTrackClient();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
            if (LoggerService.DEBUG) { Log.i(TAG, "STARTING share device"); }
            LoginStatus status = LoginStatus.OK;
            String sharetoken;
            try {
                ServerResponse.ShareDeviceResponse response = client.shareDevice(getCustomCertManager(), token, deviceName);
                sharetoken = response.getPublicToken();
                if (LoggerService.DEBUG) {
                    Log.i(TAG, "HERE IS THE TOKEN "+sharetoken);
                }
                if (prefs.getBoolean(SettingsActivity.SETTINGS_USE_SSO, false)) {
                    String settingsSsoUrl = prefs.getString(SettingsActivity.SETTINGS_SSO_URL, SettingsActivity.DEFAULT_SETTINGS);
                    settingsSsoUrl = settingsSsoUrl == null ? SettingsActivity.DEFAULT_SETTINGS : settingsSsoUrl;
                    publicUrl = settingsSsoUrl
                            .replaceAll("/+$", "")
                            + "/index.php/apps/phonetrack/publicSessionWatch/" + sharetoken;
                } else {
                    String settingsUrl = prefs.getString(SettingsActivity.SETTINGS_URL, SettingsActivity.DEFAULT_SETTINGS);
                    settingsUrl = settingsUrl == null ? SettingsActivity.DEFAULT_SETTINGS : settingsUrl;
                    publicUrl = settingsUrl
                            .replaceAll("/+$", "")
                            + "/index.php/apps/phonetrack/publicSessionWatch/" + sharetoken;
                }
            } catch (IOException e) {
                if (LoggerService.DEBUG) {
                    Log.e(TAG, "Exception", e);
                }
                exceptions.add(e);
                status = LoginStatus.CONNECTION_FAILED;
            } catch (JSONException e) {
                if (LoggerService.DEBUG) {
                    Log.e(TAG, "Exception", e);
                }
                exceptions.add(e);
                status = LoginStatus.JSON_FAILED;
            } catch (TokenMismatchException e) {
                Log.e(TAG, "Catch MISMATCHTOKEN", e);
                status = LoginStatus.SSO_TOKEN_MISMATCH;
            }
            if (LoggerService.DEBUG) {
                Log.i(TAG, "FINISHED share device");
            }
            return status;
        }

        @Override
        protected void onPostExecute(LoginStatus status) {
            super.onPostExecute(status);
            String errorString = "";
            if (status != LoginStatus.OK) {
                errorString = appContext.getString(
                        R.string.error_sync,
                        appContext.getString(status.str)
                );
                errorString += "\n\n";
                for (Throwable e : exceptions) {
                    errorString += e.getClass().getName() + ": " + e.getMessage();
                }
            }
            callback.onFinish(publicUrl, errorString);
        }
    }

    public boolean getSessionPositions(DBSession session, Long lastTimestamp, Long limit, IGetLastPosCallback callback) {
        if (isSyncPossible()) {
            GetSessionPositionsTask getSessionPositionsTask = new GetSessionPositionsTask(session, lastTimestamp, limit, callback);
            getSessionPositionsTask.execute();
            return true;
        }
        return false;
    }

    private class GetSessionPositionsTask extends BackgroundTask<Void, LoginStatus> {
        private PhoneTrackClient client;
        private final DBSession session;
        private final Long lastTimestamp;
        private final Long limit;
        private final IGetLastPosCallback callback;
        private final List<Throwable> exceptions = new ArrayList<>();
        private Map<String, List<BasicLocation>> locations;
        private Map<String, String> colors;

        public GetSessionPositionsTask(DBSession session, Long lastTimestamp, Long limit, IGetLastPosCallback callback) {
            this.session = session;
            this.lastTimestamp = lastTimestamp;
            this.limit = limit;
            this.callback = callback;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected LoginStatus doInBackground(Void... voids) {
            client = createPhoneTrackClient();
            if (LoggerService.DEBUG) { Log.i(TAG, "STARTING get positions"); }
            LoginStatus status = LoginStatus.OK;
            locations = new HashMap<>();
            try {
                ServerResponse.GetSessionPositionsResponse response = client.getSessionPositions(getCustomCertManager(), session, limit, lastTimestamp);
                locations = response.getPositions(session);
                colors = response.getColors(session);
                if (LoggerService.DEBUG) {
                    Log.i(TAG, "HERE ARE THE positions and colors "+locations.keySet().size());
                }
            } catch (IOException e) {
                if (LoggerService.DEBUG) {
                    Log.e(TAG, "Exception", e);
                }
                exceptions.add(e);
                status = LoginStatus.CONNECTION_FAILED;
            } catch (JSONException e) {
                if (LoggerService.DEBUG) {
                    Log.e(TAG, "Exception", e);
                }
                exceptions.add(e);
                status = LoginStatus.JSON_FAILED;
            } catch (TokenMismatchException e) {
                Log.e(TAG, "Catch MISMATCHTOKEN", e);
                status = LoginStatus.SSO_TOKEN_MISMATCH;
            }
            if (LoggerService.DEBUG) {
                Log.i(TAG, "FINISHED share device");
            }
            return status;
        }

        @Override
        protected void onPostExecute(LoginStatus status) {
            super.onPostExecute(status);
            String errorString = "";
            if (status != LoginStatus.OK) {
                errorString = appContext.getString(
                        R.string.error_sync,
                        appContext.getString(status.str)
                );
                errorString += "\n\n";
                for (Throwable e : exceptions) {
                    errorString += e.getClass().getName() + ": " + e.getMessage();
                }
            }
            callback.onFinish(locations, colors, errorString);
        }
    }

    public boolean createSession(String sessionName, ICallback callback) {
        if (isSyncPossible()) {
            CreateSessionTask createSessionTask = new CreateSessionTask(sessionName, callback);
            createSessionTask.execute();
            return true;
        }
        return false;
    }

    /**
     * task to ask server to create a session
     *
     */
    private class CreateSessionTask extends BackgroundTask<Void, LoginStatus> {
        private PhoneTrackClient client;
        private final String sessionName;
        private String sessionId = null;
        private final ICallback callback;
        private final List<Throwable> exceptions = new ArrayList<>();

        public CreateSessionTask(String sessionName, ICallback callback) {
            this.sessionName = sessionName;
            this.callback = callback;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected LoginStatus doInBackground(Void... voids) {
            client = createPhoneTrackClient();
            if (LoggerService.DEBUG) { Log.i(TAG, "STARTING share device"); }
            LoginStatus status = LoginStatus.OK;
            try {
                ServerResponse.CreateSessionResponse response = client.createSession(getCustomCertManager(), sessionName);
                sessionId = response.getSessionId();
                if (LoggerService.DEBUG) {
                    Log.i(TAG, "HERE IS THE ID "+sessionId);
                }
            } catch (IOException e) {
                if (LoggerService.DEBUG) {
                    Log.e(TAG, "Exception", e);
                }
                exceptions.add(e);
                status = LoginStatus.CONNECTION_FAILED;
            } catch (JSONException e) {
                if (LoggerService.DEBUG) {
                    Log.e(TAG, "Exception", e);
                }
                exceptions.add(e);
                status = LoginStatus.JSON_FAILED;
            } catch (Exception e) {
                exceptions.add(new Exception(appContext.getString(R.string.error_create_session_exists)));
            }
            if (LoggerService.DEBUG) {
                Log.i(TAG, "FINISHED create session task");
            }
            return status;
        }

        @Override
        protected void onPostExecute(LoginStatus status) {
            super.onPostExecute(status);
            String errorString = "";
            if (status != LoginStatus.OK) {
                errorString = appContext.getString(
                        R.string.error_sync,
                        appContext.getString(status.str)
                );
                errorString += "\n\n";
            }
            for (Throwable e : exceptions) {
                errorString += e.getClass().getName() + ": " + e.getMessage();
            }
            callback.onFinish(sessionId, errorString);
        }
    }

    private class GetNCUserAvatarTask extends BackgroundTask<Void, LoginStatus> {

        private final List<ICallback> callbacks = new ArrayList<>();
        private PhoneTrackClient client;
        private final List<Throwable> exceptions = new ArrayList<>();

        public GetNCUserAvatarTask() {

        }

        public void addCallbacks(List<ICallback> callbacks) {
            this.callbacks.addAll(callbacks);
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected LoginStatus doInBackground(Void... voids) {
            client = createPhoneTrackClient();
            Log.i(TAG, "STARTING get account avatar");

            LoginStatus status;
            if (client != null) {
                status = getNextcloudUserAvatar();
            } else {
                status = LoginStatus.SSO_TOKEN_MISMATCH;
            }
            return status;
        }

        private LoginStatus getNextcloudUserAvatar() {
            Log.d(TAG, "getNextcloudUserAvatar()");
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
            LoginStatus status;
            try {

                ServerResponse.AvatarResponse response = client.getAvatar(getCustomCertManager(), null);
                String avatar = response.getAvatarString();

                status = LoginStatus.OK;
                SharedPreferences.Editor editor = preferences.edit();
                if (avatar != null && !avatar.isEmpty()) {
                    Log.d(TAG, "avatar from server is "+avatar);
                    editor.putString(appContext.getString(R.string.pref_key_avatar), avatar);
                }
                editor.apply();
            } catch (ServerResponse.NotModifiedException e) {
                Log.d(TAG, "No changes, nothing to do.");
                status = LoginStatus.OK;
            } catch (IOException e) {
                Log.e(TAG, "Exception", e);
                exceptions.add(e);
                status = LoginStatus.CONNECTION_FAILED;
            } catch (JSONException e) {
                Log.e(TAG, "Exception", e);
                exceptions.add(e);
                status = LoginStatus.JSON_FAILED;
            } catch (TokenMismatchException e) {
                Log.e(TAG, "Catch MISMATCHTOKEN", e);
                status = LoginStatus.SSO_TOKEN_MISMATCH;
            }

            return status;
        }

        @Override
        protected void onPostExecute(LoginStatus status) {
            super.onPostExecute(status);
            if (status == LoginStatus.OK) {
                Intent intent = new Intent(BROADCAST_AVATAR_UPDATED);
                intent.setPackage(appContext.getPackageName());
                appContext.sendBroadcast(intent);
            }
        }
    }
}
