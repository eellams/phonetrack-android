package net.eneiluj.nextcloud.phonetrack.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;

import net.eneiluj.nextcloud.phonetrack.R;
import net.eneiluj.nextcloud.phonetrack.android.activity.LogjobsListViewActivity;
import net.eneiluj.nextcloud.phonetrack.util.CorrectingLocation;
import net.eneiluj.nextcloud.phonetrack.util.SupportUtil;
import net.eneiluj.nextcloud.phonetrack.util.SystemLogger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers an SMS position request: gets a GPS fix and sends it back by SMS.
 *
 * Runs as a short location foreground service. A background service only gets a few
 * location updates per hour and is stopped about a minute after the app leaves the
 * foreground, so the reply could be late or never sent. SMS_RECEIVED puts the app on a
 * temporary allowlist that permits starting it from {@link SmsListener}.
 */
public class SmsLocationSendService extends Service {

    private static final String TAG = SmsLocationSendService.class.getSimpleName();

    public static final String EXTRA_FROM = "from";

    /** Numbers whose request is in progress, so a repeated SMS isn't answered twice. */
    public static final Map<String, Boolean> isRunning = new ConcurrentHashMap<>();

    private static final int CHANNEL_ID = 11111;
    private static final String PROGRESS_CHANNEL_ID = "sms_position_request";
    private static final int FOREGROUND_NOTIFICATION_ID = 1526756640;
    private static int notificationId = 1526756641;

    private static final int TIMEOUT_SECONDS = 120;
    // accept a fix this accurate, or whatever comes after this many fixes
    private static final float WANTED_ACCURACY_METERS = 50;
    private static final int MAX_FIXES = 60;

    private LocationManager locManager;
    private HandlerThread thread;
    private Handler handler;
    // only touched on the handler thread
    private final Map<String, PositionRequest> requests = new HashMap<>();
    private volatile int lastStartId;

    @Override
    public void onCreate() {
        super.onCreate();
        locManager = getSystemService(LocationManager.class);
        thread = new HandlerThread("SmsLocationSendThread");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        lastStartId = startId;
        final String from = intent == null ? null : intent.getStringExtra(EXTRA_FROM);
        if (from == null || from.isEmpty()) {
            handler.post(this::stopIfIdle);
            return START_NOT_STICKY;
        }
        isRunning.put(from, true);
        if (hasLocationPermission()) {
            startLocationForeground(getContactNameForNotification(from));
        }
        handler.post(() -> startRequest(from));
        // a restart after the process died would have lost the requester
        return START_NOT_STICKY;
    }

    @VisibleForTesting
    Looper getWorkLooper() {
        return handler.getLooper();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        for (PositionRequest request : requests.values()) {
            request.cancel();
        }
        requests.clear();
        isRunning.clear();
        thread.quitSafely();
        super.onDestroy();
    }

