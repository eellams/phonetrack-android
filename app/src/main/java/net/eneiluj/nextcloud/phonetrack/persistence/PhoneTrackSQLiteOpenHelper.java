package net.eneiluj.nextcloud.phonetrack.persistence;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.VisibleForTesting;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.eneiluj.nextcloud.phonetrack.R;
import net.eneiluj.nextcloud.phonetrack.android.activity.SyslogManagerActivity;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjobLocation;
import net.eneiluj.nextcloud.phonetrack.model.DBSession;
import net.eneiluj.nextcloud.phonetrack.model.DBLogjob;
import net.eneiluj.nextcloud.phonetrack.model.DBSyslog;
import net.eneiluj.nextcloud.phonetrack.model.SyncError;
import net.eneiluj.nextcloud.phonetrack.service.LoggerService;
import net.eneiluj.nextcloud.phonetrack.util.CredentialStore;
import net.eneiluj.nextcloud.phonetrack.util.ICallback;
import net.eneiluj.nextcloud.phonetrack.util.CorrectingLocation;
import net.eneiluj.nextcloud.phonetrack.util.LogjobAutomation;
import net.eneiluj.nextcloud.phonetrack.util.SupportUtil;

/**
 * Helps to add, get, update and delete log jobs, sessions, locations with the option to trigger a session Resync with the Server.
 */
public class PhoneTrackSQLiteOpenHelper extends SQLiteOpenHelper {

    private static final String TAG = PhoneTrackSQLiteOpenHelper.class.getSimpleName();

    private static final int database_version = 21;
    private static final String database_name = "NEXTCLOUD_PHONETRACK";

    private static final String table_sessions = "SESSIONS";
    private static final String key_id = "ID";
    private static final String key_token = "TOKEN";
    private static final String key_publicToken = "PUBLICTOKEN";
    private static final String key_isFromShare = "ISFROMSHARE";
    private static final String key_isPublic = "ISPUBLIC";
    private static final String key_nextURL = "NEXTURL";
    private static final String key_name = "NAME";

    private static final String table_logjobs = "LOGJOBS";
    private static final String key_title = "TITLE";
    private static final String key_url = "URL";
    private static final String key_deviceName = "DEVICENAME";
    private static final String key_minTime = "MINTIME";
    private static final String key_minDistance = "MINDISTANCE";
    private static final String key_minAccuracy = "MINACCURACY";
    private static final String key_keepGpsOn = "KEEPGPSON";
    private static final String key_useSignificantMotion = "USESIGMOTION";
    private static final String key_useSignificantMotionMixed = "USESIGMOTIONMIXED";
    private static final String key_locationTimeout = "LOCTIMEOUT";
    private static final String key_post = "POST";
    private static final String key_enabled = "ENABLED";
    private static final String key_lastLocTimestamp = "LASTLOC";
    private static final String key_lastActivationSystemTimestamp = "LASTACTIVATIONSYSTEM";
    private static final String key_lastActivationGpsTimestamp = "LASTACTIVATIONGPS";
    private static final String key_nbsync = "NBSYNC";
    private static final String key_lastSyncTimestamp = "LASTSYNC";
    private static final String key_lastSyncErrorTimestamp = "LASTSYNCERRTIME";
    private static final String key_lastSyncErrorText = "LASTSYNCERR";
    private static final String key_login = "LOGIN";
    private static final String key_password = "PASSWORD";
    private static final String key_json = "JSON";
    private static final String key_automation = "AUTOMATION";

    private static final String table_locations = "LOCATIONS";
    private static final String key_logjobid = "LOGJOBID";
    private static final String key_lat = "LAT";
    private static final String key_lon = "LON";
    private static final String key_time = "TIME";
    private static final String key_bearing = "BEARING";
    private static final String key_altitude = "ALTITUDE";
    private static final String key_speed = "SPEED";
    private static final String key_accuracy = "ACCURACY";
    private static final String key_satellites = "SATELLITES";
    private static final String key_battery = "BATTERY";
    private static final String key_userAgent = "USERAGENT";
    private static final String key_synced = "SYNCED";
    private static final String key_currentRun = "CURRENTRUN";

    private static final String table_syslog = "SYSLOG";
    private static final String key_tag = "TAG";
    private static final String key_message = "MESSAGE";
    private static final String key_level = "LEVEL";
    private static final String key_timestamp = "TIMESTAMP";

    private static final String[] columnsSessions = {
            key_id, key_token, key_name, key_nextURL,
            key_publicToken, key_isFromShare, key_isPublic
    };
    private static final String[] columnsLogjobs = {
            key_id, key_title, key_url, key_token, key_deviceName,
            key_minTime, key_minDistance, key_minAccuracy,
            key_keepGpsOn, key_post, key_enabled,
            key_nbsync, key_lastSyncTimestamp, key_lastLocTimestamp,
            key_lastSyncErrorTimestamp, key_lastSyncErrorText, key_useSignificantMotion,
            key_useSignificantMotionMixed, key_locationTimeout,
            key_lastActivationSystemTimestamp, key_lastActivationGpsTimestamp,
            key_login, key_password, key_json, key_automation
    };
    private static final String[] columnsLocations = {
            key_id, key_logjobid, key_lat, key_lon, key_time,
            key_bearing, key_altitude, key_speed, key_accuracy,
            key_satellites, key_battery, key_userAgent,
            key_synced, key_currentRun
    };
    private static final String[] columnsSyslog = {
            key_id, key_tag, key_message, key_level, key_timestamp
    };

    private static final String default_order = key_id + " DESC";
    private static final String default_order_sessions = key_id + " ASC";

    private static PhoneTrackSQLiteOpenHelper instance;

    private final SessionServerSyncHelper serverSyncHelper;
    private final Context context;

    private final String userAgent;

    private PhoneTrackSQLiteOpenHelper(Context context) {
        super(context, database_name, null, database_version);
        this.context = context.getApplicationContext();
        userAgent = context.getString(R.string.app_name) + "/" + SupportUtil.getAppVersionName(context);
        serverSyncHelper = SessionServerSyncHelper.getInstance(this);
        //recreateDatabase(getWritableDatabase());
    }

