package com.winlator.box;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;

/**
 * Keeps the Android host process important while an interactive Windows session
 * exists. This service never starts or reparses a Box target.
 */
public final class BoxSessionForegroundService extends Service {
    private static final String TAG = "BoxSessionService";
    private static final String CHANNEL_ID = "box_windows_session";
    private static final int NOTIFICATION_ID = 0x57494e45;

    private static final String ACTION_START =
            "com.winlator.box.action.START_SESSION_FOREGROUND";
    private static final String ACTION_STOP =
            "com.winlator.box.action.STOP_SESSION_FOREGROUND";
    public static final String ACTION_RETURN_TO_SESSION =
            "com.winlator.box.action.RETURN_TO_SESSION";

    public static boolean start(Context context) {
        try {
            Intent intent = new Intent(context, BoxSessionForegroundService.class)
                    .setAction(ACTION_START);
            context.startForegroundService(intent);
            return true;
        }
        catch (RuntimeException error) {
            Log.w(TAG, "Unable to start session foreground service", error);
            return false;
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, BoxSessionForegroundService.class)
                    .setAction(ACTION_STOP));
        }
        catch (RuntimeException error) {
            Log.w(TAG, "Unable to stop session foreground service", error);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
        catch (RuntimeException error) {
            Log.w(TAG, "Unable to enter foreground state", error);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopForeground(true);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.box_session_notification_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.box_session_notification_description));
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent returnIntent = new Intent(this, XServerDisplayActivity.class)
                .setAction(ACTION_RETURN_TO_SESSION)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                returnIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        CharSequence appLabel = getApplicationInfo().loadLabel(getPackageManager());
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(appLabel)
                .setContentText(getString(R.string.box_session_notification_text))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }
}