    private boolean hasLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && SupportUtil.hasBackgroundLocationPermission(this);
    }

    private void startLocationForeground(String requester) {
        createNotificationChannel(PROGRESS_CHANNEL_ID, NotificationManager.IMPORTANCE_LOW);
        Notification notification = new NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.sms_position_in_progress_notification, requester))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
        try {
            ServiceCompat.startForeground(this, FOREGROUND_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (RuntimeException e) {
            // not allowed right now: carry on as a background service, as before
            SystemLogger.w(TAG, "Unable to start as a foreground service: " + e);
        }
    }

    /** Handler thread */
    private void startRequest(String from) {
        if (requests.containsKey(from)) {
            return;
        }
        PositionRequest request = new PositionRequest(from);
        requests.put(from, request);
        request.start();
    }

    /** Handler thread */
    private void finish(PositionRequest request) {
        requests.remove(request.from);
        isRunning.remove(request.from);
        stopIfIdle();
    }

    /** Handler thread */
    private void stopIfIdle() {
        if (requests.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            // does nothing if a newer start request arrived meanwhile
            stopSelf(lastStartId);
        }
    }

    /** One SMS position request, from one phone number. Lives on the handler thread. */
    private class PositionRequest implements LocationListener {
        final String from;
        final String fromNotification;
        private int fixes = 0;
        private boolean done = false;
        private final Runnable timeout = this::onTimeout;

        PositionRequest(String from) {
            this.from = from;
            this.fromNotification = getContactNameForNotification(from);
        }

        void start() {
            if (!hasLocationPermission()) {
                Log.d(TAG, "no permission to access GPS location");
                sendFailure(getString(R.string.sms_failure_permission_sms),
                        getString(R.string.sms_failure_permission_notification, fromNotification));
                end();
                return;
            }
            if (!locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Log.d(TAG, "GPS is disabled, impossible to get position to send SMS");
                sendFailure(getString(R.string.sms_failure_provider_sms),
                        getString(R.string.sms_failure_provider_notification, fromNotification));
                end();
                return;
            }
            try {
                locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, handler.getLooper());
            } catch (SecurityException e) {
                sendFailure(getString(R.string.sms_failure_permission_sms),
                        getString(R.string.sms_failure_permission_notification, fromNotification));
                end();
                return;
            }
            handler.postDelayed(timeout, TIMEOUT_SECONDS * 1000L);
        }

        void cancel() {
            done = true;
            locManager.removeUpdates(this);
            handler.removeCallbacks(timeout);
        }

        private void end() {
            cancel();
            finish(this);
        }

        private void onTimeout() {
            if (done) {
                return;
            }
            Log.d(TAG, "SMS sampling timeout hit");
            locManager.removeUpdates(this);
            sendFailure(getString(R.string.sms_failure_timeout_sms, TIMEOUT_SECONDS),
                    getString(R.string.sms_failure_timeout_notification, TIMEOUT_SECONDS, fromNotification));
            end();
        }

        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (done) {
                return;
            }
            fixes++;
            // wait for a better fix, but not forever
            if (location.hasAccuracy() && location.getAccuracy() > WANTED_ACCURACY_METERS && fixes < MAX_FIXES) {
                Log.d(TAG, "bad accuracy: " + location.getAccuracy());
                return;
            }
            // accepted: stop listening and the timeout, then send
            locManager.removeUpdates(this);
            handler.removeCallbacks(timeout);
            done = true;
            sendPosition(new CorrectingLocation(location));
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        private void sendFailure(String smsContent, String notificationContent) {
            smsContent += "\n\n* " + getString(R.string.popup_battery_value, getBatteryLevelOnce());
            if (canSendSms()) {
                SupportUtil.getSmsManager(SmsLocationSendService.this).sendTextMessage(from, null, smsContent, null, null);
            }
            notifySmsWasSent(smsContent, notificationContent, fromNotification);
        }

        private void sendPosition(CorrectingLocation loc) {
            Log.d(TAG, "send sms to " + from);
            String latStr = String.format(Locale.ENGLISH, "%.7f", loc.getLatitude());
            String lonStr = String.format(Locale.ENGLISH, "%.7f", loc.getLongitude());

            String smsContent1 = "* " + getString(R.string.popup_battery_value, getBatteryLevelOnce());
            if (loc.hasAltitude()) {
                smsContent1 += "\n* " + getString(R.string.popup_altitude_value, loc.getAltitude());
            }
            if (loc.hasAccuracy()) {
                smsContent1 += "\n* " + getString(R.string.popup_accuracy_value, loc.getAccuracy());
            }
            if (loc.hasSpeed()) {
                smsContent1 += "\n* " + getString(R.string.popup_speed_value, loc.getSpeed() * 3.6);
            }
            if (loc.hasBearing()) {
                smsContent1 += "\n* " + getString(R.string.sms_bearing_value, loc.getBearing());
            }
            final String smsContent2 = "\n* " + getString(R.string.sms_geo_link) + ":\ngeo:" + latStr + "," + lonStr + "?z=14\n";
            final String smsContent3 = "* " + getString(R.string.sms_osm_link) + ":\nhttps://www.openstreetmap.org/?mlat=" + latStr + "&mlon=" + lonStr
                    + "#map=14/" + latStr + "/" + lonStr;

            if (!canSendSms()) {
                Log.d(TAG, "no permission to send SMS");
                end();
                return;
            }
            final SmsManager smsManager = SupportUtil.getSmsManager(SmsLocationSendService.this);
            final String smsContent1f = smsContent1;
            smsManager.sendTextMessage(from, null, smsContent1, null, null);
            // delay the next parts so they arrive in order
            handler.postDelayed(() -> smsManager.sendTextMessage(from, null, smsContent2, null, null), 1000);
            handler.postDelayed(() -> {
                smsManager.sendTextMessage(from, null, smsContent3, null, null);
                notifySmsWasSent(smsContent1f + "\n" + smsContent2 + "\n" + smsContent3,
                        getString(R.string.sms_position_notification, fromNotification), fromNotification);
                finish(this);
            }, 2000);
        }
    }

    private boolean canSendSms() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void notifySmsWasSent(String smsContent, String notificationContent, String fromNotification) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent ptIntent = new Intent(getApplicationContext(), LogjobsListViewActivity.class);
        ptIntent.putExtra(LogjobsListViewActivity.PARAM_SMSINFO_CONTENT, smsContent);
        ptIntent.putExtra(LogjobsListViewActivity.PARAM_SMSINFO_FROM, fromNotification);

        String chanId = String.valueOf(CHANNEL_ID);
        createNotificationChannel(chanId, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, chanId)
                .setSmallIcon(R.drawable.ic_notify_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(notificationContent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(PendingIntent.getActivity(this, 1, ptIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT))
                .setAutoCancel(true);
        NotificationManagerCompat.from(this).notify(notificationId++, builder.build());
    }

    private void createNotificationChannel(String channelId, int importance) {
        NotificationChannel channel = new NotificationChannel(channelId, getString(R.string.app_name), importance);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private String getContactNameForNotification(String number) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return number;
        }
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        ContentResolver contentResolver = getContentResolver();
        try (Cursor contactLookup = contentResolver.query(uri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (contactLookup != null && contactLookup.moveToFirst()) {
                String name = contactLookup.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Contact lookup failed: " + e);
        }
        return number;
    }

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
        double batLevel = ((double) level / (double) scale) * 100.0;
        return Math.round(batLevel * 100.0) / 100.0;
    }
}