    public static PhoneTrackSQLiteOpenHelper getInstance(Context context) {
        if (instance == null)
            return instance = new PhoneTrackSQLiteOpenHelper(context.getApplicationContext());
        else
            return instance;
    }

    /** Robolectric gives every test a new Application; don't keep a helper bound to an old one. */
    @VisibleForTesting
    static void resetInstanceForTesting() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public SessionServerSyncHelper getPhonetrackServerSyncHelper() {
        return serverSyncHelper;
    }

    /**
     * Creates initial the Database
     *
     * @param db Database
     */
    @Override
    public void onCreate(SQLiteDatabase db) {
        createTableSessions(db, table_sessions);
        createTableLogjobs(db, table_logjobs);
        createTableLocations(db, table_locations);
        createTableSyslog(db, table_syslog);
        createIndexes(db);
    }

    private void createTableSessions(SQLiteDatabase db, String tableName) {
        db.execSQL("CREATE TABLE " + tableName + " ( " +
                key_id + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                key_name + " TEXT, " +
                key_nextURL + " TEXT, " +
                key_publicToken + " TEXT, " +
                key_isFromShare + " INTEGER DEFAULT 0, " +
                key_isPublic + " INTEGER DEFAULT 1, " +
                key_token + " TEXT)"
        );

    }

    private void createTableLogjobs(SQLiteDatabase db, String tableName) {
        db.execSQL("CREATE TABLE " + tableName + " ( " +
                key_id + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                key_title + " TEXT, " +
                key_url + " TEXT, " +
                key_deviceName + " TEXT, " +
                key_minTime + " INTEGER, " +
                key_minDistance + " INTEGER, " +
                key_minAccuracy + " INTEGER, " +
                key_keepGpsOn + " INTEGER DEFAULT 0, " +
                key_post + " INTEGER DEFAULT 0, " +
                key_json + " INTEGER DEFAULT 0, " +
                key_enabled + " INTEGER DEFAULT 0, " +
                key_nbsync + " INTEGER DEFAULT 0, " +
                key_lastSyncTimestamp + " INTEGER DEFAULT 0, " +
                key_lastLocTimestamp + " INTEGER DEFAULT 0, " +
                key_lastActivationSystemTimestamp + " INTEGER DEFAULT 0, " +
                key_lastActivationGpsTimestamp + " INTEGER DEFAULT 0, " +
                key_lastSyncErrorTimestamp + " INTEGER DEFAULT 0, " +
                key_lastSyncErrorText + " TEXT, " +
                key_token + " TEXT, " +
                key_login + " TEXT DEFAULT NULL, " +
                key_password + " TEXT DEFAULT NULL, " +
                key_useSignificantMotion + " INTEGER DEFAULT 0," +
                key_useSignificantMotionMixed + " INTEGER DEFAULT 0," +
                key_locationTimeout + " INTEGER," +
                key_automation + " TEXT DEFAULT NULL)"
        );
    }

    private void createTableLocations(SQLiteDatabase db, String tableName) {
        // key_id, key_logjobid, key_lat, key_lon, key_time, key_bearing, key_altitude, key_speed, key_accuracy, key_satellites, key_battery
        db.execSQL("CREATE TABLE " + tableName + " ( " +
                key_id + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                key_logjobid + " INTEGER, " +
                key_lat + " FLOAT, " +
                key_lon + " FLOAT, " +
                key_time + " INTEGER, " +
                key_bearing + " FLOAT, " +
                key_altitude + " FLOAT, " +
                key_speed + " FLOAT, " +
                key_accuracy + " FLOAT, " +
                key_satellites + " INTEGER, " +
                key_userAgent + " TEXT, " +
                key_synced + " INTEGER DEFAULT 0, " +
                key_currentRun + " INTEGER DEFAULT 0, " +
                key_battery + " FLOAT)"
        );
    }

