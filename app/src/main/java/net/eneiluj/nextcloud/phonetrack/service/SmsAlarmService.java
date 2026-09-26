package net.eneiluj.nextcloud.phonetrack.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import net.eneiluj.nextcloud.phonetrack.R;
import net.eneiluj.nextcloud.phonetrack.util.SupportUtil;
import net.eneiluj.nextcloud.phonetrack.util.SystemLogger;

/**
 * Rings the alarm requested by the "alarm" SMS command, at full alarm volume, for a number of
 * seconds; a second "alarm" SMS or the notification's Stop action ends it early.
 *
 * This used to run inside SmsListener's onReceive with a Handler to stop it and restore the
 * volume: once onReceive returned the process could be killed, leaving the alarm volume at
 * maximum. Android 17 (targetSdk 37) also only lets apps play audio in the background from a
 * foreground service. The original volume is saved in the preferences as well, so it is
 * restored at the next app start even if the process dies mid-alarm.
 */
public class SmsAlarmService extends Service {

    private static final String TAG = SmsAlarmService.class.getSimpleName();

    /** Start the alarm, or stop it if it is already ringing (the SMS command toggles). */
    public static final String ACTION_TOGGLE = "net.eneiluj.nextcloud.phonetrack.action.ALARM_TOGGLE";
    /** Stop the alarm (notification action). */
    public static final String ACTION_STOP = "net.eneiluj.nextcloud.phonetrack.action.ALARM_STOP";
    public static final String EXTRA_FROM = "from";
    public static final String EXTRA_DURATION_SECONDS = "duration";

    @VisibleForTesting
    static final String PREF_VOLUME_TO_RESTORE = "smsAlarmVolumeToRestore";
    private static final String CHANNEL_ID = "sms_alarm";
    private static final int NOTIFICATION_ID = 1526756650;

    private AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> stopAlarm(null);
    @Nullable
    private Ringtone ringtone;
    private boolean ringing = false;

    /** Start or toggle the alarm from the SMS command. */
    public static void toggle(Context context, String from, int durationSeconds) {
        Intent intent = new Intent(context, SmsAlarmService.class)
                .setAction(ACTION_TOGGLE)
                .putExtra(EXTRA_FROM, from)
                .putExtra(EXTRA_DURATION_SECONDS, durationSeconds);
        try {
            // SMS_RECEIVED allows starting a foreground service from the background
            ContextCompat.startForegroundService(context, intent);
        } catch (IllegalStateException e) {
            SystemLogger.w(TAG, "Unable to start the SMS alarm: " + e);
        }
    }

    /**
     * Put the alarm volume back if the process died while the alarm was ringing.
     * Called when the app starts; does nothing otherwise.
     */
    public static void restoreVolumeAfterInterruptedAlarm(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.contains(PREF_VOLUME_TO_RESTORE)) {
            int volume = prefs.getInt(PREF_VOLUME_TO_RESTORE, 0);
            AudioManager am = context.getSystemService(AudioManager.class);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0);
            }
            prefs.edit().remove(PREF_VOLUME_TO_RESTORE).apply();
            SystemLogger.w(TAG, "Restored the alarm volume after an interrupted SMS alarm");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = getSystemService(AudioManager.class);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // every start may come from startForegroundService(): always go (or stay) foreground
        goForeground();
        String action = intent == null ? null : intent.getAction();
        String from = intent == null ? null : intent.getStringExtra(EXTRA_FROM);
        if (ACTION_STOP.equals(action)) {
            stopAlarm(null);
        } else if (ACTION_TOGGLE.equals(action)) {
            if (ringing) {
                stopAlarm(from);
            } else {
                startAlarm(from, intent.getIntExtra(EXTRA_DURATION_SECONDS, 60));
            }
        } else if (!ringing) {
            // restarted without a request (e.g. after the process died): nothing to ring
            stopAlarm(null);
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (ringing) {
            stopAlarm(null);
        }
        super.onDestroy();
    }

    @VisibleForTesting
    boolean isRinging() {
        return ringing;
    }

    @SuppressLint("ApplySharedPref") // commit: the volume must be on disk before it is raised
    private void startAlarm(@Nullable String from, int durationSeconds) {
        int originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
        // saved first: if the process dies from here on, the next app start restores it
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putInt(PREF_VOLUME_TO_RESTORE, originalVolume).commit();
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);

        ringtone = RingtoneManager.getRingtone(this, alarmSound());
        if (ringtone != null) {
            ringtone.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            // ring for the whole duration, not just once
            ringtone.setLooping(true);
            ringtone.play();
        }
        ringing = true;
        handler.postDelayed(timeout, durationSeconds * 1000L);
        Log.d(TAG, "alarm started for " + durationSeconds + " s");
        reply(from, getString(R.string.sms_alarm_started, durationSeconds));
    }

    /**
     * @param replyTo number to tell that the alarm stopped, or null
     */
    private void stopAlarm(@Nullable String replyTo) {
        handler.removeCallbacks(timeout);
        if (ringtone != null) {
            ringtone.stop();
            ringtone = null;
        }
        if (ringing) {
            ringing = false;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            int originalVolume = prefs.getInt(PREF_VOLUME_TO_RESTORE,
                    audioManager.getStreamVolume(AudioManager.STREAM_ALARM));
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0);
            prefs.edit().remove(PREF_VOLUME_TO_RESTORE).apply();
            Log.d(TAG, "alarm stopped");
            reply(replyTo, getString(R.string.sms_alarm_stopped));
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Uri alarmSound() {
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) {
            sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        if (sound == null) {
            sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }
        return sound;
    }

    private void reply(@Nullable String to, String text) {
        if (to != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            SupportUtil.getSmsManager(this).sendTextMessage(to, null, text, null, null);
        }
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.sms_alarm_notification_channel), NotificationManager.IMPORTANCE_HIGH));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, SmsAlarmService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.sms_alarm_notification))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .addAction(0, getString(R.string.sms_alarm_notification_stop), stop)
                .build();
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } catch (RuntimeException e) {
            SystemLogger.w(TAG, "Unable to run the SMS alarm as a foreground service: " + e);
        }
    }
}