    private void createTableSyslog(SQLiteDatabase db, String tableName) {
        db.execSQL("CREATE TABLE " + tableName + " ( " +
                key_id + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                key_tag + " VARCHAR(64), " +
                key_message + " TEXT, " +
                key_level + " VARCHAR(1), " +
                key_timestamp + " INTEGER)"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_keepGpsOn + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE " + table_sessions + " ADD COLUMN " + key_publicToken + " TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE " + table_sessions + " ADD COLUMN " + key_isFromShare + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 11) {
            db.execSQL("ALTER TABLE " + table_locations + " ADD COLUMN " + key_userAgent + " TEXT DEFAULT NULL");
        }
        if (oldVersion < 12) {
            db.execSQL("ALTER TABLE " + table_sessions + " ADD COLUMN " + key_isPublic + " INTEGER DEFAULT 1");
        }
        if (oldVersion < 13) {
            db.execSQL("ALTER TABLE " + table_locations + " ADD COLUMN " + key_synced + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + table_locations + " ADD COLUMN " + key_currentRun + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 14) {
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_useSignificantMotion + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_locationTimeout + " INTEGER");
        }
        if (oldVersion < 15) {
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_useSignificantMotionMixed + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 16) {
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_lastActivationSystemTimestamp + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_lastActivationGpsTimestamp + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 17) {
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_login + " TEXT DEFAULT NULL");
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_password + " TEXT DEFAULT NULL");
        }
        if (oldVersion < 18) {
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_json + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 19) {
            createTableSyslog(db, table_syslog);
        }
        if (oldVersion < 20) {
            moveLogjobPasswordsToCredentialStore(db, context);
        }
        if (oldVersion < 21) {
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_automation + " TEXT DEFAULT NULL");
        }
    }

    /**
     * Custom logjob passwords used to be stored in the database, which is part of the cloud
     * backup. Move them to {@link CredentialStore} (excluded from it) and blank the column,
     * with secure_delete so the old values are overwritten in the file.
     */
    @VisibleForTesting
    static void moveLogjobPasswordsToCredentialStore(SQLiteDatabase db, Context context) {
        try (Cursor pragma = db.rawQuery("PRAGMA secure_delete = ON", null)) {
            pragma.moveToFirst();
        }
        try (Cursor cursor = db.query(table_logjobs, new String[]{key_id, key_password},
                key_password + " IS NOT NULL", null, null, null, null)) {
            while (cursor.moveToNext()) {
                CredentialStore.setLogjobPassword(context, cursor.getLong(0), cursor.getString(1));
            }
        }
        ContentValues values = new ContentValues();
        values.putNull(key_password);
        db.update(table_logjobs, values, key_password + " IS NOT NULL", null);
    }

    /*@Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            recreateDatabase(db);
        }
        if (oldVersion < 4) {
            clearDatabase(db);
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_remote_id + " INTEGER");
            db.execSQL("UPDATE " + table_logjobs + " SET " + key_remote_id + "=" + key_id + " WHERE (" + key_remote_id + " IS NULL OR " + key_remote_id + "=0) AND " + key_status + "!=?", new String[]{DBStatus.LOCAL_CREATED.getTitle()});
            db.execSQL("UPDATE " + table_logjobs + " SET " + key_remote_id + "=0, " + key_status + "=? WHERE " + key_status + "=?", new String[]{DBStatus.LOCAL_EDITED.getTitle(), DBStatus.LOCAL_CREATED.getTitle()});
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_favorite + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 7) {
            dropIndexes(db);
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_category + " TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + table_logjobs + " ADD COLUMN " + key_etag + " TEXT");
            createIndexes(db);
        }
        if (oldVersion < 8) {
            final String table_temp = "LOGJOBS_TEMP";
            createTable(db, table_temp);
            db.execSQL(String.format("INSERT INTO %s(%s,%s,%s,%s,%s,%s,%s,%s,%s) ", table_temp, key_id, key_remote_id, key_status, key_title, key_modified, key_content, key_favorite, key_category, key_etag)
                    + String.format("SELECT %s,%s,%s,%s,strftime('%%s',%s),%s,%s,%s,%s FROM %s", key_id, key_remote_id, key_status, key_title, key_modified, key_content, key_favorite, key_category, key_etag, table_logjobs));
            db.execSQL(String.format("DROP TABLE %s", table_logjobs));
            db.execSQL(String.format("ALTER TABLE %s RENAME TO %s", table_temp, table_logjobs));
        }
    }*/

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        recreateDatabase(db);
    }

    private void clearDatabase(SQLiteDatabase db) {
        db.delete(table_sessions, null, null);
        db.delete(table_logjobs, null, null);
    }

    private void recreateDatabase(SQLiteDatabase db) {
        dropIndexes(db);
        db.execSQL("DROP TABLE " + table_sessions);
        db.execSQL("DROP TABLE " + table_logjobs);
        db.execSQL("DROP TABLE " + table_locations);
        db.execSQL("DROP TABLE " + table_syslog);
        onCreate(db);
    }

    private void dropIndexes(SQLiteDatabase db) {
        Cursor c = db.query("sqlite_master", new String[]{"name"}, "type=?", new String[]{"index"}, null, null, null);
        while (c.moveToNext()) {
            db.execSQL("DROP INDEX " + c.getString(0));
        }
        c.close();
    }

    private void createIndexes(SQLiteDatabase db) {
        createIndex(db, table_sessions, key_token);
        createIndex(db, table_logjobs, key_token);
    }

    private void createIndex(SQLiteDatabase db, String table, String column) {
        String indexName = table + "_" + column + "_idx";
        db.execSQL("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table + "(" + column + ")");
    }

    public Context getContext() {
        return context;
    }


    public long addSessionAndSync(DBSession session) {
        DBSession dbs = new DBSession(
                0,
                session.getName(),
                session.getToken(),
                session.getNextURL(),
                session.getPublicToken(),
                session.isFromShare(),
                session.isPublic()
        );
        long id = addSession(dbs);
        notifySessionsChanged();
        //getPhonetrackServerSyncHelper().scheduleSync(true);
        return id;
    }

    /**
     * Inserts a logjob directly into the Database.
     *
     * @param logjob logjob to be added.
     */
    public long addLogjob(DBLogjob logjob) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        if (logjob.getId() > 0) {
            values.put(key_id, logjob.getId());
        }
        values.put(key_title, logjob.getTitle());
        values.put(key_token, logjob.getToken());
        values.put(key_deviceName, logjob.getDeviceName());
        values.put(key_minTime, logjob.getMinTime());
        values.put(key_minDistance, logjob.getMinDistance());
        values.put(key_minAccuracy, logjob.getMinAccuracy());
        values.put(key_keepGpsOn, logjob.keepGpsOnBetweenFixes() ? "1" : "0");
        values.put(key_enabled, logjob.isEnabled() ? "1" : "0");
        values.put(key_post, logjob.getPost() ? "1" : "0");
        values.put(key_json, logjob.getJson() ? "1" : "0");
        values.put(key_url, logjob.getUrl());
        values.put(key_nbsync, logjob.getNbSync());
        values.put(key_useSignificantMotion, logjob.useSignificantMotion() ? "1" : "0");
        values.put(key_useSignificantMotionMixed, logjob.useSignificantMotionMixed() ? "1" : "0");
        values.put(key_locationTimeout, logjob.getLocationRequestTimeout());
        values.put(key_login, logjob.getLogin());
        // the password lives in CredentialStore, not in the (backed up) database
        values.putNull(key_password);
        values.put(key_automation, logjob.getAutomation() == null ? null : logjob.getAutomation().toJson());
        long id = db.insert(table_logjobs, null, values);
        if (id != -1) {
            CredentialStore.setLogjobPassword(context, id, logjob.getPassword());
        }
        return id;
    }

    long addSession(DBSession session) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_name, session.getName());
        values.put(key_token, session.getToken());
        values.put(key_nextURL, session.getNextURL());
        values.put(key_publicToken, session.getPublicToken());
        values.put(key_isFromShare, session.isFromShare() ? "1" : "0");
        values.put(key_isPublic, session.isPublic() ? "1" : "0");
        return db.insert(table_sessions, null, values);
    }

    public void clearSessions() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(table_sessions, null, null);
    }

    /**
     * Get a single logjob by ID
     *
     * @param id int - ID of the requested log job
     * @return requested log job
     */
    public DBLogjob getLogjob(long id) {
        List<DBLogjob> logjobs = getLogjobsCustom(key_id + " = ?", new String[]{String.valueOf(id)}, null);
        return logjobs.isEmpty() ? null : logjobs.get(0);
    }

    /**
     * Query the database with a custom raw query.
     *
     * @param selection     A filter declaring which rows to return, formatted as an SQL WHERE clause (excluding the WHERE itself).
     * @param selectionArgs You may include ?s in selection, which will be replaced by the values from selectionArgs, in order that they appear in the selection. The values will be bound as Strings.
     * @param orderBy       How to order the rows, formatted as an SQL ORDER BY clause (excluding the ORDER BY itself). Passing null will use the default sort order, which may be unordered.
     * @return List of log jobs
     */
    @NonNull
    @WorkerThread
    private List<DBLogjob> getLogjobsCustom(@NonNull String selection, @NonNull String[] selectionArgs, @Nullable String orderBy) {
        SQLiteDatabase db = getReadableDatabase();
        if (selectionArgs.length > 2) {
            Log.v("Logjob", selection + "   ----   " + selectionArgs[0] + " " + selectionArgs[1] + " " + selectionArgs[2]);
        }
        Cursor cursor = db.query(table_logjobs, columnsLogjobs, selection, selectionArgs, null, null, orderBy);
        List<DBLogjob> logjobs = new ArrayList<>();
        while (cursor.moveToNext()) {
            logjobs.add(getLogjobFromCursor(cursor));
        }
        cursor.close();
        return logjobs;
    }

    /**
     * Creates a DBLogjob object from the current row of a Cursor.
     *
     * @param cursor database cursor
     * @return DBLogjob
     */
    @NonNull
    private DBLogjob getLogjobFromCursor(@NonNull Cursor cursor) {
        DBLogjob logjob = new DBLogjob(cursor.getLong(0),
                cursor.getString(1),
                cursor.getString(2),
                cursor.getString(3),
                cursor.getString(4),
                cursor.getInt(5),
                cursor.getInt(6),
                cursor.getInt(7),
                cursor.getInt(8) == 1,
                cursor.getInt(16) == 1,
                cursor.getInt(17) == 1,
                cursor.getInt(18),
                cursor.getInt(9) == 1,
                cursor.getInt(10) == 1,
                cursor.getInt(11),
                cursor.isNull(21) ? null : cursor.getString(21),
                CredentialStore.getLogjobPassword(context, cursor.getLong(0)),
                cursor.getInt(23) == 1
        );
        logjob.setAutomation(LogjobAutomation.fromJson(cursor.isNull(24) ? null : cursor.getString(24)));
        return logjob;
    }

    /**
     * Get a single session by ID
     *
     * @param id int - ID of the requested session
     * @return requested session
     */
    public DBSession getSession(long id) {
        List<DBSession> sessions = getSessionsCustom(key_id + " = ?", new String[]{String.valueOf(id)}, null);
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    /**
     * Query the database with a custom raw query.
     *
     * @param selection     A filter declaring which rows to return, formatted as an SQL WHERE clause (excluding the WHERE itself).
     * @param selectionArgs You may include ?s in selection, which will be replaced by the values from selectionArgs, in order that they appear in the selection. The values will be bound as Strings.
     * @param orderBy       How to order the rows, formatted as an SQL ORDER BY clause (excluding the ORDER BY itself). Passing null will use the default sort order, which may be unordered.
     * @return List of sessions
     */
    @NonNull
    @WorkerThread
    private List<DBSession> getSessionsCustom(@NonNull String selection, @NonNull String[] selectionArgs, @Nullable String orderBy) {
        SQLiteDatabase db = getReadableDatabase();
        if (selectionArgs.length > 2) {
            Log.v("Session", selection + "   ----   " + selectionArgs[0] + " " + selectionArgs[1] + " " + selectionArgs[2]);
        }
        Cursor cursor = db.query(table_sessions, columnsSessions, selection, selectionArgs, null, null, orderBy);
        List<DBSession> sessions = new ArrayList<>();
        while (cursor.moveToNext()) {
            sessions.add(getSessionFromCursor(cursor));
        }
        cursor.close();
        return sessions;
    }

    /**
     * Creates a DBLogjob object from the current row of a Cursor.
     *
     * @param cursor database cursor
     * @return DBLogjob
     */
    @NonNull
    private DBSession getSessionFromCursor(@NonNull Cursor cursor) {
        return new DBSession(
                cursor.getLong(0),
                cursor.getString(1),
                cursor.getString(2),
                cursor.getString(3),
                cursor.getString(4),
                cursor.getInt(5) == 1,
                cursor.getInt(6) == 1
        );
    }

    public void debugPrintFullDB() {
        List<DBSession> sessions = getSessionsCustom("", new String[]{}, default_order);
        Log.v(getClass().getSimpleName(), "Full Database (" + sessions.size() + " sessions):");
        for (DBSession session : sessions) {
            Log.v(getClass().getSimpleName(), "     " + session);
        }

        List<DBLogjob> logjobs = getLogjobsCustom("", new String[]{}, default_order);
        Log.v(getClass().getSimpleName(), "Full Database (" + logjobs.size() + " logjobs):");
        for (DBLogjob logjob : logjobs) {
            Log.v(getClass().getSimpleName(), "     " + logjob);
        }
    }

    @NonNull
    @WorkerThread
    public Map<String, DBSession> getTokenMap() {
        Map<String, DBSession> result = new HashMap<>();
        for (DBSession s : getSessions()) {
            result.put(s.getToken(), s);
        }
        return result;
    }

    /**
     * Returns a list of all sessions in the Database
     *
     * @return List&lt;DBSession&gt;
     */
    @NonNull
    @WorkerThread
    public List<DBSession> getSessions() {
        return getSessionsCustom("", new String[]{}, default_order_sessions);
    }

    @NonNull
    @WorkerThread
    public List<DBSession> getSessionsNotShared() {
        return getSessionsCustom(key_isFromShare + " = 0", new String[]{}, default_order_sessions);
    }

    @NonNull
    @WorkerThread
    public List<DBSession> getSessionsPublic() {
        return getSessionsCustom(key_isPublic + " = 1", new String[]{}, default_order_sessions);
    }

    @NonNull
    @WorkerThread
    public List<DBLogjob> getLogjobs() {
        return getLogjobsCustom("", new String[]{}, default_order);
    }

    /**
     * Returns a list of all logjobs in the Database
     *
     * @return List&lt;DBLogjob&gt;
     */
    @NonNull
    @WorkerThread
    public List<DBLogjob> searchLogjobs(@Nullable CharSequence query, @Nullable Boolean enabled) {
        List<String> where = new ArrayList<>();
        List<String> args = new ArrayList<>();

        if (query != null) {
            where.add("(" + key_title + " LIKE ? OR " + key_url + " LIKE ? OR " + key_deviceName + " LIKE ?)");
            args.add("%" + query + "%");
            args.add("%" + query + "%");
            args.add("%" + query + "%");
        }

        if (enabled != null) {
            // TODO search with enabled
            //where.add(key_enabled + "=?");
            //args.add(enabled ? "1" : "0");
        }

        return getLogjobsCustom(TextUtils.join(" AND ", where), args.toArray(new String[]{}), key_title);
    }


    @NonNull
    //@WorkerThread
    public Map<String, Integer> getEnabledCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                table_logjobs,
                new String[]{key_enabled, "COUNT(*)"},
                "",
                new String[]{},
                key_enabled,
                null,
                key_enabled);
        Map<String, Integer> enabled = new HashMap<>(cursor.getCount());
        while (cursor.moveToNext()) {
            enabled.put(cursor.getString(0), cursor.getInt(1));
        }
        cursor.close();
        return enabled;
    }

    public void resetLogjobCurrentRun(long ljId) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        // reset nbSync
        values.put(key_nbsync, 0);
        db.update(table_logjobs, values, key_id + " = ?", new String[]{String.valueOf(ljId)});

        // delete locations which are already synced
        db.delete(table_locations,
                key_logjobid + " = ? AND " + key_synced + " = 1",
                new String[]{String.valueOf(ljId)});

        // set currentRun of locations
        ContentValues locValues = new ContentValues();
        locValues.put(key_currentRun, 0);
        db.update(table_locations, locValues, key_logjobid + " = ?", new String[]{String.valueOf(ljId)});
    }

    public void toggleEnabled(@NonNull DBLogjob logjob, @Nullable ICallback callback, boolean resetStats) {
        logjob.setEnabled(!logjob.isEnabled());
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_enabled, logjob.isEnabled() ? "1" : "0");

        db.update(table_logjobs, values, key_id + " = ?", new String[]{String.valueOf(logjob.getId())});

        if (resetStats && logjob.isEnabled()) {
            resetLogjobCurrentRun(logjob.getId());
        }
        if (logjob.isEnabled()) {
            long ts = System.currentTimeMillis() / 1000;
            setLastActivationSystemTimestamp(logjob.getId(), ts);
            // TODO check that (why not using tss?)
            // this way we're sure the comparison between last activation and last location will be correct
            long tss = getLastLocTimestamp(logjob.getId()) + 1;
            setLastActivationGpsTimestamp(logjob.getId(), ts);
        }
    }

    /**
     * Persists only the automation conditions of a log job, leaving every other
     * column (and the sync scheduling) untouched.
     */
    public void updateLogjobAutomation(long logjobId, @Nullable LogjobAutomation automation) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_automation, automation == null ? null : automation.toJson());
        db.update(table_logjobs, values, key_id + " = ?", new String[]{String.valueOf(logjobId)});
    }

    public DBLogjob updateLogjobAndSync(@NonNull DBLogjob oldLogjob, @Nullable String newTitle, @Nullable String newToken,
                                        @Nullable String newUrl, @Nullable String newDevicename, boolean newPost,
                                        int newMinTime, int newMinDistance, int newMinAccuracy,
                                        boolean newKeepGpsOn, boolean newUseSignificantMotion,
                                        boolean newUseSignificantMotionMixed, int newLocationTimeout,
                                        @Nullable String newLogin, @Nullable String newPassword,
                                        boolean newJson, @Nullable ICallback callback) {
//        debugPrintFullDB();
        DBLogjob newLogjob;
        if (newTitle == null) {

            newLogjob = new DBLogjob(
                    oldLogjob.getId(), oldLogjob.getTitle(), oldLogjob.getUrl(),
                    oldLogjob.getToken(), oldLogjob.getDeviceName(),
                    oldLogjob.getMinTime(), oldLogjob.getMinDistance(), oldLogjob.getMinAccuracy(),
                    oldLogjob.keepGpsOnBetweenFixes(), oldLogjob.useSignificantMotion(),
                    oldLogjob.useSignificantMotionMixed(), oldLogjob.getLocationRequestTimeout(),
                    oldLogjob.getPost(), oldLogjob.isEnabled(), oldLogjob.getNbSync(),
                    oldLogjob.getLogin(), oldLogjob.getPassword(), oldLogjob.getJson()
            );
        }
        else {

            newLogjob = new DBLogjob(
                    oldLogjob.getId(), newTitle, newUrl, newToken, newDevicename,
                    newMinTime, newMinDistance, newMinAccuracy, newKeepGpsOn,
                    newUseSignificantMotion, newUseSignificantMotionMixed, newLocationTimeout,
                    newPost, oldLogjob.isEnabled(), oldLogjob.getNbSync(),
                    newLogin, newPassword, newJson
            );
        }
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_title, newLogjob.getTitle());
        values.put(key_url, newLogjob.getUrl());
        values.put(key_token, newLogjob.getToken());
        values.put(key_post, newLogjob.getPost() ? 1 : 0);
        values.put(key_json, newLogjob.getJson() ? 1 : 0);
        values.put(key_keepGpsOn, newLogjob.keepGpsOnBetweenFixes() ? 1 : 0);
        values.put(key_minTime, newLogjob.getMinTime());
        values.put(key_minDistance, newLogjob.getMinDistance());
        values.put(key_minAccuracy, newLogjob.getMinAccuracy());
        values.put(key_deviceName, newLogjob.getDeviceName());
        values.put(key_useSignificantMotion, newLogjob.useSignificantMotion() ? 1 : 0);
        values.put(key_useSignificantMotionMixed, newLogjob.useSignificantMotionMixed() ? 1 : 0);
        values.put(key_locationTimeout, newLogjob.getLocationRequestTimeout());
        values.put(key_login, newLogjob.getLogin());
        values.putNull(key_password);
        values.put(key_automation, newLogjob.getAutomation() == null ? null : newLogjob.getAutomation().toJson());
        int rows = db.update(table_logjobs, values, key_id + " = ?", new String[]{String.valueOf(newLogjob.getId())});
        if (rows > 0) {
            CredentialStore.setLogjobPassword(context, newLogjob.getId(), newLogjob.getPassword());
        }
        // if data was changed, set new status and schedule sync (with callback); otherwise invoke callback directly.
        if (rows > 0) {
            return newLogjob;
        } else {
            if (callback != null) {
                callback.onFinish();
            }
            return oldLogjob;
        }
    }

    /**
     * Updates a single session with data from the server
     *
     * @param id                        local ID of session
     * @param remoteSession                session from the server.
     * @return The number of the Rows affected.
     */
    int updateSession(long id, @NonNull DBSession remoteSession) {
        SQLiteDatabase db = this.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(key_name, remoteSession.getName());
        values.put(key_token, remoteSession.getToken());
        values.put(key_nextURL, remoteSession.getNextURL());
        values.put(key_publicToken, remoteSession.getPublicToken());
        values.put(key_isFromShare, remoteSession.isFromShare() ? "1" : "0");
        values.put(key_isPublic, remoteSession.isPublic() ? "1" : "0");
        String whereClause;
        String[] whereArgs;

        whereClause = key_id + " = ?";
        whereArgs = new String[]{String.valueOf(id)};

        int i = db.update(table_sessions, values, whereClause, whereArgs);
        Log.d(getClass().getSimpleName(), "updateSession: " + remoteSession + " => " + i + " rows updated");
        return i;
    }

    /**
     * Delete a single Logjob from the Database
     *
     * @param id            long - ID of the Logjob that should be deleted.
     */
    public void deleteLogjob(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        // delete all locations
        db.delete(table_locations,
                key_logjobid + " = ?",
                new String[]{String.valueOf(id)});
        // delete the log job
        db.delete(table_logjobs,
                key_id + " = ?",
                new String[]{String.valueOf(id)});
        CredentialStore.removeLogjobPassword(context, id);
    }

    void deleteSession(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(table_sessions,
                key_id + " = ?",
                new String[]{String.valueOf(id)});
    }

    /**
     * key_lat, key_lon, key_time, key_bearing, key_altitude, key_speed, key_accuracy, key_satellites, key_battery
     *
     */
    public void addLocation(long ljId, CorrectingLocation loc, double battery) {
        if (LoggerService.DEBUG) { Log.d(TAG, "[writeLocation from ljid, loc, battery]"); }
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_logjobid, ljId);
        values.put(key_time, loc.getTime() / 1000);
        values.put(key_lat, loc.getLatitude());
        values.put(key_lon, loc.getLongitude());
        values.put(key_bearing, loc.hasBearing() ? loc.getBearing() : null);
        values.put(key_altitude, loc.hasAltitude() ? loc.getAltitude() : null);
        values.put(key_speed, loc.hasSpeed() ? loc.getSpeed() : null);
        values.put(key_accuracy, loc.hasAccuracy() ? loc.getAccuracy() : null);
        values.put(key_battery, battery);
        Integer sat = null;
        //if (LoggerService.DEBUG) { Log.d(TAG, "[PROVIDER "+loc.getProvider()+"]"); }
        if(loc.getProvider().equals("gps") && loc.getExtras() != null) {
            sat = loc.getExtras().getInt("satellites", -1);
            if (sat == -1) {
                sat = null;
            }
        }
        values.put(key_satellites, sat);
        values.put(key_userAgent, userAgent);
        values.put(key_synced, 0);
        values.put(key_currentRun, 1);

        db.insert(table_locations, null, values);

        setLastLocTimestamp(ljId, loc.getTime() / 1000);
    }

    public void addLocation(DBLogjobLocation dbLoc) {
        if (LoggerService.DEBUG) { Log.d(TAG, "[writeLocation from dblocation]"); }
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();

        values.put(key_logjobid, dbLoc.getLogjobId());
        values.put(key_time, dbLoc.getTimestamp());
        values.put(key_lat, dbLoc.getLat());
        values.put(key_lon, dbLoc.getLon());
        values.put(key_bearing, dbLoc.getBearing());
        values.put(key_altitude, dbLoc.getAltitude());
        values.put(key_speed, dbLoc.getSpeed());
        values.put(key_accuracy, dbLoc.getAccuracy());
        values.put(key_battery, dbLoc.getBattery());
        values.put(key_satellites, dbLoc.getSatellites());
        values.put(key_userAgent, dbLoc.getUserAgent());
        values.put(key_synced, dbLoc.isSynced() ? 1 : 0);
        values.put(key_currentRun, dbLoc.isCurrentRun() ? 1 : 0);

        db.insert(table_locations, null, values);
    }

    /**
     * Get a single logjob by ID
     *
     * @param ljId int - ID of the logjob
     * @return requested locations
     */
    public List<DBLogjobLocation> getLocationsOfLogjob(long ljId) {
        return getLocationsCustom(
                key_logjobid + " = ?",
                new String[]{String.valueOf(ljId)},
                key_time + " ASC"
        );
    }

    public List<DBLogjobLocation> getLocationsToSyncOfLogjob(long ljId) {
        return getLocationsCustom(
                key_logjobid + " = ? AND " + key_synced + " = 0",
                new String[]{String.valueOf(ljId)},
                key_time + " ASC"
        );
    }

    public List<DBLogjobLocation> getCurrentRunLocationsOfLogjob(long ljId) {
        return getLocationsCustom(
                key_logjobid + " = ? AND " + key_currentRun + " = 1",
                new String[]{String.valueOf(ljId)},
                key_time + " ASC"
        );
    }

    public DBLogjobLocation getLocation(long id) {
        List<DBLogjobLocation> locations = getLocationsCustom(key_id + " = ?", new String[]{String.valueOf(id)}, null);
        return locations.isEmpty() ? null : locations.get(0);
    }

    /**
     * Query the database with a custom raw query.
     *
     * @param selection     A filter declaring which rows to return, formatted as an SQL WHERE clause (excluding the WHERE itself).
     * @param selectionArgs You may include ?s in selection, which will be replaced by the values from selectionArgs, in order that they appear in the selection. The values will be bound as Strings.
     * @param orderBy       How to order the rows, formatted as an SQL ORDER BY clause (excluding the ORDER BY itself). Passing null will use the default sort order, which may be unordered.
     * @return List of locations
     */
    @NonNull
    @WorkerThread
    private List<DBLogjobLocation> getLocationsCustom(@NonNull String selection, @NonNull String[] selectionArgs, @Nullable String orderBy) {
        SQLiteDatabase db = getReadableDatabase();
        if (selectionArgs.length > 2) {
            Log.v("Location", selection + "   ----   " + selectionArgs[0] + " " + selectionArgs[1] + " " + selectionArgs[2]);
        }
        Cursor cursor = db.query(table_locations, columnsLocations, selection, selectionArgs, null, null, orderBy);
        List<DBLogjobLocation> locations = new ArrayList<>();
        while (cursor.moveToNext()) {
            locations.add(getLocationFromCursor(cursor));
        }
        cursor.close();
        return locations;
    }

    /**
     * Creates a DBLogjobLocation object from the current row of a Cursor.
     * key_id, key_logjobid, key_lat, key_lon, 4 key_time, 5 key_bearing, 6 key_altitude, 7 key_speed, 8 key_accuracy, 9 key_satellites, 10 key_battery
     *
     * @param cursor database cursor
     * @return DBLogjobLocation
     */
    @NonNull
    private DBLogjobLocation getLocationFromCursor(@NonNull Cursor cursor) {

        Log.v(getClass().getSimpleName(), "altitude from db : "+cursor.isNull(6));
        return new DBLogjobLocation(
                cursor.getLong(0),
                cursor.getLong(1),
                cursor.getDouble(2),
                cursor.getDouble(3),
                cursor.getLong(4),
                cursor.isNull(5) ? null : cursor.getDouble(5),
                cursor.isNull(6) ? null : cursor.getDouble(6),
                cursor.isNull(7) ? null : cursor.getDouble(7),
                cursor.isNull(8) ? null : cursor.getDouble(8),
                cursor.isNull(9) ? null : cursor.getLong(9),
                cursor.isNull(10) ? null : cursor.getDouble(10),
                cursor.isNull(11) ? null : cursor.getString(11),
                cursor.getInt(12) == 1,
                cursor.getInt(13) == 1
        );
    }

    @WorkerThread
    public int getLogjobLocationCurrentRunCount(long ljId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                table_locations,
                new String[]{"COUNT(*)"},
                key_logjobid +" = ? AND " + key_currentRun + " = 1",
                new String[]{String.valueOf(ljId)},
                null,
                null,
                null);
        int result = 0;
        while (cursor.moveToNext()) {
            result = cursor.getInt(0);
            break;
        }
        cursor.close();
        return result;
    }

    @WorkerThread
    public int getLogjobLocationNotSyncedCount(long ljId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                table_locations,
                new String[]{"COUNT(*)"},
                key_logjobid +" = ? AND " + key_synced + " = 0",
                new String[]{String.valueOf(ljId)},
                null,
                null,
                null);
        int result = 0;
        while (cursor.moveToNext()) {
            result = cursor.getInt(0);
            break;
        }
        cursor.close();
        return result;
    }

    @WorkerThread
    public int getLocationNotSyncedCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                table_locations,
                new String[]{"COUNT(*)"},
                key_synced + " = 0",
                new String[]{},
                null,
                null,
                null);
        int result = 0;
        while (cursor.moveToNext()) {
            result = cursor.getInt(0);
            break;
        }
        cursor.close();
        return result;
    }

    /**
     * location is now synced with success
     * it can be deleted if it's not a location of the "current run"
     */
    public void setLocationSynced(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_synced, 1);
        db.update(table_locations, values, key_id + " = ?", new String[]{String.valueOf(id)});

        DBLogjobLocation loc = this.getLocation(id);
        if (!loc.isCurrentRun()) {
            deleteLocation(id);
        }
    }

    public void deleteLocation(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(table_locations,
                key_id + " = ?",
                new String[]{String.valueOf(id)});
    }

    public void incNbSync(@NonNull DBLogjob logjob) {
        logjob.setNbSync(logjob.getNbSync() + 1);
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_nbsync, logjob.getNbSync());
        db.update(table_logjobs, values, key_id + " = ?", new String[]{String.valueOf(logjob.getId())});
    }

    public int getNbSync(long logjobId) {
        DBLogjob lj = getLogjob(logjobId);
        return (lj == null) ? 0 : lj.getNbSync();
    }

    public int getNbTotalSync() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                table_logjobs,
                new String[]{"SUM("+key_nbsync+")"},
                null,
                new String[]{},
                null,
                null,
                null);
        int result = 0;
        while (cursor.moveToNext()) {
            result = cursor.getInt(0);
            break;
        }
        cursor.close();
        return result;
    }

    public void setLastLocTimestamp(long ljId, long ts) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_lastLocTimestamp, ts);
        db.update(table_logjobs, values, key_id + " = ?", new String[]{String.valueOf(ljId)});
    }

    public long getLastLocTimestamp(long ljId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(table_logjobs, new String[]{key_lastLocTimestamp}, key_id + " = ?", new String[]{String.valueOf(ljId)}, null, null, null);
        long res = 0;
        while (cursor.moveToNext()) {
            res = cursor.getLong(0);
            break;
        }
        cursor.close();
        return res;
    }
    public void setLastActivationSystemTimestamp(long ljId, long ts) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_lastActivationSystemTimestamp, ts);
        db.update(table_logjobs, values, key_id + " = ?", new String[]{String.valueOf(ljId)});
    }

    public long getLastActivationSystemTimestamp(long ljId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(table_logjobs, new String[]{key_lastActivationSystemTimestamp}, key_id + " = ?", new String[]{String.valueOf(ljId)}, null, null, null);
        long res = 0;
        while (cursor.moveToNext()) {
            res = cursor.getLong(0);
            break;
        }
        cursor.close();
        return res;
    }
    public void setLastActivationGpsTimestamp(long ljId, long ts) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_lastActivationGpsTimestamp, ts);
        db.update(table_logjobs, values, key_id + " = ?", new String[]{String.valueOf(ljId)});
    }

    public long getLastActivationGpsTimestamp(long ljId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(table_logjobs, new String[]{key_lastActivationGpsTimestamp}, key_id + " = ?", new String[]{String.valueOf(ljId)}, null, null, null);
        long res = 0;
        while (cursor.moveToNext()) {
            res = cursor.getLong(0);
            break;
        }
        cursor.close();
        return res;
    }

    public void setLastSyncTimestamp(long ljId, long ts) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_lastSyncTimestamp, ts);
        db.update(table_logjobs, values, key_id + " = ?", new String[]{String.valueOf(ljId)});
    }

    public long getLastSyncTimestamp(long ljId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(table_logjobs, new String[]{key_lastSyncTimestamp}, key_id + " = ?", new String[]{String.valueOf(ljId)}, null, null, null);
        long res = 0;
        while (cursor.moveToNext()) {
            res = cursor.getLong(0);
            break;
        }
        cursor.close();
        return res;
    }

    public void setLastSyncError(long ljId, long ts, String message) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_lastSyncErrorTimestamp, ts);
        values.put(key_lastSyncErrorText, message);
        db.update(table_logjobs, values, key_id + " = ?", new String[]{String.valueOf(ljId)});
    }

    public void resetLastSyncError(long ljId) {
        setLastSyncError(ljId, 0, "");
    }

    public SyncError getLastSyncError(long ljId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(table_logjobs, new String[]{key_lastSyncErrorTimestamp, key_lastSyncErrorText}, key_id + " = ?", new String[]{String.valueOf(ljId)}, null, null, null);
        long ts = 0;
        String msg = "";
        while (cursor.moveToNext()) {
            ts = cursor.getLong(0);
            msg = cursor.getString(1);
            break;
        }
        cursor.close();
        return new SyncError(ts, msg);
    }

    /**
     * Notify about changed logjob.
     */
    void notifySessionsChanged() {
        // update the widgets
    }

    public long addSyslog(String level, String tag, String message) {
        long timestamp = System.currentTimeMillis() / 1000;
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(key_level, level);
        values.put(key_tag, tag);
        values.put(key_message, message);
        values.put(key_timestamp, timestamp);

        Intent intent = new Intent(SyslogManagerActivity.BROADCAST_NEW_SYSLOG);
        intent.putExtra(SyslogManagerActivity.BROADCAST_MESSAGE, message);
        intent.putExtra(SyslogManagerActivity.BROADCAST_TIMESTAMP, timestamp);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);

        return db.insert(table_syslog, null, values);
    }

    public List<DBSyslog> getSyslogs(@Nullable Integer tsMin, @Nullable Integer tsMax) {
        if (tsMin != null && tsMax != null) {
            return getSyslogsCustom(
                key_timestamp + " >= ? AND " + key_timestamp + " <= ?",
                new String[]{String.valueOf(tsMin), String.valueOf(tsMax)},
                key_timestamp
            );
        } else if (tsMin != null) {
            return getSyslogsCustom(
                    key_timestamp + " >= ?",
                    new String[]{String.valueOf(tsMin)},
                    key_timestamp
            );
        } else if (tsMax != null) {
            return getSyslogsCustom(
                    key_timestamp + " <= ?",
                    new String[]{String.valueOf(tsMax)},
                    key_timestamp
            );
        } else {
            return getSyslogsCustom("", new String[]{}, key_timestamp);
        }
    }

    @NonNull
    @WorkerThread
    private List<DBSyslog> getSyslogsCustom(@NonNull String selection, @NonNull String[] selectionArgs, @Nullable String orderBy) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(table_syslog, columnsSyslog, selection, selectionArgs, null, null, orderBy);
        List<DBSyslog> syslogs = new ArrayList<>();
        while (cursor.moveToNext()) {
            syslogs.add(getSyslogFromCursor(cursor));
        }
        cursor.close();
        return syslogs;
    }

    @SuppressLint("Range")
    @NonNull
    private DBSyslog getSyslogFromCursor(@NonNull Cursor cursor) {
        return new DBSyslog(
            cursor.getString(cursor.getColumnIndex(key_message)),
            cursor.getString(cursor.getColumnIndex(key_tag)),
            cursor.getString(cursor.getColumnIndex(key_level)),
            cursor.getLong(cursor.getColumnIndex(key_timestamp))
        );
    }

    public void clearSyslog() {
        Log.d(TAG, "Clear syslog");
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(table_syslog, null, null);
    }
}
